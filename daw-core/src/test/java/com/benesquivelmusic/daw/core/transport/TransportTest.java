package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportTest {

    @Test
    void shouldStartInStoppedState() {
        Transport transport = new Transport();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isZero();
    }

    @Test
    void shouldTransitionToPlaying() {
        Transport transport = new Transport();
        transport.play();
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
    }

    @Test
    void shouldStopAndResetPosition() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.play();
        transport.stop();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isZero();
    }

    @Test
    void shouldPauseFromPlayingState() {
        Transport transport = new Transport();
        transport.play();
        transport.pause();
        assertThat(transport.getState()).isEqualTo(TransportState.PAUSED);
    }

    @Test
    void shouldPauseFromRecordingState() {
        Transport transport = new Transport();
        transport.record();
        transport.pause();
        assertThat(transport.getState()).isEqualTo(TransportState.PAUSED);
    }

    @Test
    void shouldNotPauseFromStoppedState() {
        Transport transport = new Transport();
        transport.pause();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
    }

    @Test
    void shouldTransitionToRecording() {
        Transport transport = new Transport();
        transport.record();
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
    }

    @Test
    void shouldSetTempo() {
        Transport transport = new Transport();
        transport.setTempo(140.0);
        assertThat(transport.getTempo()).isEqualTo(140.0);
    }

    @Test
    void shouldRejectTempoOutOfRange() {
        Transport transport = new Transport();
        assertThatThrownBy(() -> transport.setTempo(19.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setTempo(1000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetTimeSignature() {
        Transport transport = new Transport();
        transport.setTimeSignature(3, 4);
        assertThat(transport.getTimeSignatureNumerator()).isEqualTo(3);
        assertThat(transport.getTimeSignatureDenominator()).isEqualTo(4);
    }

    @Test
    void shouldRejectInvalidTimeSignature() {
        Transport transport = new Transport();
        assertThatThrownBy(() -> transport.setTimeSignature(0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setTimeSignature(4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativePosition() {
        Transport transport = new Transport();
        assertThatThrownBy(() -> transport.setPositionInBeats(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHaveDefaultTimeSignature() {
        Transport transport = new Transport();
        assertThat(transport.getTimeSignatureNumerator()).isEqualTo(4);
        assertThat(transport.getTimeSignatureDenominator()).isEqualTo(4);
    }

    // ── Loop mode tests ────────────────────────────────────────────────────

    @Test
    void shouldHaveLoopDisabledByDefault() {
        Transport transport = new Transport();
        assertThat(transport.isLoopEnabled()).isFalse();
    }

    @Test
    void shouldHaveDefaultLoopRegion() {
        Transport transport = new Transport();
        assertThat(transport.getLoopStartInBeats()).isEqualTo(0.0);
        assertThat(transport.getLoopEndInBeats()).isEqualTo(16.0);
    }

    @Test
    void shouldToggleLoopEnabled() {
        Transport transport = new Transport();
        transport.setLoopEnabled(true);
        assertThat(transport.isLoopEnabled()).isTrue();
        transport.setLoopEnabled(false);
        assertThat(transport.isLoopEnabled()).isFalse();
    }

    @Test
    void shouldSetLoopRegion() {
        Transport transport = new Transport();
        transport.setLoopRegion(4.0, 32.0);
        assertThat(transport.getLoopStartInBeats()).isEqualTo(4.0);
        assertThat(transport.getLoopEndInBeats()).isEqualTo(32.0);
    }

    @Test
    void shouldRejectNegativeLoopStart() {
        Transport transport = new Transport();
        assertThatThrownBy(() -> transport.setLoopRegion(-1.0, 16.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectLoopEndNotGreaterThanStart() {
        Transport transport = new Transport();
        assertThatThrownBy(() -> transport.setLoopRegion(8.0, 8.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setLoopRegion(8.0, 4.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAdvancePositionLinearly() {
        Transport transport = new Transport();
        transport.advancePosition(4.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(4.0);
        transport.advancePosition(2.5);
        assertThat(transport.getPositionInBeats()).isEqualTo(6.5);
    }

    @Test
    void shouldWrapPositionAtLoopEnd() {
        Transport transport = new Transport();
        transport.setLoopEnabled(true);
        transport.setLoopRegion(4.0, 12.0);
        transport.setPositionInBeats(10.0);
        transport.advancePosition(3.0);
        // 10 + 3 = 13, loop length = 8, wraps to 13 - 8 = 5
        assertThat(transport.getPositionInBeats()).isEqualTo(5.0);
    }

    @Test
    void shouldWrapPositionExactlyAtLoopEnd() {
        Transport transport = new Transport();
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 8.0);
        transport.setPositionInBeats(7.0);
        transport.advancePosition(1.0);
        // 7 + 1 = 8 which is exactly loop end, wraps to 0
        assertThat(transport.getPositionInBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldNotWrapWhenLoopDisabled() {
        Transport transport = new Transport();
        transport.setLoopEnabled(false);
        transport.setLoopRegion(0.0, 8.0);
        transport.setPositionInBeats(7.0);
        transport.advancePosition(3.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(10.0);
    }

    @Test
    void shouldWrapMultipleTimesForLargeDelta() {
        Transport transport = new Transport();
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 4.0);
        transport.setPositionInBeats(3.0);
        transport.advancePosition(9.0);
        // 3 + 9 = 12, loop length = 4, 12 - 4 = 8, 8 - 4 = 4, 4 - 4 = 0
        assertThat(transport.getPositionInBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectNegativeDeltaBeats() {
        Transport transport = new Transport();
        assertThatThrownBy(() -> transport.advancePosition(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
