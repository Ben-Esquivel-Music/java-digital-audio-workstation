package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TunerPluginTest {

    private TunerPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new TunerPlugin();
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldHavePublicNoArgConstructor() {
        TunerPlugin fresh = new TunerPlugin();
        assertThat(fresh).isNotNull();
    }

    // ── Descriptor Metadata ────────────────────────────────────────────

    @Test
    void shouldReturnMenuLabel() {
        assertThat(plugin.getMenuLabel()).isEqualTo("Chromatic Tuner");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(plugin.getMenuIcon()).isEqualTo("spectrum");
    }

    @Test
    void shouldReturnUtilityCategory() {
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.UTILITY);
    }

    @Test
    void shouldReturnDescriptorWithAnalyzerType() {
        var descriptor = plugin.getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.ANALYZER);
        assertThat(descriptor.name()).isEqualTo("Chromatic Tuner");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.tuner");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void pluginIdConstantShouldMatchDescriptorId() {
        assertThat(TunerPlugin.PLUGIN_ID).isEqualTo(plugin.getDescriptor().id());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    void initializeShouldRejectNullContext() {
        assertThatThrownBy(() -> plugin.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void initializeShouldCreatePitchDetector() {
        assertThat(plugin.getPitchDetector()).isNull();
        plugin.initialize(stubContext());
        assertThat(plugin.getPitchDetector()).isNotNull();
    }

    @Test
    void initializeShouldConfigureDetectorWithSampleRate() {
        plugin.initialize(stubContext());
        assertThat(plugin.getPitchDetector().getSampleRate()).isEqualTo(44100.0);
    }

    @Test
    void initializeShouldConfigureDetectorWithDefaultBufferSize() {
        plugin.initialize(stubContext());
        assertThat(plugin.getPitchDetector().getBufferSize())
                .isEqualTo(TunerPlugin.DEFAULT_BUFFER_SIZE);
    }

    @Test
    void activateShouldMarkActive() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();
    }

    @Test
    void deactivateShouldMarkInactive() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        assertThat(plugin.isActive()).isFalse();
    }

    @Test
    void deactivateShouldClearLastResult() {
        plugin.initialize(stubContext());
        plugin.activate();

        float[] a440 = generateSineWave(440.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);
        plugin.process(a440);
        assertThat(plugin.getLastResult()).isNotNull();

        plugin.deactivate();
        assertThat(plugin.getLastResult()).isNull();
    }

    @Test
    void deactivateBeforeInitializeShouldNotThrow() {
        plugin.deactivate();
    }

    @Test
    void disposeShouldReleasePitchDetector() {
        plugin.initialize(stubContext());
        assertThat(plugin.getPitchDetector()).isNotNull();

        plugin.dispose();
        assertThat(plugin.getPitchDetector()).isNull();
    }

    @Test
    void disposeShouldMarkInactive() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();

        plugin.dispose();
        assertThat(plugin.isActive()).isFalse();
    }

    @Test
    void disposeShouldClearLastResult() {
        plugin.initialize(stubContext());
        float[] a440 = generateSineWave(440.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);
        plugin.process(a440);
        assertThat(plugin.getLastResult()).isNotNull();

        plugin.dispose();
        assertThat(plugin.getLastResult()).isNull();
    }

    @Test
    void disposeBeforeInitializeShouldNotThrow() {
        plugin.dispose();
    }

    @Test
    void shouldImplementFullLifecycle() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    // ── Reference Pitch ────────────────────────────────────────────────

    @Test
    void defaultReferencePitchShouldBe440() {
        assertThat(plugin.getReferencePitchHz()).isEqualTo(440.0);
    }

    @Test
    void shouldAllowSettingReferencePitch() {
        plugin.setReferencePitchHz(432.0);
        assertThat(plugin.getReferencePitchHz()).isEqualTo(432.0);
    }

    @Test
    void shouldAllowMinReferencePitch() {
        plugin.setReferencePitchHz(TunerPlugin.MIN_REFERENCE_PITCH_HZ);
        assertThat(plugin.getReferencePitchHz()).isEqualTo(TunerPlugin.MIN_REFERENCE_PITCH_HZ);
    }

    @Test
    void shouldAllowMaxReferencePitch() {
        plugin.setReferencePitchHz(TunerPlugin.MAX_REFERENCE_PITCH_HZ);
        assertThat(plugin.getReferencePitchHz()).isEqualTo(TunerPlugin.MAX_REFERENCE_PITCH_HZ);
    }

    @Test
    void shouldRejectReferencePitchBelowMin() {
        assertThatThrownBy(() -> plugin.setReferencePitchHz(414.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectReferencePitchAboveMax() {
        assertThatThrownBy(() -> plugin.setReferencePitchHz(467.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Pitch Detection ────────────────────────────────────────────────

    @Test
    void processShouldRejectNullSamples() {
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.process(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void processShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.process(new float[TunerPlugin.DEFAULT_BUFFER_SIZE]))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void processShouldDetectA440() {
        plugin.initialize(stubContext());
        float[] a440 = generateSineWave(440.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);

        TunerPlugin.TuningResult result = plugin.process(a440);

        assertThat(result).isNotNull();
        assertThat(result.noteName()).isEqualTo("A");
        assertThat(result.octave()).isEqualTo(4);
        assertThat(result.frequencyHz()).isCloseTo(440.0, within(2.0));
        assertThat(result.centsOffset()).isCloseTo(0.0, within(5.0));
    }

    @Test
    void processShouldDetectE2() {
        plugin.initialize(stubContext());
        // E2 ≈ 82.41 Hz (lowest guitar string in standard tuning)
        float[] e2 = generateSineWave(82.41, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);

        TunerPlugin.TuningResult result = plugin.process(e2);

        assertThat(result).isNotNull();
        assertThat(result.noteName()).isEqualTo("E");
        assertThat(result.octave()).isEqualTo(2);
        assertThat(result.frequencyHz()).isCloseTo(82.41, within(1.0));
    }

    @Test
    void processShouldDetectC5() {
        plugin.initialize(stubContext());
        // C5 ≈ 523.25 Hz
        float[] c5 = generateSineWave(523.25, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);

        TunerPlugin.TuningResult result = plugin.process(c5);

        assertThat(result).isNotNull();
        assertThat(result.noteName()).isEqualTo("C");
        assertThat(result.octave()).isEqualTo(5);
        assertThat(result.frequencyHz()).isCloseTo(523.25, within(2.0));
    }

    @Test
    void processShouldReturnNullForSilence() {
        plugin.initialize(stubContext());
        float[] silence = new float[TunerPlugin.DEFAULT_BUFFER_SIZE];

        TunerPlugin.TuningResult result = plugin.process(silence);

        assertThat(result).isNull();
    }

    @Test
    void processShouldStoreLastResult() {
        plugin.initialize(stubContext());
        float[] a440 = generateSineWave(440.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);

        TunerPlugin.TuningResult result = plugin.process(a440);

        assertThat(plugin.getLastResult()).isSameAs(result);
    }

    @Test
    void processShouldClearLastResultOnUnpitched() {
        plugin.initialize(stubContext());

        float[] a440 = generateSineWave(440.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);
        plugin.process(a440);
        assertThat(plugin.getLastResult()).isNotNull();

        float[] silence = new float[TunerPlugin.DEFAULT_BUFFER_SIZE];
        plugin.process(silence);
        assertThat(plugin.getLastResult()).isNull();
    }

    @Test
    void inTuneShouldBeTrueWhenCentsWithinThreshold() {
        plugin.initialize(stubContext());
        // A pure 440 Hz signal should be detected very close to 0 cents
        float[] a440 = generateSineWave(440.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);

        TunerPlugin.TuningResult result = plugin.process(a440);

        assertThat(result).isNotNull();
        assertThat(result.inTune()).isTrue();
    }

    @Test
    void shouldRespectCustomReferencePitch() {
        plugin.initialize(stubContext());
        plugin.setReferencePitchHz(432.0);

        // Generate a 432 Hz sine — should be "A4" and in tune with 432 Hz reference
        float[] a432 = generateSineWave(432.0, 44100.0, TunerPlugin.DEFAULT_BUFFER_SIZE);
        TunerPlugin.TuningResult result = plugin.process(a432);

        assertThat(result).isNotNull();
        assertThat(result.noteName()).isEqualTo("A");
        assertThat(result.octave()).isEqualTo(4);
        assertThat(result.centsOffset()).isCloseTo(0.0, within(5.0));
    }

    // ── TuningResult Record ────────────────────────────────────────────

    @Test
    void tuningResultShouldRejectNullNoteName() {
        assertThatThrownBy(() -> new TunerPlugin.TuningResult(null, 4, 440.0, 0.0, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tuningResultShouldExposeAllComponents() {
        var result = new TunerPlugin.TuningResult("A", 4, 440.0, -1.5, true);
        assertThat(result.noteName()).isEqualTo("A");
        assertThat(result.octave()).isEqualTo(4);
        assertThat(result.frequencyHz()).isEqualTo(440.0);
        assertThat(result.centsOffset()).isEqualTo(-1.5);
        assertThat(result.inTune()).isTrue();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }

    /**
     * Generates a pure sine wave at the given frequency.
     */
    private static float[] generateSineWave(double frequencyHz, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequencyHz * i / sampleRate);
        }
        return samples;
    }
}
