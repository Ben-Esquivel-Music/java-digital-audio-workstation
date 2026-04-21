package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class ClipGainEnvelopeTest {

    @Test
    void constant_singleBreakpoint_clampsEverywhere() {
        var env = ClipGainEnvelope.constant(-6.0);
        assertThat(env.breakpoints()).hasSize(1);
        assertThat(env.dbAtFrame(0)).isEqualTo(-6.0);
        assertThat(env.dbAtFrame(1_000_000)).isEqualTo(-6.0);
        assertThat(env.linearAtFrame(0)).isCloseTo(Math.pow(10.0, -6.0 / 20.0), offset(1e-12));
    }

    @Test
    void emptyOrNull_breakpoints_throw() {
        assertThatThrownBy(() -> new ClipGainEnvelope(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClipGainEnvelope(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void linearCurve_interpolatesDbAtMidpoint() {
        var env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(1000L, -12.0, CurveShape.LINEAR)));
        // Exactly at breakpoints
        assertThat(env.dbAtFrame(0L)).isEqualTo(0.0);
        assertThat(env.dbAtFrame(1000L)).isEqualTo(-12.0);
        // Sample-accurate linear interpolation at 25%, 50%, 75%
        assertThat(env.dbAtFrame(250L)).isCloseTo(-3.0, offset(1e-12));
        assertThat(env.dbAtFrame(500L)).isCloseTo(-6.0, offset(1e-12));
        assertThat(env.dbAtFrame(750L)).isCloseTo(-9.0, offset(1e-12));
    }

    @Test
    void exponentialCurve_usesQuadraticWeight() {
        var env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.EXPONENTIAL),
                new ClipGainEnvelope.BreakpointDb(100L, -20.0, CurveShape.LINEAR)));
        // w(0.5) = 0.25 → db = 0 + (-20) * 0.25 = -5
        assertThat(env.dbAtFrame(50L)).isCloseTo(-5.0, offset(1e-12));
        // w(0.1) = 0.01 → db = -0.2
        assertThat(env.dbAtFrame(10L)).isCloseTo(-0.2, offset(1e-12));
    }

    @Test
    void sCurve_usesSmoothstepWeight() {
        var env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.S_CURVE),
                new ClipGainEnvelope.BreakpointDb(100L, -10.0, CurveShape.LINEAR)));
        // w(0.5) = 0.5, w(0.25) = 0.15625, w(0.75) = 0.84375
        assertThat(env.dbAtFrame(50L)).isCloseTo(-5.0, offset(1e-12));
        assertThat(env.dbAtFrame(25L)).isCloseTo(-1.5625, offset(1e-9));
        assertThat(env.dbAtFrame(75L)).isCloseTo(-8.4375, offset(1e-9));
    }

    @Test
    void outOfRangeFrames_clampToEndpoints() {
        var env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(100L, -3.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(200L, -9.0, CurveShape.LINEAR)));
        assertThat(env.dbAtFrame(0L)).isEqualTo(-3.0);
        assertThat(env.dbAtFrame(500L)).isEqualTo(-9.0);
    }

    @Test
    void constructor_sortsBreakpointsByFrameOffset() {
        var env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(200L, -10.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(100L, -5.0, CurveShape.LINEAR)));
        assertThat(env.breakpoints())
                .extracting(ClipGainEnvelope.BreakpointDb::frameOffsetInClip)
                .containsExactly(0L, 100L, 200L);
    }

    @Test
    void breakpoint_rejectsNegativeFrameOffsetAndNullCurve() {
        assertThatThrownBy(() ->
                new ClipGainEnvelope.BreakpointDb(-1L, 0.0, CurveShape.LINEAR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withBreakpoint_replacesExistingAtSameOffset() {
        var env = ClipGainEnvelope.constant(-6.0);
        var updated = env.withBreakpoint(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR));
        assertThat(updated.breakpoints()).hasSize(1);
        assertThat(updated.dbAtFrame(0L)).isEqualTo(0.0);
    }

    @Test
    void withoutBreakpoint_rejectsLastRemaining() {
        var env = ClipGainEnvelope.constant(0.0);
        assertThatThrownBy(() -> env.withoutBreakpoint(0))
                .isInstanceOf(IllegalStateException.class);
    }
}
