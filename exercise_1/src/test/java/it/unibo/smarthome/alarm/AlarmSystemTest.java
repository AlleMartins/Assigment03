package it.unibo.smarthome.alarm;

import it.unibo.smarthome.model.*;
import it.unibo.smarthome.model.AlarmEvent.*;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the AlarmSystem state machine.
 * Uses Pekko TestKit with synchronous probes for deterministic testing.
 */
class AlarmSystemTest {

    private static ActorTestKit testKit;

    /** Short delays for fast test execution. */
    private static final Duration EXIT_DELAY = Duration.ofSeconds(1);
    private static final Duration ENTRY_DELAY = Duration.ofSeconds(1);
    private static final String PIN = "1234";
    private static final String WRONG_PIN = "9999";

    private static final AlarmConfig CONFIG = new AlarmConfig(EXIT_DELAY, ENTRY_DELAY, PIN);

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    // ── Helper to spawn a fresh alarm system + event probe ─────────────

    private record TestSetup(ActorRef<AlarmCommand> alarm, TestProbe<AlarmEvent> probe) {}

    private TestSetup createAlarmSystem() {
        return createAlarmSystem(Set.of(Zone.values()));
    }

    private TestSetup createAlarmSystem(Set<Zone> activeZones) {
        TestProbe<AlarmEvent> probe = testKit.createTestProbe(AlarmEvent.class);
        ActorRef<AlarmCommand> alarm = testKit.spawn(
                AlarmSystem.createWithZones(CONFIG, activeZones, probe.ref())
        );
        return new TestSetup(alarm, probe);
    }

    // ── 1. DISARMED → EXIT_DELAY → ARMED ──────────────────────────────

    @Test
    @DisplayName("Arming the system transitions through EXIT_DELAY to ARMED")
    void testArmingSequence() {
        var t = createAlarmSystem();

        // Arm with correct PIN
        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));

        // Expect: transition to EXIT_DELAY
        var exitDelayState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.EXIT_DELAY, exitDelayState.state());

        // Expect: ExitDelayStarted event
        t.probe.expectMessageClass(ExitDelayStarted.class);

        // Wait for exit delay timer to expire
        // Expect: transition to ARMED
        var armedState = t.probe.expectMessageClass(
                SystemStateUpdated.class, Duration.ofSeconds(3));
        assertEquals(State.ARMED, armedState.state());
    }

    // ── 2. ARMED → ENTRY_DELAY → ALARM ────────────────────────────────

    @Test
    @DisplayName("Sensor event in ARMED state triggers ENTRY_DELAY then ALARM")
    void testIntrusionSequence() {
        var t = createAlarmSystem();

        // Arm and wait until ARMED
        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        // Trigger sensor in active zone
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));

        // Expect: transition to ENTRY_DELAY
        var entryState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.ENTRY_DELAY, entryState.state());

        // Expect: EntryDelayStarted
        t.probe.expectMessageClass(EntryDelayStarted.class);

        // Wait for entry delay to expire
        // Expect: transition to ALARM
        var alarmState = t.probe.expectMessageClass(
                SystemStateUpdated.class, Duration.ofSeconds(3));
        assertEquals(State.ALARM, alarmState.state());

        // Expect: AlarmTriggered event
        t.probe.expectMessageClass(AlarmTriggered.class);
    }

    // ── 3. ENTRY_DELAY → DISARMED (user disarms in time) ──────────────

    @Test
    @DisplayName("Disarming during ENTRY_DELAY prevents alarm")
    void testDisarmDuringEntryDelay() {
        var t = createAlarmSystem();

        // Arm and wait until ARMED
        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        // Trigger sensor
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.DOOR, Zone.PERIMETER, true));
        t.probe.expectMessageClass(SystemStateUpdated.class); // ENTRY_DELAY
        t.probe.expectMessageClass(EntryDelayStarted.class);

        // Disarm before timer expires
        t.alarm.tell(new AlarmCommand.DisarmSystem(PIN));

        // Expect: transition to DISARMED
        var disarmedState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.DISARMED, disarmedState.state());

        // No ALARM should follow — verify no more messages for 2s
        t.probe.expectNoMessage(Duration.ofSeconds(2));
    }

    // ── 4. ALARM → DISARMED via StopAlarm ──────────────────────────────

    @Test
    @DisplayName("StopAlarm with correct PIN transitions from ALARM to DISARMED")
    void testStopAlarm() {
        var t = createAlarmSystem();

        // Arm → wait ARMED → trigger → wait ALARM
        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));
        t.probe.expectMessageClass(SystemStateUpdated.class); // ENTRY_DELAY
        t.probe.expectMessageClass(EntryDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ALARM
        t.probe.expectMessageClass(AlarmTriggered.class);

        // Stop alarm with correct PIN
        t.alarm.tell(new AlarmCommand.StopAlarm(PIN));

        // Expect: DISARMED + AlarmStopped
        var disarmedState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.DISARMED, disarmedState.state());

        var stopped = t.probe.expectMessageClass(AlarmStopped.class);
        assertEquals(PIN, stopped.pin());
    }

    // ── 5. Invalid PIN is rejected ─────────────────────────────────────

    @Test
    @DisplayName("Invalid PIN does not arm the system")
    void testInvalidPinArm() {
        var t = createAlarmSystem();

        t.alarm.tell(new AlarmCommand.ArmSystem(WRONG_PIN));

        // Expect: InvalidPin event, no state change
        var invalid = t.probe.expectMessageClass(InvalidPin.class);
        assertEquals(WRONG_PIN, invalid.attemptedPin());

        t.probe.expectNoMessage(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("Invalid PIN does not stop alarm")
    void testInvalidPinStopAlarm() {
        var t = createAlarmSystem();

        // Get to ALARM state
        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));
        t.probe.expectMessageClass(SystemStateUpdated.class);
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));
        t.probe.expectMessageClass(SystemStateUpdated.class);
        t.probe.expectMessageClass(EntryDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ALARM
        t.probe.expectMessageClass(AlarmTriggered.class);

        // Try stopping with wrong PIN
        t.alarm.tell(new AlarmCommand.StopAlarm(WRONG_PIN));

        var invalid = t.probe.expectMessageClass(InvalidPin.class);
        assertEquals(WRONG_PIN, invalid.attemptedPin());

        // System should still be in ALARM — no state change
        t.probe.expectNoMessage(Duration.ofMillis(500));
    }

    // ── 6. Zone-based: inactive zones are ignored ──────────────────────

    @Test
    @DisplayName("Sensor in inactive zone does not trigger entry delay")
    void testInactiveZoneIgnored() {
        // Only PERIMETER is active
        var t = createAlarmSystem(Set.of(Zone.PERIMETER));

        // Arm with partial zones and wait ARMED
        t.alarm.tell(new AlarmCommand.ArmSystemPartial(PIN, Set.of(Zone.PERIMETER)));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        // Trigger sensor in LIVING_AREA (not active)
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));

        // Should be ignored — no state transition
        t.probe.expectNoMessage(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Sensor in active zone triggers entry delay")
    void testActiveZoneTriggers() {
        var t = createAlarmSystem();

        t.alarm.tell(new AlarmCommand.ArmSystemPartial(PIN, Set.of(Zone.PERIMETER)));
        t.probe.expectMessageClass(SystemStateUpdated.class);
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        // Trigger sensor in PERIMETER (active zone)
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.DOOR, Zone.PERIMETER, true));

        // Should trigger ENTRY_DELAY
        var entryState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.ENTRY_DELAY, entryState.state());
    }

    // ── 7. Sensors ignored in DISARMED and EXIT_DELAY ──────────────────

    @Test
    @DisplayName("Sensor events are ignored in DISARMED state")
    void testSensorIgnoredWhenDisarmed() {
        var t = createAlarmSystem();

        // Send sensor event while DISARMED
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));

        // No events should be emitted
        t.probe.expectNoMessage(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("Sensor events are ignored during EXIT_DELAY")
    void testSensorIgnoredDuringExitDelay() {
        var t = createAlarmSystem();

        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);

        // Trigger sensor during EXIT_DELAY
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.LIVING_AREA, true));

        // Next message should be ARMED (from timer), not ENTRY_DELAY
        var armedState = t.probe.expectMessageClass(
                SystemStateUpdated.class, Duration.ofSeconds(3));
        assertEquals(State.ARMED, armedState.state());
    }

    // ── 8. Partial arming (bonus) ──────────────────────────────────────

    @Test
    @DisplayName("ArmSystemPartial activates only specified zones")
    void testPartialArming() {
        var t = createAlarmSystem();

        // Partial arming: only PERIMETER + GROUND_FLOOR
        t.alarm.tell(new AlarmCommand.ArmSystemPartial(PIN,
                Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);
        t.probe.expectMessageClass(SystemStateUpdated.class, Duration.ofSeconds(3)); // ARMED

        // Trigger in UPPER_FLOOR (inactive) — should be ignored
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.MOTION, Zone.UPPER_FLOOR, true));
        t.probe.expectNoMessage(Duration.ofMillis(500));

        // Trigger in PERIMETER (active) — should trigger entry delay
        t.alarm.tell(new AlarmCommand.SensorEvent(SensorType.DOOR, Zone.PERIMETER, true));
        var entryState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.ENTRY_DELAY, entryState.state());
    }

    // ── 9. Disarming during EXIT_DELAY ─────────────────────────────────

    @Test
    @DisplayName("Disarming during EXIT_DELAY cancels arming")
    void testDisarmDuringExitDelay() {
        var t = createAlarmSystem();

        t.alarm.tell(new AlarmCommand.ArmSystem(PIN));
        t.probe.expectMessageClass(SystemStateUpdated.class); // EXIT_DELAY
        t.probe.expectMessageClass(ExitDelayStarted.class);

        // Disarm immediately
        t.alarm.tell(new AlarmCommand.DisarmSystem(PIN));

        // Should go to DISARMED
        var disarmedState = t.probe.expectMessageClass(SystemStateUpdated.class);
        assertEquals(State.DISARMED, disarmedState.state());

        // ARMED should never follow
        t.probe.expectNoMessage(Duration.ofSeconds(2));
    }
}
