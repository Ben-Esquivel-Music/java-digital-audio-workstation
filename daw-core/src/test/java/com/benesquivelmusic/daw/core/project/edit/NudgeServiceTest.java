package com.benesquivelmusic.daw.core.project.edit;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.NudgeClipsAction;
import com.benesquivelmusic.daw.core.project.edit.NudgeService.TimingContext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link NudgeSettings}, {@link NudgeUnit},
 * {@link NudgeService}, and {@link NudgeClipsAction}.
 *
 * <p>Story — Nudge Clips and Selections by Grid and by Sample.</p>
 */
class NudgeServiceTest {

    private static final double EPS = 1e-9;

    // ── NudgeSettings / NudgeUnit ─────────────────────────────────────────

    @Test
    void defaultSettingsAreOneGridStep() {
        assertThat(NudgeSettings.DEFAULT.unit()).isEqualTo(NudgeUnit.GRID_STEPS);
        assertThat(NudgeSettings.DEFAULT.amount()).isEqualTo(1.0);
    }

    @Test
    void nudgeSettingsRejectsNullUnit() {
        assertThatNullPointerException()
                .isThrownBy(() -> new NudgeSettings(null, 1.0));
    }

    @Test
    void nudgeSettingsRejectsNonPositiveAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NudgeSettings(NudgeUnit.FRAMES, 0.0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NudgeSettings(NudgeUnit.FRAMES, -1.0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NudgeSettings(NudgeUnit.FRAMES, Double.NaN));
    }

    // ── NudgeService.beatsFor — unit conversions ──────────────────────────

    /**
     * At 120 BPM a beat is 0.5s = 500ms = 24000 frames @ 48 kHz.
     * A context used across the conversion tests below.
     */
    private static TimingContext ctx() {
        return new TimingContext(120.0, 48000.0, 0.25, 4.0);
    }

    @Test
    void framesUnitConvertsWithTempoAndSampleRate() {
        // 24000 frames @ 48kHz = 0.5s; at 120 BPM → 1 beat
        double beats = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.FRAMES, 24000.0), ctx(), +1);
        assertThat(beats).isEqualTo(1.0, within(EPS));
    }

    @Test
    void oneSampleNudgeMatchesOneFrameFormula() {
        // One sample at 48kHz, 120 BPM = 1/48000 * 2 beats/sec = 4.166e-5 beats
        double beats = NudgeService.beatsForOneSample(ctx(), +1);
        assertThat(beats).isEqualTo(120.0 / 60.0 / 48000.0, within(EPS));
    }

    @Test
    void millisecondsUnitConvertsWithTempo() {
        // 500ms at 120 BPM → 1 beat
        double beats = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.MILLISECONDS, 500.0), ctx(), +1);
        assertThat(beats).isEqualTo(1.0, within(EPS));
    }

    @Test
    void gridStepsUnitMultipliesByGridStepBeats() {
        // 4 grid steps × 0.25 beats/step = 1.0 beat
        double beats = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.GRID_STEPS, 4.0), ctx(), +1);
        assertThat(beats).isEqualTo(1.0, within(EPS));
    }

    @Test
    void barFractionUnitMultipliesByBarBeats() {
        // 0.25 bars × 4 beats/bar = 1.0 beat
        double beats = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.BAR_FRACTION, 0.25), ctx(), +1);
        assertThat(beats).isEqualTo(1.0, within(EPS));
    }

    @Test
    void directionMultiplierInvertsSign() {
        double right = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.GRID_STEPS, 1.0), ctx(), +1);
        double left = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.GRID_STEPS, 1.0), ctx(), -1);
        assertThat(right).isEqualTo(0.25, within(EPS));
        assertThat(left).isEqualTo(-0.25, within(EPS));
    }

    @Test
    void directionMultiplierSupportsTenTimesShortcut() {
        // Ctrl+Shift+Right — 10× multiplier
        double beats = NudgeService.beatsFor(
                new NudgeSettings(NudgeUnit.GRID_STEPS, 1.0), ctx(), +10);
        assertThat(beats).isEqualTo(2.5, within(EPS));
    }

    @Test
    void timingContextRejectsNonPositiveValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimingContext(0, 48000, 0.25, 4));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimingContext(120, -1, 0.25, 4));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimingContext(120, 48000, 0, 4));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TimingContext(120, 48000, 0.25, 0));
    }

    // ── NudgeService.buildAction + NudgeClipsAction ────────────────────────

    @Test
    void buildActionReturnsNullForEmptyClips() {
        assertThat(NudgeService.buildAction(List.of(), 1.0)).isNull();
    }

    @Test
    void buildActionReturnsNullForZeroDelta() {
        AudioClip clip = new AudioClip("c", 2.0, 4.0, null);
        assertThat(NudgeService.buildAction(List.of(clip), 0.0)).isNull();
    }

    @Test
    void executeMovesAllClipsByDelta() {
        AudioClip c1 = new AudioClip("a", 4.0, 2.0, null);
        AudioClip c2 = new AudioClip("b", 8.0, 2.0, null);

        NudgeClipsAction action = NudgeService.buildAction(List.of(c1, c2), 0.5);
        assertThat(action).isNotNull();
        action.execute();

        assertThat(c1.getStartBeat()).isEqualTo(4.5, within(EPS));
        assertThat(c2.getStartBeat()).isEqualTo(8.5, within(EPS));
        assertThat(action.getAppliedBeatDelta()).isEqualTo(0.5, within(EPS));
    }

    @Test
    void undoRestoresAllClipPositions() {
        AudioClip c1 = new AudioClip("a", 4.0, 2.0, null);
        AudioClip c2 = new AudioClip("b", 8.0, 2.0, null);

        NudgeClipsAction action = NudgeService.buildAction(List.of(c1, c2), -1.0);
        action.execute();
        action.undo();

        assertThat(c1.getStartBeat()).isEqualTo(4.0, within(EPS));
        assertThat(c2.getStartBeat()).isEqualTo(8.0, within(EPS));
    }

    @Test
    void boundaryClampPreventsNegativePositions() {
        // Earliest clip is at beat 1.0; asking for -5.0 must clamp to -1.0
        // so nothing goes negative and relative spacing is preserved.
        AudioClip early = new AudioClip("early", 1.0, 1.0, null);
        AudioClip later = new AudioClip("later", 5.0, 1.0, null);

        NudgeClipsAction action = NudgeService.buildAction(List.of(early, later), -5.0);
        action.execute();

        assertThat(action.getAppliedBeatDelta()).isEqualTo(-1.0, within(EPS));
        assertThat(early.getStartBeat()).isEqualTo(0.0, within(EPS));
        assertThat(later.getStartBeat()).isEqualTo(4.0, within(EPS));
    }

    @Test
    void multiSelectionNudgeIsSingleUndoStep() {
        // The whole selection must move/undo through one action object,
        // so the undo manager sees exactly one step for N clips.
        AudioClip a = new AudioClip("a", 2.0, 1.0, null);
        AudioClip b = new AudioClip("b", 5.0, 1.0, null);
        AudioClip c = new AudioClip("c", 9.0, 1.0, null);

        NudgeClipsAction action = NudgeService.buildAction(List.of(a, b, c), 0.25);
        assertThat(action).isNotNull();
        action.execute();
        assertThat(a.getStartBeat()).isEqualTo(2.25, within(EPS));
        assertThat(b.getStartBeat()).isEqualTo(5.25, within(EPS));
        assertThat(c.getStartBeat()).isEqualTo(9.25, within(EPS));

        action.undo();
        assertThat(a.getStartBeat()).isEqualTo(2.0, within(EPS));
        assertThat(b.getStartBeat()).isEqualTo(5.0, within(EPS));
        assertThat(c.getStartBeat()).isEqualTo(9.0, within(EPS));

        assertThat(action.description()).isEqualTo("Nudge Clips");
    }

    @Test
    void nudgeClipsActionRejectsEmptyList() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NudgeClipsAction(List.of(), 1.0));
    }

    @Test
    void nudgeClipsActionRejectsNonFiniteDelta() {
        AudioClip c = new AudioClip("c", 0.0, 1.0, null);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NudgeClipsAction(List.of(c), Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new NudgeClipsAction(List.of(c), Double.POSITIVE_INFINITY));
    }
}
