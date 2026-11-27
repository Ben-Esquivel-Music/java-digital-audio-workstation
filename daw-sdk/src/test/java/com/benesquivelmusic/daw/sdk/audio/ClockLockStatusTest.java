package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClockLockStatus}: the level carries its
 * measurement's newest changes of lock as {@link ClockLockStatus.Transition}s,
 * and a status is only ever built consistent with them.
 */
class ClockLockStatusTest {

    private static final DeviceId DEVICE = new DeviceId("ASIO", "Clocked Interface");
    private static final int WORD_CLOCK = 1;

    @Test
    void aStatusBuiltWithoutTransitionsCarriesNone() {
        ClockLockStatus status = new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, ClockLockStatus.Lock.UNLOCKED, 7L);

        assertThat(status.transitions()).isEmpty();
        assertThat(status.lastSequence()).isZero();
    }

    @Test
    void theTransitionsAreCopied() {
        List<ClockLockStatus.Transition> given = new ArrayList<>(List.of(unlock(1L, 7L)));
        ClockLockStatus status = new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, ClockLockStatus.Lock.UNLOCKED, 7L,
                given);

        given.add(relock(2L, 9L));

        assertThat(status.transitions()).as("the caller's later change does not reach the status")
                .containsExactly(unlock(1L, 7L));
    }

    @Test
    void aGapInTheSequencesIsRejected() {
        assertThatThrownBy(() -> new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, ClockLockStatus.Lock.UNLOCKED, 11L,
                List.of(unlock(1L, 7L), unlock(3L, 11L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not contiguous");
    }

    @Test
    void aRelockAtAnOddSequenceIsRejected() {
        assertThatThrownBy(() -> new ClockLockStatus.Transition(1L, true, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a relock");
    }

    @Test
    void aSequenceBelowOneIsRejected() {
        assertThatThrownBy(() -> new ClockLockStatus.Transition(0L, true, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below 1");
    }

    @Test
    void aLockThatIsNotDecidedCarriesNoTransitions() {
        for (ClockLockStatus.Lock undecided : List.of(ClockLockStatus.Lock.CHECKING,
                ClockLockStatus.Lock.NOT_MEASURABLE)) {
            assertThatThrownBy(() -> new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, undecided, 7L,
                    List.of(unlock(1L, 7L))))
                    .as(undecided.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("carries no transitions");
        }
    }

    @Test
    void aNewestTransitionThatContradictsTheLockIsRejected() {
        assertThatThrownBy(() -> new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, ClockLockStatus.Lock.LOCKED, 7L,
                List.of(unlock(1L, 7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not LOCKED");
    }

    @Test
    void anEvidenceThatIsNotTheNewestTransitionsIsRejected() {
        assertThatThrownBy(() -> new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, ClockLockStatus.Lock.UNLOCKED, 8L,
                List.of(unlock(1L, 7L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceNanos 8");
    }

    @Test
    void lastSequenceIsTheNewestSequence() {
        ClockLockStatus status = new ClockLockStatus(DEVICE, WORD_CLOCK, 1L, ClockLockStatus.Lock.LOCKED, 9L,
                List.of(unlock(3L, 7L), relock(4L, 9L)));

        assertThat(status.lastSequence()).as("the dropped changes count too").isEqualTo(4L);
    }

    private static ClockLockStatus.Transition unlock(long sequence, long evidenceNanos) {
        return new ClockLockStatus.Transition(sequence, false, evidenceNanos);
    }

    private static ClockLockStatus.Transition relock(long sequence, long evidenceNanos) {
        return new ClockLockStatus.Transition(sequence, true, evidenceNanos);
    }
}
