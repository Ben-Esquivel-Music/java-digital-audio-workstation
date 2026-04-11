package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.testjar.ExternalEffectPlugin;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;
import com.benesquivelmusic.daw.sdk.plugin.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    @TempDir
    Path tempDir;

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
                injectStubRendererIfNeeded(plugin);
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

    // ── Unified mixer wiring via createSlotFromPlugin ─────────────────────

    @Test
    void builtInEffectPluginShouldWireIntoMixerChannel() {
        var plugin = new CompressorPlugin();
        plugin.initialize(stubContext());

        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        MixerChannel channel = new MixerChannel("Test");
        channel.addInsert(slot);

        assertThat(channel.getEffectsChain().getProcessors()).hasSize(1);
        assertThat(channel.getEffectsChain().getProcessors().getFirst())
                .isSameAs(plugin.asAudioProcessor().orElseThrow());
    }

    @Test
    void mockExternalPluginShouldWireIntoMixerChannel() {
        var mockHost = new MockExternalPluginHost();

        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(mockHost).orElseThrow();
        MixerChannel channel = new MixerChannel("Test");
        channel.addInsert(slot);

        assertThat(channel.getEffectsChain().getProcessors()).hasSize(1);
        assertThat(channel.getEffectsChain().getProcessors().getFirst())
                .isSameAs(mockHost);
    }

    @Test
    void mixerChannelShouldProcessAudioThroughPluginInsert() {
        var plugin = new ReverbPlugin();
        plugin.initialize(stubContext());

        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        MixerChannel channel = new MixerChannel("Test");
        channel.addInsert(slot);
        channel.prepareEffectsChain(2, BUFFER_SIZE);

        float[][] input = new float[2][BUFFER_SIZE];
        float[][] output = new float[2][BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            input[0][i] = 0.5f;
            input[1][i] = 0.5f;
        }

        channel.getEffectsChain().process(input, output, BUFFER_SIZE);

        boolean hasOutput = false;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            if (output[0][i] != 0.0f) {
                hasOutput = true;
                break;
            }
        }
        assertThat(hasOutput).isTrue();
    }

    @Test
    void nonProcessingPluginShouldNotWireIntoMixerChannel() {
        var plugin = new SpectrumAnalyzerPlugin();
        plugin.initialize(stubContext());

        assertThat(InsertEffectFactory.createSlotFromPlugin(plugin)).isEmpty();
    }

    @Test
    void multiplePluginsShouldChainInMixerChannel() {
        var compressor = new CompressorPlugin();
        compressor.initialize(stubContext());
        var reverb = new ReverbPlugin();
        reverb.initialize(stubContext());

        MixerChannel channel = new MixerChannel("Test");
        InsertEffectFactory.createSlotFromPlugin(compressor)
                .ifPresent(channel::addInsert);
        InsertEffectFactory.createSlotFromPlugin(reverb)
                .ifPresent(channel::addInsert);

        assertThat(channel.getInsertCount()).isEqualTo(2);
        assertThat(channel.getEffectsChain().getProcessors()).hasSize(2);
        assertThat(channel.getEffectsChain().getProcessors().get(0))
                .isSameAs(compressor.asAudioProcessor().orElseThrow());
        assertThat(channel.getEffectsChain().getProcessors().get(1))
                .isSameAs(reverb.asAudioProcessor().orElseThrow());
    }

    // ── External JAR plugin satisfies the contract ──────────────────────────

    @Test
    void externalJarPluginShouldLoadAndSatisfyContract() throws Exception {
        Path jarPath = buildExternalPluginJar();
        String className = ExternalEffectPlugin.class.getName();

        DawPlugin plugin = ExternalPluginLoader.load(jarPath, className);
        plugin.initialize(stubContext());

        assertThat(plugin.getDescriptor().type()).isEqualTo(PluginType.EFFECT);
        assertEffectPluginContract(plugin);
    }

    @Test
    void externalJarPluginShouldWireIntoMixerChannel() throws Exception {
        Path jarPath = buildExternalPluginJar();
        String className = ExternalEffectPlugin.class.getName();

        DawPlugin plugin = ExternalPluginLoader.load(jarPath, className);
        plugin.initialize(stubContext());

        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        MixerChannel channel = new MixerChannel("ExtTest");
        channel.addInsert(slot);

        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getEffectsChain().getProcessors()).hasSize(1);
    }

    @Test
    void externalJarPluginShouldProcessAudioThroughMixerChannel() throws Exception {
        Path jarPath = buildExternalPluginJar();
        String className = ExternalEffectPlugin.class.getName();

        DawPlugin plugin = ExternalPluginLoader.load(jarPath, className);
        plugin.initialize(stubContext());

        InsertSlot slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
        MixerChannel channel = new MixerChannel("ExtTest");
        channel.addInsert(slot);
        channel.prepareEffectsChain(2, BUFFER_SIZE);

        float[][] input = new float[2][BUFFER_SIZE];
        float[][] output = new float[2][BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            input[0][i] = 0.7f;
            input[1][i] = -0.3f;
        }

        channel.getEffectsChain().process(input, output, BUFFER_SIZE);

        // Pass-through processor copies input to output
        assertThat(output[0][0]).isEqualTo(0.7f);
        assertThat(output[1][0]).isEqualTo(-0.3f);
    }

    @Test
    void externalJarPluginShouldReturnEmptyBeforeInitialize() throws Exception {
        Path jarPath = buildExternalPluginJar();
        String className = ExternalEffectPlugin.class.getName();

        DawPlugin plugin = ExternalPluginLoader.load(jarPath, className);
        // NOT initialized — should return empty
        assertThat(plugin.asAudioProcessor()).isEmpty();
    }

    /**
     * Builds a JAR file containing the compiled {@link ExternalEffectPlugin}
     * class and its inner classes from the test classpath.
     */
    private Path buildExternalPluginJar() throws IOException {
        Path jarPath = tempDir.resolve("test-external-plugin.jar");
        URI jarUri = URI.create("jar:" + jarPath.toUri());

        try (FileSystem jarFs = FileSystems.newFileSystem(jarUri, Map.of("create", "true"))) {
            // Copy ExternalEffectPlugin and inner classes from test classpath
            String baseName = ExternalEffectPlugin.class.getName().replace('.', '/');
            String classResource = baseName + ".class";
            copyClassToJar(jarFs, classResource);

            // Copy inner classes (e.g., PassThroughProcessor)
            for (Class<?> inner : ExternalEffectPlugin.class.getDeclaredClasses()) {
                String innerResource = inner.getName().replace('.', '/') + ".class";
                copyClassToJar(jarFs, innerResource);
            }
        }

        return jarPath;
    }

    private static void copyClassToJar(FileSystem jarFs, String resourcePath) throws IOException {
        try (var is = DawPluginAudioProcessingContractTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Class resource not found on classpath: " + resourcePath);
            }
            Path entryPath = jarFs.getPath(resourcePath);
            Path parent = entryPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(is, entryPath);
        }
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
     * Injects a no-op {@link SoundFontRenderer} into {@link VirtualKeyboardPlugin}
     * instances so that {@code initialize()} does not attempt to open the
     * platform's Java Sound synthesizer — which is unavailable in headless CI.
     */
    private static void injectStubRendererIfNeeded(BuiltInDawPlugin plugin) {
        if (plugin instanceof VirtualKeyboardPlugin vk) {
            vk.setRenderer(new NoOpSoundFontRenderer());
        }
    }

    /**
     * Minimal no-op {@link SoundFontRenderer} used to satisfy
     * {@link VirtualKeyboardPlugin#initialize(PluginContext)} without
     * requiring real audio hardware.
     */
    private static final class NoOpSoundFontRenderer implements SoundFontRenderer {
        @Override public void initialize(double sampleRate, int bufferSize) {}
        @Override public SoundFontInfo loadSoundFont(Path path) { return new SoundFontInfo(0, path, List.of()); }
        @Override public void unloadSoundFont(int soundFontId) {}
        @Override public List<SoundFontInfo> getLoadedSoundFonts() { return List.of(); }
        @Override public void selectPreset(int channel, int bank, int program) {}
        @Override public void sendEvent(MidiEvent event) {}
        @Override public void render(float[][] outputBuffer, int numFrames) {}
        @Override public float[][] bounce(List<MidiEvent> events, int totalFrames) { return new float[2][totalFrames]; }
        @Override public void setReverbEnabled(boolean enabled) {}
        @Override public void setChorusEnabled(boolean enabled) {}
        @Override public void setGain(float gain) {}
        @Override public boolean isAvailable() { return true; }
        @Override public String getRendererName() { return "NoOp"; }
        @Override public void allNotesOff() {}
        @Override public void close() {}
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
