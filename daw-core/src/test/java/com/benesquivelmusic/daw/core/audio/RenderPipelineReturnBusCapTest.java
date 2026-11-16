package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The return-bus cap notice (story 337, ruling E25): a mix with more return
 * buses than the pipeline pre-allocated is one {@code audio.config} record
 * per pipeline — no throwable, the bus count as detail, the constant
 * {@link RenderPipeline#RETURN_BUS_CAP_SUBJECT} as subject — recorded into
 * the render thread's lane instead of a logger call on the render thread.
 * The once-flag is set only when the notice was delivered, and an offline
 * render writes it as one WARNING per pipeline before its first block (story
 * 337, ruling E76).
 */
class RenderPipelineReturnBusCapTest {

    private static final int BLOCK = 64;
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 16, BLOCK);

    @Test
    void tooManyReturnBusesAreRecordedOncePerPipelineOnTheRenderThread() throws Exception {
        Mixer mixer = overCapacityMixer();
        AudioHealthRecord record = new AudioHealthRecord();
        RenderPipeline pipeline = new RenderPipeline(FORMAT, 4, BLOCK, record);
        Transport transport = new Transport();
        transport.play();
        MasteringChain chain = new MasteringChain();
        chain.allocateIntermediateBuffers(2, BLOCK);

        onRenderThread(record, () -> {
            for (int block = 0; block < 3; block++) {
                pipeline.renderBlock(null, new float[2][BLOCK], BLOCK, transport, mixer, List.of(),
                        null, chain, null, null);
            }
        });

        assertThat(record.total(AudioFailureCategory.CONFIG))
                .as("raised once per pipeline, not once per block")
                .isEqualTo(1);
        List<AudioFailureSample> samples = new ArrayList<>();
        record.read(samples::add);
        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.failure()).as("a configuration notice has no throwable").isEmpty();
            assertThat(sample.subject()).contains(RenderPipeline.RETURN_BUS_CAP_SUBJECT);
            assertThat(sample.detail()).isEqualTo(Mixer.MAX_RETURN_BUSES + 1);
        });
    }

    /**
     * Ruling E76: a block that could not record (here, on the test thread,
     * which has no lane) records nothing and leaves the once-flag unset, so
     * the same pipeline's first render-thread block still delivers the
     * notice.
     */
    @Test
    void aBlockOffARenderThreadRecordsNothingAndDoesNotUseUpTheNotice() throws Exception {
        Mixer mixer = overCapacityMixer();
        AudioHealthRecord record = new AudioHealthRecord();
        RenderPipeline pipeline = new RenderPipeline(FORMAT, 4, BLOCK, record);
        Transport transport = new Transport();
        transport.play();
        MasteringChain chain = new MasteringChain();
        chain.allocateIntermediateBuffers(2, BLOCK);

        pipeline.renderBlock(null, new float[2][BLOCK], BLOCK, transport, mixer, List.of(),
                null, chain, null, null);

        assertThat(record.totals()).containsOnly(0L);

        onRenderThread(record, () -> pipeline.renderBlock(null, new float[2][BLOCK], BLOCK,
                transport, mixer, List.of(), null, chain, null, null));

        assertThat(record.total(AudioFailureCategory.CONFIG))
                .as("the block that could not record left the notice for the render thread")
                .isEqualTo(1);
    }

    /**
     * Ruling E76: an offline render over the cap writes one WARNING per
     * pipeline, with {@code main}'s sentence, before its first block — and
     * renders on; the pipeline's record stays empty.
     */
    @Test
    void anOfflineRenderOverTheCapIsLoggedOncePerPipelineBeforeItsFirstBlock() {
        Mixer mixer = overCapacityMixer();
        AtomicInteger warningsAtFirstBlock = new AtomicInteger(-1);
        MixerChannel probed = new MixerChannel("Probed");
        AudioHealthRecord record = new AudioHealthRecord();
        RenderPipeline pipeline = new RenderPipeline(FORMAT, 4, BLOCK, record);
        MasteringChain chain = new MasteringChain();
        try (CapturedPipelineLog log = CapturedPipelineLog.attach()) {
            probed.addInsert(new InsertSlot("Probe",
                    new FirstBlockProbe(() -> warningsAtFirstBlock.compareAndSet(-1, log.records().size()))));
            mixer.addChannel(probed);
            mixer.prepareForPlayback(2, BLOCK);

            for (int render = 0; render < 2; render++) {
                Transport transport = new Transport();
                transport.play();
                pipeline.renderOffline(transport, mixer, List.of(), null, chain,
                        new float[2][BLOCK * 2], BLOCK * 2, BLOCK);
            }

            assertThat(log.records()).singleElement().satisfies(entry -> {
                assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
                assertThat(entry.getMessage()).isEqualTo("Mixer has " + (Mixer.MAX_RETURN_BUSES + 1)
                        + " return buses but only " + Mixer.MAX_RETURN_BUSES
                        + " are supported; extra buses will not receive send audio");
            });
            assertThat(warningsAtFirstBlock.get())
                    .as("the warning was written before the first block rendered")
                    .isEqualTo(1);
            assertThat(record.totals()).as("an offline render records nothing").containsOnly(0L);
        }
    }

    private static Mixer overCapacityMixer() {
        Mixer mixer = new Mixer();
        for (int bus = 0; mixer.getReturnBusCount() < Mixer.MAX_RETURN_BUSES; bus++) {
            mixer.addReturnBus("Return " + bus);
        }
        // The undo overload does not enforce the cap (future story 452).
        mixer.addReturnBus(new MixerChannel("Restored by undo"));
        mixer.prepareForPlayback(2, BLOCK);
        assertThat(mixer.getReturnBusCount()).isEqualTo(Mixer.MAX_RETURN_BUSES + 1);
        return mixer;
    }

    private static void onRenderThread(AudioHealthRecord record, Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RenderThread thread = new RenderThread(record.claimLane(LaneRole.RENDER_DRIVE), () -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "pipeline-render-thread");
        thread.start();
        thread.join(5_000L);
        assertThat(thread.isAlive()).as("the render thread finished within the guard budget").isFalse();
        assertThat(failure.get()).as("nothing escaped the render thread").isNull();
    }

    /** A pass-through insert that runs {@code onFirstBlock} when it first processes. */
    private static final class FirstBlockProbe implements AudioProcessor {

        private final Runnable onFirstBlock;
        private boolean processed;

        FirstBlockProbe(Runnable onFirstBlock) {
            this.onFirstBlock = onFirstBlock;
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            if (!processed) {
                processed = true;
                onFirstBlock.run();
            }
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }

    /** Captures {@link RenderPipeline}'s logger for one test. */
    private static final class CapturedPipelineLog extends Handler implements AutoCloseable {

        private static final Logger PIPELINE_LOG = Logger.getLogger(RenderPipeline.class.getName());

        private final List<LogRecord> entries = new CopyOnWriteArrayList<>();

        static CapturedPipelineLog attach() {
            CapturedPipelineLog log = new CapturedPipelineLog();
            PIPELINE_LOG.addHandler(log);
            return log;
        }

        List<LogRecord> records() {
            return List.copyOf(entries);
        }

        @Override
        public void publish(LogRecord entry) {
            entries.add(entry);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            PIPELINE_LOG.removeHandler(this);
        }
    }
}
