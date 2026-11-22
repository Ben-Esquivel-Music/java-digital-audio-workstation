package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.StarvedRuns.Line;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A source's run of starved blocks as the lines of the file it becomes. */
class StarvedRunsTest {

    private final StarvedRuns runs = new StarvedRuns(2);

    @Test
    void contiguousBlocksAreOneLineCountedOncePerBlock() {
        runs.addBlock(0, 8, 16, 0, 0);
        runs.addBlock(0, 16, 24, 0, 0);

        StarvedRuns.Run run = runs.end(0);

        assertThat(run.lines()).containsExactly(new Line(8, 16));
        assertThat(run.blocks()).isEqualTo(2);
        assertThat(run.frames()).isEqualTo(16);
        assertThat(runs.isOpen(0)).as("ending a run forgets it").isFalse();
    }

    @Test
    void aRunAcrossTheLoopWrapIsCutAtTheLoopEndAndContinuesFromTheLoopStart() {
        runs.addBlock(0, 1024, 1032, 1000, 1036);
        runs.addBlock(0, 1032, 1040, 1000, 1036);
        runs.addBlock(0, 1004, 1012, 1000, 1036);

        StarvedRuns.Run run = runs.end(0);

        assertThat(run.lines()).containsExactly(new Line(1024, 12), new Line(1000, 12));
        assertThat(run.lines()).allSatisfy(line -> assertThat(line.startFrame() + line.frames()).isLessThanOrEqualTo(1036));
        assertThat(run.blocks()).as("the block across the wrap counts once").isEqualTo(3);
        assertThat(run.frames()).isEqualTo(24);
    }

    @Test
    void anEmptySliceAddsNothingAndCountsNothing() {
        runs.addBlock(0, 2004, 2004, 0, 0);

        assertThat(runs.isOpen(0)).isFalse();
        assertThat(runs.end(0)).isEqualTo(StarvedRuns.Run.NONE);
    }

    @Test
    void aReEntryIntoTheRegionStartsANewLineAndSourcesAreKeptApart() {
        runs.addBlock(0, 1004, 1008, 0, 0);
        runs.addBlock(0, 1004, 1012, 0, 0);
        runs.addBlock(1, 1004, 1012, 0, 0);

        assertThat(runs.end(0).lines()).containsExactly(new Line(1004, 4), new Line(1004, 8));
        assertThat(runs.end(1).lines()).containsExactly(new Line(1004, 8));
    }
}
