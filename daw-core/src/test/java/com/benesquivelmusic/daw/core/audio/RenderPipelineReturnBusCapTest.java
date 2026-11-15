package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The return-bus cap notice (story 337, ruling E25): a mix with more return
 * buses than the pipeline pre-allocated is one {@code audio.config} record
 * per pipeline — no throwable, the bus count as detail, the constant
 * {@link RenderPipeline#RETURN_BUS_CAP_SUBJECT} as subject — recorded into
 * the render thread's lane instead of a logger call on the render thread.
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
        record.drainSamples(samples::add);
        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.failure()).as("a configuration notice has no throwable").isEmpty();
            assertThat(sample.subject()).contains(RenderPipeline.RETURN_BUS_CAP_SUBJECT);
            assertThat(sample.detail()).isEqualTo(Mixer.MAX_RETURN_BUSES + 1);
        });
    }

    @Test
    void anOfflineRenderOverTheCapRecordsNothingAndKeepsRendering() {
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
}
