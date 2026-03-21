package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportTest {

    @Test
    void shouldStartInStoppedState() {
        var transport = new Transport();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isZero();
    }

    @Test
    void shouldTransitionToPlaying() {
        var transport = new Transport();
        transport.play();
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
    }

    @Test
    void shouldStopAndResetPosition() {
        var transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.play();
        transport.stop();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isZero();
    }

    @Test
    void shouldPauseFromPlayingState() {
        var transport = new Transport();
        transport.play();
        transport.pause();
        assertThat(transport.getState()).isEqualTo(TransportState.PAUSED);
    }

    @Test
    void shouldPauseFromRecordingState() {
        var transport = new Transport();
        transport.record();
        transport.pause();
        assertThat(transport.getState()).isEqualTo(TransportState.PAUSED);
    }

    @Test
    void shouldNotPauseFromStoppedState() {
        var transport = new Transport();
        transport.pause();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
    }

    @Test
    void shouldTransitionToRecording() {
        var transport = new Transport();
        transport.record();
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
    }

    @Test
    void shouldSetTempo() {
        var transport = new Transport();
        transport.setTempo(140.0);
        assertThat(transport.getTempo()).isEqualTo(140.0);
    }

    @Test
    void shouldRejectTempoOutOfRange() {
        var transport = new Transport();
        assertThatThrownBy(() -> transport.setTempo(19.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setTempo(1000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetTimeSignature() {
        var transport = new Transport();
        transport.setTimeSignature(3, 4);
        assertThat(transport.getTimeSignatureNumerator()).isEqualTo(3);
        assertThat(transport.getTimeSignatureDenominator()).isEqualTo(4);
    }

    @Test
    void shouldRejectInvalidTimeSignature() {
        var transport = new Transport();
        assertThatThrownBy(() -> transport.setTimeSignature(0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.setTimeSignature(4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativePosition() {
        var transport = new Transport();
        assertThatThrownBy(() -> transport.setPositionInBeats(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHaveDefaultTimeSignature() {
        var transport = new Transport();
        assertThat(transport.getTimeSignatureNumerator()).isEqualTo(4);
        assertThat(transport.getTimeSignatureDenominator()).isEqualTo(4);
    }
}
