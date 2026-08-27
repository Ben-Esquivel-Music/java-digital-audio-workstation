package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 review — the seek queue's ownership seam
 * ({@link Transport#setRealTimeClockActive(boolean)}), Audio Engine Wiring
 * Design Book §6.1 (thread ownership).
 *
 * <p>Deferring a seek is only safe while something is genuinely draining the
 * queue. Story 317 makes the application refuse PLAYING when no callback
 * opened, but the core transport can still be driven directly by tests and
 * recovery/import code. The independent clock claim therefore remains the
 * authority: an unclaimed transport applies seeks inline instead of queueing
 * them behind a drain that will never come.</p>
 */
class TransportRealTimeClockOwnershipTest {

    @Test
    void theClockIsUnclaimedByDefault() {
        assertThat(new Transport().isRealTimeClockActive()).isFalse();
    }

    @Test
    void seekWhilePlayingAppliesInlineWhenNoRealTimeClockIsDrivingTheTransport() {
        // The exact regression: PLAYING, but no callback is rendering.
        Transport transport = new Transport();
        transport.play();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.setPositionInBeats(68.0); // ruler click on bar 17 (4/4)

        assertThat(transport.getPositionInBeats())
                .as("nothing would ever drain a queued seek here, so it must apply now")
                .isEqualTo(68.0);
        assertThat(fired).containsExactly(ChangeKind.POSITION);
    }

    @Test
    void seekWhileRecordingAppliesInlineWhenNoRealTimeClockIsDriving() {
        Transport transport = new Transport();
        transport.record();

        transport.setPositionInBeats(12.0);

        assertThat(transport.getPositionInBeats()).isEqualTo(12.0);
    }

    @Test
    void everySeekLandsWhenTheClockIsUnclaimedSoThePlayheadNeverFreezes() {
        Transport transport = new Transport();
        transport.play();

        transport.setPositionInBeats(4.0);
        transport.setPositionInBeats(8.0);
        transport.setPositionInBeats(12.0);

        assertThat(transport.getPositionInBeats()).isEqualTo(12.0);

        transport.stop(); // a stop must not discard positions that already applied
        assertThat(transport.getPositionInBeats())
                .as("stop() returns to the play-start anchor, not to a lost seek")
                .isZero();
    }

    @Test
    void seekWhilePlayingIsDeferredOnceARealTimeClockClaimsTheTransport() {
        Transport transport = new Transport();
        transport.setRealTimeClockActive(true);
        transport.play();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.setPositionInBeats(68.0);

        assertThat(transport.getPositionInBeats())
                .as("a claimed transport defers to the RT thread")
                .isZero();
        assertThat(fired).isEmpty();

        transport.advancePosition(0.25);

        assertThat(transport.getPositionInBeats()).isEqualTo(68.0);
        assertThat(fired).containsExactly(ChangeKind.POSITION);
    }

    @Test
    void releasingTheClaimDrainsAPendingSeekInlineSoItIsNeverLost() {
        // The stream closes microseconds after the user clicked the ruler.
        Transport transport = new Transport();
        transport.setRealTimeClockActive(true);
        transport.play();
        transport.setPositionInBeats(33.0); // queued
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.setRealTimeClockActive(false);

        assertThat(transport.isRealTimeClockActive()).isFalse();
        assertThat(transport.getPositionInBeats()).isEqualTo(33.0);
        assertThat(fired).containsExactly(ChangeKind.POSITION);
    }

    @Test
    void releasingTheClaimWithNothingPendingFiresNothing() {
        Transport transport = new Transport();
        transport.setRealTimeClockActive(true);
        transport.play();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.setRealTimeClockActive(false);

        assertThat(fired).isEmpty();
    }

    @Test
    void seeksAfterTheClaimIsReleasedApplyInlineAgain() {
        Transport transport = new Transport();
        transport.setRealTimeClockActive(true);
        transport.play();
        transport.setRealTimeClockActive(false);

        transport.setPositionInBeats(16.0);

        assertThat(transport.getPositionInBeats()).isEqualTo(16.0);
    }

    @Test
    void theAdvanceStillDrainsAQueuedSeekAfterTheClaimWasReleasedMidFlight() {
        // A claim released while a block was already in flight must not strand
        // a seek: advancePosition drains the queue unconditionally.
        Transport transport = new Transport();
        transport.setRealTimeClockActive(true);
        transport.play();
        transport.setPositionInBeats(20.0); // queued

        // The release path would normally drain it; simulate the race where
        // the RT block boundary wins by re-queuing without a claim.
        transport.setRealTimeClockActive(false);
        assertThat(transport.getPositionInBeats()).isEqualTo(20.0);

        transport.setRealTimeClockActive(true);
        transport.setPositionInBeats(25.0); // queued again
        transport.advancePosition(0.5);

        assertThat(transport.getPositionInBeats()).isEqualTo(25.0);
    }
}
