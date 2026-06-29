package it.unibo.smarthome.model;

import java.time.Instant;

/**
 * Events emitted by the AlarmSystem actor.
 */
public interface AlarmEvent {
    record SystemStateUpdated(State state, Instant timestamp) implements AlarmEvent {}
    record AlarmTriggered(Zone zone, Instant timestamp) implements AlarmEvent {}
    record AlarmStopped(String pin, Instant timestamp) implements AlarmEvent {}
    record EntryDelayStarted(Zone zone, Instant timestamp) implements AlarmEvent {}
    record ExitDelayStarted(String pin, Instant timestamp) implements AlarmEvent {}
    record InvalidPin(String attemptedPin, Instant timestamp) implements AlarmEvent {}
}
