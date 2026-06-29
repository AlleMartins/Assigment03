package it.unibo.smarthome;

import it.unibo.smarthome.alarm.AlarmSystem;
import it.unibo.smarthome.model.*;
import it.unibo.smarthome.sensor.Sensor;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.Set;

/**
 * Main entry point for the Smart Home Alarm System.
 * <p>
 * Demonstrates the full alarm lifecycle with a timed simulation:
 * <ol>
 * <li>System starts DISARMED</li>
 * <li>User arms the system (partial arming — PERIMETER + LIVING_AREA only)</li>
 * <li>Exit delay countdown begins</li>
 * <li>System becomes ARMED</li>
 * <li>Motion sensor triggers in LIVING_AREA → entry delay</li>
 * <li>Entry delay expires → ALARM</li>
 * <li>User enters PIN to stop alarm → DISARMED</li>
 * <li>Bonus: window sensor in SLEEPING_AREA is ignored (zone not active)</li>
 * </ol>
 */
public class Main {

    public static void main(String[] args) {
        // Short delays for demo purposes
        AlarmConfig config = new AlarmConfig(
                Duration.ofSeconds(5), // exit delay
                Duration.ofSeconds(3), // entry delay
                "1234" // PIN
        );

        // Set of zones to arm (partial arming — bonus feature demo)
        Set<Zone> partialZones = Set.of(Zone.PERIMETER, Zone.LIVING_AREA);

        // Create the actor system with a guardian actor that sets up everything
        ActorSystem<Void> system = ActorSystem.create(
                guardianBehavior(config, partialZones),
                "SmartHomeAlarmSystem");
    }

    /**
     * Guardian actor: spawns the AlarmSystem, EventLogger, and Sensors,
     * then runs a timed simulation.
     */
    private static Behavior<Void> guardianBehavior(AlarmConfig config, Set<Zone> partialZones) {
        return Behaviors.setup(context -> {
            context.getLog().info("╔══════════════════════════════════════════╗");
            context.getLog().info("║   Smart Home Alarm System — Starting    ║");
            context.getLog().info("╚══════════════════════════════════════════╝");

            // ── Spawn the event logger actor ───────────────────────────
            ActorRef<AlarmEvent> eventLogger = context.spawn(
                    Behaviors.setup(ctx -> new AbstractBehavior<AlarmEvent>(ctx) {
                        @Override
                        public Receive<AlarmEvent> createReceive() {
                            return newReceiveBuilder()
                                    .onMessage(AlarmEvent.class, event -> {
                                        getContext().getLog().info("📢 EVENT: {}", event);
                                        return Behaviors.same();
                                    })
                                    .build();
                        }
                    }),
                    "event-logger");

            // ── Spawn the alarm system actor ───────────────────────────
            ActorRef<AlarmCommand> alarmSystem = context.spawn(
                    AlarmSystem.createWithZones(config, partialZones, eventLogger),
                    "alarm-system");

            // ── Spawn sensor actors ────────────────────────────────────
            ActorRef<Sensor.Command> motionLiving = context.spawn(
                    Sensor.create(SensorType.MOTION, Zone.LIVING_AREA, alarmSystem),
                    "sensor-motion-living");

            ActorRef<Sensor.Command> doorPerimeter = context.spawn(
                    Sensor.create(SensorType.DOOR, Zone.PERIMETER, alarmSystem),
                    "sensor-door-perimeter");

            ActorRef<Sensor.Command> windowSleeping = context.spawn(
                    Sensor.create(SensorType.WINDOW, Zone.SLEEPING_AREA, alarmSystem),
                    "sensor-window-sleeping");

            // ── Schedule simulation events ─────────────────────────────
            var scheduler = context.getSystem().scheduler();
            var ec = context.getSystem().executionContext();

            // t=1s: Arm with partial zones
            scheduler.scheduleOnce(Duration.ofSeconds(1), () -> {
                System.out.println("\n━━━ [SIM] User enters PIN to arm (partial: " + partialZones + ") ━━━");
                alarmSystem.tell(new AlarmCommand.ArmSystemPartial("1234", partialZones));
            }, ec);

            // t=3s: Try triggering a sensor in inactive zone (SLEEPING_AREA) — should be
            // ignored
            scheduler.scheduleOnce(Duration.ofSeconds(3), () -> {
                System.out.println("\n━━━ [SIM] Window sensor in SLEEPING_AREA (inactive zone) ━━━");
                windowSleeping.tell(new Sensor.Trigger());
            }, ec);

            // t=8s: Motion detected in LIVING_AREA (exit delay = 5s, so system is ARMED by
            // now)
            scheduler.scheduleOnce(Duration.ofSeconds(8), () -> {
                System.out.println("\n━━━ [SIM] Motion detected in LIVING_AREA! ━━━");
                motionLiving.tell(new Sensor.Trigger());
            }, ec);

            // t=14s: Entry delay (3s) has expired → ALARM is active. User stops alarm.
            scheduler.scheduleOnce(Duration.ofSeconds(14), () -> {
                System.out.println("\n━━━ [SIM] User enters PIN to stop alarm ━━━");
                alarmSystem.tell(new AlarmCommand.StopAlarm("1234"));
            }, ec);

            // t=16s: Arm again (full arming this time)
            scheduler.scheduleOnce(Duration.ofSeconds(16), () -> {
                System.out.println("\n━━━ [SIM] User arms full system ━━━");
                alarmSystem.tell(new AlarmCommand.ArmSystem("1234"));
            }, ec);

            // t=23s: Door detected in PERIMETER → entry delay
            scheduler.scheduleOnce(Duration.ofSeconds(23), () -> {
                System.out.println("\n━━━ [SIM] Door opened at PERIMETER! ━━━");
                doorPerimeter.tell(new Sensor.Trigger());
            }, ec);

            // t=25s: User disarms during entry delay (before alarm triggers)
            scheduler.scheduleOnce(Duration.ofSeconds(25), () -> {
                System.out.println("\n━━━ [SIM] User enters PIN to disarm during entry delay ━━━");
                alarmSystem.tell(new AlarmCommand.DisarmSystem("1234"));
            }, ec);

            // t=28s: Shutdown
            scheduler.scheduleOnce(Duration.ofSeconds(28), () -> {
                System.out.println("\n╔══════════════════════════════════════════╗");
                System.out.println("║   Simulation complete — shutting down    ║");
                System.out.println("╚══════════════════════════════════════════╝");
                context.getSystem().terminate();
            }, ec);

            return Behaviors.empty();
        });
    }
}
