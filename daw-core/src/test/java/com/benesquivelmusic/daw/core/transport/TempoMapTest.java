package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TempoMapTest {

    private TempoMap map;

    @BeforeEach
    void setUp() {
        map = new TempoMap();
    }

    // ── construction ────────────────────────────────────────────────────────

    @Test
    void shouldHaveDefaultInitialTempo() {
        assertThat(map.getTempoChanges()).hasSize(1);
        assertThat(map.getTempoChanges().get(0).bpm()).isEqualTo(120.0);
        assertThat(map.getTempoChanges().get(0).positionInBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldHaveDefaultInitialTimeSignature() {
        assertThat(map.getTimeSignatureChanges()).hasSize(1);
        assertThat(map.getTimeSignatureChanges().get(0).numerator()).isEqualTo(4);
        assertThat(map.getTimeSignatureChanges().get(0).denominator()).isEqualTo(4);
    }

    @Test
    void shouldCreateWithCustomInitialValues() {
        TempoMap custom = new TempoMap(140.0, 3, 4);
        assertThat(custom.getTempoChanges().get(0).bpm()).isEqualTo(140.0);
        assertThat(custom.getTimeSignatureChanges().get(0).numerator()).isEqualTo(3);
        assertThat(custom.getTimeSignatureChanges().get(0).denominator()).isEqualTo(4);
    }

    // ── addTempoChange ──────────────────────────────────────────────────────

    @Test
    void shouldAddTempoChange() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        assertThat(map.getTempoChangeCount()).isEqualTo(2);
        assertThat(map.getTempoChanges().get(1).bpm()).isEqualTo(140.0);
    }

    @Test
    void shouldReplaceSamePositionTempoChange() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        map.addTempoChange(TempoChangeEvent.instant(8.0, 160.0));
        assertThat(map.getTempoChangeCount()).isEqualTo(2);
        assertThat(map.getTempoChanges().get(1).bpm()).isEqualTo(160.0);
    }

    @Test
    void shouldMaintainSortedOrder() {
        map.addTempoChange(TempoChangeEvent.instant(16.0, 160.0));
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        List<TempoChangeEvent> changes = map.getTempoChanges();
        assertThat(changes.get(0).positionInBeats()).isEqualTo(0.0);
        assertThat(changes.get(1).positionInBeats()).isEqualTo(8.0);
        assertThat(changes.get(2).positionInBeats()).isEqualTo(16.0);
    }

    @Test
    void shouldRejectNullTempoChange() {
        assertThatThrownBy(() -> map.addTempoChange(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReplaceInitialTempoAtBeatZero() {
        map.addTempoChange(TempoChangeEvent.instant(0.0, 90.0));
        assertThat(map.getTempoChangeCount()).isEqualTo(1);
        assertThat(map.getTempoChanges().get(0).bpm()).isEqualTo(90.0);
    }

    // ── removeTempoChange ───────────────────────────────────────────────────

    @Test
    void shouldRemoveTempoChange() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        boolean removed = map.removeTempoChange(8.0);
        assertThat(removed).isTrue();
        assertThat(map.getTempoChangeCount()).isEqualTo(1);
    }

    @Test
    void shouldNotRemoveInitialTempoChange() {
        boolean removed = map.removeTempoChange(0.0);
        assertThat(removed).isFalse();
        assertThat(map.getTempoChangeCount()).isEqualTo(1);
    }

    @Test
    void shouldReturnFalseWhenRemovingNonexistent() {
        boolean removed = map.removeTempoChange(999.0);
        assertThat(removed).isFalse();
    }

    // ── addTimeSignatureChange ──────────────────────────────────────────────

    @Test
    void shouldAddTimeSignatureChange() {
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(8.0, 3, 4));
        assertThat(map.getTimeSignatureChangeCount()).isEqualTo(2);
    }

    @Test
    void shouldReplaceSamePositionTimeSignatureChange() {
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(8.0, 3, 4));
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(8.0, 6, 8));
        assertThat(map.getTimeSignatureChangeCount()).isEqualTo(2);
        assertThat(map.getTimeSignatureChanges().get(1).numerator()).isEqualTo(6);
    }

    @Test
    void shouldRejectNullTimeSignatureChange() {
        assertThatThrownBy(() -> map.addTimeSignatureChange(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── removeTimeSignatureChange ───────────────────────────────────────────

    @Test
    void shouldRemoveTimeSignatureChange() {
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(8.0, 3, 4));
        boolean removed = map.removeTimeSignatureChange(8.0);
        assertThat(removed).isTrue();
        assertThat(map.getTimeSignatureChangeCount()).isEqualTo(1);
    }

    @Test
    void shouldNotRemoveInitialTimeSignatureChange() {
        boolean removed = map.removeTimeSignatureChange(0.0);
        assertThat(removed).isFalse();
        assertThat(map.getTimeSignatureChangeCount()).isEqualTo(1);
    }

    // ── getTempoAtBeat ──────────────────────────────────────────────────────

    @Test
    void shouldReturnInitialTempoAtBeatZero() {
        assertThat(map.getTempoAtBeat(0.0)).isEqualTo(120.0);
    }

    @Test
    void shouldReturnInitialTempoBeforeFirstChange() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        assertThat(map.getTempoAtBeat(4.0)).isEqualTo(120.0);
    }

    @Test
    void shouldReturnNewTempoAfterInstantChange() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        assertThat(map.getTempoAtBeat(8.0)).isEqualTo(140.0);
        assertThat(map.getTempoAtBeat(12.0)).isEqualTo(140.0);
    }

    @Test
    void shouldReturnTempoWithMultipleChanges() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        map.addTempoChange(TempoChangeEvent.instant(16.0, 100.0));
        assertThat(map.getTempoAtBeat(4.0)).isEqualTo(120.0);
        assertThat(map.getTempoAtBeat(12.0)).isEqualTo(140.0);
        assertThat(map.getTempoAtBeat(20.0)).isEqualTo(100.0);
    }

    @Test
    void shouldInterpolateLinearTempo() {
        map.addTempoChange(TempoChangeEvent.linear(8.0, 160.0));
        // At midpoint (beat 4), tempo should be ~140 BPM (120 + 0.5*(160-120))
        assertThat(map.getTempoAtBeat(4.0)).isCloseTo(140.0, within(0.01));
    }

    @Test
    void shouldInterpolateLinearTempoAtStart() {
        map.addTempoChange(TempoChangeEvent.linear(8.0, 160.0));
        // At beat 0, tempo should be 120 (start of transition)
        assertThat(map.getTempoAtBeat(0.0)).isCloseTo(120.0, within(0.01));
    }

    @Test
    void shouldInterpolateLinearTempoAtEnd() {
        map.addTempoChange(TempoChangeEvent.linear(8.0, 160.0));
        // At beat 8, tempo should be 160 (end of transition)
        assertThat(map.getTempoAtBeat(8.0)).isCloseTo(160.0, within(0.01));
    }

    @Test
    void shouldInterpolateCurvedTempo() {
        map.addTempoChange(TempoChangeEvent.curved(8.0, 160.0));
        // At midpoint (beat 4), smoothstep(0.5) = 0.5, so tempo ~ 140
        assertThat(map.getTempoAtBeat(4.0)).isCloseTo(140.0, within(0.01));
    }

    @Test
    void shouldInterpolateCurvedTempoNearStart() {
        map.addTempoChange(TempoChangeEvent.curved(8.0, 160.0));
        // At beat 2 (t=0.25), smoothstep(0.25) = 0.15625, tempo ~ 126.25
        assertThat(map.getTempoAtBeat(2.0)).isCloseTo(126.25, within(0.01));
    }

    // ── getTimeSignatureAtBeat ──────────────────────────────────────────────

    @Test
    void shouldReturnInitialTimeSignature() {
        TimeSignatureChangeEvent sig = map.getTimeSignatureAtBeat(0.0);
        assertThat(sig.numerator()).isEqualTo(4);
        assertThat(sig.denominator()).isEqualTo(4);
    }

    @Test
    void shouldReturnInitialTimeSignatureBeforeChange() {
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(16.0, 3, 4));
        TimeSignatureChangeEvent sig = map.getTimeSignatureAtBeat(8.0);
        assertThat(sig.numerator()).isEqualTo(4);
        assertThat(sig.denominator()).isEqualTo(4);
    }

    @Test
    void shouldReturnChangedTimeSignature() {
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(16.0, 3, 4));
        TimeSignatureChangeEvent sig = map.getTimeSignatureAtBeat(16.0);
        assertThat(sig.numerator()).isEqualTo(3);
        assertThat(sig.denominator()).isEqualTo(4);
    }

    @Test
    void shouldReturnChangedTimeSignatureAfterChange() {
        map.addTimeSignatureChange(new TimeSignatureChangeEvent(16.0, 3, 4));
        TimeSignatureChangeEvent sig = map.getTimeSignatureAtBeat(24.0);
        assertThat(sig.numerator()).isEqualTo(3);
        assertThat(sig.denominator()).isEqualTo(4);
    }

    // ── beatsToSeconds (constant tempo) ─────────────────────────────────────

    @Test
    void shouldConvertZeroBeatsToZeroSeconds() {
        assertThat(map.beatsToSeconds(0.0)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldConvertBeatsToSecondsAtDefaultTempo() {
        // 120 BPM → 1 beat = 0.5 s → 4 beats = 2.0 s
        assertThat(map.beatsToSeconds(4.0)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void shouldConvertBeatsToSecondsAt60Bpm() {
        TempoMap m = new TempoMap(60.0, 4, 4);
        // 60 BPM → 1 beat = 1 s
        assertThat(m.beatsToSeconds(1.0)).isCloseTo(1.0, within(1e-9));
        assertThat(m.beatsToSeconds(4.0)).isCloseTo(4.0, within(1e-9));
    }

    // ── beatsToSeconds (with tempo change) ──────────────────────────────────

    @Test
    void shouldConvertBeatsToSecondsWithInstantTempoChange() {
        // 120 BPM for first 4 beats (= 2.0 s), then 60 BPM for next 4 beats (= 4.0 s)
        map.addTempoChange(TempoChangeEvent.instant(4.0, 60.0));
        assertThat(map.beatsToSeconds(4.0)).isCloseTo(2.0, within(1e-9));
        assertThat(map.beatsToSeconds(8.0)).isCloseTo(6.0, within(1e-9));
    }

    @Test
    void shouldConvertBeatsToSecondsWithMultipleInstantChanges() {
        // 120 BPM [0-4): 2.0 s, 60 BPM [4-8): 4.0 s, 240 BPM [8-12): 1.0 s
        map.addTempoChange(TempoChangeEvent.instant(4.0, 60.0));
        map.addTempoChange(TempoChangeEvent.instant(8.0, 240.0));
        assertThat(map.beatsToSeconds(12.0)).isCloseTo(7.0, within(1e-9));
    }

    @Test
    void shouldConvertBeatsToSecondsWithLinearTransition() {
        // Linear from 120 to 240 BPM over beats [0, 8)
        // The integral of 60/tempo over a linear ramp should be computed numerically
        map.addTempoChange(TempoChangeEvent.linear(8.0, 240.0));
        double seconds = map.beatsToSeconds(8.0);
        // For constant 120: 4.0 s. For constant 240: 2.0 s. Linear should be between.
        assertThat(seconds).isGreaterThan(2.0);
        assertThat(seconds).isLessThan(4.0);
    }

    // ── secondsToBeats ──────────────────────────────────────────────────────

    @Test
    void shouldConvertZeroSecondsToZeroBeats() {
        assertThat(map.secondsToBeats(0.0)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldConvertSecondsToBeatsAtDefaultTempo() {
        // 120 BPM → 2.0 s = 4 beats
        assertThat(map.secondsToBeats(2.0)).isCloseTo(4.0, within(1e-6));
    }

    @Test
    void shouldRoundTripBeatsToSecondsAndBack() {
        map.addTempoChange(TempoChangeEvent.instant(8.0, 140.0));
        map.addTempoChange(TempoChangeEvent.instant(16.0, 100.0));

        double originalBeats = 12.0;
        double seconds = map.beatsToSeconds(originalBeats);
        double recoveredBeats = map.secondsToBeats(seconds);
        assertThat(recoveredBeats).isCloseTo(originalBeats, within(1e-6));
    }

    @Test
    void shouldRoundTripWithLinearTransition() {
        map.addTempoChange(TempoChangeEvent.linear(8.0, 160.0));

        double originalBeats = 6.0;
        double seconds = map.beatsToSeconds(originalBeats);
        double recoveredBeats = map.secondsToBeats(seconds);
        assertThat(recoveredBeats).isCloseTo(originalBeats, within(1e-4));
    }

    @Test
    void shouldRoundTripWithCurvedTransition() {
        map.addTempoChange(TempoChangeEvent.curved(8.0, 160.0));

        double originalBeats = 5.0;
        double seconds = map.beatsToSeconds(originalBeats);
        double recoveredBeats = map.secondsToBeats(seconds);
        assertThat(recoveredBeats).isCloseTo(originalBeats, within(1e-4));
    }

    // ── edge cases ──────────────────────────────────────────────────────────

    @Test
    void shouldHandleNegativeBeatsAsZeroSeconds() {
        assertThat(map.beatsToSeconds(-1.0)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldHandleNegativeSecondsAsZeroBeats() {
        assertThat(map.secondsToBeats(-1.0)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldReturnUnmodifiableTempoChangesList() {
        List<TempoChangeEvent> changes = map.getTempoChanges();
        assertThatThrownBy(() -> changes.add(TempoChangeEvent.instant(8.0, 140.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableTimeSignatureChangesList() {
        List<TimeSignatureChangeEvent> changes = map.getTimeSignatureChanges();
        assertThatThrownBy(() -> changes.add(new TimeSignatureChangeEvent(8.0, 3, 4)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
