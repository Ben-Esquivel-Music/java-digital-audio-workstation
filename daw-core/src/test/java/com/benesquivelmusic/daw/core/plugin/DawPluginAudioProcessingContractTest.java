package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@link DawPlugin#asAudioProcessor()} contract is satisfied
 * uniformly across built-in effect plugins, non-processing plugins, and
 * external plugin hosts (mock CLAP).
 *
 * <p>This is the contract test from user story 089: every DawPlugin that
 * processes audio must return a non-empty {@link Optional} from
 * {@code asAudioProcessor()}, and every non-processing plugin must return empty.</p>
 */
class DawPluginAudioProcessingContractTest {

    private static final double SAMPLE_RATE = 44100.0;
    private static final int BUFFER_SIZE = 512;

    // ── Built-in effect plugins return a processor ─────────────────────────

    @Test
    void reverbPluginShouldReturnAudioProcessor() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        assertEffectPluginContract(plugin);
    }

    @Test
    void compressorPluginShouldReturnAudioProcessor() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());
        assertEffectPluginContract(plugin);
    }

    @Test
    void parametricEqPluginShouldReturnAudioProcessor() {
        var plugin = new ParametricEqPlugin();
        plugin.initialize(stubContext());
        assertEffectPluginContract(plugin);
    }

    // ── Non-processing plugins return empty ────────────────────────────────

    @Test
    void spectrumAnalyzerPluginShouldReturnEmpty() {
        var plugin = new SpectrumAnalyzerPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void tunerPluginShouldReturnEmpty() {
        var plugin = new TunerPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void soundWaveTelemetryPluginShouldReturnEmpty() {
        var plugin = new SoundWaveTelemetryPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void metronomePluginShouldReturnEmpty() {
        var plugin = new MetronomePlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    @Test
    void signalGeneratorPluginShouldReturnEmpty() {
        var plugin = new SignalGeneratorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    // ── External plugin host (mock CLAP) satisfies the contract ────────────

    @Test
    void externalPluginHostShouldReturnItself() {
        var mockHost = new MockExternalPluginHost();
        Optional<AudioProcessor> processor = mockHost.asAudioProcessor();

        assertThat(processor).isPresent();
        assertThat(processor.get()).isSameAs(mockHost);
    }

    @Test
    void externalPluginHostProcessorShouldBeCallable() {
        var mockHost = new MockExternalPluginHost();
        AudioProcessor processor = mockHost.asAudioProcessor().orElseThrow();

        float[][] input = {{0.5f, -0.5f}};
        float[][] output = {{0.0f, 0.0f}};
        processor.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.5f, -0.5f);
    }

    // ── Effect plugin processors are functional ────────────────────────────

    @Test
    void effectPluginProcessorShouldProcessAudio() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());
        AudioProcessor processor = plugin.asAudioProcessor().orElseThrow();

        float[][] input = new float[2][BUFFER_SIZE];
        float[][] output = new float[2][BUFFER_SIZE];
        // Fill with a test signal
        for (int i = 0; i < BUFFER_SIZE; i++) {
            input[0][i] = 0.5f;
            input[1][i] = 0.5f;
        }

        processor.process(input, output, BUFFER_SIZE);
        // Reverb with default mix > 0 should produce non-zero output
        boolean hasOutput = false;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            if (output[0][i] != 0.0f) {
                hasOutput = true;
                break;
            }
        }
        assertThat(hasOutput).isTrue();
    }

    // ── Dispose clears the processor ───────────────────────────────────────

    @Test
    void disposeShouldClearProcessorForAllEffectPlugins() {
        List<BuiltInDawPlugin> effectPlugins = List.of(
                new ReverbPlugin(),
                new CompressorPlugin(),
                new ParametricEqPlugin()
        );

        for (BuiltInDawPlugin plugin : effectPlugins) {
            plugin.initialize(stubContext());
            assertThat(plugin.asAudioProcessor())
                    .as("after initialize: %s", plugin.getClass().getSimpleName())
                    .isPresent();

            plugin.dispose();
            assertThat(plugin.asAudioProcessor())
                    .as("after dispose: %s", plugin.getClass().getSimpleName())
                    .isEmpty();
        }
    }

    // ── All EFFECT-type built-in plugins return a processor ────────────────

    @Test
    void allEffectTypeBuiltInPluginsShouldReturnProcessor() {
        int checked = 0;
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            if (plugin.getDescriptor().type() == PluginType.EFFECT) {
                plugin.initialize(stubContext());
                assertThat(plugin.asAudioProcessor())
                        .as("%s (EFFECT type) must return a processor",
                                plugin.getClass().getSimpleName())
                        .isPresent();
                plugin.dispose();
                checked++;
            }
        }
        assertThat(checked).as("at least one EFFECT plugin must be discovered").isGreaterThan(0);
    }

    @Test
    void allNonEffectTypeBuiltInPluginsShouldReturnEmpty() {
        int checked = 0;
        for (BuiltInDawPlugin plugin : BuiltInDawPlugin.discoverAll()) {
            if (plugin.getDescriptor().type() != PluginType.EFFECT) {
                plugin.initialize(stubContext());
                assertThat(plugin.asAudioProcessor())
                        .as("%s (non-EFFECT type) should return empty",
                                plugin.getClass().getSimpleName())
                        .isEmpty();
                plugin.dispose();
                checked++;
            }
        }
        assertThat(checked).as("at least one non-EFFECT plugin must be discovered").isGreaterThan(0);
    }

    // ── DawPlugin default returns empty ────────────────────────────────────

    @Test
    void dawPluginDefaultShouldReturnEmpty() {
        DawPlugin minimal = new DawPlugin() {
            @Override public PluginDescriptor getDescriptor() {
                return new PluginDescriptor("test", "Test", "1.0", "Test", PluginType.ANALYZER);
            }
            @Override public void initialize(PluginContext context) {}
            @Override public void activate() {}
            @Override public void deactivate() {}
            @Override public void dispose() {}
        };

        assertThat(minimal.asAudioProcessor()).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void assertEffectPluginContract(DawPlugin plugin) {
        Optional<AudioProcessor> opt = plugin.asAudioProcessor();
        assertThat(opt)
                .as("%s should return an AudioProcessor", plugin.getClass().getSimpleName())
                .isPresent();

        AudioProcessor processor = opt.get();
        assertThat(processor.getInputChannelCount()).isGreaterThan(0);
        assertThat(processor.getOutputChannelCount()).isGreaterThan(0);
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return SAMPLE_RATE; }
            @Override public int getBufferSize() { return BUFFER_SIZE; }
            @Override public void log(String message) {}
        };
    }

    /**
     * Mock {@link ExternalPluginHost} simulating a CLAP plugin that passes
     * audio through unchanged (useful for contract testing only).
     */
    private static final class MockExternalPluginHost implements ExternalPluginHost {

        @Override
        public ExternalPluginFormat getFormat() {
            return ExternalPluginFormat.CLAP;
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of();
        }

        @Override
        public double getParameterValue(int parameterId) {
            return 0;
        }

        @Override
        public void setParameterValue(int parameterId, double value) {}

        @Override
        public int getLatencySamples() {
            return 0;
        }

        @Override
        public byte[] saveState() {
            return new byte[0];
        }

        @Override
        public void loadState(byte[] state) {}

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor(
                    "mock-clap", "Mock CLAP", "1.0", "Test", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {}

        @Override
        public void activate() {}

        @Override
        public void deactivate() {}

        @Override
        public void dispose() {}

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < Math.min(inputBuffer.length, outputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {}

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
