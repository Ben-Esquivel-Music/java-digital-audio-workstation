package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SignalGeneratorPluginTest {

    private SignalGeneratorPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new SignalGeneratorPlugin();
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldHavePublicNoArgConstructor() {
        SignalGeneratorPlugin fresh = new SignalGeneratorPlugin();
        assertThat(fresh).isNotNull();
    }

    // ── Descriptor Metadata ────────────────────────────────────────────

    @Test
    void shouldReturnMenuLabel() {
        assertThat(plugin.getMenuLabel()).isEqualTo("Signal Generator");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(plugin.getMenuIcon()).isEqualTo("waveform");
    }

    @Test
    void shouldReturnUtilityCategory() {
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.UTILITY);
    }

    @Test
    void shouldReturnDescriptorWithInstrumentType() {
        var descriptor = plugin.getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.INSTRUMENT);
        assertThat(descriptor.name()).isEqualTo("Signal Generator");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.signal-generator");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void pluginIdConstantShouldMatchDescriptorId() {
        assertThat(SignalGeneratorPlugin.PLUGIN_ID).isEqualTo(plugin.getDescriptor().id());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    void initializeShouldRejectNullContext() {
        assertThatThrownBy(() -> plugin.initialize(null))
                .isInstanceOf(NullPointerException.class);
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
    void deactivateBeforeInitializeShouldNotThrow() {
        plugin.deactivate();
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

    // ── Default Parameters ─────────────────────────────────────────────

    @Test
    void defaultWaveformTypeShouldBeSine() {
        assertThat(plugin.getWaveformType()).isEqualTo(SignalGeneratorPlugin.WaveformType.SINE);
    }

    @Test
    void defaultFrequencyShouldBe1000Hz() {
        assertThat(plugin.getFrequencyHz()).isEqualTo(1000.0);
    }

    @Test
    void defaultAmplitudeShouldBeMinus18dB() {
        assertThat(plugin.getAmplitudeDb()).isEqualTo(-18.0);
    }

    @Test
    void defaultSweepModeShouldBeOff() {
        assertThat(plugin.getSweepMode()).isEqualTo(SignalGeneratorPlugin.SweepMode.OFF);
    }

    @Test
    void defaultSweepStartFrequencyShouldBeMinFrequency() {
        assertThat(plugin.getSweepStartFrequencyHz()).isEqualTo(SignalGeneratorPlugin.MIN_FREQUENCY_HZ);
    }

    @Test
    void defaultSweepEndFrequencyShouldBeMaxFrequency() {
        assertThat(plugin.getSweepEndFrequencyHz()).isEqualTo(SignalGeneratorPlugin.MAX_FREQUENCY_HZ);
    }

    @Test
    void defaultSweepDurationShouldBe5Seconds() {
        assertThat(plugin.getSweepDurationSeconds()).isEqualTo(5.0);
    }

    @Test
    void defaultMutedShouldBeFalse() {
        assertThat(plugin.isMuted()).isFalse();
    }

    // ── Waveform Type ──────────────────────────────────────────────────

    @Test
    void shouldAllowSettingWaveformType() {
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SQUARE);
        assertThat(plugin.getWaveformType()).isEqualTo(SignalGeneratorPlugin.WaveformType.SQUARE);
    }

    @Test
    void setWaveformTypeShouldRejectNull() {
        assertThatThrownBy(() -> plugin.setWaveformType(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportAllWaveformTypes() {
        for (SignalGeneratorPlugin.WaveformType type : SignalGeneratorPlugin.WaveformType.values()) {
            plugin.setWaveformType(type);
            assertThat(plugin.getWaveformType()).isEqualTo(type);
        }
    }

    // ── Frequency ──────────────────────────────────────────────────────

    @Test
    void shouldAllowSettingFrequency() {
        plugin.setFrequencyHz(440.0);
        assertThat(plugin.getFrequencyHz()).isEqualTo(440.0);
    }

    @Test
    void shouldAllowMinFrequency() {
        plugin.setFrequencyHz(SignalGeneratorPlugin.MIN_FREQUENCY_HZ);
        assertThat(plugin.getFrequencyHz()).isEqualTo(SignalGeneratorPlugin.MIN_FREQUENCY_HZ);
    }

    @Test
    void shouldAllowMaxFrequency() {
        plugin.setFrequencyHz(SignalGeneratorPlugin.MAX_FREQUENCY_HZ);
        assertThat(plugin.getFrequencyHz()).isEqualTo(SignalGeneratorPlugin.MAX_FREQUENCY_HZ);
    }

    @Test
    void shouldRejectFrequencyBelowMin() {
        assertThatThrownBy(() -> plugin.setFrequencyHz(19.9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectFrequencyAboveMax() {
        assertThatThrownBy(() -> plugin.setFrequencyHz(20_001.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Amplitude ──────────────────────────────────────────────────────

    @Test
    void shouldAllowSettingAmplitude() {
        plugin.setAmplitudeDb(-6.0);
        assertThat(plugin.getAmplitudeDb()).isEqualTo(-6.0);
    }

    @Test
    void shouldAllowZeroDbAmplitude() {
        plugin.setAmplitudeDb(0.0);
        assertThat(plugin.getAmplitudeDb()).isEqualTo(0.0);
    }

    @Test
    void shouldAllowNegativeInfinityAmplitude() {
        plugin.setAmplitudeDb(Double.NEGATIVE_INFINITY);
        assertThat(plugin.getAmplitudeDb()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void shouldRejectAmplitudeAboveZeroDb() {
        assertThatThrownBy(() -> plugin.setAmplitudeDb(0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Sweep Parameters ───────────────────────────────────────────────

    @Test
    void shouldAllowSettingSweepMode() {
        plugin.setSweepMode(SignalGeneratorPlugin.SweepMode.LINEAR);
        assertThat(plugin.getSweepMode()).isEqualTo(SignalGeneratorPlugin.SweepMode.LINEAR);
    }

    @Test
    void setSweepModeShouldRejectNull() {
        assertThatThrownBy(() -> plugin.setSweepMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAllowSettingSweepStartFrequency() {
        plugin.setSweepStartFrequencyHz(100.0);
        assertThat(plugin.getSweepStartFrequencyHz()).isEqualTo(100.0);
    }

    @Test
    void shouldRejectSweepStartFrequencyBelowMin() {
        assertThatThrownBy(() -> plugin.setSweepStartFrequencyHz(19.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSweepStartFrequencyAboveMax() {
        assertThatThrownBy(() -> plugin.setSweepStartFrequencyHz(20_001.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowSettingSweepEndFrequency() {
        plugin.setSweepEndFrequencyHz(10_000.0);
        assertThat(plugin.getSweepEndFrequencyHz()).isEqualTo(10_000.0);
    }

    @Test
    void shouldRejectSweepEndFrequencyBelowMin() {
        assertThatThrownBy(() -> plugin.setSweepEndFrequencyHz(19.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectSweepEndFrequencyAboveMax() {
        assertThatThrownBy(() -> plugin.setSweepEndFrequencyHz(20_001.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowSettingSweepDuration() {
        plugin.setSweepDurationSeconds(10.0);
        assertThat(plugin.getSweepDurationSeconds()).isEqualTo(10.0);
    }

    @Test
    void shouldRejectNonPositiveSweepDuration() {
        assertThatThrownBy(() -> plugin.setSweepDurationSeconds(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plugin.setSweepDurationSeconds(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Mute / Panic ───────────────────────────────────────────────────

    @Test
    void shouldToggleMute() {
        plugin.setMuted(true);
        assertThat(plugin.isMuted()).isTrue();
        plugin.setMuted(false);
        assertThat(plugin.isMuted()).isFalse();
    }

    @Test
    void panicShouldMute() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setMuted(false);

        plugin.panic();

        assertThat(plugin.isMuted()).isTrue();
    }

    @Test
    void deactivateShouldResetMuteState() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setMuted(true);

        plugin.deactivate();

        assertThat(plugin.isMuted()).isFalse();
    }

    // ── Audio Generation ───────────────────────────────────────────────

    @Test
    void generateShouldRejectNullBuffer() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThatThrownBy(() -> plugin.generate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void generateShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.generate(new float[512]))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generateShouldProduceSilenceWhenMuted() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setMuted(true);

        float[] buffer = new float[512];
        plugin.generate(buffer);

        for (float sample : buffer) {
            assertThat(sample).isEqualTo(0.0f);
        }
    }

    @Test
    void generateShouldProduceSilenceWhenInactive() {
        plugin.initialize(stubContext());
        // don't activate

        float[] buffer = new float[512];
        plugin.generate(buffer);

        for (float sample : buffer) {
            assertThat(sample).isEqualTo(0.0f);
        }
    }

    @Test
    void generateShouldProduceSineWave() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SINE);
        plugin.setFrequencyHz(440.0);
        plugin.setAmplitudeDb(0.0);

        float[] buffer = new float[512];
        plugin.generate(buffer);

        // Should produce non-zero samples
        assertThat(hasNonZeroSamples(buffer)).isTrue();
        // Peak should not exceed 1.0 (0 dBFS)
        assertThat(peakAmplitude(buffer)).isLessThanOrEqualTo(1.0f + 1e-6f);
    }

    @Test
    void generateShouldProduceSquareWave() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SQUARE);
        plugin.setFrequencyHz(440.0);
        plugin.setAmplitudeDb(0.0);

        float[] buffer = new float[512];
        plugin.generate(buffer);

        assertThat(hasNonZeroSamples(buffer)).isTrue();
        // Square wave should have samples very close to +1 or -1
        for (float sample : buffer) {
            assertThat(Math.abs(Math.abs(sample) - 1.0f)).isLessThan(0.01f);
        }
    }

    @Test
    void generateShouldProduceTriangleWave() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.TRIANGLE);
        plugin.setFrequencyHz(440.0);
        plugin.setAmplitudeDb(0.0);

        float[] buffer = new float[512];
        plugin.generate(buffer);

        assertThat(hasNonZeroSamples(buffer)).isTrue();
        assertThat(peakAmplitude(buffer)).isLessThanOrEqualTo(1.0f + 1e-6f);
    }

    @Test
    void generateShouldProduceSawtoothWave() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SAWTOOTH);
        plugin.setFrequencyHz(440.0);
        plugin.setAmplitudeDb(0.0);

        float[] buffer = new float[512];
        plugin.generate(buffer);

        assertThat(hasNonZeroSamples(buffer)).isTrue();
        assertThat(peakAmplitude(buffer)).isLessThanOrEqualTo(1.0f + 1e-6f);
    }

    @Test
    void generateShouldProduceWhiteNoise() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.WHITE_NOISE);
        plugin.setAmplitudeDb(0.0);

        float[] buffer = new float[1024];
        plugin.generate(buffer);

        assertThat(hasNonZeroSamples(buffer)).isTrue();
        // White noise should have variety in sample values
        assertThat(countDistinctValues(buffer)).isGreaterThan(100);
    }

    @Test
    void generateShouldProducePinkNoise() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.PINK_NOISE);
        plugin.setAmplitudeDb(0.0);

        float[] buffer = new float[1024];
        plugin.generate(buffer);

        assertThat(hasNonZeroSamples(buffer)).isTrue();
        assertThat(countDistinctValues(buffer)).isGreaterThan(100);
    }

    @Test
    void generateShouldRespectAmplitude() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SINE);
        plugin.setFrequencyHz(440.0);

        // At -18 dBFS, peak should be approximately 0.1259
        plugin.setAmplitudeDb(-18.0);
        float[] buffer = new float[44100]; // 1 second at 44100 Hz
        plugin.generate(buffer);

        double expectedLinear = SignalGeneratorPlugin.dbToLinear(-18.0);
        assertThat(peakAmplitude(buffer)).isCloseTo((float) expectedLinear, within(0.02f));
    }

    @Test
    void generateShouldProduceSilenceAtNegativeInfinity() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SINE);
        plugin.setAmplitudeDb(Double.NEGATIVE_INFINITY);

        float[] buffer = new float[512];
        plugin.generate(buffer);

        for (float sample : buffer) {
            assertThat(sample).isEqualTo(0.0f);
        }
    }

    @Test
    void panicShouldSilenceOutput() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.setWaveformType(SignalGeneratorPlugin.WaveformType.SINE);
        plugin.setAmplitudeDb(0.0);

        // Verify signal is generated
        float[] buffer1 = new float[512];
        plugin.generate(buffer1);
        assertThat(hasNonZeroSamples(buffer1)).isTrue();

        // Panic
        plugin.panic();

        // Should now be silent (muted)
        float[] buffer2 = new float[512];
        plugin.generate(buffer2);
        for (float sample : buffer2) {
            assertThat(sample).isEqualTo(0.0f);
        }
    }

    // ── dB-to-linear conversion ────────────────────────────────────────

    @Test
    void dbToLinearShouldReturnOneForZeroDb() {
        assertThat(SignalGeneratorPlugin.dbToLinear(0.0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void dbToLinearShouldReturnZeroForNegativeInfinity() {
        assertThat(SignalGeneratorPlugin.dbToLinear(Double.NEGATIVE_INFINITY)).isEqualTo(0.0);
    }

    @Test
    void dbToLinearShouldReturnHalfForMinus6dB() {
        assertThat(SignalGeneratorPlugin.dbToLinear(-6.0)).isCloseTo(0.5012, within(0.001));
    }

    @Test
    void dbToLinearShouldReturnCorrectValueForMinus18dB() {
        assertThat(SignalGeneratorPlugin.dbToLinear(-18.0)).isCloseTo(0.1259, within(0.001));
    }

    // ── Sealed-interface discovery ─────────────────────────────────────

    @Test
    void shouldBeDiscoverableViaBuiltInDawPlugin() {
        var plugins = BuiltInDawPlugin.discoverAll();
        assertThat(plugins)
                .anyMatch(p -> p instanceof SignalGeneratorPlugin);
    }

    @Test
    void shouldAppearInMenuEntries() {
        var entries = BuiltInDawPlugin.menuEntries();
        assertThat(entries)
                .extracting(BuiltInDawPlugin.MenuEntry::label)
                .contains("Signal Generator");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }

    private static boolean hasNonZeroSamples(float[] buffer) {
        for (float sample : buffer) {
            if (sample != 0.0f) {
                return true;
            }
        }
        return false;
    }

    private static float peakAmplitude(float[] buffer) {
        float peak = 0.0f;
        for (float sample : buffer) {
            float abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
        }
        return peak;
    }

    private static int countDistinctValues(float[] buffer) {
        var distinct = new java.util.HashSet<Float>();
        for (float sample : buffer) {
            distinct.add(sample);
        }
        return distinct.size();
    }
}
