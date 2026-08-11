package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
    void stopShouldReturnToThePlayStartAnchor() {
        // Story 315 — play from beat 60, advance, Stop: the playhead sits at
        // beat 60 again (not zero), so auditioning a section never loses the
        // engineer's place in the song.
        Transport transport = new Transport();
        transport.setPositionInBeats(60.0);
        transport.play();
        transport.advancePosition(4.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(64.0);

        transport.stop();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(60.0);
    }

    @Test
    void stopWhileAlreadyStoppedIsANoOp() {
        // Story 315 — the "second Stop rewinds to zero" gesture lives in the
        // UI layer; the model-level stop() is idempotent so internal stops
        // (e.g. RecordingPipeline.stop()) never double-rewind the playhead.
        Transport transport = new Transport();
        transport.setPositionInBeats(60.0);
        transport.play();
        transport.stop();
        assertThat(transport.getPositionInBeats()).isEqualTo(60.0);

        transport.stop();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(60.0);
    }

    @Test
    void stopShouldLeaveThePositionWhenReturnToStartIsDisabled() {
        Transport transport = new Transport();
        transport.setReturnToStartOnStop(false);
        transport.setPositionInBeats(60.0);
        transport.play();
        transport.advancePosition(4.0);

        transport.stop();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(64.0);
        assertThat(transport.isReturnToStartOnStop()).isFalse();
    }

    @Test
    void returnToStartOnStopDefaultsToTrue() {
        assertThat(new Transport().isReturnToStartOnStop()).isTrue();
    }

    @Test
    void recordShouldAnchorAtTheRecordStartPosition() {
        Transport transport = new Transport();
        transport.setPositionInBeats(8.0);
        transport.record();
        transport.advancePosition(2.0);

        transport.stop();

        assertThat(transport.getPositionInBeats()).isEqualTo(8.0);
    }

    @Test
    void resumeFromPauseReAnchorsAtTheResumePosition() {
        Transport transport = new Transport();
        transport.setPositionInBeats(10.0);
        transport.play();               // anchor = 10
        transport.advancePosition(2.0); // 12
        transport.pause();
        transport.play();               // re-anchor = 12
        transport.advancePosition(1.0); // 13

        transport.stop();

        assertThat(transport.getPositionInBeats()).isEqualTo(12.0);
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
        transport.play();
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
        transport.play();
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
        transport.play();
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
        transport.play();
        transport.advancePosition(3.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(10.0);
    }

    @Test
    void shouldWrapMultipleTimesForLargeDelta() {
        Transport transport = new Transport();
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 4.0);
        transport.setPositionInBeats(3.0);
        transport.play();
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

    @Test
    void advancePositionIsANoOpWhenTheTransportIsNotRolling() {
        // Story 315 — the RT advance backs off unless the transport is
        // PLAYING or RECORDING, closing the stop-vs-advance race (a CAS retry
        // that observes stop()'s anchor store also observes STOPPED).
        Transport transport = new Transport();
        transport.setPositionInBeats(5.0);

        transport.advancePosition(1.0); // STOPPED
        assertThat(transport.getPositionInBeats()).isEqualTo(5.0);

        transport.play();
        transport.pause();
        transport.advancePosition(1.0); // PAUSED
        assertThat(transport.getPositionInBeats()).isEqualTo(5.0);
    }

    @Test
    void degenerateTinyLoopRegionWrapsInConstantTime() {
        // Story 315 — the ruler can create a pathological 0.001-beat (or even
        // smaller) loop region mid-gesture. The closed-form modulo wrap must
        // terminate in O(1) where the old while-subtract loop would iterate
        // once per loop length. A large delta over a 1e-9-beat region would
        // take ~1e12 iterations under the old code.
        Transport transport = new Transport();
        transport.setLoopEnabled(true);
        transport.setLoopRegion(0.0, 1e-9);
        transport.play();

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> transport.advancePosition(1000.0));

        assertThat(transport.getPositionInBeats()).isGreaterThanOrEqualTo(0.0);
        assertThat(transport.getPositionInBeats()).isLessThan(1e-9);

        // And the 0.001-beat region the ruler realistically creates:
        Transport ruler = new Transport();
        ruler.setLoopEnabled(true);
        ruler.setLoopRegion(2.0, 2.001);
        ruler.setPositionInBeats(2.0);
        ruler.play();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> ruler.advancePosition(500.0));
        assertThat(ruler.getPositionInBeats()).isGreaterThanOrEqualTo(2.0);
        assertThat(ruler.getPositionInBeats()).isLessThan(2.001);
    }

    @Test
    void shouldHaveNoPunchRegionByDefault() {
        Transport transport = new Transport();

        assertThat(transport.getPunchRegion()).isNull();
        assertThat(transport.isPunchEnabled()).isFalse();
    }

    @Test
    void shouldInstallPunchRegion() {
        Transport transport = new Transport();
        PunchRegion region = new PunchRegion(44_100L, 88_200L, true);

        transport.setPunchRegion(region);

        assertThat(transport.getPunchRegion()).isEqualTo(region);
        assertThat(transport.isPunchEnabled()).isTrue();
    }

    @Test
    void isPunchEnabledShouldReflectEnabledFlag() {
        Transport transport = new Transport();
        transport.setPunchRegion(new PunchRegion(100L, 200L, false));

        assertThat(transport.getPunchRegion()).isNotNull();
        assertThat(transport.isPunchEnabled()).isFalse();
    }

    @Test
    void clearPunchRegionShouldRemoveRegion() {
        Transport transport = new Transport();
        transport.setPunchRegion(new PunchRegion(100L, 200L, true));

        transport.clearPunchRegion();

        assertThat(transport.getPunchRegion()).isNull();
        assertThat(transport.isPunchEnabled()).isFalse();
    }

    @Test
    void setPunchRegionShouldRejectNull() {
        Transport transport = new Transport();

        assertThatThrownBy(() -> transport.setPunchRegion(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Pre-roll / Post-roll tests ─────────────────────────────────────────

    @Test
    void shouldDefaultToDisabledPreRollPostRoll() {
        Transport transport = new Transport();

        assertThat(transport.getPreRollPostRoll()).isEqualTo(PreRollPostRoll.DISABLED);
        assertThat(transport.isPreRollPostRollEnabled()).isFalse();
        assertThat(transport.isInPreRoll()).isFalse();
        assertThat(transport.isInPostRoll()).isFalse();
        assertThat(transport.isInputCaptureGated()).isFalse();
    }

    @Test
    void shouldInstallPreRollPostRoll() {
        Transport transport = new Transport();
        PreRollPostRoll config = PreRollPostRoll.enabled(2, 1);

        transport.setPreRollPostRoll(config);

        assertThat(transport.getPreRollPostRoll()).isEqualTo(config);
        assertThat(transport.isPreRollPostRollEnabled()).isTrue();
    }

    @Test
    void setPreRollPostRollShouldRejectNull() {
        Transport transport = new Transport();

        assertThatThrownBy(() -> transport.setPreRollPostRoll(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void clearPreRollPostRollShouldResetToDisabled() {
        Transport transport = new Transport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 1));

        transport.clearPreRollPostRoll();

        assertThat(transport.getPreRollPostRoll()).isEqualTo(PreRollPostRoll.DISABLED);
        assertThat(transport.isPreRollPostRollEnabled()).isFalse();
    }

    @Test
    void playWithoutPreRollBehavesLikePlay() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);

        double shift = transport.playWithPreRoll();

        assertThat(shift).isZero();
        assertThat(transport.getPositionInBeats()).isEqualTo(16.0);
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.isInPreRoll()).isFalse();
    }

    @Test
    void playWithPreRollShouldSeekBackExactlyPreBarsInBeats() {
        // At 4/4 and punch-in at bar 25 → beat 96 (0-based beat 96 = bar 25 start),
        // 2 bars pre-roll = 8 beats, so play should start at beat 88.
        Transport transport = new Transport();
        transport.setTimeSignature(4, 4);
        transport.setPositionInBeats(96.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));

        double shift = transport.playWithPreRoll();

        assertThat(shift).isEqualTo(8.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(88.0);
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.isInPreRoll()).isTrue();
        assertThat(transport.isInputCaptureGated()).isTrue();
    }

    @Test
    void playWithPreRollShouldHonorTimeSignatureBeatsPerBar() {
        // 3/4 time, 4 bars of pre-roll → 12 beats rewound
        Transport transport = new Transport();
        transport.setTimeSignature(3, 4);
        transport.setPositionInBeats(48.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(4, 0));

        double shift = transport.playWithPreRoll();

        assertThat(shift).isEqualTo(12.0);
        assertThat(transport.getPositionInBeats()).isEqualTo(36.0);
    }

    @Test
    void playWithPreRollShouldClampToZeroWhenRewindExceedsPosition() {
        Transport transport = new Transport();
        transport.setPositionInBeats(4.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0)); // 8 beats

        double shift = transport.playWithPreRoll();

        assertThat(shift).isEqualTo(4.0);
        assertThat(transport.getPositionInBeats()).isZero();
        assertThat(transport.isInPreRoll()).isTrue();
    }

    @Test
    void playWithPreRollShouldIgnoreConfigWhenDisabled() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.setPreRollPostRoll(new PreRollPostRoll(2, 1, false));

        double shift = transport.playWithPreRoll();

        assertThat(shift).isZero();
        assertThat(transport.getPositionInBeats()).isEqualTo(16.0);
        assertThat(transport.isInPreRoll()).isFalse();
    }

    @Test
    void finishPreRollShouldClearInputGating() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        transport.playWithPreRoll();

        assertThat(transport.isInputCaptureGated()).isTrue();

        transport.finishPreRoll();

        assertThat(transport.isInPreRoll()).isFalse();
        assertThat(transport.isInputCaptureGated()).isFalse();
        // The transport continues playing — click/playback uninterrupted.
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
    }

    @Test
    void requestStopWithoutPostRollShouldStopImmediately() {
        Transport transport = new Transport();
        transport.play();

        boolean enteredPostRoll = transport.requestStop();

        assertThat(enteredPostRoll).isFalse();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.isInPostRoll()).isFalse();
    }

    @Test
    void requestStopWithPostRollShouldKeepPlayingAndGateInput() {
        Transport transport = new Transport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.record();

        boolean enteredPostRoll = transport.requestStop();

        assertThat(enteredPostRoll).isTrue();
        assertThat(transport.isInPostRoll()).isTrue();
        // Post-roll plays back but does not record
        assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.isInputCaptureGated()).isTrue();
    }

    @Test
    void finishPostRollShouldStopAndReturnToThePlayStartAnchor() {
        // Story 315 — finishPostRoll() is the deferred stop, so it aligns
        // with stop(): the playhead returns to the play-start anchor instead
        // of hard-resetting to zero.
        Transport transport = new Transport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.setPositionInBeats(32.0);
        transport.play();               // anchor = 32
        transport.advancePosition(2.0); // post-roll playback runs past the stop
        transport.requestStop();
        transport.advancePosition(1.0); // still rolling through the post-roll

        transport.finishPostRoll();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.isInPostRoll()).isFalse();
        assertThat(transport.getPositionInBeats()).isEqualTo(32.0);
    }

    @Test
    void finishPostRollShouldLeaveThePositionWhenReturnToStartIsDisabled() {
        Transport transport = new Transport();
        transport.setReturnToStartOnStop(false);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.setPositionInBeats(32.0);
        transport.play();
        transport.advancePosition(2.0);
        transport.requestStop();

        transport.finishPostRoll();

        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(34.0);
    }

    @Test
    void playWithPreRollShouldAnchorAtThePreRewindPosition() {
        // Story 315 — Stop returns to where the user started Play, not to the
        // rewound pre-roll spot.
        Transport transport = new Transport();
        transport.setTimeSignature(4, 4);
        transport.setPositionInBeats(96.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));

        transport.playWithPreRoll();
        assertThat(transport.getPositionInBeats()).isEqualTo(88.0);

        transport.stop();
        assertThat(transport.getPositionInBeats()).isEqualTo(96.0);
    }

    @Test
    void stopShouldClearPreAndPostRollFlags() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 2));
        transport.playWithPreRoll();
        assertThat(transport.isInPreRoll()).isTrue();

        transport.stop();

        assertThat(transport.isInPreRoll()).isFalse();
        assertThat(transport.isInPostRoll()).isFalse();
        assertThat(transport.isInputCaptureGated()).isFalse();
    }
}
