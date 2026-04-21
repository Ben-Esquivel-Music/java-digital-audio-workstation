package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link ClipGainEnvelopeRenderer} coordinate conversion and
 * hit-testing logic. These tests exercise only the pure static utility
 * methods and do not require a JavaFX toolkit &mdash; safe for headless CI.
 */
class ClipGainEnvelopeRendererTest {

    private static final double SAMPLES_PER_BEAT = 22050.0; // 44100 sr @ 120 BPM
    private static final double PIXELS_PER_BEAT = 40.0;

    // ── frameToX / xToFrame ────────────────────────────────────────────────

    @Test
    void frameZero_mapsToClipStartBeat() {
        double x = ClipGainEnvelopeRenderer.frameToX(0L, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, /*scrollXBeats*/ 0.0,
                /*clipStartBeat*/ 4.0, /*sourceOffsetBeats*/ 0.0);
        assertThat(x).isCloseTo(4.0 * PIXELS_PER_BEAT, within(1e-6));
    }

    @Test
    void frameToX_accountsForSourceOffset() {
        // clipStart=4 beats, sourceOffset=1 beat → frame 0 of the source is
        // 1 beat *before* the clip starts: pixel = (4 - 1) * pixelsPerBeat.
        double x = ClipGainEnvelopeRenderer.frameToX(0L, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, 0.0, 4.0, 1.0);
        assertThat(x).isCloseTo(3.0 * PIXELS_PER_BEAT, within(1e-6));
    }

    @Test
    void frameToX_accountsForScroll() {
        double x = ClipGainEnvelopeRenderer.frameToX(0L, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, /*scrollXBeats*/ 2.0,
                /*clipStartBeat*/ 4.0, 0.0);
        assertThat(x).isCloseTo((4.0 - 2.0) * PIXELS_PER_BEAT, within(1e-6));
    }

    @Test
    void xToFrame_roundTripsWithFrameToX() {
        long frame = 11_025L; // 0.5 beats @ SAMPLES_PER_BEAT
        double x = ClipGainEnvelopeRenderer.frameToX(frame, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, 0.0, 4.0, 0.0);
        long back = ClipGainEnvelopeRenderer.xToFrame(x, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, 0.0, 4.0, 0.0);
        assertThat(back).isEqualTo(frame);
    }

    @Test
    void xToFrame_clampsNegativeToZero() {
        // A pixel far to the left of the clip should clamp to frame 0.
        long frame = ClipGainEnvelopeRenderer.xToFrame(-1000.0, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, 0.0, 4.0, 0.0);
        assertThat(frame).isZero();
    }

    // ── dbToY / yToDb ─────────────────────────────────────────────────────

    @Test
    void dbToY_maxAtTopInset() {
        double y = ClipGainEnvelopeRenderer.dbToY(
                ClipGainEnvelopeRenderer.MAX_DB, 100.0, 60.0);
        // Top inset is 3 px
        assertThat(y).isCloseTo(103.0, within(1e-6));
    }

    @Test
    void dbToY_minAtBottomInset() {
        double y = ClipGainEnvelopeRenderer.dbToY(
                ClipGainEnvelopeRenderer.MIN_DB, 100.0, 60.0);
        assertThat(y).isCloseTo(100.0 + 60.0 - 3.0, within(1e-6));
    }

    @Test
    void dbToY_clampsOutOfRange() {
        double topY = ClipGainEnvelopeRenderer.dbToY(+1000.0, 0.0, 60.0);
        double botY = ClipGainEnvelopeRenderer.dbToY(-1000.0, 0.0, 60.0);
        assertThat(topY).isCloseTo(3.0, within(1e-6));
        assertThat(botY).isCloseTo(57.0, within(1e-6));
    }

    @Test
    void yToDb_roundTripsWithDbToY() {
        double db = -6.0;
        double y = ClipGainEnvelopeRenderer.dbToY(db, 0.0, 60.0);
        double back = ClipGainEnvelopeRenderer.yToDb(y, 0.0, 60.0);
        assertThat(back).isCloseTo(db, within(1e-9));
    }

    // ── hitTestBreakpoint ─────────────────────────────────────────────────

    @Test
    void hitTest_returnsIndexOfClosestBreakpoint() {
        ClipGainEnvelope env = new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(11_025L, -6.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(22_050L, -12.0, CurveShape.LINEAR)));
        double clipY = 100.0, clipHeight = 60.0;

        double px = ClipGainEnvelopeRenderer.frameToX(11_025L, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, 0.0, 0.0, 0.0);
        double py = ClipGainEnvelopeRenderer.dbToY(-6.0, clipY, clipHeight);

        int idx = ClipGainEnvelopeRenderer.hitTestBreakpoint(env, px, py,
                clipY, clipHeight, PIXELS_PER_BEAT, 0.0,
                SAMPLES_PER_BEAT, 0.0, 0.0);
        assertThat(idx).isEqualTo(1);
    }

    @Test
    void hitTest_returnsMinusOneOutsideTolerance() {
        ClipGainEnvelope env = ClipGainEnvelope.constant(0.0);
        int idx = ClipGainEnvelopeRenderer.hitTestBreakpoint(env,
                1000.0, 1000.0, 0.0, 60.0,
                PIXELS_PER_BEAT, 0.0, SAMPLES_PER_BEAT, 0.0, 0.0);
        assertThat(idx).isEqualTo(-1);
    }

    @Test
    void hitTest_nullEnvelope_returnsMinusOne() {
        int idx = ClipGainEnvelopeRenderer.hitTestBreakpoint(null, 0, 0,
                0, 60, PIXELS_PER_BEAT, 0, SAMPLES_PER_BEAT, 0, 0);
        assertThat(idx).isEqualTo(-1);
    }

    // ── addBreakpointAt ───────────────────────────────────────────────────

    @Test
    void addBreakpointAt_insertsAtXY_onExistingEnvelope() {
        ClipGainEnvelope env = ClipGainEnvelope.constant(0.0);
        double clipY = 100.0, clipHeight = 60.0;
        long targetFrame = 11_025L;
        double px = ClipGainEnvelopeRenderer.frameToX(targetFrame, SAMPLES_PER_BEAT,
                PIXELS_PER_BEAT, 0.0, 0.0, 0.0);
        double py = ClipGainEnvelopeRenderer.dbToY(-6.0, clipY, clipHeight);

        ClipGainEnvelope updated = ClipGainEnvelopeRenderer.addBreakpointAt(
                env, px, py, clipY, clipHeight,
                PIXELS_PER_BEAT, 0.0, SAMPLES_PER_BEAT, 0.0, 0.0,
                CurveShape.LINEAR);

        assertThat(updated.breakpoints()).hasSize(2);
        var added = updated.breakpoints().get(1);
        assertThat(added.frameOffsetInClip()).isEqualTo(targetFrame);
        assertThat(added.dbGain()).isCloseTo(-6.0, within(1e-9));
        assertThat(added.curve()).isEqualTo(CurveShape.LINEAR);
    }

    @Test
    void addBreakpointAt_nullEnvelope_createsOne() {
        ClipGainEnvelope updated = ClipGainEnvelopeRenderer.addBreakpointAt(
                null, 0.0, 30.0, 0.0, 60.0,
                PIXELS_PER_BEAT, 0.0, SAMPLES_PER_BEAT, 0.0, 0.0,
                null);
        assertThat(updated).isNotNull();
        assertThat(updated.breakpoints()).isNotEmpty();
    }
}
