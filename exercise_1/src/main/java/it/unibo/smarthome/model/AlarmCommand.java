package it.unibo.smarthome.model;

/**
 * Commands that can be sent to the AlarmSystem actor.
 */
public interface AlarmCommand {
    record ArmSystem(String pin) implements AlarmCommand {}
    record DisarmSystem(String pin) implements AlarmCommand {}
    record SensorEvent(SensorType type, Zone zone, boolean triggered) implements AlarmCommand {}
    record StopAlarm(String pin) implements AlarmCommand {}
    record ExitDelayTimer() implements AlarmCommand {}
    record EntryDelayTimer(Zone zone) implements AlarmCommand {}
}
