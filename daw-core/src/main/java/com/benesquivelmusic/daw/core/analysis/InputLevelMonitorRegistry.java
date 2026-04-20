package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of {@link InputLevelMonitor} instances keyed by track id.
 *
 * <p>The recording pipeline calls {@link #getOrCreate(String)} for each
 * currently-armed track when tapping the input signal ahead of any
 * processing. The mixer UI and arrangement-view track header call
 * {@link #get(String)} to read a monitor's latest snapshot for display.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *     <li><b>Runtime-only.</b> This registry is never persisted — user story
 *     137 explicitly requires no persistence of meter state.</li>
 *     <li>Monitors are kept for the lifetime of the DAW session. Disarming a
 *     track leaves its monitor in place (cheap; a few words of state) so
 *     that re-arming resumes immediately without a state-creation race.
 *     {@link #remove(String)} is provided for callers that want to eagerly
 *     free state on track deletion.</li>
 *     <li>{@link #resetAll()} clears the sticky clip flag on every monitor;
 *     it is wired to the {@code Alt+click} gesture on any clip LED.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Backed by a {@link ConcurrentHashMap} so that the audio render thread
 * and the UI thread can both consult the registry without locking. The
 * {@link InputLevelMonitor} instances it holds have their own thread-safety
 * contract (volatile snapshot, audio-thread-only mutation of internal
 * state) — see that class's javadoc.</p>
 */
public final class InputLevelMonitorRegistry {

    private final Map<String, InputLevelMonitor> byTrackId = new ConcurrentHashMap<>();

    /** Returns the monitor for {@code trackId}, creating one on first access. */
    public InputLevelMonitor getOrCreate(String trackId) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        return byTrackId.computeIfAbsent(trackId, _ -> new InputLevelMonitor());
    }

    /**
     * Convenience overload that keys by {@link Track#getId()}.
     */
    public InputLevelMonitor getOrCreate(Track track) {
        Objects.requireNonNull(track, "track must not be null");
        return getOrCreate(track.getId());
    }

    /** Returns the existing monitor for {@code trackId}, or {@code null}. */
    public InputLevelMonitor get(String trackId) {
        if (trackId == null) {
            return null;
        }
        return byTrackId.get(trackId);
    }

    /** Removes and returns the monitor for {@code trackId}, or {@code null}. */
    public InputLevelMonitor remove(String trackId) {
        if (trackId == null) {
            return null;
        }
        return byTrackId.remove(trackId);
    }

    /** Returns the number of monitors currently registered. */
    public int size() {
        return byTrackId.size();
    }

    /**
     * Resets the sticky clip flag on every registered monitor.
     *
     * <p>Wired to the {@code Alt+click} gesture on any clip LED so engineers
     * can acknowledge clips on many tracks at once.</p>
     */
    public void resetAll() {
        for (InputLevelMonitor m : byTrackId.values()) {
            m.reset();
        }
    }

    /** Removes every registered monitor. */
    public void clear() {
        byTrackId.clear();
    }
}
