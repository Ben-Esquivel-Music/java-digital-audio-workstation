package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class OfflineParameterPreparationTest {
    @Test
    void offlineRenderAwaitsAutomationAndEveryInsertBeforeCalculatingCompensation() {
        var mixer = new Mixer();
        try (var compensation = mixer.getDelayCompensation()) {
            var channel = new MixerChannel("Source");
            mixer.addChannel(channel);
            var trackProcessor = new PreparedGain();
            var returnProcessor = new PreparedGain();
            var masterProcessor = new PreparedGain();
            var outputProcessor = new PreparedGain();
            var owners = List.of(channel, mixer.getAuxBus(), mixer.getMasterChannel());
            var processors = List.of(trackProcessor, returnProcessor, masterProcessor);
            for (int index = 0; index < owners.size(); index++) {
                var slot = new InsertSlot("Prepared gain", processors.get(index));
                owners.get(index).addInsert(slot);
                slot.getParameterStore().writeFromUiById(0, 0.25);
            }
            var outputChain = new com.benesquivelmusic.daw.core.mastering.MasteringChain(1);
            mixer.getMasterChannel().addInsert(new InsertSlot("Output gain", outputProcessor));
            outputProcessor.setGain(0.25);
            outputChain.allocateIntermediateBuffers(1, 64);
            mixer.prepareForPlayback(1, 64);
            var track = new Track("Source", TrackType.AUDIO);
            var clip = new AudioClip("Tone", 0, 1, null);
            float[][] source = {new float[24_000]};
            Arrays.fill(source[0], 1);
            clip.setAudioData(source);
            track.addClip(clip);
            var target = mixer.getReflectiveParameterBinder().getAutomatablePluginParameters(channel).getFirst();
            track.getAutomationData().getOrCreatePluginLane(target).addPoint(new AutomationPoint(0, 0));
            trackProcessor.beforeProcess = () -> assertThat(compensation.getChannelLatencySamples(0)).isZero();
            var transport = new Transport();
            transport.play();
            var pipeline = new RenderPipeline(new AudioFormat(48_000, 1, 16, 64), 1, 64);
            float[][] output = {new float[128]};

            pipeline.renderOffline(transport, mixer, List.of(track), null, outputChain, output, 128, 64);

            assertThat(output[0]).containsOnly(0);
            assertThat(trackProcessor.processCalls).isEqualTo(2);
            for (PreparedGain processor : List.of(trackProcessor, returnProcessor, masterProcessor, outputProcessor)) {
                assertThat(processor.waits).isEqualTo(2);
                assertThat(processor.gain).isEqualTo(processor.requested);
            }
        }
    }

    public static final class PreparedGain implements DynamicLatencyProcessor, PreparedParameterProcessor {
        double requested = 1;
        double gain = 1;
        int waits;
        int processCalls;
        Runnable beforeProcess = () -> { };
        @ProcessorParam(id = 0, name = "Gain", min = 0, max = 1, defaultValue = 1)
        public double getGain() { return requested; }
        public void setGain(double value) { requested = value; }
        @Override public void enableRealtimeParameterPreparation() { }
        @Override public void applyPreparedParameters() { }
        @Override public void awaitParameterPreparation() { gain = requested; waits++; }
        @Override public void closeParameterPreparation() { }
        @Override public int getLatencySamples() { return (int) (gain * 32); }
        @Override public void process(float[][] input, float[][] output, int frames) {
            beforeProcess.run();
            assertThat(gain).isEqualTo(requested);
            processCalls++;
            for (int frame = 0; frame < frames; frame++) output[0][frame] = (float) (input[0][frame] * gain);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }
}
