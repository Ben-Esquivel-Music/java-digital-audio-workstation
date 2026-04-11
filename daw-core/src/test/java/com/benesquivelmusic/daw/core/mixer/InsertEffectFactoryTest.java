package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.*;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsertEffectFactoryTest {

    private static final int CHANNELS = 2;
    private static final double SAMPLE_RATE = 44100.0;

    @Test
    void shouldCreateCompressorProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.COMPRESSOR, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(CompressorProcessor.class);
    }

    @Test
    void shouldCreateLimiterProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.LIMITER, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(LimiterProcessor.class);
    }

    @Test
    void shouldCreateReverbProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.REVERB, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(ReverbProcessor.class);
    }

    @Test
    void shouldCreateDelayProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.DELAY, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(DelayProcessor.class);
    }

    @Test
    void shouldCreateChorusProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.CHORUS, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(ChorusProcessor.class);
    }

    @Test
    void shouldCreateNoiseGateProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.NOISE_GATE, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(NoiseGateProcessor.class);
    }

    @Test
    void shouldCreateStereoImagerProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.STEREO_IMAGER, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(StereoImagerProcessor.class);
    }

    @Test
    void shouldCreateParametricEqProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.PARAMETRIC_EQ, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(ParametricEqProcessor.class);
    }

    @Test
    void shouldCreateGraphicEqProcessor() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.GRAPHIC_EQ, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(GraphicEqProcessor.class);
    }

    @Test
    void shouldRejectClapPluginType() {
        assertThatThrownBy(() ->
                InsertEffectFactory.createProcessor(InsertEffectType.CLAP_PLUGIN, CHANNELS, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullType() {
        assertThatThrownBy(() ->
                InsertEffectFactory.createProcessor(null, CHANNELS, SAMPLE_RATE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateSlotWithCorrectName() {
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, CHANNELS, SAMPLE_RATE);
        assertThat(slot.getName()).isEqualTo("Compressor");
        assertThat(slot.getProcessor()).isInstanceOf(CompressorProcessor.class);
        assertThat(slot.isBypassed()).isFalse();
    }

    @Test
    void shouldCreateSlotWithEffectType() {
        InsertSlot slot = InsertEffectFactory.createSlot(
                InsertEffectType.REVERB, CHANNELS, SAMPLE_RATE);
        assertThat(slot.getEffectType()).isEqualTo(InsertEffectType.REVERB);
    }

    @Test
    void shouldRejectNonStereoChannelsForStereoImager() {
        assertThatThrownBy(() ->
                InsertEffectFactory.createProcessor(InsertEffectType.STEREO_IMAGER, 1, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 channels");
    }

    @Test
    void shouldReturnEmptyParametersForParametricEq() {
        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(
                InsertEffectType.PARAMETRIC_EQ);
        assertThat(params).isEmpty();
    }

    @Test
    void shouldRejectMismatchedProcessorType() {
        ReverbProcessor wrongProcessor = new ReverbProcessor(CHANNELS, SAMPLE_RATE);
        assertThatThrownBy(() ->
                InsertEffectFactory.createParameterHandler(InsertEffectType.COMPRESSOR, wrongProcessor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = {"CLAP_PLUGIN", "PARAMETRIC_EQ"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldReturnNonEmptyParameterDescriptors(InsertEffectType type) {
        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(type);
        assertThat(params).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = {"CLAP_PLUGIN", "PARAMETRIC_EQ"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldReturnParameterDescriptorsWithValidRanges(InsertEffectType type) {
        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(type);
        for (PluginParameter param : params) {
            assertThat(param.minValue()).isLessThanOrEqualTo(param.maxValue());
            assertThat(param.defaultValue()).isBetween(param.minValue(), param.maxValue());
        }
    }

    @Test
    void shouldReturnEmptyParametersForClapPlugin() {
        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(
                InsertEffectType.CLAP_PLUGIN);
        assertThat(params).isEmpty();
    }

    @Test
    void shouldCreateCompressorParameterHandler() {
        CompressorProcessor processor = new CompressorProcessor(CHANNELS, SAMPLE_RATE);
        BiConsumer<Integer, Double> handler = InsertEffectFactory.createParameterHandler(
                InsertEffectType.COMPRESSOR, processor);

        handler.accept(0, -30.0);
        assertThat(processor.getThresholdDb()).isEqualTo(-30.0);

        handler.accept(1, 8.0);
        assertThat(processor.getRatio()).isEqualTo(8.0);
    }

    @Test
    void shouldCreateReverbParameterHandler() {
        ReverbProcessor processor = new ReverbProcessor(CHANNELS, SAMPLE_RATE);
        BiConsumer<Integer, Double> handler = InsertEffectFactory.createParameterHandler(
                InsertEffectType.REVERB, processor);

        handler.accept(0, 0.8);
        assertThat(processor.getRoomSize()).isEqualTo(0.8);

        handler.accept(3, 0.6);
        assertThat(processor.getMix()).isEqualTo(0.6);
    }

    @Test
    void shouldAvailableTypesExcludeClapPlugin() {
        List<InsertEffectType> types = InsertEffectFactory.availableTypes();
        assertThat(types).doesNotContain(InsertEffectType.CLAP_PLUGIN);
        assertThat(types).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
    void shouldCreateProcessorForAllBuiltInTypes(InsertEffectType type) {
        AudioProcessor processor = InsertEffectFactory.createProcessor(type, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
    void shouldCreateParameterHandlerForAllBuiltInTypes(InsertEffectType type) {
        AudioProcessor processor = InsertEffectFactory.createProcessor(type, CHANNELS, SAMPLE_RATE);
        BiConsumer<Integer, Double> handler = InsertEffectFactory.createParameterHandler(type, processor);
        assertThat(handler).isNotNull();
    }

    // ── getParameterValues tests ────────────────────────────────────────────

    @Test
    void shouldReturnCurrentCompressorValues() {
        CompressorProcessor processor = new CompressorProcessor(CHANNELS, SAMPLE_RATE);
        processor.setThresholdDb(-30.0);
        processor.setRatio(8.0);

        Map<Integer, Double> values = InsertEffectFactory.getParameterValues(
                InsertEffectType.COMPRESSOR, processor);

        assertThat(values).containsEntry(0, -30.0);
        assertThat(values).containsEntry(1, 8.0);
        assertThat(values).hasSize(6);
    }

    @Test
    void shouldReturnCurrentReverbValues() {
        ReverbProcessor processor = new ReverbProcessor(CHANNELS, SAMPLE_RATE);
        processor.setRoomSize(0.8);
        processor.setMix(0.6);

        Map<Integer, Double> values = InsertEffectFactory.getParameterValues(
                InsertEffectType.REVERB, processor);

        assertThat(values).containsEntry(0, 0.8);
        assertThat(values).containsEntry(3, 0.6);
        assertThat(values).hasSize(4);
    }

    @Test
    void shouldReturnEmptyValuesForParametricEq() {
        AudioProcessor processor = InsertEffectFactory.createProcessor(
                InsertEffectType.PARAMETRIC_EQ, CHANNELS, SAMPLE_RATE);
        Map<Integer, Double> values = InsertEffectFactory.getParameterValues(
                InsertEffectType.PARAMETRIC_EQ, processor);
        assertThat(values).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = {"CLAP_PLUGIN", "PARAMETRIC_EQ"}, mode = EnumSource.Mode.EXCLUDE)
    void shouldReturnParameterValuesMatchingDescriptorCount(InsertEffectType type) {
        AudioProcessor processor = InsertEffectFactory.createProcessor(type, CHANNELS, SAMPLE_RATE);
        Map<Integer, Double> values = InsertEffectFactory.getParameterValues(type, processor);
        List<PluginParameter> descriptors = InsertEffectFactory.getParameterDescriptors(type);
        assertThat(values).hasSize(descriptors.size());
    }

    @Test
    void shouldRejectMismatchedProcessorTypeForGetParameterValues() {
        ReverbProcessor wrongProcessor = new ReverbProcessor(CHANNELS, SAMPLE_RATE);
        assertThatThrownBy(() ->
                InsertEffectFactory.getParameterValues(InsertEffectType.COMPRESSOR, wrongProcessor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── createSlotFromPlugin tests ──────────────────────────────────────────

    @Test
    void shouldCreateSlotFromEffectPlugin() {
        var plugin = new com.benesquivelmusic.daw.core.plugin.CompressorPlugin();
        plugin.initialize(stubContext());

        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(plugin);

        assertThat(optSlot).isPresent();
        InsertSlot slot = optSlot.get();
        assertThat(slot.getName()).isEqualTo("Compressor");
        assertThat(slot.getProcessor()).isInstanceOf(CompressorProcessor.class);
        assertThat(slot.getProcessor()).isSameAs(plugin.getProcessor());
    }

    @Test
    void shouldCreateSlotFromReverbPlugin() {
        var plugin = new com.benesquivelmusic.daw.core.plugin.ReverbPlugin();
        plugin.initialize(stubContext());

        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(plugin);

        assertThat(optSlot).isPresent();
        assertThat(optSlot.get().getName()).isEqualTo("Reverb");
        assertThat(optSlot.get().getProcessor()).isInstanceOf(ReverbProcessor.class);
    }

    @Test
    void shouldCreateSlotFromParametricEqPlugin() {
        var plugin = new com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin();
        plugin.initialize(stubContext());

        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(plugin);

        assertThat(optSlot).isPresent();
        assertThat(optSlot.get().getName()).isEqualTo("Parametric EQ");
        assertThat(optSlot.get().getProcessor()).isInstanceOf(ParametricEqProcessor.class);
    }

    @Test
    void shouldReturnEmptyForNonProcessingPlugin() {
        DawPlugin analyzerPlugin = new DawPlugin() {
            @Override public PluginDescriptor getDescriptor() {
                return new PluginDescriptor("test-analyzer", "Test Analyzer", "1.0", "Test", PluginType.ANALYZER);
            }
            @Override public void initialize(PluginContext context) {}
            @Override public void activate() {}
            @Override public void deactivate() {}
            @Override public void dispose() {}
        };

        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(analyzerPlugin);

        assertThat(optSlot).isEmpty();
    }

    @Test
    void shouldReturnEmptyForDisposedPlugin() {
        var plugin = new com.benesquivelmusic.daw.core.plugin.CompressorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();

        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(plugin);

        assertThat(optSlot).isEmpty();
    }

    @Test
    void shouldRejectNullPluginForCreateSlot() {
        assertThatThrownBy(() -> InsertEffectFactory.createSlotFromPlugin(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("plugin");
    }

    @Test
    void shouldCreateSlotFromPluginThatCanBeAddedToMixerChannel() {
        var plugin = new com.benesquivelmusic.daw.core.plugin.CompressorPlugin();
        plugin.initialize(stubContext());

        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        MixerChannel channel = new MixerChannel("Test");
        channel.addInsert(slot);

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getEffectsChain().getProcessors()).hasSize(1);
        assertThat(channel.getEffectsChain().getProcessors().getFirst())
                .isSameAs(plugin.getProcessor());
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return SAMPLE_RATE; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
