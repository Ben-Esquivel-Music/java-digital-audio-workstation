package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The render-side contract of a count-in (story 328, Recording Reliability
 * book §5.6), driven block by block through {@link AudioEngine#processBlock}:
 * with a pending schedule and a stopped transport each block carries the
 * schedule's clicks at their exact frame offsets and nothing else, the
 * playhead holds; at the end boundary the render thread runs the record
 * start and the transport is recording from that very block on; after the
 * fire the running click follows the metronome's own flag; a cancelled
 * schedule renders no further click and never fires; a stop cancels a pending
 * schedule; a rolling transport refuses one, as does a schedule built for
 * another block size or rate than the stream's; with master-insert latency
 * beyond the alignment pad every click, the first included, still reaches
 * the output on its beat.
 */
class CountInRenderContractTest {

    private static final int BLOCK = 256;
    private static final double RATE = 48_000.0;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 2, 16, BLOCK);
    /** 20 ms click at 48 kHz. */
    private static final int CLICK_FRAMES = 960;
    private static final double RECORD_POSITION = 8.0;

    private AudioEngine engine;
    private Transport transport;
    private Mixer mixer;
    private Metronome metronome;
    private final float[][] output = new float[2][BLOCK];

    @BeforeEach
    void bindAStoppedProjectAtTheRecordPosition() {
        DawProject project = new DawProject("Count-in", FORMAT);
        project.createAudioTrack("Silent");
        transport = project.getTransport();
        transport.setTempo(120);
        transport.setPositionInBeats(RECORD_POSITION);
        mixer = project.getMixer();
        engine = new AudioEngine(FORMAT);
        engine.setGraph(transport, mixer, project.getTracks());
        metronome = new Metronome(RATE, 2);
        metronome.setEnabled(false);
        engine.setMetronome(metronome);
        engine.setMetronomeSideOutputRouter(new MetronomeSideOutputRouter());
        engine.start();
    }

    @AfterEach
    void shutDown() {
        engine.shutdown();
    }

    private CountInSchedule oneBar() {
        return oneBar(0);
    }

    private CountInSchedule oneBar(int masterLatencyFrames) {
        return CountInSchedule.forMode(CountInMode.ONE_BAR, 4, 120.0, RATE, BLOCK, masterLatencyFrames).orElseThrow();
    }

    private float[] renderBlockMono() {
        engine.processBlock(null, output, BLOCK);
        return output[0].clone();
    }

    @Test
    void whileCountingInOnlyTheScheduledClicksSoundAndTheTransportHoldsUntilTheEndBoundaryFires() {
        CountInSchedule schedule = oneBar();
        assertThat(schedule.totalFrames()).isEqualTo(96_000L);
        int blocks = (int) (schedule.totalFrames() / BLOCK);
        AtomicInteger firedAtBlock = new AtomicInteger(-1);
        AtomicInteger blockIndex = new AtomicInteger();
        schedule.setRecordStart(() -> {
            firedAtBlock.set(blockIndex.get());
            transport.record();
        });
        engine.scheduleCountIn(schedule);
        assertThat(engine.pendingCountIn()).contains(schedule);

        float[] rendered = new float[blocks * BLOCK];
        for (int block = 0; block < blocks; block++) {
            blockIndex.set(block);
            float[] mono = renderBlockMono();
            System.arraycopy(mono, 0, rendered, block * BLOCK, BLOCK);
            if (block < blocks - 1) {
                assertThat(transport.getState()).as("block %d holds", block).isEqualTo(TransportState.STOPPED);
                assertThat(schedule.isPending()).isTrue();
            }
            assertThat(transport.getPositionInBeats()).as("the playhead holds at P").isEqualTo(RECORD_POSITION);
        }

        assertThat(firedAtBlock).as("the record start ran at the end of the last count-in block").hasValue(blocks - 1);
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.FIRED);
        assertThat(engine.pendingCountIn()).isEmpty();

        // Every non-zero sample lies inside a scheduled click; every click sounds from its second sample.
        for (int frame = 0; frame < rendered.length; frame++) {
            if (rendered[frame] != 0f) {
                assertThat(insideAClick(schedule, frame)).as("frame %d sounds outside every click", frame).isTrue();
            }
        }
        for (int click = 0; click < schedule.clickCount(); click++) {
            long start = schedule.clickFrame(click);
            assertThat(rendered[(int) start]).as("click %d starts from a zero crossing", click).isZero();
            assertThat(rendered[(int) start + 1]).as("click %d sounds at its exact frame", click).isNotZero();
            if (start > 0) {
                assertThat(rendered[(int) start - 1]).as("nothing precedes click %d", click).isZero();
            }
        }
        assertThat(peak(rendered, schedule.clickFrame(0))).as("the downbeat is accented")
                .isGreaterThan(peak(rendered, schedule.clickFrame(1)));
        assertThat(peak(rendered, schedule.clickFrame(1))).isEqualTo(peak(rendered, schedule.clickFrame(2)));
    }

    @Test
    void afterTheFireTheRunningClickFollowsTheMetronomesOwnFlag() {
        CountInSchedule disabled = oneBar();
        disabled.setRecordStart(transport::record);
        engine.scheduleCountIn(disabled);
        renderWholeCountIn(disabled);
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        float[] firstRecordedBlock = renderBlockMono();
        assertThat(firstRecordedBlock).as("no click after the fire while the metronome is disabled")
                .containsOnly(0f);
        assertThat(transport.getPositionInBeats()).as("the transport rolls once recording")
                .isGreaterThan(RECORD_POSITION);

        // The same count-in with the running click enabled: bar 1's downbeat clicks at the block's start.
        transport.stop();
        transport.setPositionInBeats(RECORD_POSITION);
        metronome.setEnabled(true);
        CountInSchedule enabled = oneBar();
        enabled.setRecordStart(transport::record);
        engine.scheduleCountIn(enabled);
        renderWholeCountIn(enabled);
        float[] barOne = renderBlockMono();
        assertThat(barOne[0]).isZero();
        assertThat(barOne[1]).as("the running click sounds on bar 1's downbeat").isNotZero();
        assertThat(maxAbs(barOne)).as("one click, not the running walk's and the count-in's summed")
                .isLessThanOrEqualTo(accentedClickPeak());
    }

    @Test
    void withMasterLatencyTheCountInEmitsBarOnesDownbeatSoItReachesTheOutputAtTheRecordPosition() {
        // Story 328 review: the running click's walk starts at P advanced by
        // the master chain's latency, so with any latency the downbeat at P
        // itself is nobody's. The count-in emits it, L frames early like its
        // own clicks, when the running metronome is enabled.
        int masterLatency = 1_000;
        mixer.getMasterChannel().addInsert(new InsertSlot("Master delay", new SampleDelay(masterLatency)));
        metronome.setEnabled(true);
        CountInSchedule schedule = oneBar(masterLatency);
        schedule.setRecordStart(transport::record);
        engine.scheduleCountIn(schedule);

        int blocks = (int) (schedule.totalFrames() / BLOCK);
        float[] rendered = new float[blocks * BLOCK];
        for (int block = 0; block < blocks; block++) {
            float[] mono = renderBlockMono();
            System.arraycopy(mono, 0, rendered, block * BLOCK, BLOCK);
        }
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
        int lastClickEnd = (int) schedule.clickFrame(schedule.clickCount() - 1) + CLICK_FRAMES;
        assertThat(Arrays.copyOfRange(rendered, lastClickEnd, rendered.length))
                .as("after click 4 the output is silent until the end frame: the downbeat is still in the delay line")
                .containsOnly(0f);

        float[] barOne = renderBlockMono();
        assertThat(barOne[0]).isZero();
        assertThat(barOne[1]).as("bar 1's downbeat reaches the output at the record position").isNotZero();
        assertThat(maxAbs(barOne)).as("a single accented click").isLessThanOrEqualTo(accentedClickPeak());
        assertThat(maxAbs(barOne)).as("accented like the count-in's own downbeat")
                .isGreaterThan(peak(rendered, schedule.clickFrame(1)));

        // With the running metronome disabled the count-in still clicks, but
        // bar 1's downbeat is the running click's and stays silent.
        transport.stop();
        transport.setPositionInBeats(RECORD_POSITION);
        metronome.setEnabled(false);
        CountInSchedule silentBarOne = oneBar(masterLatency);
        silentBarOne.setRecordStart(transport::record);
        engine.scheduleCountIn(silentBarOne);
        for (int block = 0; block < blocks; block++) {
            renderBlockMono();
        }
        assertThat(silentBarOne.isFired()).isTrue();
        for (int block = 0; block < 8; block++) {
            assertThat(renderBlockMono()).as("no downbeat with the metronome disabled, block %d", block)
                    .containsOnly(0f);
        }
    }

    @Test
    void theEmittedDownbeatIsAccentedByTheProjectBarGridLikeTheRunningClick() {
        // Story 328 review round 2: at a record position that is not
        // bar-aligned (beat 2 in 4/4) the running click at that beat is
        // unaccented; the downbeat the count-in emits under master latency
        // follows the same rule, so the hand-off click does not change
        // with the master chain's latency.
        int masterLatency = 1_000;
        mixer.getMasterChannel().addInsert(new InsertSlot("Master delay", new SampleDelay(masterLatency)));
        metronome.setEnabled(true);
        transport.setPositionInBeats(2.0);
        CountInSchedule schedule = oneBar(masterLatency);
        schedule.setRecordStart(transport::record);
        engine.scheduleCountIn(schedule);
        renderWholeCountIn(schedule);
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);

        float[] barOne = renderBlockMono();
        assertThat(barOne[1]).as("the record position's click reaches the output").isNotZero();
        assertThat(maxAbs(barOne)).as("beat 2 of the project grid is not a downbeat: unaccented")
                .isLessThan(accentedClickPeak())
                .isLessThanOrEqualTo(unaccentedClickPeak());

        assertThat(RenderPipeline.isProjectBarDownbeat(8.0, 4)).isTrue();
        assertThat(RenderPipeline.isProjectBarDownbeat(2.0, 4)).isFalse();
        assertThat(RenderPipeline.isProjectBarDownbeat(6.0, 3)).isTrue();
        assertThat(RenderPipeline.isProjectBarDownbeat(6.0, 0)).as("no numerator reads as 4/4, like the running click")
                .isFalse();
    }

    private float accentedClickPeak() {
        return clickPeak(true);
    }

    private float unaccentedClickPeak() {
        return clickPeak(false);
    }

    private float clickPeak(boolean accent) {
        float peak = 0f;
        for (float sample : metronome.generateClick(accent)[0]) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static float maxAbs(float[] samples) {
        float peak = 0f;
        for (float sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    @Test
    void aCancelledScheduleRendersNoFurtherClickAndNeverFires() {
        CountInSchedule schedule = oneBar();
        AtomicInteger starts = new AtomicInteger();
        schedule.setRecordStart(starts::incrementAndGet);
        engine.scheduleCountIn(schedule);
        for (int block = 0; block < 100; block++) {
            renderBlockMono();
        }
        assertThat(engine.cancelCountIn(schedule)).isTrue();
        assertThat(engine.pendingCountIn()).isEmpty();
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);

        int remaining = (int) (schedule.totalFrames() / BLOCK) - 100 + 10;
        for (int block = 0; block < remaining; block++) {
            assertThat(renderBlockMono()).as("block %d after the cancel", block).containsOnly(0f);
        }
        assertThat(starts).hasValue(0);
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(RECORD_POSITION);
    }

    @Test
    void theEnginesStopCancelsAPendingScheduleRatherThanStrandingIt() {
        CountInSchedule schedule = oneBar();
        engine.scheduleCountIn(schedule);
        renderBlockMono();
        engine.stop();
        assertThat(schedule.phase()).isEqualTo(CountInSchedule.Phase.CANCELLED);
        assertThat(engine.pendingCountIn()).isEmpty();
    }

    @Test
    void aRollingTransportAndASecondPendingScheduleAreRefused() {
        transport.play();
        assertThatThrownBy(() -> engine.scheduleCountIn(oneBar()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rolling");
        transport.stop();
        transport.setPositionInBeats(RECORD_POSITION);

        CountInSchedule first = oneBar();
        engine.scheduleCountIn(first);
        assertThatThrownBy(() -> engine.scheduleCountIn(oneBar()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already pending");
        engine.cancelCountIn(first);
        assertThatThrownBy(() -> engine.scheduleCountIn(first))
                .as("a spent schedule is not reinstalled").isInstanceOf(IllegalArgumentException.class);
        engine.scheduleCountIn(oneBar());
        assertThat(engine.pendingCountIn()).isPresent();
    }

    @Test
    void aScheduleBuiltForAnotherBlockSizeOrRateThanTheStreamsIsRefusedBeforeItIsInstalled() {
        CountInSchedule otherBlockSize = new CountInSchedule(3, 3, 120.0, RATE, 100, 0);
        assertThatThrownBy(() -> engine.scheduleCountIn(otherBlockSize))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("100-frame blocks").hasMessageContaining("256-frame blocks");
        assertThat(engine.pendingCountIn()).isEmpty();
        assertThat(otherBlockSize.isPending()).as("the refused schedule is untouched").isTrue();

        CountInSchedule otherRate = new CountInSchedule(3, 3, 120.0, 44_100.0, BLOCK, 0);
        assertThatThrownBy(() -> engine.scheduleCountIn(otherRate))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("44100.0 Hz");
        assertThat(engine.pendingCountIn()).isEmpty();

        engine.scheduleCountIn(oneBar());
        assertThat(engine.pendingCountIn()).as("a schedule in the stream's format installs").isPresent();
    }

    @Test
    void withMasterInsertLatencyBeyondThePadEveryClickIncludingTheFirstReachesTheOutputOnItsBeat() {
        // One bar is 375 blocks exactly, so the alignment pad is 0: 1000
        // frames of master PDC need four whole blocks of pad, and click 1
        // is emitted 24 frames into block 0 — never clamped to its start.
        int masterLatency = 1_000;
        mixer.getMasterChannel().addInsert(new InsertSlot("Master delay", new SampleDelay(masterLatency)));
        assertThat(mixer.getDelayCompensation().getMasterLatencySamples()).as("premise").isEqualTo(masterLatency);
        CountInSchedule schedule = oneBar(masterLatency);
        assertThat(schedule.pad()).isEqualTo(4L * BLOCK);
        assertThat(schedule.clickFrame(0) - masterLatency).isEqualTo(24L);
        schedule.setRecordStart(transport::record);
        engine.scheduleCountIn(schedule);

        int blocks = (int) (schedule.totalFrames() / BLOCK);
        float[] rendered = new float[blocks * BLOCK];
        for (int block = 0; block < blocks; block++) {
            float[] mono = renderBlockMono();
            System.arraycopy(mono, 0, rendered, block * BLOCK, BLOCK);
        }
        assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);

        // Through the delayed master chain the clicks reach the output on the grid, click 1 included.
        for (int click = 0; click < schedule.clickCount(); click++) {
            long start = schedule.clickFrame(click);
            assertThat(rendered[(int) start - 1]).as("nothing precedes click %d", click).isZero();
            assertThat(rendered[(int) start]).as("click %d starts from a zero crossing", click).isZero();
            assertThat(rendered[(int) start + 1]).as("click %d sounds on its beat", click).isNotZero();
        }
        assertThat(Arrays.copyOfRange(rendered, 0, (int) schedule.clickFrame(0)))
                .as("the pad before click 1 is silent at the output").containsOnly(0f);
    }

    /** A master insert whose only effect is {@code latency} frames of delay. */
    private static final class SampleDelay implements AudioProcessor {
        private final float[][] history;
        private int position;

        private SampleDelay(int latency) {
            history = new float[2][latency];
        }

        @Override
        public void process(float[][] input, float[][] output, int frames) {
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < 2; channel++) {
                    float sample = input[channel][frame];
                    output[channel][frame] = history[channel][position];
                    history[channel][position] = sample;
                }
                position = (position + 1) % history[0].length;
            }
        }

        @Override
        public void reset() {
            for (float[] lane : history) {
                Arrays.fill(lane, 0f);
            }
            position = 0;
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }

        @Override
        public int getLatencySamples() {
            return history[0].length;
        }
    }

    private void renderWholeCountIn(CountInSchedule schedule) {
        int blocks = (int) (schedule.totalFrames() / BLOCK);
        for (int block = 0; block < blocks; block++) {
            renderBlockMono();
        }
        assertThat(schedule.isFired()).as("premise: the count-in fired").isTrue();
    }

    private static boolean insideAClick(CountInSchedule schedule, int frame) {
        for (int click = 0; click < schedule.clickCount(); click++) {
            long start = schedule.clickFrame(click);
            if (frame >= start && frame < start + CLICK_FRAMES) {
                return true;
            }
        }
        return false;
    }

    private static float peak(float[] rendered, long clickStart) {
        float peak = 0f;
        for (int frame = (int) clickStart; frame < clickStart + CLICK_FRAMES && frame < rendered.length; frame++) {
            peak = Math.max(peak, Math.abs(rendered[frame]));
        }
        return peak;
    }
}
