package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TimelineRulerModelTest {

    private Transport transport;
    private TimelineRulerModel model;

    @BeforeEach
    void setUp() {
        transport = new Transport();           // 120 BPM, 4/4
        model = new TimelineRulerModel(transport);
    }

    // ── construction ────────────────────────────────────────────────────────

    @Test
    void shouldRejectNullTransport() {
        assertThatThrownBy(() -> new TimelineRulerModel(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldExposeTransport() {
        assertThat(model.getTransport()).isSameAs(transport);
    }

    // ── display mode ────────────────────────────────────────────────────────

    @Test
    void shouldDefaultToBarsBeatsTicks() {
        assertThat(model.getDisplayMode()).isEqualTo(TimeDisplayMode.BARS_BEATS_TICKS);
    }

    @Test
    void shouldSetDisplayMode() {
        model.setDisplayMode(TimeDisplayMode.TIME);
        assertThat(model.getDisplayMode()).isEqualTo(TimeDisplayMode.TIME);
    }

    @Test
    void shouldRejectNullDisplayMode() {
        assertThatThrownBy(() -> model.setDisplayMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldToggleDisplayMode() {
        model.toggleDisplayMode();
        assertThat(model.getDisplayMode()).isEqualTo(TimeDisplayMode.TIME);
        model.toggleDisplayMode();
        assertThat(model.getDisplayMode()).isEqualTo(TimeDisplayMode.BARS_BEATS_TICKS);
    }

    // ── beats ↔ seconds conversion ──────────────────────────────────────────

    @Test
    void shouldConvertBeatsToSecondsAt120Bpm() {
        // 120 BPM → 1 beat = 0.5 s
        assertThat(model.beatsToSeconds(4.0)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void shouldConvertSecondsToBeatsAt120Bpm() {
        assertThat(model.secondsToBeats(2.0)).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void shouldConvertBeatsToSecondsAt60Bpm() {
        transport.setTempo(60.0);
        assertThat(model.beatsToSeconds(1.0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldConvertSecondsToBeatsAt60Bpm() {
        transport.setTempo(60.0);
        assertThat(model.secondsToBeats(1.0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldReturnZeroSecondsForZeroBeats() {
        assertThat(model.beatsToSeconds(0.0)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldReturnPositionInSeconds() {
        transport.setPositionInBeats(8.0);
        // 8 beats at 120 BPM = 4 seconds
        assertThat(model.getPositionInSeconds()).isCloseTo(4.0, within(1e-9));
    }

    // ── pixel ↔ beat conversion ─────────────────────────────────────────────

    @Test
    void shouldConvertPixelToBeats() {
        assertThat(model.pixelToBeats(100.0, 50.0)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void shouldClampNegativePixelToZero() {
        assertThat(model.pixelToBeats(-10.0, 50.0)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldRejectNonPositivePixelsPerBeat() {
        assertThatThrownBy(() -> model.pixelToBeats(10.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.pixelToBeats(10.0, -5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldConvertBeatsToPixel() {
        assertThat(model.beatsToPixel(2.0, 50.0)).isCloseTo(100.0, within(1e-9));
    }

    // ── formatPosition — bars:beats:ticks ───────────────────────────────────

    @Test
    void shouldFormatZeroAsBarOne() {
        assertThat(model.formatPosition(0.0)).isEqualTo("1:1:000");
    }

    @Test
    void shouldFormatOneBeatAsBeatTwo() {
        assertThat(model.formatPosition(1.0)).isEqualTo("1:2:000");
    }

    @Test
    void shouldFormatFourBeatsAsBarTwo() {
        assertThat(model.formatPosition(4.0)).isEqualTo("2:1:000");
    }

    @Test
    void shouldFormatFractionalBeatWithTicks() {
        // 0.5 beats = 480 ticks
        assertThat(model.formatPosition(0.5)).isEqualTo("1:1:480");
    }

    @Test
    void shouldRespectTimeSignatureInFormat() {
        transport.setTimeSignature(3, 4);
        // 3 beats = bar boundary in 3/4 → bar 2, beat 1
        assertThat(model.formatPosition(3.0)).isEqualTo("2:1:000");
    }

    // ── formatPosition — time mode ──────────────────────────────────────────

    @Test
    void shouldFormatTimeAtZero() {
        model.setDisplayMode(TimeDisplayMode.TIME);
        assertThat(model.formatPosition(0.0)).isEqualTo("00:00:000");
    }

    @Test
    void shouldFormatTimeAtOneMinute() {
        model.setDisplayMode(TimeDisplayMode.TIME);
        // 120 BPM → 120 beats = 60 seconds
        assertThat(model.formatPosition(120.0)).isEqualTo("01:00:000");
    }

    @Test
    void shouldFormatTimeWithMilliseconds() {
        model.setDisplayMode(TimeDisplayMode.TIME);
        // 1 beat at 120 BPM = 0.5 s = 500 ms
        assertThat(model.formatPosition(1.0)).isEqualTo("00:00:500");
    }

    // ── subdivisionForZoom ──────────────────────────────────────────────────

    @Test
    void shouldReturnFinestSubdivisionAtHighZoom() {
        // At 400 pixels per beat, even 32nd note (0.125) spans 50 px > 40
        double sub = model.subdivisionForZoom(400.0);
        assertThat(sub).isCloseTo(0.125, within(1e-9));
    }

    @Test
    void shouldReturnCoarserSubdivisionAtLowZoom() {
        // At 10 pixels per beat, 1 bar (4 beats) = 40 px → meets threshold
        double sub = model.subdivisionForZoom(10.0);
        assertThat(sub).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void shouldReturnQuarterNoteAtModerateZoom() {
        // At 50 pixels per beat, quarter (1.0) = 50 px > 40, eighth (0.5) = 25 px < 40
        double sub = model.subdivisionForZoom(50.0);
        assertThat(sub).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldReturnLargestSubdivisionAtVeryLowZoom() {
        // At 1 pixel per beat, nothing small enough → returns 4-bar block
        double sub = model.subdivisionForZoom(1.0);
        assertThat(sub).isCloseTo(16.0, within(1e-9));
    }

    @Test
    void shouldAdaptSubdivisionToTimeSignature() {
        transport.setTimeSignature(3, 4);
        // At 10 ppb, 1 bar (3 beats) = 30 px < 40, so next coarser: 2 bars = 60 px
        double sub = model.subdivisionForZoom(10.0);
        assertThat(sub).isCloseTo(6.0, within(1e-9));
    }
}
