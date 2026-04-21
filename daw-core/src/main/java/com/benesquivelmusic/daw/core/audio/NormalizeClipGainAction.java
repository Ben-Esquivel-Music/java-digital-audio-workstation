package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An undoable "Normalize to &minus;X&nbsp;dB" action: analyzes the peak
 * sample level of the clip's source audio and adjusts the
 * {@link ClipGainEnvelope}'s first breakpoint so that the peak reaches the
 * specified target level in dBFS.
 *
 * <p>The adjustment is applied as a constant offset to every breakpoint's
 * dB value so the envelope's shape is preserved. If the clip has no
 * envelope, a one-breakpoint envelope at frame&nbsp;0 is created at the
 * computed offset. Silent clips (peak = 0) leave the envelope unchanged.</p>
 */
public final class NormalizeClipGainAction implements UndoableAction {

    private static final double SILENCE_EPSILON = 1.0e-12;

    private final AudioClip clip;
    private final double targetDb;

    private ClipGainEnvelope originalEnvelope;
    private boolean originalEnvelopeCaptured;

    /**
     * Creates a new normalize-clip-gain action.
     *
     * @param clip     the clip to analyze and re-gain
     * @param targetDb the desired peak level in dBFS (e.g. {@code -1.0})
     */
    public NormalizeClipGainAction(AudioClip clip, double targetDb) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.targetDb = targetDb;
    }

    @Override
    public String description() {
        return "Normalize Clip Gain";
    }

    @Override
    public void execute() {
        this.originalEnvelope = clip.gainEnvelope().orElse(null);
        this.originalEnvelopeCaptured = true;

        double peak = computePeak(clip.getAudioData());
        if (peak <= SILENCE_EPSILON) {
            return; // nothing to normalize
        }
        double peakDb = 20.0 * Math.log10(peak);
        double offsetDb = targetDb - peakDb;

        ClipGainEnvelope next;
        if (originalEnvelope == null) {
            next = new ClipGainEnvelope(List.of(
                    new ClipGainEnvelope.BreakpointDb(0L, offsetDb, CurveShape.LINEAR)));
        } else {
            var shifted = new ArrayList<ClipGainEnvelope.BreakpointDb>(
                    originalEnvelope.breakpoints().size());
            for (ClipGainEnvelope.BreakpointDb bp : originalEnvelope.breakpoints()) {
                shifted.add(new ClipGainEnvelope.BreakpointDb(
                        bp.frameOffsetInClip(),
                        bp.dbGain() + offsetDb,
                        bp.curve()));
            }
            next = new ClipGainEnvelope(shifted);
        }
        clip.setGainEnvelope(next);
    }

    @Override
    public void undo() {
        if (!originalEnvelopeCaptured) {
            throw new IllegalStateException("undo() called before execute()");
        }
        clip.setGainEnvelope(originalEnvelope);
    }

    private static double computePeak(float[][] audioData) {
        if (audioData == null || audioData.length == 0) {
            return 0.0;
        }
        double peak = 0.0;
        for (float[] channel : audioData) {
            if (channel == null) continue;
            for (float s : channel) {
                double a = Math.abs(s);
                if (a > peak) peak = a;
            }
        }
        return peak;
    }
}
