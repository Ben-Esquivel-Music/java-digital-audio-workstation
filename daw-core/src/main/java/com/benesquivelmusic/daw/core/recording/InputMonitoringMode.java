package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.MonitoringResolution;

import java.util.Objects;

/**
 * Controls when input monitoring is active on an armed track.
 *
 * <p>Input monitoring routes the audio input signal through the track's
 * mixer channel so the performer can hear themselves in real time. The
 * mode is owned per-track by {@link com.benesquivelmusic.daw.core.track.Track};
 * {@link RecordingPipeline} exposes a pipeline-level mode that acts as the
 * default for newly armed tracks.</p>
 *
 * <p>Every user-visible recording workflow has a natural monitoring
 * policy:</p>
 * <ul>
 *   <li>{@link #OFF} — e.g. a kick drum where the headphone send comes
 *       from a separate cue mix and the DAW must never mix the input.</li>
 *   <li>{@link #AUTO} — e.g. a lead vocal: hear yourself only while armed
 *       and recording; hear the comp on playback.</li>
 *   <li>{@link #ALWAYS} — e.g. a synth player who is performing live
 *       throughout the song, recording or not.</li>
 *   <li>{@link #TAPE} — e.g. a punch-in vocal take: behave like an analog
 *       tape machine so the singer hears continuous audio across the
 *       punch boundary (tape-head semantics).</li>
 * </ul>
 */
public enum InputMonitoringMode {

    /**
     * Input monitoring is disabled. The input signal is not routed
     * through the mixer channel during recording.
     */
    OFF,

    /**
     * Input monitoring is automatically enabled while the track is
     * armed and the transport is in recording mode, and disabled
     * during playback.
     */
    AUTO,

    /**
     * Input monitoring is always enabled while the track is armed,
     * regardless of the transport state.
     */
    ALWAYS,

    /**
     * Tape-machine style monitoring. While stopped or recording inside
     * the punch range, the live input is audible; while playing back
     * (or playing outside the punch range), the tape — i.e. the
     * recorded audio on the track — is audible. Matches Pro Tools'
     * <em>TrackInput</em>, Studio One's <em>Tape</em> and Cubase's
     * <em>Tapemachine Style</em> behaviour.
     *
     * <p>The resolver supplies a short equal-power
     * {@linkplain MonitoringResolution#crossfadeFrames() crossfade}
     * at the input&nbsp;&#8596;&nbsp;playback transition so the boundary
     * is click-free.</p>
     */
    TAPE;

    /**
     * Default duration, in frames, of the tape-mode crossfade applied
     * at the input&nbsp;&#8596;&nbsp;playback transition. 5&nbsp;ms at
     * 48&nbsp;kHz is inaudible yet long enough to smooth any DC step at
     * the boundary. This value is used when callers don't supply a
     * sample rate.
     */
    private static final double DEFAULT_TAPE_CROSSFADE_FRAMES = 240.0;

    /**
     * Tape-mode crossfade duration, in seconds. The resolver converts
     * this to frames when a sample rate is provided.
     */
    public static final double TAPE_CROSSFADE_SECONDS = 0.005;

    /**
     * Resolves this mode against the current transport state, the
     * track's armed/recording status, and whether playback is currently
     * inside a punch range, returning the {@link MonitoringResolution}
     * that the render pipeline should apply for the next block.
     *
     * <p>Semantics:</p>
     * <ul>
     *   <li>{@link #OFF} — always {@link MonitoringResolution#SILENT}
     *       for the input path; playback follows normal track routing.</li>
     *   <li>{@link #ALWAYS} — input is audible whenever the track is
     *       armed; playback is not duplicated through the monitor.</li>
     *   <li>{@link #AUTO} — input is audible only while the transport
     *       is recording and the track is armed.</li>
     *   <li>{@link #TAPE} — input is audible while stopped, paused, or
     *       recording inside the punch range; otherwise playback is
     *       audible. Supplies a short crossfade at the punch
     *       boundary.</li>
     * </ul>
     *
     * <p>If the track is not armed, the resolution is always
     * {@link MonitoringResolution#SILENT} — an unarmed track cannot be
     * monitored.</p>
     *
     * @param state          the current transport state (never {@code null})
     * @param armed          {@code true} if the track is armed for recording
     * @param insidePunch    {@code true} if the transport position is inside
     *                       the configured punch range (ignored unless mode
     *                       is {@link #TAPE}); when no punch range is
     *                       configured, pass {@code true} to treat the whole
     *                       timeline as "inside" so tape-mode behaves like
     *                       a classic tape head
     * @param sampleRate     the audio sample rate in Hz, used to compute
     *                       the tape-mode crossfade length in frames;
     *                       pass {@code 0} to use the default 240-frame
     *                       crossfade
     * @return the resolution that the render pipeline should apply;
     *         never {@code null}
     */
    public MonitoringResolution resolve(TransportState state,
                                         boolean armed,
                                         boolean insidePunch,
                                         double sampleRate) {
        Objects.requireNonNull(state, "state must not be null");
        if (!armed) {
            return MonitoringResolution.SILENT;
        }
        double tapeXfadeFrames = (sampleRate > 0.0)
                ? TAPE_CROSSFADE_SECONDS * sampleRate
                : DEFAULT_TAPE_CROSSFADE_FRAMES;
        return switch (this) {
            case OFF -> MonitoringResolution.SILENT;
            case ALWAYS -> MonitoringResolution.INPUT_AUDIBLE;
            case AUTO -> (state == TransportState.RECORDING)
                    ? MonitoringResolution.INPUT_AUDIBLE
                    : MonitoringResolution.SILENT;
            case TAPE -> resolveTape(state, insidePunch, tapeXfadeFrames);
        };
    }

    private static MonitoringResolution resolveTape(TransportState state,
                                                     boolean insidePunch,
                                                     double crossfadeFrames) {
        // Tape-machine semantics:
        //   - Stopped / paused → hear the input (the singer hears themselves
        //     before and after punching).
        //   - Recording + inside the punch → hear the input (you are
        //     overwriting tape; hear what you are singing).
        //   - Playing back, or recording *outside* the punch → hear the
        //     tape, i.e. the recorded playback (continuous monitoring
        //     across the punch boundary).
        boolean inputAudible = switch (state) {
            case STOPPED, PAUSED -> true;
            case RECORDING -> insidePunch;
            case PLAYING -> false;
        };
        return new MonitoringResolution(inputAudible, !inputAudible, crossfadeFrames);
    }
}
