package it.unibo.smarthome;

import it.unibo.smarthome.model.AlarmConfig;
import it.unibo.smarthome.model.AlarmCommand;
import it.unibo.smarthome.model.AlarmEvent;
import it.unibo.smarthome.model.Zone;
import it.unibo.smarthome.model.SensorType;
import it.unibo.smarthome.model.AlarmSystem;
import it.unibo.smarthome.sensor.Sensor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import scala.concurrent.duration.Duration$;
import scala.concurrent.duration.FiniteDuration;

/**
 * Main entry point for the Smart Home Alarm System.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("Starting Smart Home Alarm System...");
        System.out.println("=========================================");

        AlarmConfig config = new AlarmConfig(
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                "1234"
        );

        ActorRef<AlarmEvent> eventConsumer = Behaviors.setup(ctx -> ctx.spawn(
                Behaviors.receive(AlarmEvent.class)
                        .onMessage(AlarmEvent.class, e -> {
                            ctx.getLogger().info("EVENT: {}", e);
                            return Behaviors.same();
                        })
                        .build(),
                "event-logger"
        ));

        ActorSystem<AlarmCommand> actorSystem = ActorSystem.create(
                AlarmSystem.createWithZones(config, new HashSet<>(Set.of(Zone.PERIMETER, Zone.LIVING_AREA)), eventConsumer),
                "SmartHomeAlarmSystem"
        );

        ActorRef<AlarmCommand> alarmSystem = actorSystem;

        ActorRef<AlarmCommand> motionSensorLiving = actorSystem.spawn(
                Sensor.create(SensorType.MOTION, Zone.LIVING_AREA, alarmSystem),
                "motion-living"
        );

        ActorRef<AlarmCommand> doorSensorPerimeter = actorSystem.spawn(
                Sensor.create(SensorType.DOOR, Zone.PERIMETER, alarmSystem),
                "door-perimeter"
        );

        ActorRef<AlarmCommand> windowSensorSleeping = actorSystem.spawn(
                Sensor.create(SensorType.WINDOW, Zone.SLEEPING_AREA, alarmSystem),
                "window-sleeping"
        );

        System.out.println("\nSystem initialized. Available commands:");
        System.out.println("  1. alarmSystem.tell(new ArmSystem(\"1234\")) - Arm the system");
        System.out.println("  2. alarmSystem.tell(new DisarmSystem(\"1234\")) - Disarm the system");
        System.out.println("  3. sensor.tell(new SensorEvent(..., true)) - Trigger sensor");

        FiniteDuration delay2s = Duration$.MODULE$.apply(2, "seconds");
        FiniteDuration delay3s = Duration$.MODULE$.apply(3, "seconds");
        FiniteDuration delay5s = Duration$.MODULE$.apply(5, "seconds");
        FiniteDuration delay8s = Duration$.MODULE$.apply(8, "seconds");
        FiniteDuration delay10s = Duration$.MODULE$.apply(10, "seconds");
        FiniteDuration delay12s = Duration$.MODULE$.apply(12, "seconds");

        scala.concurrent.ExecutionContextExecutor ec = actorSystem.executionContext();

        actorSystem.scheduler().scheduleOnce(delay2s, () -> {
            System.out.println("\n--- Simulation: Arming the system in 2 seconds ---");
        }, ec);

        actorSystem.scheduler().scheduleOnce(delay3s, () -> {
            System.out.println("\n--- Simulating: User enters PIN to arm system ---");
            alarmSystem.tell(new AlarmCommand.ArmSystem("1234"));
        }, ec);

        actorSystem.scheduler().scheduleOnce(delay5s, () -> {
            System.out.println("--- Simulating: Motion detected in living area ---");
            alarmSystem.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));
        }, ec);

        actorSystem.scheduler().scheduleOnce(delay8s, () -> {
            System.out.println("--- Simulating: User enters PIN to stop alarm ---");
            alarmSystem.tell(new AlarmCommand.StopAlarm("1234"));
        }, ec);

        actorSystem.scheduler().scheduleOnce(delay10s, () -> {
            System.out.println("\n--- Simulation complete ---");
        }, ec);

        actorSystem.scheduler().scheduleOnce(delay12s, () -> {
            System.out.println("\nShutting down...");
            actorSystem.terminate();
        }, ec);
    }
}
