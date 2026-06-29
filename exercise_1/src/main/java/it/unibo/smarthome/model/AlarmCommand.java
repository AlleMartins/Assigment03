package it.unibo.smarthome.model;

import java.util.Set;

/**
 * Commands that can be sent to the AlarmSystem actor.
 */
public interface AlarmCommand {
    /** Arm the system with all zones active. */
    record ArmSystem(String pin) implements AlarmCommand {}

    /** Arm the system with only the specified zones active (partial arming). */
    record ArmSystemPartial(String pin, Set<Zone> zones) implements AlarmCommand {}

    /** Disarm the system (valid in EXIT_DELAY, ARMED, ENTRY_DELAY). */
    record DisarmSystem(String pin) implements AlarmCommand {}

    /** Event from a sensor detecting activity. */
    record SensorEvent(SensorType type, Zone zone, boolean triggered) implements AlarmCommand {}

    /** Stop the alarm siren (valid only in ALARM state). */
    record StopAlarm(String pin) implements AlarmCommand {}

    // --- Internal timer messages (not meant for external use) ---

    /** Fired when the exit delay timer expires. */
    record ExitDelayTimeout() implements AlarmCommand {}

    /** Fired when the entry delay timer expires. */
    record EntryDelayTimeout(Zone zone) implements AlarmCommand {}
}
