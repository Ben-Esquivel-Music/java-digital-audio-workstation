package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.midi.CountInDownbeat;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The frame math and the phase protocol of a {@link CountInSchedule}
 * (story 328, Recording Reliability book §5.6): the count-in ends on a block
 * boundary, its clicks are laid backwards from that end so the grid meets
 * bar 1 sample-exactly, the pad holds the master latency the clicks are
 * emitted early by, each bar's downbeat is accented, the fire is stamped on
 * the monotonic clock, and the single-writer phases reconcile a cancel
 * against a fire without an atomic read-modify-write.
 */
class CountInScheduleTest {

    @Test
    void twoBarsOfFourFourAt120Bpm48kHz256FrameBlocksEndOnABlockBoundaryWithClicksOnTheGrid() {
        CountInSchedule schedule = new CountInSchedule(8, 4, 120.0, 48_000.0, 256, 0);

        assertThat(schedule.framesPerBeat()).isEqualTo(24_000.0);
        assertThat(schedule.countInFrames()).isEqualTo(192_000L);
        assertThat(schedule.pad()).as("192000 is already a multiple of 256").isZero();
        assertThat(schedule.masterLatencyFrames()).isZero();
        assertThat(schedule.totalFrames()).isEqualTo(192_000L);
        assertThat(schedule.totalFrames() % 256).isZero();
        assertThat(schedule.countInDurationMicros()).isEqualTo(4_000_000L);
        assertThat(schedule.clickCount()).isEqualTo(8);
        for (int beat = 0; beat < 8; beat++) {
            assertThat(schedule.clickFrame(beat)).as("click %d", beat).isEqualTo(beat * 24_000L);
            assertThat(schedule.isAccent(beat)).as("accent %d", beat).isEqualTo(beat == 0 || beat == 4);
        }
    }

    @Test
    void aCountInWhoseLengthIsNotBlockAlignedIsPaddedAtItsStartSoItsEndIs() {
        // 4 beats at 120 BPM and 44.1 kHz: 88200 frames = 172 × 512 + 136.
        CountInSchedule schedule = new CountInSchedule(4, 4, 120.0, 44_100.0, 512, 0);

        assertThat(schedule.countInFrames()).isEqualTo(88_200L);
        assertThat(schedule.pad()).isEqualTo(512L - 136L);
        assertThat(schedule.totalFrames()).isEqualTo(88_576L);
        assertThat(schedule.totalFrames() % 512).isZero();
        assertThat(schedule.clickFrame(0)).as("the first click lands at the pad").isEqualTo(schedule.pad());
        assertThat(schedule.clickFrame(3)).isEqualTo(schedule.totalFrames() - 22_050L);
        assertThat(schedule.countInDurationMicros()).isEqualTo(Math.round(88_576L * 1_000_000.0 / 44_100.0));
    }

    @Test
    void aMasterLatencyBeyondTheAlignmentPadAddsWholeBlocksSoTheFirstClicksEmissionFrameIsNeverNegative() {
        // Alignment pad 376; 1000 frames of master PDC need two more 512-frame blocks.
        CountInSchedule schedule = new CountInSchedule(4, 4, 120.0, 44_100.0, 512, 1_000);

        assertThat(schedule.masterLatencyFrames()).isEqualTo(1_000);
        assertThat(schedule.pad()).isEqualTo(376L + 2 * 512L);
        assertThat(schedule.pad() % 512).as("the pad keeps the alignment phase").isEqualTo(376L);
        assertThat(schedule.totalFrames()).isEqualTo(schedule.pad() + 88_200L);
        assertThat(schedule.totalFrames() % 512).as("the end frame stays block-aligned").isZero();
        assertThat(schedule.clickFrame(0)).isEqualTo(schedule.pad());
        assertThat(schedule.clickFrame(0) - schedule.masterLatencyFrames())
                .as("click 1 is emitted at frame pad − latency, inside the schedule").isEqualTo(400L);
        assertThat(schedule.clickFrame(3)).isEqualTo(schedule.totalFrames() - 22_050L);
        assertThat(schedule.countInDurationMicros()).as("the MIDI window covers the whole padded count-in")
                .isEqualTo(Math.round(schedule.totalFrames() * 1_000_000.0 / 44_100.0));
    }

    @Test
    void aMasterLatencyWithinTheAlignmentPadAddsNothingAndOneFrameBeyondItAddsABlock() {
        CountInSchedule fits = new CountInSchedule(4, 4, 120.0, 44_100.0, 512, 376);
        assertThat(fits.pad()).isEqualTo(376L);
        assertThat(fits.clickFrame(0) - fits.masterLatencyFrames()).isZero();

        CountInSchedule oneOver = new CountInSchedule(4, 4, 120.0, 44_100.0, 512, 377);
        assertThat(oneOver.pad()).isEqualTo(376L + 512L);
        assertThat(oneOver.totalFrames() % 512).isZero();

        CountInSchedule aligned = new CountInSchedule(8, 4, 120.0, 48_000.0, 256, 256);
        assertThat(aligned.pad()).as("no alignment pad: exactly one block covers one block of latency").isEqualTo(256L);
        assertThat(aligned.clickFrame(0) - aligned.masterLatencyFrames()).isZero();
    }

    @Test
    void theTempoHandedInIsTheGridsTempo() {
        CountInSchedule fast = new CountInSchedule(3, 3, 180.0, 48_000.0, 64, 0);

        assertThat(fast.framesPerBeat()).isEqualTo(16_000.0);
        assertThat(fast.countInFrames()).isEqualTo(48_000L);
        assertThat(fast.isAccent(0)).isTrue();
        assertThat(fast.isAccent(1)).isFalse();
        assertThat(fast.isAccent(2)).isFalse();
    }

    @Test
    void forModeIsEmptyForOffAndCountsTheModesBarsOtherwise() {
        assertThat(CountInSchedule.forMode(CountInMode.OFF, 4, 120.0, 48_000.0, 256, 0)).isEmpty();
        assertThat(CountInSchedule.forMode(CountInMode.FOUR_BARS, 3, 120.0, 48_000.0, 256, 0))
                .get().extracting(CountInSchedule::totalBeats).isEqualTo(12);
        assertThat(CountInSchedule.forMode(CountInMode.ONE_BAR, 4, 120.0, 48_000.0, 256, 300))
                .get().extracting(CountInSchedule::masterLatencyFrames).isEqualTo(300);
    }

    @Test
    void invalidParametersAreRefused() {
        assertThatThrownBy(() -> new CountInSchedule(0, 4, 120.0, 48_000.0, 256, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountInSchedule(4, 0, 120.0, 48_000.0, 256, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountInSchedule(4, 4, 0.0, 48_000.0, 256, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountInSchedule(4, 4, 120.0, Double.NaN, 256, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountInSchedule(4, 4, 120.0, 48_000.0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountInSchedule(4, 4, 120.0, 48_000.0, 256, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRenderThreadFiresTheRecordStartOnceAtTheEndFrameAndNeverAfterACancel() {
        CountInSchedule schedule = new CountInSchedule(4, 4, 120.0, 48_000.0, 256, 0);
        AtomicInteger starts = new AtomicInteger();
        schedule.setRecordStart(starts::incrementAndGet);
        assertThatThrownBy(() -> schedule.setRecordStart(() -> { }))
                .as("the record start is set once").isInstanceOf(IllegalStateException.class);

        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.PENDING);
        for (int block = 0; block < 375; block++) {
            boolean due = schedule.advance(256);
            assertThat(due).as("block %d", block).isEqualTo(block == 374);
        }
        assertThat(schedule.cursor()).isEqualTo(schedule.totalFrames());
        assertThat(schedule.fire()).isTrue();
        assertThat(starts).hasValue(1);
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.FIRED);
        assertThat(schedule.isPending()).isFalse();
        assertThat(schedule.cancel()).as("a cancel after the fire lost").isFalse();
        assertThat(schedule.phase()).as("a fired schedule stays fired").isEqualTo(CountInSchedule.Phase.FIRED);
    }

    @Test
    void theFireIsStampedOnTheMonotonicClockBeforeItIsPublishedAndTheScheduleIsTheRecordersDownbeat() {
        CountInSchedule schedule = new CountInSchedule(4, 4, 120.0, 48_000.0, 256, 0);
        CountInDownbeat downbeat = schedule;
        assertThat(downbeat.isFired()).isFalse();
        schedule.advance((int) schedule.totalFrames());

        long before = System.nanoTime();
        assertThat(schedule.fire()).isTrue();
        long after = System.nanoTime();

        assertThat(downbeat.isFired()).isTrue();
        assertThat(downbeat.fireNanos()).isBetween(before, after);
        assertThat(schedule.fireNanos()).isEqualTo(downbeat.fireNanos());
    }

    @Test
    void aCancelBeforeTheEndFrameWinsAndTheRecordStartNeverRuns() {
        CountInSchedule schedule = new CountInSchedule(4, 4, 120.0, 48_000.0, 256, 0);
        AtomicInteger starts = new AtomicInteger();
        schedule.setRecordStart(starts::incrementAndGet);
        schedule.advance(256 * 100);

        assertThat(schedule.cancel()).isTrue();
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(schedule.isPending()).isFalse();
        assertThat(schedule.advance((int) schedule.totalFrames())).isTrue();
        assertThat(schedule.fire()).as("the render thread sees the cancel before it fires").isFalse();
        assertThat(starts).hasValue(0);
        assertThat(schedule.isFired()).isFalse();
    }

    @Test
    void aSilentScheduleFiresWithoutARecordStartAtTheFirstBoundaryAtOrPastItsEndFrame() {
        // Built for 100-frame blocks, walked in 256-frame ones: 88200 frames
        // end 120 frames into the 345th block. The engine refuses such a
        // mismatch at scheduling time; the walk itself tolerates it.
        CountInSchedule schedule = new CountInSchedule(4, 4, 120.0, 44_100.0, 100, 0);
        assertThat(schedule.pad()).isZero();
        int blocks = 0;
        while (!schedule.advance(256)) {
            blocks++;
        }
        assertThat(blocks).isEqualTo(344);
        assertThat(schedule.cursor()).isEqualTo(345L * 256);
        assertThat(schedule.fire()).isTrue();
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.FIRED);
    }

    @Test
    void clicksAreConsumedInOrderOnTheRenderThread() {
        CountInSchedule schedule = new CountInSchedule(2, 2, 120.0, 48_000.0, 256, 0);
        assertThat(schedule.hasPendingClick()).isTrue();
        assertThat(schedule.nextClickFrame()).isEqualTo(schedule.clickFrame(0));
        assertThat(schedule.nextClickAccent()).isTrue();
        schedule.consumeClick();
        assertThat(schedule.nextClickFrame()).isEqualTo(schedule.clickFrame(1));
        assertThat(schedule.nextClickAccent()).isFalse();
        schedule.consumeClick();
        assertThat(schedule.hasPendingClick()).isFalse();
    }
}
