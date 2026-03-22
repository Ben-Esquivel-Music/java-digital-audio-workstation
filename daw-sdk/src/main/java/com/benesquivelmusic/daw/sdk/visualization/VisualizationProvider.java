package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Interface for components that produce visualization data.
 *
 * <p>Plugins and built-in processors that generate metering or analysis
 * data implement this interface to supply display-ready snapshots to
 * the DAW's visual display system.</p>
 *
 * <p>Implementations must be safe to call from both the audio thread
 * (for pushing data) and the UI thread (for reading snapshots).</p>
 *
 * @param <T> the type of visualization data produced
 */
public interface VisualizationProvider<T> {

    /**
     * Returns the most recent visualization data snapshot.
     *
     * <p>This method is called on the UI thread at the display refresh
     * rate. Implementations should return the latest available data
     * without blocking.</p>
     *
     * @return the latest visualization snapshot, or {@code null} if
     *         no data is available yet
     */
    T getLatestData();

    /**
     * Returns whether this provider currently has valid data available.
     *
     * @return {@code true} if {@link #getLatestData()} will return
     *         a non-null value
     */
    boolean hasData();
}
