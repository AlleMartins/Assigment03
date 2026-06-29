package it.unibo.smarthome.sensor;

import it.unibo.smarthome.model.AlarmCommand;
import it.unibo.smarthome.model.SensorType;
import it.unibo.smarthome.model.Zone;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

/**
 * Simulates a sensor in the smart home.
 * <p>
 * Each sensor has its own type (MOTION, DOOR, WINDOW) and belongs to a zone.
 * When triggered, it sends a {@link AlarmCommand.SensorEvent} to the alarm system.
 */
public class Sensor extends AbstractBehavior<Sensor.Command> {

    // ── Sensor protocol ────────────────────────────────────────────────

    /** Commands that a Sensor actor can receive. */
    public interface Command {}

    /** Triggers the sensor — simulates detection of intrusion. */
    public record Trigger() implements Command {}

    /** Resets the sensor to its idle state. */
    public record Reset() implements Command {}

    // ── Fields ─────────────────────────────────────────────────────────

    private final SensorType type;
    private final Zone zone;
    private final ActorRef<AlarmCommand> alarmSystem;
    private boolean triggered;

    // ── Factory ────────────────────────────────────────────────────────

    /**
     * Creates a Sensor actor behavior.
     *
     * @param type        the sensor type (MOTION, DOOR, WINDOW)
     * @param zone        the zone this sensor belongs to
     * @param alarmSystem reference to the AlarmSystem actor to notify
     */
    public static Behavior<Command> create(
            SensorType type,
            Zone zone,
            ActorRef<AlarmCommand> alarmSystem
    ) {
        return Behaviors.setup(ctx -> new Sensor(ctx, type, zone, alarmSystem));
    }

    // ── Constructor ────────────────────────────────────────────────────

    private Sensor(
            ActorContext<Command> context,
            SensorType type,
            Zone zone,
            ActorRef<AlarmCommand> alarmSystem
    ) {
        super(context);
        this.type = type;
        this.zone = zone;
        this.alarmSystem = alarmSystem;
        this.triggered = false;

        getContext().getLog().info("Sensor created: type={}, zone={}", type, zone);
    }

    // ── Message handling ───────────────────────────────────────────────

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Trigger.class, this::onTrigger)
                .onMessage(Reset.class, this::onReset)
                .build();
    }

    private Behavior<Command> onTrigger(Trigger msg) {
        if (!triggered) {
            triggered = true;
            getContext().getLog().info("Sensor TRIGGERED: type={}, zone={}", type, zone);
            alarmSystem.tell(new AlarmCommand.SensorEvent(type, zone, true));
        } else {
            getContext().getLog().debug("Sensor already triggered (type={}, zone={})", type, zone);
        }
        return Behaviors.same();
    }

    private Behavior<Command> onReset(Reset msg) {
        triggered = false;
        getContext().getLog().info("Sensor RESET: type={}, zone={}", type, zone);
        return Behaviors.same();
    }
}
