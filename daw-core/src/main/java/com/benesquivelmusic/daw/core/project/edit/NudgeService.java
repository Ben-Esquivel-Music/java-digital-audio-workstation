package com.benesquivelmusic.daw.core.project.edit;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.NudgeClipsAction;
import com.benesquivelmusic.daw.core.clip.LockedClipException;

import java.util.List;
import java.util.Objects;

/**
 * Stateless helpers that convert {@link NudgeSettings} to a beat-delta
 * and build {@link NudgeClipsAction}s.
 *
 * <p>This service is deliberately side-effect free: it computes the
 * exact beat-delta for a given unit, tempo, sample rate, and grid
 * context, and returns an undoable action that the caller pushes onto
 * the undo stack. The single-step, 10× multiplier, and single-sample
 * nudge variants described in the user story are wired in the UI layer
 * on top of this service.</p>
 *
 * <p>Unit conversion formulae (tempo is in BPM = beats per minute,
 * sample rate in frames per second):</p>
 *
 * <ul>
 *   <li>{@code FRAMES}       → {@code beats = frames / sampleRate *
 *                                              tempo / 60}</li>
 *   <li>{@code MILLISECONDS} → {@code beats = ms / 1000 * tempo / 60}</li>
 *   <li>{@code GRID_STEPS}   → {@code beats = steps * gridStepBeats}</li>
 *   <li>{@code BAR_FRACTION} → {@code beats = fraction * barBeats}</li>
 * </ul>
 *
 * <p>Story — Nudge Clips and Selections by Grid and by Sample.</p>
 */
public final class NudgeService {

    private NudgeService() { /* utility class */ }

    /**
     * The timing context required to convert any {@link NudgeUnit} to
     * a beat delta.
     *
     * @param tempoBpm       project tempo in beats per minute
     *                       (strictly positive)
     * @param sampleRate     audio sample rate in frames per second
     *                       (strictly positive)
     * @param gridStepBeats  size of one editor grid step in beats
     *                       (strictly positive; e.g. {@code 0.25} for
     *                       1/16 note at 4/4)
     * @param barBeats       length of one bar in beats (strictly
     *                       positive; e.g. {@code 4.0} for 4/4)
     */
    public record TimingContext(double tempoBpm,
                                double sampleRate,
                                double gridStepBeats,
                                double barBeats) {
        public TimingContext {
            require(tempoBpm, "tempoBpm");
            require(sampleRate, "sampleRate");
            require(gridStepBeats, "gridStepBeats");
            require(barBeats, "barBeats");
        }

        private static void require(double v, String name) {
            if (!Double.isFinite(v) || v <= 0.0) {
                throw new IllegalArgumentException(
                        name + " must be a finite, strictly positive number: " + v);
            }
        }
    }

    /**
     * Converts a {@link NudgeSettings} value to a signed beat delta
     * using the supplied timing context. The sign is controlled by
     * {@code direction}: positive values nudge later, negative values
     * nudge earlier.
     *
     * <p>Example: {@code beatsFor(new NudgeSettings(GRID_STEPS, 1), ctx, +1)}
     * returns {@code ctx.gridStepBeats()}.</p>
     *
     * @param settings  the nudge configuration
     * @param context   timing context (tempo, sample rate, grid)
     * @param direction {@code +1} for "nudge right / later",
     *                  {@code -1} for "nudge left / earlier". Any
     *                  other finite value is accepted and treated as a
     *                  user-supplied multiplier (e.g. {@code 10} for
     *                  the 10× nudge shortcut).
     * @return the signed beat delta corresponding to one nudge in the
     *         configured unit multiplied by {@code direction}
     */
    public static double beatsFor(NudgeSettings settings,
                                  TimingContext context,
                                  double direction) {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (!Double.isFinite(direction)) {
            throw new IllegalArgumentException(
                    "direction must be a finite number: " + direction);
        }
        double magnitudeBeats = switch (settings.unit()) {
            case FRAMES        -> settings.amount() / context.sampleRate()
                                  * context.tempoBpm() / 60.0;
            case MILLISECONDS  -> settings.amount() / 1000.0
                                  * context.tempoBpm() / 60.0;
            case GRID_STEPS    -> settings.amount() * context.gridStepBeats();
            case BAR_FRACTION  -> settings.amount() * context.barBeats();
        };
        return magnitudeBeats * direction;
    }

    /**
     * Convenience overload: convert a single sample (frame) to beats.
     * This backs the "nudge by one sample" shortcut, which is
     * independent of the user's configured {@link NudgeSettings}.
     *
     * @param context   timing context
     * @param direction {@code +1} or {@code -1}
     * @return the signed beat delta for a one-sample nudge
     */
    public static double beatsForOneSample(TimingContext context, double direction) {
        return beatsFor(new NudgeSettings(NudgeUnit.FRAMES, 1.0), context, direction);
    }

    /**
     * Builds a single {@link NudgeClipsAction} that nudges every clip
     * in {@code clips} by the same {@code beatDelta}. The action is
     * <em>one</em> undo step even when many clips are involved —
     * exactly what the user story's "multi-selection nudge is a single
     * undo step" requirement asks for.
     *
     * <p>If {@code beatDelta} is zero, {@code clips} is empty, or every
     * requested move would be clamped away (negative delta against a
     * selection whose earliest clip is already at beat {@code 0}),
     * {@code null} is returned and the caller should skip the undo
     * push.</p>
     *
     * @param clips      the clips to nudge (already-resolved selection,
     *                   possibly from a time-range selection or a
     *                   multi-selection)
     * @param beatDelta  the signed beat delta to apply to each clip
     * @return the undoable nudge action, or {@code null} for a no-op
     */
    public static NudgeClipsAction buildAction(List<AudioClip> clips, double beatDelta) {
        Objects.requireNonNull(clips, "clips must not be null");
        if (clips.isEmpty() || beatDelta == 0.0 || !Double.isFinite(beatDelta)) {
            return null;
        }
        // Refuse the entire nudge if any selected clip is locked. Surfaced
        // by the UI as a status-bar message — see story
        // "Clip-Level Time Lock Preventing Accidental Movement".
        LockedClipException.requireUnlocked("Nudge", clips);
        // Pre-clamp negative deltas: if every clip in the selection is
        // already at beat 0 (or the selection's earliest start beat is
        // 0), a leftward nudge would clamp to 0 in NudgeClipsAction.execute()
        // and produce a no-op undo step. Skip the push so the undo
        // history stays clean.
        if (beatDelta < 0.0) {
            double earliestStartBeat = Double.POSITIVE_INFINITY;
            for (AudioClip clip : clips) {
                earliestStartBeat = Math.min(earliestStartBeat, clip.getStartBeat());
            }
            if (Double.isFinite(earliestStartBeat)
                    && Math.max(beatDelta, -earliestStartBeat) == 0.0) {
                return null;
            }
        }
        return new NudgeClipsAction(clips, beatDelta);
    }
}
