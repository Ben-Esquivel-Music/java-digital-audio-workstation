package com.benesquivelmusic.daw.core.persistence;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the auto-save / checkpoint system.
 *
 * <p>Controls how frequently the DAW saves project data and how many
 * checkpoint copies are retained. Designed to protect long-running
 * recording sessions (hours or days) against data loss.</p>
 *
 * @param autoSaveInterval  the interval between automatic saves
 * @param maxCheckpoints    the maximum number of checkpoint files to retain
 * @param enabled           whether auto-save is enabled
 */
public record AutoSaveConfig(
        Duration autoSaveInterval,
        int maxCheckpoints,
        boolean enabled
) {
    /** Default configuration: auto-save every 2 minutes, keep 50 checkpoints. */
    public static final AutoSaveConfig DEFAULT =
            new AutoSaveConfig(Duration.ofMinutes(2), 50, true);

    /** Aggressive configuration for long sessions: save every 30 seconds, keep 200 checkpoints. */
    public static final AutoSaveConfig LONG_SESSION =
            new AutoSaveConfig(Duration.ofSeconds(30), 200, true);

    public AutoSaveConfig {
        Objects.requireNonNull(autoSaveInterval, "autoSaveInterval must not be null");
        if (autoSaveInterval.isNegative() || autoSaveInterval.isZero()) {
            throw new IllegalArgumentException("autoSaveInterval must be positive: " + autoSaveInterval);
        }
        if (maxCheckpoints < 1) {
            throw new IllegalArgumentException("maxCheckpoints must be at least 1: " + maxCheckpoints);
        }
    }

    /**
     * Returns a copy with auto-save enabled or disabled.
     *
     * @param enabled whether auto-save should be enabled
     * @return the updated config
     */
    public AutoSaveConfig withEnabled(boolean enabled) {
        return new AutoSaveConfig(autoSaveInterval, maxCheckpoints, enabled);
    }
}
