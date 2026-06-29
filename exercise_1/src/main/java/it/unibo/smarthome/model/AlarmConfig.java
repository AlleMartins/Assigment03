package it.unibo.smarthome.model;

import java.time.Duration;

/**
 * Configuration for the alarm system delays.
 */
public record AlarmConfig(
    Duration exitDelay,
    Duration entryDelay,
    String defaultPin
) {
    public static final Duration DEFAULT_EXIT_DELAY = Duration.ofSeconds(30);
    public static final Duration DEFAULT_ENTRY_DELAY = Duration.ofSeconds(15);
    public static final String DEFAULT_PIN = "1234";

    public static AlarmConfig defaults() {
        return new AlarmConfig(DEFAULT_EXIT_DELAY, DEFAULT_ENTRY_DELAY, DEFAULT_PIN);
    }
}
