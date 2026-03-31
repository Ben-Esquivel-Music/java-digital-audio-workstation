package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.ChorusProcessor;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DelayProcessor;
import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.core.dsp.NoiseGateProcessor;
import com.benesquivelmusic.daw.core.dsp.ParametricEqProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.core.dsp.StereoImagerProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
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

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
    void shouldReturnNonEmptyParameterDescriptors(InsertEffectType type) {
        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(type);
        assertThat(params).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
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
        assertThat(types).hasSize(9);
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
}
