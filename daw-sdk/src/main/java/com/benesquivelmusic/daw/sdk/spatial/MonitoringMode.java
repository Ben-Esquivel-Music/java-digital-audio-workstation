package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Monitoring output mode for A/B switching between speaker and binaural rendering.
 *
 * <p>When set to {@link #SPEAKER}, audio passes through unchanged for
 * loudspeaker playback. When set to {@link #BINAURAL}, the
 * {@link BinauralRenderer} applies HRTF-based spatialization for
 * headphone monitoring.</p>
 */
public enum MonitoringMode {

    /** Standard loudspeaker output — no binaural processing. */
    SPEAKER,

    /** Headphone output with HRTF-based binaural rendering. */
    BINAURAL
}
