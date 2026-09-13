package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginGraph;
import com.benesquivelmusic.daw.core.plugin.CompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.MetronomePlugin;
import com.benesquivelmusic.daw.core.plugin.SignalGeneratorPlugin;
import com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginGraphLivenessTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 256);
    @TempDir Path directory;

    @Test
    void keyboardIsAudibleOnTheConfiguredBackendWithoutAJavaSoundDevice() throws Exception {
        var graph = graph();
        var plugin = new VirtualKeyboardPlugin();
        graph.channel().addInsert(new BuiltInPluginGraph(new com.benesquivelmusic.daw.core.mixer.ProcessorRegistry())
                .createSlot(plugin, context(), null));
        var backend = new com.benesquivelmusic.daw.sdk.audio.MockAudioBackend();
        graph.engine().setStreamingProvision(new StreamingProvision(backend.name(), List.of(
                new BackendStreamRung(backend, com.benesquivelmusic.daw.sdk.audio.DeviceId.defaultFor(backend.name())))));
        plugin.getProcessor().noteOn(69, 100);
        try {
            graph.engine().startAudioOutput();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (backend.recordedOutput().length == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            graph.engine().stopAudioOutput();
            assertThat(backend.recordedOutput()).isNotEmpty();
            boolean audible = false;
            for (byte sample : backend.recordedOutput()) { audible |= sample != 0; }
            assertThat(audible).isTrue();
        } finally {
            graph.engine().stopAudioOutput();
            graph.engine().stop();
            plugin.dispose();
        }
    }

    @Test
    void storeWritesReachTheProcessorByTheNextEngineBlockIncludingBypass() {
        var graph = graph();
        var processor = new GainProcessor();
        var slot = new InsertSlot("Gain", processor);
        graph.channel().addInsert(slot);
        var source = new SignalGeneratorPlugin();
        graph.channel().insertInsert(0, new BuiltInPluginGraph(new com.benesquivelmusic.daw.core.mixer.ProcessorRegistry()).createSlot(source, context(), null));
        graph.engine().start();
        try {
            slot.getParameterStore().writeFromUiById(7, 0);
            var output = new float[2][256];
            graph.engine().processBlock(null, output, 256);
            assertThat(processor.getGain()).isZero();
            assertThat(peak(output)).isZero();
            graph.channel().setInsertBypassed(1, true);
            slot.getParameterStore().writeFromUiById(7, 0.75);
            graph.engine().processBlock(null, output, 256);
            assertThat(processor.getGain()).isEqualTo(0.75);
            assertThat(peak(output)).isGreaterThan(0.01f);
            graph.channel().setInsertBypassed(1, false);
            graph.engine().processBlock(null, output, 256);
            assertThat(peak(output)).isBetween(0.01f, 0.1f);
        } finally { graph.engine().stop(); }
    }

    @Test
    void menuCompressorRoutesThroughItsAnnotatedProcessorDespiteLegacyNoOpAutomation() {
        var plugin = new CompressorPlugin();
        var slot = new BuiltInPluginGraph(new com.benesquivelmusic.daw.core.mixer.ProcessorRegistry()).createSlot(plugin, context(), null);
        slot.getParameterStore().writeFromUiById(0, -37);
        slot.drainParametersToAudio();
        assertThat(plugin.getProcessor().getThresholdDb()).isEqualTo(-37);
        assertThat(slot.getEditorPlugin()).isSameAs(plugin);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bypassedInsertsOnMutedReturnsAndMasterReceiveWritesAtEveryEngineBlock(boolean playing) {
        var graph = graph();
        var channels = List.of(graph.channel(), graph.mixer().getAuxBus(),
                graph.mixer().addReturnBus("Delay Return"), graph.mixer().getMasterChannel());
        for (MixerChannel channel : channels) {
            channel.addInsert(new InsertSlot("Gain", new GainProcessor()));
            channel.setInsertBypassed(0, true);
            channel.setMuted(true);
        }
        graph.engine().start();
        if (playing) { graph.transport().play(); }
        try {
            var output = new float[2][256];
            for (double gain : new double[] {0.25, 0.75}) {
                for (MixerChannel channel : channels) {
                    channel.getInsertSlots().getFirst().getParameterStore().writeFromUiById(7, gain);
                }
                graph.engine().processBlock(null, output, 256);
                for (MixerChannel channel : channels) {
                    var slot = channel.getInsertSlots().getFirst();
                    assertThat(((GainProcessor) slot.getProcessor()).getGain())
                            .as("queued gain on %s with playing=%s", channel.getName(), playing)
                            .isEqualTo(gain);
                    assertThat(slot.isBypassed()).isTrue();
                }
                assertThat(peak(output)).isZero();
            }
        } finally { graph.engine().stop(); }
    }

    @Test
    void metronomeStoreControlsTheEngineSingletonWithoutAddingAnotherClick() {
        var graph = graph();
        var metronome = new Metronome(FORMAT.sampleRate(), FORMAT.channels());
        graph.engine().setMetronome(metronome);
        var plugin = new MetronomePlugin();
        var slot = new BuiltInPluginGraph(new com.benesquivelmusic.daw.core.mixer.ProcessorRegistry()).createSlot(plugin, context(), metronome);
        graph.channel().addInsert(slot);
        graph.engine().start();
        try {
            slot.getParameterStore().writeFromUiById(1, 0.23);
            graph.engine().processBlock(null, new float[2][256], 256);
            assertThat(plugin.getMetronome()).isSameAs(graph.engine().getMetronome());
            assertThat(metronome.getVolume()).isEqualTo(0.23f);
            graph.channel().setInsertBypassed(0, true);
            assertThat(metronome.isEnabled()).isFalse();
            graph.channel().removeInsert(slot);
            assertThat(metronome.isEnabled()).as("removing the configuration slot releases its bypass gate").isTrue();
            graph.channel().addInsert(slot);
            assertThat(metronome.isEnabled()).as("undo restores the slot's bypass state").isFalse();
            graph.channel().setInsertBypassed(0, false);
            assertThat(metronome.isEnabled()).isTrue();
        } finally { graph.engine().stop(); }
    }

    @Test
    void keyboardAuditionsThroughGraphWhenStoppedAndRecordsOnItsArmedTrack() {
        var graph = graph();
        var plugin = new VirtualKeyboardPlugin();
        var slot = new BuiltInPluginGraph(new com.benesquivelmusic.daw.core.mixer.ProcessorRegistry()).createSlot(plugin, context(), null);
        graph.channel().addInsert(slot);
        graph.engine().start();
        var recording = new RecordingPipeline(graph.engine(), graph.transport(), FORMAT,
                directory, List.of(graph.track()));
        try {
            plugin.getProcessor().noteOn(69, 100);
            var output = new float[2][256];
            graph.engine().processBlock(null, output, 256);
            assertThat(peak(output)).as("stopped audition through active engine output").isGreaterThan(0.01f);
            recording.start();
            graph.engine().processBlock(null, output, 256);
            graph.engine().processBlock(null, output, 256);
            var clips = recording.stop();
            assertThat(clips).hasSize(1);
            assertThat(peak(clips.getFirst().getAudioData())).as("instrument audio recorded without physical input")
                    .isGreaterThan(0.01f);
            assertThat(graph.engine().hasGraphInstrument(graph.track())).isTrue();
        } finally {
            if (recording.isActive()) { recording.stop(); }
            graph.engine().stop();
            plugin.dispose();
        }
    }

    @Test
    void rawProcessorEditorSharesLiveStateAndDoesNotResetItOnOpening() {
        var processor = new GainProcessor();
        processor.setGain(0.3);
        var slot = new InsertSlot("External DSP", processor);
        assertThat(slot.getEditorPlugin().asAudioProcessor()).containsSame(processor);
        assertThat(slot.getParameterStore().valueById(7)).isEqualTo(0.3);
        assertThat(slot.getParameterStore()).isSameAs(slot.getParameterStore());
    }

    private static Graph graph() {
        var engine = new AudioEngine(FORMAT);
        var transport = new Transport();
        var mixer = new Mixer();
        var channel = new MixerChannel("Instrument");
        var track = new Track("Instrument", TrackType.AUDIO);
        track.setArmed(true);
        mixer.addChannel(channel);
        engine.setGraph(transport, mixer, List.of(track));
        return new Graph(engine, transport, mixer, channel, track);
    }

    private record Graph(AudioEngine engine, Transport transport, Mixer mixer, MixerChannel channel, Track track) { }

    private static PluginContext context() {
        return new PluginContext() {
            @Override public double getSampleRate() { return FORMAT.sampleRate(); }
            @Override public int getBufferSize() { return FORMAT.bufferSize(); }
            @Override public int getAudioChannels() { return FORMAT.channels(); }
            @Override public void log(String message) { }
        };
    }

    private static float peak(float[][] buffer) {
        float peak = 0;
        for (float[] channel : buffer) {
            for (float sample : channel) { peak = Math.max(peak, Math.abs(sample)); }
        }
        return peak;
    }

    public static final class GainProcessor implements AudioProcessor {
        private double gain = 1;
        @ProcessorParam(id = 7, name = "Gain", min = 0, max = 1, defaultValue = 1)
        public double getGain() { return gain; }
        public void setGain(double gain) { this.gain = gain; }
        @Override @RealTimeSafe public void process(float[][] input, float[][] output, int frames) {
            for (int ch = 0; ch < output.length; ch++) {
                for (int frame = 0; frame < frames; frame++) { output[ch][frame] = (float) (input[ch][frame] * gain); }
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}

