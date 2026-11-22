package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A take's dropout figures (story 338, rulings E24, E45, E48 and E49): blocks
 * lost are the take's ring overflows, its starved blocks and the input blocks
 * the engine lost before the rings since the take started; dropouts add the
 * truncation runs; the figures stay as the last take's until the next take
 * starts or a clear.
 */
class TakeDropoutTallyTest {

    private final AtomicLong lostBeforeTheRings = new AtomicLong(40);
    private final TakeDropoutTally tally = new TakeDropoutTally(lostBeforeTheRings::get);
    private final FakeCounts take = new FakeCounts();

    @Test
    void thereAreNoFiguresBeforeTheFirstAudioTake() {
        assertThat(tally.current()).isEmpty();
    }

    @Test
    void blocksLostAddTheRingsTheStarvedBlocksAndTheLossesBeforeTheRingsSinceTheTakeStarted() {
        tally.takeStarted(take);
        take.overflow = 3;
        take.starved = 5;
        take.truncatedFrames = 700;
        take.episodes = 2;
        lostBeforeTheRings.addAndGet(7);

        TakeDropouts figures = tally.current().orElseThrow();

        assertThat(figures).isEqualTo(new TakeDropouts(3 + 5 + 7, 2, 700, true));
        assertThat(figures.dropouts()).as("each lost block and each truncation run").isEqualTo(17);
    }

    @Test
    void theLossesBeforeTheRingsAreLatchedWhenTheTakeStopsAndItsOwnCountsStayReadable() {
        tally.takeStarted(take);
        lostBeforeTheRings.addAndGet(4);
        take.starved = 1;

        tally.takeStopped();
        lostBeforeTheRings.addAndGet(100);
        take.starved = 2;

        assertThat(tally.current()).as("a later stream's losses never reach the finished take")
                .contains(new TakeDropouts(4 + 2, 0, 0, false));
    }

    @Test
    void theNextTakeReplacesTheLastTakesFiguresAndAMidiOnlyTakeHasNone() {
        tally.takeStarted(take);
        take.overflow = 9;
        tally.takeStopped();
        FakeCounts next = new FakeCounts();

        tally.takeStarted(next);
        assertThat(tally.current()).contains(new TakeDropouts(0, 0, 0, true));
        tally.takeStopped();

        tally.takeStarted(null);
        assertThat(tally.current()).as("a MIDI-only take started: no audio figures").isEmpty();
        tally.takeStopped();
        assertThat(tally.current()).isEmpty();
    }

    /** Ruling E97: a finished take that lost nothing keeps its figures, all zero, for the dialog's line. */
    @Test
    void aFinishedTakeThatLostNothingKeepsItsZeroFigures() {
        tally.takeStarted(take);
        tally.takeStopped();

        assertThat(tally.current()).contains(new TakeDropouts(0, 0, 0, false));
    }

    @Test
    void clearForgetsAFinishedTakeAndLeavesATakeStillRecordingAlone() {
        tally.takeStarted(take);
        take.overflow = 1;
        tally.clear();
        assertThat(tally.current()).as("the take in flight keeps its figures")
                .contains(new TakeDropouts(1, 0, 0, true));

        tally.takeStopped();
        assertThat(tally.current()).as("negative control: the last take's figures stay until a clear")
                .isPresent();
        tally.clear();

        assertThat(tally.current()).isEqualTo(Optional.empty());
    }

    @Test
    void aSecondStopLatchesNothingNew() {
        tally.takeStarted(take);
        lostBeforeTheRings.addAndGet(2);
        tally.takeStopped();
        lostBeforeTheRings.addAndGet(50);
        tally.takeStopped();

        assertThat(tally.current().orElseThrow().blocksLost()).isEqualTo(2);
    }

    /** A punch take's figures are its files': the pipeline's file counts, not the rings' raw ones (E76, E86). */
    @Test
    void aPipelinesFiguresAreTheCountsOfTheTakesFiles() {
        RecordingPipeline pipeline = mock(RecordingPipeline.class);
        when(pipeline.getOverflowCount()).thenReturn(9L);
        when(pipeline.getFileOverflowBlocks()).thenReturn(3L);
        when(pipeline.getStarvedInputBlocks()).thenReturn(2L);
        when(pipeline.getTruncatedFrames()).thenReturn(12L);
        when(pipeline.getFileTruncatedFrames()).thenReturn(2L);
        when(pipeline.getTruncationEpisodes()).thenReturn(2L);
        when(pipeline.getFileTruncationEpisodes()).thenReturn(1L);

        tally.takeStarted(TakeDropoutTally.TakeCounts.of(pipeline));

        assertThat(tally.current()).contains(new TakeDropouts(3 + 2, 1, 2, true));
    }

    @Test
    void negativeFiguresAreRefused() {
        assertThatThrownBy(() -> new TakeDropouts(-1, 0, 0, false)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeCounts implements TakeDropoutTally.TakeCounts {
        long overflow;
        long starved;
        long truncatedFrames;
        long episodes;

        @Override
        public long overflowBlocks() {
            return overflow;
        }

        @Override
        public long starvedBlocks() {
            return starved;
        }

        @Override
        public long truncatedFrames() {
            return truncatedFrames;
        }

        @Override
        public long truncationEpisodes() {
            return episodes;
        }
    }
}
