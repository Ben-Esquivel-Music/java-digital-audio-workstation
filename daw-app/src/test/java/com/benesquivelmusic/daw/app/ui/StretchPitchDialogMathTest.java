package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.StretchQuality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Headless unit tests for the math helpers and result records used by
 * {@link TimeStretchClipDialog} and {@link PitchShiftClipDialog}.
 *
 * <p>The dialog's FX surface itself requires a screen and therefore is not
 * exercised here — the behaviour these tests cover is the pure logic that
 * the dialog relies on (ratio↔duration conversion, formatting, clamping)
 * and the {@code Result} record contracts.</p>
 */
class StretchPitchDialogMathTest {

    // ── TimeStretchClipDialog math ──────────────────────────────────────────

    @Test
    void ratioFromDurationIsClampedToSupportedRange() {
        // 2× source duration → 2.0 ratio (at the upper bound).
        assertThat(TimeStretchClipDialog.ratioFromDuration(8.0, 4.0))
                .isCloseTo(2.0, within(1e-9));
        // Asking for 10× would exceed the dialog's 0.5–2.0 range; clamp.
        assertThat(TimeStretchClipDialog.ratioFromDuration(40.0, 4.0))
                .isCloseTo(TimeStretchClipDialog.MAX_RATIO, within(1e-9));
        // Asking for 0.1× clamps at MIN_RATIO.
        assertThat(TimeStretchClipDialog.ratioFromDuration(0.4, 4.0))
                .isCloseTo(TimeStretchClipDialog.MIN_RATIO, within(1e-9));
    }

    @Test
    void durationFromRatioIsInverseOfRatioFromDuration() {
        double sourceSeconds = 12.0;
        double ratio = 1.25;
        double target = TimeStretchClipDialog.durationFromRatio(ratio, sourceSeconds);
        assertThat(target).isCloseTo(15.0, within(1e-9));
        assertThat(TimeStretchClipDialog.ratioFromDuration(target, sourceSeconds))
                .isCloseTo(ratio, within(1e-9));
    }

    @Test
    void parseDurationAcceptsPlainSecondsAndMmSs() {
        assertThat(TimeStretchClipDialog.parseDuration("1:23.500")).isCloseTo(83.5, within(1e-6));
        assertThat(TimeStretchClipDialog.parseDuration("0:30")).isCloseTo(30.0, within(1e-6));
        assertThat(TimeStretchClipDialog.parseDuration("12.5")).isCloseTo(12.5, within(1e-6));
        assertThat(Double.isNaN(TimeStretchClipDialog.parseDuration("garbage"))).isTrue();
        assertThat(Double.isNaN(TimeStretchClipDialog.parseDuration(""))).isTrue();
        assertThat(Double.isNaN(TimeStretchClipDialog.parseDuration(null))).isTrue();
    }

    @Test
    void formatDurationProducesMmSsMs() {
        assertThat(TimeStretchClipDialog.formatDuration(0.0)).isEqualTo("0:00.000");
        assertThat(TimeStretchClipDialog.formatDuration(1.5)).isEqualTo("0:01.500");
        assertThat(TimeStretchClipDialog.formatDuration(83.5)).isEqualTo("1:23.500");
    }

    @Test
    void timeStretchResultValidatesRange() {
        assertThatThrownBy(() ->
                new TimeStretchClipDialog.Result(3.0, StretchQuality.MEDIUM, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TimeStretchClipDialog.Result(0.1, StretchQuality.MEDIUM, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── PitchShiftClipDialog math ───────────────────────────────────────────

    @Test
    void pitchShiftResultCombinesSemitonesAndCents() {
        // +5 semitones, +50 cents = 5.5 semitones combined.
        assertThat(new PitchShiftClipDialog.Result(5, 50, true, false).totalSemitones())
                .isCloseTo(5.5, within(1e-9));
        // -3 semitones, -75 cents = -3.75 semitones combined.
        assertThat(new PitchShiftClipDialog.Result(-3, -75, true, false).totalSemitones())
                .isCloseTo(-3.75, within(1e-9));
    }

    @Test
    void pitchShiftResultIsClampedToEngineRange() {
        // Engine accepts [-24, +24]. 24 semitones + 50 cents would overshoot.
        double clamped = new PitchShiftClipDialog.Result(24, 50, true, false).totalSemitones();
        assertThat(clamped).isCloseTo(PitchShiftClipDialog.MAX_SEMITONES, within(1e-9));
    }

    @Test
    void pitchShiftResultValidatesPerFieldRanges() {
        assertThatThrownBy(() ->
                new PitchShiftClipDialog.Result(25, 0, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new PitchShiftClipDialog.Result(0, 200, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
