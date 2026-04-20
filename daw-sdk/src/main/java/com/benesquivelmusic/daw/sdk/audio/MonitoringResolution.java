package com.benesquivelmusic.daw.sdk.audio;

/**
 * The resolved audibility decision for a track's monitoring source on a
 * single processing block.
 *
 * <p>A {@code MonitoringResolution} describes which signal — the live
 * <em>input</em>, the recorded <em>playback</em>, or both during a short
 * equal-power crossfade — should be made audible on the track's mixer
 * channel. It is the typed return value of resolving a track's
 * {@code InputMonitoringMode} against the current transport state,
 * recording activity and punch region.</p>
 *
 * <p>The resolution is produced by
 * {@code InputMonitoringMode.resolve(...)} in {@code daw-core} and
 * consumed by the render pipeline in the per-track read step, where it
 * selects the routed input buffer, the playback signal, or a
 * crossfaded mix of the two at tape-mode punch boundaries.</p>
 *
 * <p>This type is intentionally placed in {@code daw-sdk} so that UI
 * layers, audio processors, and extension plugins can reason about the
 * resolved monitoring state without depending on the core recording
 * pipeline's internals.</p>
 *
 * <p>Although Java records are already implicitly {@code final}, this
 * type is part of a small conceptual family (alongside future
 * {@code MuteResolution}, {@code SoloResolution}) and is treated as a
 * sealed value carrier for that family — no other types may implement
 * or extend it.</p>
 *
 * @param inputAudible     {@code true} if the live input should be routed
 *                         through the mixer channel
 * @param playbackAudible  {@code true} if the recorded playback should be
 *                         routed through the mixer channel
 * @param crossfadeFrames  the number of frames over which to ramp between
 *                         {@code inputAudible} and {@code playbackAudible}
 *                         when they differ from the previous block's
 *                         resolution; {@code 0} means an instantaneous
 *                         hard switch. Non-negative.
 */
public record MonitoringResolution(
        boolean inputAudible,
        boolean playbackAudible,
        double crossfadeFrames) {

    /** A resolution that mutes all monitoring (no input, no playback). */
    public static final MonitoringResolution SILENT =
            new MonitoringResolution(false, false, 0.0);

    /** A resolution that makes only the live input audible. */
    public static final MonitoringResolution INPUT_AUDIBLE =
            new MonitoringResolution(true, false, 0.0);

    /** A resolution that makes only the recorded playback audible. */
    public static final MonitoringResolution PLAYBACK_AUDIBLE =
            new MonitoringResolution(false, true, 0.0);

    /**
     * Compact constructor: validates that {@code crossfadeFrames} is a
     * finite, non-negative number of frames.
     */
    public MonitoringResolution {
        if (Double.isNaN(crossfadeFrames) || Double.isInfinite(crossfadeFrames)) {
            throw new IllegalArgumentException(
                    "crossfadeFrames must be finite, was " + crossfadeFrames);
        }
        if (crossfadeFrames < 0.0) {
            throw new IllegalArgumentException(
                    "crossfadeFrames must be non-negative, was " + crossfadeFrames);
        }
    }

    /**
     * Returns a resolution with the same audibility flags but with all
     * monitoring forcibly muted. Used by the "Mute All Inputs" panic
     * button on the mixer to silence every track's monitor send without
     * changing any configured monitoring modes.
     *
     * @return a {@link #SILENT} resolution
     */
    public MonitoringResolution muted() {
        return SILENT;
    }
}
