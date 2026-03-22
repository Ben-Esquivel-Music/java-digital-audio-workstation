package com.benesquivelmusic.daw.sdk.spatial;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Binaural rendering engine that spatializes audio for headphone playback
 * using Head-Related Transfer Functions (HRTFs).
 *
 * <p>Implementations apply direction-dependent HRTF filtering to produce
 * a binaural stereo signal that simulates 3D audio perception over
 * headphones. Key capabilities include:</p>
 * <ul>
 *   <li>Loading HRTF data from SOFA files or other sources</li>
 *   <li>HRTF interpolation for arbitrary source positions</li>
 *   <li>Partitioned convolution for efficient real-time filtering</li>
 *   <li>Interaural time difference (ITD) modeling</li>
 *   <li>Crossfade between HRTF filters on position changes</li>
 *   <li>A/B monitoring mode switching (speaker ↔ binaural)</li>
 * </ul>
 *
 * <p>The renderer accepts mono or stereo input and always produces
 * stereo (binaural) output when in {@link MonitoringMode#BINAURAL} mode.</p>
 */
public interface BinauralRenderer extends AudioProcessor {

    /**
     * Loads an HRTF dataset for binaural rendering.
     *
     * <p>This replaces any previously loaded dataset and rebuilds
     * internal convolution structures.</p>
     *
     * @param data the HRTF dataset to use
     */
    void loadHrtfData(HrtfData data);

    /**
     * Returns the currently loaded HRTF dataset, or {@code null} if none is loaded.
     *
     * @return the current HRTF data
     */
    HrtfData getHrtfData();

    /**
     * Sets the virtual source position for rendering.
     *
     * <p>When the position changes, the renderer crossfades between the
     * previous and new HRTF filters to avoid audible clicks.</p>
     *
     * @param position the source direction in spherical coordinates
     */
    void setSourcePosition(SphericalCoordinate position);

    /**
     * Returns the current virtual source position.
     *
     * @return the source position
     */
    SphericalCoordinate getSourcePosition();

    /**
     * Sets the monitoring mode.
     *
     * <p>In {@link MonitoringMode#SPEAKER} mode, audio passes through
     * unchanged. In {@link MonitoringMode#BINAURAL} mode, HRTF-based
     * spatialization is applied.</p>
     *
     * @param mode the monitoring mode
     */
    void setMonitoringMode(MonitoringMode mode);

    /**
     * Returns the current monitoring mode.
     *
     * @return the monitoring mode
     */
    MonitoringMode getMonitoringMode();

    /**
     * Sets the crossfade duration used when switching HRTF filters.
     *
     * @param durationMs crossfade duration in milliseconds (must be non-negative)
     */
    void setCrossfadeDurationMs(double durationMs);
}
