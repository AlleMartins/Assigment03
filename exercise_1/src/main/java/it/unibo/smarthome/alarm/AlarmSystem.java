package it.unibo.smarthome.alarm;

import it.unibo.smarthome.model.*;
import it.unibo.smarthome.model.AlarmCommand.*;
import it.unibo.smarthome.model.AlarmEvent.*;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Main alarm system actor implementing the state machine.
 * <p>
 * States: DISARMED → EXIT_DELAY → ARMED → ENTRY_DELAY → ALARM
 * <p>
 * Uses {@link TimerScheduler} for managing exit/entry delay timers
 * in an actor-safe manner.
 */
public class AlarmSystem extends AbstractBehavior<AlarmCommand> {

    // Timer keys for cancellation
    private static final String EXIT_TIMER_KEY = "exit-delay";
    private static final String ENTRY_TIMER_KEY = "entry-delay";

    private final TimerScheduler<AlarmCommand> timers;
    private final Duration exitDelay;
    private final Duration entryDelay;
    private final String defaultPin;
    private final ActorRef<AlarmEvent> eventSink;

    private State currentState;
    private Set<Zone> activeZones;

    // ── Factory methods ────────────────────────────────────────────────

    /**
     * Creates an AlarmSystem with all zones active by default.
     */
    public static Behavior<AlarmCommand> create(AlarmConfig config, ActorRef<AlarmEvent> eventSink) {
        return create(EnumSet.allOf(Zone.class), config.exitDelay(), config.entryDelay(), config.defaultPin(), eventSink);
    }

    /**
     * Creates an AlarmSystem with the given initial set of active zones.
     */
    public static Behavior<AlarmCommand> createWithZones(
            AlarmConfig config,
            Set<Zone> activeZones,
            ActorRef<AlarmEvent> eventSink
    ) {
        return create(activeZones, config.exitDelay(), config.entryDelay(), config.defaultPin(), eventSink);
    }

    /**
     * Full factory with explicit parameters.
     */
    public static Behavior<AlarmCommand> create(
            Set<Zone> activeZones,
            Duration exitDelay,
            Duration entryDelay,
            String defaultPin,
            ActorRef<AlarmEvent> eventSink
    ) {
        return Behaviors.withTimers(timers ->
                Behaviors.setup(ctx ->
                        new AlarmSystem(ctx, timers, activeZones, exitDelay, entryDelay, defaultPin, eventSink)
                )
        );
    }

    // ── Constructor ────────────────────────────────────────────────────

    private AlarmSystem(
            ActorContext<AlarmCommand> context,
            TimerScheduler<AlarmCommand> timers,
            Set<Zone> activeZones,
            Duration exitDelay,
            Duration entryDelay,
            String defaultPin,
            ActorRef<AlarmEvent> eventSink
    ) {
        super(context);
        this.timers = timers;
        this.activeZones = EnumSet.copyOf(activeZones);
        this.exitDelay = exitDelay;
        this.entryDelay = entryDelay;
        this.defaultPin = defaultPin;
        this.eventSink = eventSink;
        this.currentState = State.DISARMED;

        getContext().getLog().info("AlarmSystem started in DISARMED state. Active zones: {}", this.activeZones);
    }

    // ── Message handling ───────────────────────────────────────────────

    @Override
    public Receive<AlarmCommand> createReceive() {
        return newReceiveBuilder()
                .onMessage(ArmSystem.class, this::onArmSystem)
                .onMessage(ArmSystemPartial.class, this::onArmSystemPartial)
                .onMessage(DisarmSystem.class, this::onDisarmSystem)
                .onMessage(SensorEvent.class, this::onSensorEvent)
                .onMessage(StopAlarm.class, this::onStopAlarm)
                .onMessage(ExitDelayTimeout.class, this::onExitDelayTimeout)
                .onMessage(EntryDelayTimeout.class, this::onEntryDelayTimeout)
                .build();
    }

    // ── Arm (full) ─────────────────────────────────────────────────────

    private Behavior<AlarmCommand> onArmSystem(ArmSystem cmd) {
        if (currentState != State.DISARMED) {
            getContext().getLog().warn("Cannot arm: system is not DISARMED (current={})", currentState);
            return Behaviors.same();
        }
        if (!validatePin(cmd.pin())) {
            return Behaviors.same();
        }
        // Full arming: activate all zones
        this.activeZones = EnumSet.allOf(Zone.class);
        startExitDelay(cmd.pin());
        return Behaviors.same();
    }

    // ── Arm (partial — bonus) ──────────────────────────────────────────

    private Behavior<AlarmCommand> onArmSystemPartial(ArmSystemPartial cmd) {
        if (currentState != State.DISARMED) {
            getContext().getLog().warn("Cannot arm: system is not DISARMED (current={})", currentState);
            return Behaviors.same();
        }
        if (!validatePin(cmd.pin())) {
            return Behaviors.same();
        }
        // Partial arming: activate only specified zones
        this.activeZones = EnumSet.copyOf(cmd.zones());
        getContext().getLog().info("Partial arming — active zones: {}", activeZones);
        startExitDelay(cmd.pin());
        return Behaviors.same();
    }

    // ── Disarm ─────────────────────────────────────────────────────────

    private Behavior<AlarmCommand> onDisarmSystem(DisarmSystem cmd) {
        if (currentState != State.EXIT_DELAY
                && currentState != State.ARMED
                && currentState != State.ENTRY_DELAY) {
            getContext().getLog().warn("Cannot disarm in state {}", currentState);
            return Behaviors.same();
        }
        if (!validatePin(cmd.pin())) {
            return Behaviors.same();
        }
        // Cancel any pending timer
        timers.cancel(EXIT_TIMER_KEY);
        timers.cancel(ENTRY_TIMER_KEY);

        transitionTo(State.DISARMED);
        getContext().getLog().info("System DISARMED by user");
        return Behaviors.same();
    }

    // ── Sensor event ───────────────────────────────────────────────────

    private Behavior<AlarmCommand> onSensorEvent(SensorEvent cmd) {
        getContext().getLog().info("Sensor event: type={}, zone={}, triggered={} [state={}]",
                cmd.type(), cmd.zone(), cmd.triggered(), currentState);

        // In DISARMED and EXIT_DELAY: ignore sensor events
        if (currentState == State.DISARMED || currentState == State.EXIT_DELAY) {
            getContext().getLog().debug("Sensor event ignored (state={})", currentState);
            return Behaviors.same();
        }

        // In ENTRY_DELAY or ALARM: already triggered, no additional effect
        if (currentState == State.ENTRY_DELAY || currentState == State.ALARM) {
            return Behaviors.same();
        }

        // In ARMED: check zone and trigger entry delay
        if (currentState == State.ARMED) {
            if (!activeZones.contains(cmd.zone())) {
                getContext().getLog().info("Sensor in inactive zone {} — ignored", cmd.zone());
                return Behaviors.same();
            }
            if (cmd.triggered()) {
                startEntryDelay(cmd.zone());
            }
        }
        return Behaviors.same();
    }

    // ── Stop alarm ─────────────────────────────────────────────────────

    private Behavior<AlarmCommand> onStopAlarm(StopAlarm cmd) {
        if (currentState != State.ALARM) {
            getContext().getLog().warn("StopAlarm ignored — system is not in ALARM state (current={})", currentState);
            return Behaviors.same();
        }
        if (!validatePin(cmd.pin())) {
            return Behaviors.same();
        }
        transitionTo(State.DISARMED);
        emitEvent(new AlarmStopped(cmd.pin(), Instant.now()));
        getContext().getLog().info("Alarm STOPPED — system DISARMED");
        return Behaviors.same();
    }

    // ── Timer callbacks ────────────────────────────────────────────────

    private Behavior<AlarmCommand> onExitDelayTimeout(ExitDelayTimeout msg) {
        if (currentState == State.EXIT_DELAY) {
            transitionTo(State.ARMED);
            getContext().getLog().info("Exit delay expired — system is now ARMED (active zones: {})", activeZones);
        }
        return Behaviors.same();
    }

    private Behavior<AlarmCommand> onEntryDelayTimeout(EntryDelayTimeout msg) {
        if (currentState == State.ENTRY_DELAY) {
            transitionTo(State.ALARM);
            emitEvent(new AlarmTriggered(msg.zone(), Instant.now()));
            getContext().getLog().info("Entry delay expired — ALARM TRIGGERED in zone {}", msg.zone());
        }
        return Behaviors.same();
    }

    // ── Private helpers ────────────────────────────────────────────────

    private void startExitDelay(String pin) {
        transitionTo(State.EXIT_DELAY);
        emitEvent(new ExitDelayStarted(pin, Instant.now()));
        getContext().getLog().info("EXIT DELAY started ({} seconds) — leave the house now!", exitDelay.getSeconds());
        timers.startSingleTimer(EXIT_TIMER_KEY, new ExitDelayTimeout(), exitDelay);
    }

    private void startEntryDelay(Zone zone) {
        transitionTo(State.ENTRY_DELAY);
        emitEvent(new EntryDelayStarted(zone, Instant.now()));
        getContext().getLog().info("ENTRY DELAY started for zone {} ({} seconds) — enter PIN to disarm!",
                zone, entryDelay.getSeconds());
        timers.startSingleTimer(ENTRY_TIMER_KEY, new EntryDelayTimeout(zone), entryDelay);
    }

    private void transitionTo(State newState) {
        State old = this.currentState;
        this.currentState = newState;
        emitEvent(new SystemStateUpdated(newState, Instant.now()));
        getContext().getLog().info("State transition: {} → {}", old, newState);
    }

    private boolean validatePin(String pin) {
        if (!pin.equals(defaultPin)) {
            emitEvent(new InvalidPin(pin, Instant.now()));
            getContext().getLog().warn("Invalid PIN attempt: {}", pin);
            return false;
        }
        return true;
    }

    private void emitEvent(AlarmEvent event) {
        if (eventSink != null) {
            eventSink.tell(event);
        }
    }
}
