package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.analysis.WindowType;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnalyzerInsertPersistenceTest {
    private static final AudioFormat FORMAT = new AudioFormat(96_000, 2, 24, 256);

    @ParameterizedTest
    @ValueSource(strings = {"track", "return", "master"})
    void restoresAnalyzerIdentityConfigurationAndSlotSemanticsOnEveryHost(String host) throws Exception {
        var original = project();
        var channel = channel(original, host);
        var spectrum = new SpectrumAnalyzerPlugin();
        var tuner = new TunerPlugin();
        var telemetry = new SoundWaveTelemetryPlugin();
        var inactive = new SpectrumAnalyzerPlugin();
        for (var plugin : List.of(spectrum, tuner, telemetry, inactive)) initialize((LiveAnalyzerPlugin) plugin);
        spectrum.reconfigure(2048, WindowType.HAMMING);
        tuner.setReferencePitchHz(432);
        inactive.deactivate();
        channel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, FORMAT.sampleRate()));
        var spectrumSlot = slot("My spectrum", spectrum);
        spectrumSlot.setBypassed(true);
        spectrumSlot.setExpensive(true);
        spectrumSlot.setSidechainSource(original.getMixer().getChannels().getFirst());
        channel.addInsert(spectrumSlot);
        channel.addInsert(slot("My tuner", tuner));
        channel.addInsert(slot("My telemetry", telemetry));
        channel.addInsert(slot("Inactive analyzer", inactive));
        channel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.REVERB, 2, FORMAT.sampleRate()));

        String xml = new ProjectSerializer().serialize(original);
        assertThat(xml).contains(SpectrumAnalyzerPlugin.PLUGIN_ID, TunerPlugin.PLUGIN_ID,
                SoundWaveTelemetryPlugin.PLUGIN_ID);
        var restored = new ProjectDeserializer().deserialize(xml);
        var slots = channel(restored, host).getInsertSlots();
        assertThat(slots).extracting(InsertSlot::getName)
                .containsExactlyElementsOf(channel.getInsertSlots().stream().map(InsertSlot::getName).toList());
        assertThat(slots.getFirst().getEffectType()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(slots.getLast().getEffectType()).isEqualTo(InsertEffectType.REVERB);
        assertThat(slots.get(1).isBypassed()).isTrue();
        assertThat(slots.get(1).isExpensive()).isTrue();
        assertThat(slots.get(1).getSidechainSource()).isSameAs(restored.getMixer().getChannels().getFirst());
        var restoredSpectrum = (SpectrumAnalyzerPlugin) slots.get(1).getPlugin();
        assertThat(restoredSpectrum).isNotSameAs(spectrum);
        assertThat(restoredSpectrum.getAnalyzer().getSampleRate()).isEqualTo(FORMAT.sampleRate());
        assertThat(restoredSpectrum.getAnalyzer().getFftSize()).isEqualTo(2048);
        assertThat(restoredSpectrum.getAnalyzer().getWindowType()).isEqualTo(WindowType.HAMMING);
        var restoredTuner = (TunerPlugin) slots.get(2).getPlugin();
        assertThat(restoredTuner.getPitchDetector()).isNotNull();
        assertThat(restoredTuner.getReferencePitchHz()).isEqualTo(432);
        assertThat(((LiveAnalyzerPlugin) slots.get(4).getPlugin()).isActive()).isFalse();
        assertThat(((SpectrumAnalyzerPlugin) slots.get(4).getPlugin()).getAnalyzer().getFftSize()).isEqualTo(4096);
        for (int index = 1; index <= 3; index++) {
            var restoredPlugin = (LiveAnalyzerPlugin) slots.get(index).getPlugin();
            assertThat(restoredPlugin.isActive()).isTrue();
            assertThat(slots.get(index).getEffectType()).isNull();
            assertTransparent(slots.get(index));
            try (var consumer = restoredPlugin.createAnalysisConsumer(restoredPlugin::acceptAnalysis)) {
                consumer.onBlock(tone(), 2, 8192, FORMAT.sampleRate());
            }
        }
        assertThat(restoredSpectrum.getLatestSpectrum().fftSize()).isEqualTo(2048);
        assertThat(restoredTuner.getLastResult().centsOffset()).isCloseTo(0, within(2.0));
        assertThat(((SoundWaveTelemetryPlugin) slots.get(3).getPlugin()).getWaveform()).isNotNull();
    }

    @Test
    void unknownIdentityIsSkippedWithoutLosingAdjacentEffects() throws Exception {
        var project = project();
        var channel = channel(project, "track");
        var tuner = new TunerPlugin();
        initialize(tuner);
        channel.addInsert(slot("Unknown", tuner));
        channel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, FORMAT.sampleRate()));
        String xml = new ProjectSerializer().serialize(project).replace(TunerPlugin.PLUGIN_ID, "unknown.analyzer");
        var restored = new ProjectDeserializer().deserialize(xml);
        assertThat(channel(restored, "track").getInsertSlots()).extracting(InsertSlot::getEffectType)
                .containsExactly(InsertEffectType.COMPRESSOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "1073741824", "invalid"})
    void invalidOptionalConfigurationRetainsInitializedAnalyzerDefaults(String invalidFftSize) throws Exception {
        var project = project();
        var channel = channel(project, "track");
        var spectrum = new SpectrumAnalyzerPlugin();
        var tuner = new TunerPlugin();
        initialize(spectrum);
        initialize(tuner);
        channel.addInsert(slot("Spectrum", spectrum));
        channel.addInsert(slot("Tuner", tuner));
        String xml = new ProjectSerializer().serialize(project)
                .replace("fft-size=\"4096\"", "fft-size=\"" + invalidFftSize + "\"")
                .replace("reference-pitch-hz=\"440.0\"", "reference-pitch-hz=\"NaN\"");
        var restored = channel(new ProjectDeserializer().deserialize(xml), "track").getInsertSlots();
        assertThat(restored).hasSize(2);
        assertThat(((SpectrumAnalyzerPlugin) restored.getFirst().getPlugin()).getAnalyzer().getFftSize()).isEqualTo(4096);
        assertThat(((TunerPlugin) restored.getLast().getPlugin()).getReferencePitchHz()).isEqualTo(440);
        assertThat(((LiveAnalyzerPlugin) restored.getFirst().getPlugin()).isActive()).isTrue();
    }

    private static DawProject project() {
        var project = new DawProject("Analyzer persistence", FORMAT);
        project.createAudioTrack("Host");
        project.getMixer().addReturnBus("Return");
        return project;
    }

    private static MixerChannel channel(DawProject project, String host) {
        return switch (host) {
            case "track" -> project.getMixer().getChannels().getFirst();
            case "return" -> project.getMixer().getReturnBuses().getFirst();
            case "master" -> project.getMixer().getMasterChannel();
            default -> throw new IllegalArgumentException(host);
        };
    }

    private static void initialize(LiveAnalyzerPlugin plugin) {
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return FORMAT.sampleRate(); }
            @Override public int getBufferSize() { return FORMAT.bufferSize(); }
            @Override public int getAudioChannels() { return FORMAT.channels(); }
            @Override public void log(String message) { }
        });
        plugin.activate();
    }

    private static InsertSlot slot(String name, LiveAnalyzerPlugin plugin) {
        return new InsertSlot(name, plugin.asAudioProcessor().orElseThrow(), null, plugin);
    }

    private static void assertTransparent(InsertSlot slot) {
        float[][] input = {{0.25f, -0.5f}, {-0.75f, 0.125f}};
        float[][] output = new float[2][2];
        slot.getProcessor().process(input, output, 2);
        assertThat(output).isDeepEqualTo(input);
        double[][] inputDouble = {{0.25, -0.5}, {-0.75, 0.125}};
        double[][] outputDouble = new double[2][2];
        slot.getProcessor().processDouble(inputDouble, outputDouble, 2);
        assertThat(outputDouble).isDeepEqualTo(inputDouble);
    }

    private static float[][] tone() {
        float[][] samples = new float[2][8192];
        for (int frame = 0; frame < samples[0].length; frame++) {
            samples[0][frame] = samples[1][frame] = (float) (0.5 * Math.sin(2 * Math.PI * 432 * frame / FORMAT.sampleRate()));
        }
        return samples;
    }
}
