package it.unibo.smarthome.model;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import scala.concurrent.duration.FiniteDuration;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Main alarm system actor implementing the state machine.
 * States: Disarmed, ExitDelay, Armed, EntryDelay, Alarm.
 */
public class AlarmSystem {

    private AlarmSystem() {}

    public static Behavior<AlarmCommand> create(
            Set<Zone> activeZones,
            Duration exitDelay,
            Duration entryDelay,
            String defaultPin,
            ActorRef<AlarmEvent> eventSink
    ) {
        return Behaviors.setup(ctx -> new AlarmSystemImpl(ctx, activeZones, exitDelay, entryDelay, defaultPin, eventSink));
    }

    public static Behavior<AlarmCommand> create(AlarmConfig config, ActorRef<AlarmEvent> eventSink) {
        return create(new HashSet<>(Set.of(Zone.values())), config.exitDelay(), config.entryDelay(), config.defaultPin(), eventSink);
    }

    public static Behavior<AlarmCommand> createWithZones(AlarmConfig config, Set<Zone> activeZones, ActorRef<AlarmEvent> eventSink) {
        return create(activeZones, config.exitDelay(), config.entryDelay(), config.defaultPin(), eventSink);
    }

    // Implementation class - private to hide implementation details
    private static class AlarmSystemImpl extends AbstractBehavior<AlarmCommand> {
        private final ActorContext<AlarmCommand> context;
        private final Set<Zone> activeZones;
        private final Duration exitDelay;
        private final Duration entryDelay;
        private final String defaultPin;
        private final ActorRef<AlarmEvent> eventSink;

        private State currentState;
        private Instant stateEnterTime;

        private AlarmSystemImpl(
                ActorContext<AlarmCommand> context,
                Set<Zone> activeZones,
                Duration exitDelay,
                Duration entryDelay,
                String defaultPin,
                ActorRef<AlarmEvent> eventSink
        ) {
            this.context = context;
            this.activeZones = activeZones;
            this.exitDelay = exitDelay;
            this.entryDelay = entryDelay;
            this.defaultPin = defaultPin;
            this.eventSink = eventSink;
            this.currentState = State.DISARMED;
            this.stateEnterTime = Instant.now();
        }

        @Override
        public Receive<AlarmCommand> createReceive() {
            return newReceiveBuilder()
                    .onMessage(ArmSystem.class, this::onArmSystem)
                    .onMessage(DisarmSystem.class, this::onDisarmSystem)
                    .onMessage(SensorEvent.class, this::onSensorEvent)
                    .onMessage(StopAlarm.class, this::onStopAlarm)
                    .onMessage(ExitDelayTimer.class, this::onExitDelayTimer)
                    .onMessage(EntryDelayTimer.class, this::onEntryDelayTimer)
                    .build();
        }

        private Behavior<AlarmCommand> onArmSystem(ArmSystem cmd) {
            if (!cmd.pin().equals(defaultPin)) {
                emitEvent(new InvalidPin(cmd.pin(), Instant.now()));
                return Behaviors.same();
            }
            startExitDelay(cmd.pin());
            return Behaviors.same();
        }

        private Behavior<AlarmCommand> onDisarmSystem(DisarmSystem cmd) {
            if (!cmd.pin().equals(defaultPin)) {
                emitEvent(new InvalidPin(cmd.pin(), Instant.now()));
                return Behaviors.same();
            }
            transitionTo(State.DISARMED);
            emitEvent(new SystemStateUpdated(State.DISARMED, Instant.now()));
            return Behaviors.same();
        }

        private Behavior<AlarmCommand> onSensorEvent(SensorEvent cmd) {
            if (currentState == State.DISARMED || currentState == State.EXIT_DELAY) {
                return Behaviors.same();
            }
            if (!activeZones.contains(cmd.zone())) {
                return Behaviors.same();
            }
            if (currentState == State.ARMED) {
                startEntryDelay(cmd.zone());
            }
            return Behaviors.same();
        }

        private Behavior<AlarmCommand> onStopAlarm(StopAlarm cmd) {
            if (!cmd.pin().equals(defaultPin)) {
                emitEvent(new InvalidPin(cmd.pin(), Instant.now()));
                return Behaviors.same();
            }
            transitionTo(State.DISARMED);
            emitEvent(new AlarmStopped(cmd.pin(), Instant.now()));
            return Behaviors.same();
        }

        private Behavior<AlarmCommand> onExitDelayTimer(ExitDelayTimer timer) {
            if (currentState == State.EXIT_DELAY) {
                transitionTo(State.ARMED);
                emitEvent(new SystemStateUpdated(State.ARMED, Instant.now()));
            }
            return Behaviors.same();
        }

        private Behavior<AlarmCommand> onEntryDelayTimer(EntryDelayTimer timer) {
            if (currentState == State.ENTRY_DELAY) {
                transitionTo(State.ALARM);
                emitEvent(new AlarmTriggered(timer.zone(), Instant.now()));
            }
            return Behaviors.same();
        }

        private void startExitDelay(String pin) {
            transitionTo(State.EXIT_DELAY);
            emitEvent(new ExitDelayStarted(pin, Instant.now()));
            context.getLogger().info("Starting EXIT DELAY ({} seconds)", exitDelay.getSeconds());
            FiniteDuration fd = FiniteDuration.create(exitDelay.getSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            context.getScheduler().scheduleOnce(fd, new ExitDelayTimer(), self());
        }

        private void startEntryDelay(Zone zone) {
            transitionTo(State.ENTRY_DELAY);
            emitEvent(new EntryDelayStarted(zone, Instant.now()));
            context.getLogger().info("Starting ENTRY DELAY for zone {} ({} seconds)", zone, entryDelay.getSeconds());
            FiniteDuration fd = FiniteDuration.create(entryDelay.getSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            context.getScheduler().scheduleOnce(fd, new EntryDelayTimer(zone), self());
        }

        private void transitionTo(State newState) {
            this.currentState = newState;
            this.stateEnterTime = Instant.now();
            emitEvent(new SystemStateUpdated(newState, stateEnterTime));
        }

        private void emitEvent(AlarmEvent event) {
            if (eventSink != null) {
                eventSink.tell(event);
            }
        }
    }
}
