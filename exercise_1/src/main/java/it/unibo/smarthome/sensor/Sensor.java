package it.unibo.smarthome.sensor;

import it.unibo.smarthome.model.AlarmCommand;
import it.unibo.smarthome.model.AlarmEvent;
import it.unibo.smarthome.model.Zone;
import it.unibo.smarthome.model.SensorType;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

/**
 * Simulates a sensor in the smart home.
 */
public class Sensor extends AbstractBehavior<AlarmCommand> {
    private final ActorContext<AlarmCommand> context;
    private final SensorType type;
    private final Zone zone;
    private final ActorRef<AlarmCommand> alarmSystem;

    private Sensor(
            ActorContext<AlarmCommand> context,
            SensorType type,
            Zone zone,
            ActorRef<AlarmCommand> alarmSystem
    ) {
        this.context = context;
        this.type = type;
        this.zone = zone;
        this.alarmSystem = alarmSystem;
    }

    public static Behavior<AlarmCommand> create(
            SensorType type,
            Zone zone,
            ActorRef<AlarmCommand> alarmSystem
    ) {
        return Behaviors.setup(ctx -> new Sensor(ctx, type, zone, alarmSystem));
    }

    @Override
    public Receive<AlarmCommand> createReceive() {
        return Behaviors.receive(AlarmCommand.class)
                .onMessage(AlarmCommand.class, this::onCommand)
                .build();
    }

    private Behavior<AlarmCommand> onCommand(AlarmCommand msg) {
        // Sensors just log events and don't trigger themselves
        // Real sensor behavior would be simulated externally
        return Behaviors.same();
    }

    public void trigger() {
        context.getLogger().info("Sensor {} in {} triggered", type, zone);
        alarmSystem.tell(new AlarmCommand.SensorEvent(type, zone, true));
    }

    public ActorRef<AlarmCommand> getReference() {
        return self();
    }
}
