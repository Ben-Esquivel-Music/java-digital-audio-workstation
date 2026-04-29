package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.*;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessorRegistryTest {

    private static final int CHANNELS = 2;
    private static final double SAMPLE_RATE = 44100.0;

    // Constructed fresh per test class — no shared singleton state leaks across
    // tests. See the singleton-removal migration in CONTRIBUTING.md.
    private final ProcessorRegistry registry = new ProcessorRegistry();

    @Test
    void shouldDiscoverAllNonClapTypes() {
        List<InsertEffectType> available = registry.availableTypes();
        for (InsertEffectType type : InsertEffectType.values()) {
            if (type == InsertEffectType.CLAP_PLUGIN) {
                assertThat(available).doesNotContain(type);
            } else {
                assertThat(available).contains(type);
            }
        }
    }

    @Test
    void shouldNotRegisterClapPlugin() {
        assertThat(registry.isRegistered(InsertEffectType.CLAP_PLUGIN)).isFalse();
        assertThat(registry.processorClassFor(InsertEffectType.CLAP_PLUGIN)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
    void shouldCreateProcessorForEveryBuiltInType(InsertEffectType type) {
        AudioProcessor processor = registry.createProcessor(type, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isNotNull();
        assertThat(processor.getClass()).isEqualTo(registry.processorClassFor(type));
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
    void shouldInferTypeForEveryBuiltInProcessor(InsertEffectType type) {
        AudioProcessor processor = registry.createProcessor(type, CHANNELS, SAMPLE_RATE);
        assertThat(registry.inferType(processor)).isEqualTo(type);
    }

    @ParameterizedTest
    @EnumSource(value = InsertEffectType.class, names = "CLAP_PLUGIN", mode = EnumSource.Mode.EXCLUDE)
    void shouldRoundTripCreateThenInfer(InsertEffectType type) {
        AudioProcessor processor = registry.createProcessor(type, CHANNELS, SAMPLE_RATE);
        InsertEffectType inferred = registry.inferType(processor);
        AudioProcessor roundTripped = registry.createProcessor(inferred, CHANNELS, SAMPLE_RATE);
        assertThat(roundTripped).isInstanceOf(processor.getClass());
    }

    @Test
    void shouldReturnNullWhenInferringUnknownProcessor() {
        AudioProcessor foreign = new AudioProcessor() {
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
            @Override public void process(float[][] in, float[][] out, int frames) { }
            @Override public void reset() { }
        };
        assertThat(registry.inferType(foreign)).isNull();
    }

    @Test
    void shouldReturnNullForNullProcessor() {
        assertThat(registry.inferType(null)).isNull();
    }

    @Test
    void shouldRejectClapPluginOnCreate() {
        assertThatThrownBy(() ->
                registry.createProcessor(InsertEffectType.CLAP_PLUGIN, CHANNELS, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTypeOnCreate() {
        assertThatThrownBy(() -> registry.createProcessor(null, CHANNELS, SAMPLE_RATE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldEnforceStereoOnlyChannelCount() {
        assertThatThrownBy(() ->
                registry.createProcessor(InsertEffectType.STEREO_IMAGER, 1, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 channels");
    }

    @Test
    void shouldPersistenceKeysMatchInsertEffectTypeNames() {
        for (InsertEffectType type : registry.availableTypes()) {
            Class<? extends AudioProcessor> cls = registry.processorClassFor(type);
            InsertEffect annotation = cls.getAnnotation(InsertEffect.class);
            assertThat(annotation).as("@InsertEffect on %s", cls).isNotNull();
            assertThat(annotation.type())
                    .as("annotation.type() on %s", cls.getSimpleName())
                    .isEqualTo(type.name());
            assertThat(annotation.displayName())
                    .as("annotation.displayName() on %s", cls.getSimpleName())
                    .isEqualTo(type.getDisplayName());
        }
    }

    @Test
    void shouldCreateGainStagingWithZeroDefaultGain() {
        AudioProcessor processor = registry.createProcessor(
                InsertEffectType.GAIN_STAGING, CHANNELS, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(GainStagingProcessor.class);
        // sampleRate must be ignored, not interpreted as gainDb.
        assertThat(((GainStagingProcessor) processor).getGainDb()).isEqualTo(0.0);
    }

    @Test
    void shouldCreateStereoImagerFromSingleArgConstructor() {
        AudioProcessor processor = registry.createProcessor(
                InsertEffectType.STEREO_IMAGER, 2, SAMPLE_RATE);
        assertThat(processor).isInstanceOf(StereoImagerProcessor.class);
    }

    /**
     * Regression test for the singleton-removal migration: independently
     * constructed registries must yield equivalent catalogs (same available
     * types in the same order, same processor classes per type) but be
     * distinct instances with their own internal lookup tables. This is the
     * test that was not possible while the constructor was {@code private}.
     */
    @Test
    void shouldConstructIndependentInstancesWithEquivalentCatalogs() {
        ProcessorRegistry a = new ProcessorRegistry();
        ProcessorRegistry b = new ProcessorRegistry();

        assertThat(a).isNotSameAs(b);
        assertThat(a.availableTypes()).containsExactlyElementsOf(b.availableTypes());
        for (InsertEffectType type : a.availableTypes()) {
            assertThat(a.processorClassFor(type)).isEqualTo(b.processorClassFor(type));
        }
    }

    /**
     * Regression test for the singleton-removal migration: the deprecated
     * {@link ProcessorRegistry#getInstance()} pass-through must continue to
     * return a working registry until every call site is converted.
     */
    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void shouldRetainDeprecatedGetInstanceAsPassThrough() {
        ProcessorRegistry legacy = ProcessorRegistry.getInstance();
        assertThat(legacy).isNotNull();
        assertThat(legacy.availableTypes())
                .containsExactlyElementsOf(registry.availableTypes());
    }
}
