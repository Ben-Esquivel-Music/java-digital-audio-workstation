package com.benesquivelmusic.daw.sdk.visualization;

/**
 * Analysis modes for stereo correlation metering.
 *
 * <p>Controls whether the correlation meter operates in real-time
 * (updating on every audio buffer) or post-playback mode (accumulating
 * statistics across the entire playback session for a final summary).</p>
 */
public enum AnalysisMode {

    /** Real-time analysis — updates correlation data on every audio buffer. */
    REAL_TIME("Real-Time"),

    /** Post-playback analysis — accumulates statistics for a summary after playback. */
    POST_PLAYBACK("Post-Playback");

    private final String displayName;

    AnalysisMode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a human-readable display name for this analysis mode.
     *
     * @return display name (e.g. "Real-Time")
     */
    public String displayName() {
        return displayName;
    }
}
