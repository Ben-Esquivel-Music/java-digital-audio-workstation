package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultibandCompressorPluginTest {

    @Test
    void shouldHavePublicNoArgConstructor() {
        assertThat(new MultibandCompressorPlugin()).isNotNull();
    }

    @Test
    void shouldReturnMenuLabel() {
        assertThat(new MultibandCompressorPlugin().getMenuLabel())
                .isEqualTo("Multiband Compressor");
    }

    @Test
    void shouldReturnEffectCategory() {
        assertThat(new MultibandCompressorPlugin().getCategory())
                .isEqualTo(BuiltInPluginCategory.EFFECT);
    }

    @Test
    void shouldReturnDescriptorWithEffectType() {
        var d = new MultibandCompressorPlugin().getDescriptor();
        assertThat(d.type()).isEqualTo(PluginType.EFFECT);
        assertThat(d.name()).isEqualTo("Multiband Compressor");
        assertThat(d.id()).isEqualTo(MultibandCompressorPlugin.PLUGIN_ID);
        assertThat(d.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void shouldImplementDawPluginLifecycle() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    @Test
    void shouldReturnProcessorAfterInitialize() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isInstanceOf(MultibandCompressorProcessor.class);
        assertThat(plugin.asAudioProcessor()).isPresent();
    }

    @Test
    void asAudioProcessorShouldReturnStableInstanceAcrossRebuilds() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());

        var firstWrapper = plugin.asAudioProcessor().orElseThrow();
        var firstInner = plugin.getProcessor();

        plugin.setBandCount(5);

        // The wrapper exposed to the mixer chain stays the same instance even
        // though the underlying processor was swapped, so InsertSlot/EffectsChain
        // continue routing audio through the live processor.
        assertThat(plugin.asAudioProcessor()).isPresent();
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(firstWrapper);
        assertThat(plugin.getProcessor()).isNotSameAs(firstInner);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(5);
    }

    @Test
    void shouldDefaultToFourBands() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        assertThat(plugin.getBandCount()).isEqualTo(4);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(4);
    }

    @Test
    void shouldChangeBandCountAndRebuildProcessor() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());

        plugin.setBandCount(3);
        assertThat(plugin.getBandCount()).isEqualTo(3);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(3);

        plugin.setBandCount(5);
        assertThat(plugin.getBandCount()).isEqualTo(5);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(5);
        assertThat(plugin.getProcessor().getCrossoverFrequencies()).hasSize(4);
    }

    @Test
    void shouldRejectInvalidBandCount() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.setBandCount(2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plugin.setBandCount(6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBandCountChangeBeforeInitialize() {
        var plugin = new MultibandCompressorPlugin();
        assertThatThrownBy(() -> plugin.setBandCount(3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldExposeAllBandParameters() {
        var plugin = new MultibandCompressorPlugin();
        var params = plugin.getParameters();
        // 1 band-count + 1 linear-phase + 4 crossovers + 5 * 8 per-band = 46
        assertThat(params).hasSize(46);
        assertThat(params.get(0).name()).isEqualTo("Band Count");
        assertThat(params.get(1).name()).isEqualTo("Linear Phase Toggle");
        assertThat(params.get(2).name()).startsWith("Crossover 1");
        assertThat(params.stream().map(p -> p.name()))
                .anyMatch(n -> n.startsWith("Band 5"))
                .anyMatch(n -> n.equals("Band 1 Bypass Toggle"))
                .anyMatch(n -> n.equals("Band 1 Mute Toggle"))
                .anyMatch(n -> n.equals("Band 1 Solo Toggle"));
    }

    @Test
    void crossoverParameterDefaultsShouldMatchDefaultBandLayout() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        double[] processorCrossovers = plugin.getProcessor().getCrossoverFrequencies();
        var params = plugin.getParameters();
        // The first DEFAULT_BAND_COUNT - 1 crossover defaults must match the
        // processor's actual crossover layout in its initial (DEFAULT_BAND_COUNT) state.
        for (int i = 0; i < processorCrossovers.length; i++) {
            assertThat(params.get(2 + i).defaultValue())
                    .as("Crossover %d default", i + 1)
                    .isEqualTo(processorCrossovers[i]);
        }
    }

    @Test
    void shouldSupportLinearPhaseFlag() {
        var plugin = new MultibandCompressorPlugin();
        assertThat(plugin.isLinearPhase()).isFalse();
        plugin.setLinearPhase(true);
        assertThat(plugin.isLinearPhase()).isTrue();
    }

    @Test
    void shouldClearProcessorOnDispose() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        plugin.dispose();
        assertThat(plugin.asAudioProcessor()).isEmpty();
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void shouldHaveDistinctIdFromCompressorPlugins() {
        assertThat(MultibandCompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(CompressorPlugin.PLUGIN_ID)
                .isNotEqualTo(BusCompressorPlugin.PLUGIN_ID);
    }

    @Test
    void automatableParametersShouldExcludeBandCount() {
        var plugin = new MultibandCompressorPlugin();
        var automatable = plugin.getAutomatableParameters();
        // 46 - 1 (Band Count is excluded because rebuilding the processor
        // is not real-time safe).
        assertThat(automatable).hasSize(45);
        assertThat(automatable).noneMatch(p -> p.id() == 0);
        assertThat(automatable.get(0).displayName()).isEqualTo("Linear Phase Toggle");
    }

    @Test
    void setAutomatableParameterShouldRouteToProcessorState() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());

        // Linear-phase toggle (id 1)
        plugin.setAutomatableParameter(1, 1.0);
        assertThat(plugin.isLinearPhase()).isTrue();
        plugin.setAutomatableParameter(1, 0.0);
        assertThat(plugin.isLinearPhase()).isFalse();

        // Per-band threshold (id 6 = Band 1 Threshold (dB))
        plugin.setAutomatableParameter(6, -33.0);
        assertThat(plugin.getProcessor().getBandCompressor(0).getThresholdDb())
                .isEqualTo(-33.0);

        // Per-band makeup gain (id 10 = Band 1 Makeup Gain (dB))
        plugin.setAutomatableParameter(10, 6.0);
        assertThat(plugin.getProcessor().getBandMakeupGainDb(0)).isEqualTo(6.0);

        // Solo toggle (id 13 = Band 1 Solo Toggle)
        plugin.setAutomatableParameter(13, 1.0);
        assertThat(plugin.getProcessor().isBandSoloed(0)).isTrue();
        plugin.setAutomatableParameter(13, 0.0);
        assertThat(plugin.getProcessor().isBandSoloed(0)).isFalse();

        // Out-of-range band index for the current 4-band layout: must not throw
        plugin.setAutomatableParameter(6 + 8 * 4, -10.0);

        // Band Count selects a configuration allocated at initialization; the
        // processor captured by the insert stays stable across that selection.
        var signalPath = plugin.asAudioProcessor().orElseThrow();
        plugin.setAutomatableParameter(0, 5.0);
        assertThat(plugin.asAudioProcessor().orElseThrow()).isSameAs(signalPath);
        assertThat(plugin.getBandCount()).isEqualTo(5);
        assertThat(plugin.getProcessor().getBandCount()).isEqualTo(5);
        assertThat(plugin.getProcessor().getBandCompressor(0).getThresholdDb()).isEqualTo(-33);
    }

    @Test
    void shouldBeDiscoveredAsBuiltInPlugin() {
        assertThat(BuiltInDawPlugin.discoverAll())
                .anyMatch(p -> p instanceof MultibandCompressorPlugin);
        assertThat(BuiltInDawPlugin.menuEntries())
                .anyMatch(e -> e.pluginClass().equals(MultibandCompressorPlugin.class));
    }

    @Test
    void booleanRecallBypassesAndRestoresTheBandsSavedMakeup() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        try {
            plugin.setAutomatableParameter(10, 6.0);
            plugin.setAutomatableParameter(11, 1.0);
            assertThat(plugin.getProcessor().isBandBypassed(0)).isTrue();
            plugin.setAutomatableParameter(12, 1.0);
            assertThat(plugin.getProcessor().getBandMakeupGainDb(0)).isEqualTo(-120.0);
            plugin.setAutomatableParameter(10, 9.0);
            assertThat(plugin.getProcessor().getBandMakeupGainDb(0)).isEqualTo(-120.0);
            plugin.setAutomatableParameter(12, 0.0);
            assertThat(plugin.getProcessor().getBandMakeupGainDb(0)).isEqualTo(9.0);
            plugin.setAutomatableParameter(11, 0.0);
            assertThat(plugin.getProcessor().isBandBypassed(0)).isFalse();
        } finally {
            plugin.dispose();
        }
    }

    @Test
    void queuedCrossoverRecallReachesLiveFiltersWithoutLosingOtherBandState() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        try {
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            var store = slot.getParameterStore();
            var processor = plugin.getProcessor();
            var compressor = processor.getBandCompressor(0);
            plugin.setAutomatableParameter(6, -33);
            plugin.setAutomatableParameter(10, 6);
            plugin.setAutomatableParameter(11, 1);
            plugin.setAutomatableParameter(13, 1);
            // The low cutoff crosses the previous middle cutoff during this recall.
            store.writeFromUiById(2, 2200);
            store.writeFromUiById(2, 3000);
            store.writeFromUiById(3, 6000);
            store.writeFromUiById(4, 12000);
            store.writeFromUiById(5, 16000);
            assertThat(processor.getCrossoverFrequencies()).containsExactly(200, 2000, 8000);
            slot.drainParametersToAudio();
            assertThat(processor.getCrossoverFrequencies()).containsExactly(3000, 6000, 12000);
            assertThat(processor.getBandCompressor(0)).isSameAs(compressor);
            assertThat(compressor.getThresholdDb()).isEqualTo(-33);
            assertThat(processor.getBandMakeupGainDb(0)).isEqualTo(6);
            assertThat(processor.isBandBypassed(0)).isTrue();
            assertThat(processor.isBandSoloed(0)).isTrue();
            store.writeFromUiById(0, 3);
            slot.drainParametersToAudio();
            store.writeFromUiById(0, 5);
            slot.drainParametersToAudio();
            assertThat(plugin.getProcessor().getCrossoverFrequencies()).containsExactly(3000, 6000, 12000, 16000);
            assertThat(slot.isBypassed()).isFalse();
        } finally {
            plugin.dispose();
        }
    }

    @Test
    void bandCountOnlyEditSurvivesFullPresetRecallWithoutChangingCrossovers() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        try {
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            var store = slot.getParameterStore();
            store.writeFromUiById(0, 5);
            slot.drainParametersToAudio();
            double[] before = plugin.getProcessor().getCrossoverFrequencies();
            for (int index = 0; index < store.parameterCount(); index++) {
                store.writeFromUi(index, store.value(index));
            }
            slot.drainParametersToAudio();
            assertThat(plugin.getProcessor().getCrossoverFrequencies())
                    .containsExactly(before).containsExactly(200, 2000, 8000, 16000);
        } finally {
            plugin.dispose();
        }
    }

    @Test
    void slotCrossoverEditChangesTheActualSoloedBandResponse() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(stubContext());
        try {
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            var store = slot.getParameterStore();
            store.writeFromUiById(11, 1);
            store.writeFromUiById(13, 1);
            slot.drainParametersToAudio();
            double before = steadyToneRms(slot, 44100, 1000);
            store.writeFromUiById(2, 1500);
            slot.drainParametersToAudio();
            double after = steadyToneRms(slot, 44100, 1000);
            assertThat(after).isGreaterThan(before * 100);
            assertThat(plugin.getProcessor().getMeteringData().crossoverFrequencies()).startsWith(1500);
        } finally {
            plugin.dispose();
        }
    }

    @Test
    void crossoverRangeStaysBelowNyquistAndMatchesTheStoreAtLowerSampleRates() {
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(context(32000));
        try {
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            slot.getParameterStore().writeFromUiById(4, 20000);
            slot.drainParametersToAudio();
            double frequency = plugin.getProcessor().getCrossoverFrequencies()[2];
            assertThat(frequency).isEqualTo(slot.getParameterStore().valueById(4)).isLessThan(16000);
            assertThat(steadyToneRms(slot, 32000, 15000)).isFinite().isLessThan(1);
        } finally {
            plugin.dispose();
        }
    }

    @Test
    void crossoverWritesAndStoreDrainAllocateNothingAfterWarmup(@TempDir Path directory) throws Exception {
        var result = runAllocationProbe(directory);
        assertThat(result.exitCode()).as(result.transcript()).isZero();
    }

    @Test
    void crossoverAllocationProbeRejectsAnAllocationInTheMeasuredWorkload(@TempDir Path directory)
            throws Exception {
        var result = runAllocationProbe(directory, "--allocate-in-workload");
        assertThat(result.exitCode()).as(result.transcript()).isNotZero();
        assertThat(result.transcript()).containsPattern("measurement batch 0 allocated [1-9][0-9]* bytes");
    }

    private record AllocationProbeResult(int exitCode, String transcript) { }

    private static AllocationProbeResult runAllocationProbe(Path directory, String... arguments)
            throws IOException, InterruptedException, URISyntaxException {
        Set<String> classPath = new LinkedHashSet<>();
        classPath.add(codeSourceOf(CrossoverAllocationProbe.class));
        classPath.add(codeSourceOf(MultibandCompressorPlugin.class));
        addPathEntries(classPath, System.getProperty("jdk.module.path"));
        addPathEntries(classPath, System.getProperty("java.class.path"));
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windowsLauncher = bin.resolve("java.exe");
        Path launcher = Files.isRegularFile(windowsLauncher) ? windowsLauncher : bin.resolve("java");
        assertThat(launcher).as("the launcher of this JVM's own JDK").isRegularFile();
        // C2 submission interns the requesting class's unrelated constant-pool strings on
        // this thread. Interpret only this probe so its byte counter measures the workload,
        // and so escape analysis cannot hide an allocation introduced into that workload.
        var command = new ArrayList<>(java.util.List.of(
                launcher.toString(), "-Xint", "--enable-native-access=ALL-UNNAMED",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", String.join(File.pathSeparator, classPath),
                CrossoverAllocationProbe.class.getName()));
        command.addAll(java.util.List.of(arguments));
        Path output = directory.resolve("crossover-allocation-probe.log");
        Process probe = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertThat(probe.waitFor(2, TimeUnit.MINUTES))
                    .as("allocation probe exited within two minutes; %s", output).isTrue();
            return new AllocationProbeResult(probe.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            stopAllocationProbe(probe);
        }
    }

    private static String codeSourceOf(Class<?> type) throws URISyntaxException {
        var source = type.getProtectionDomain().getCodeSource();
        assertThat(source).as("%s was loaded from a code source", type.getName()).isNotNull();
        return Path.of(source.getLocation().toURI()).toString();
    }

    private static void addPathEntries(Set<String> classPath, String pathList) {
        if (pathList == null) { return; }
        for (String entry : pathList.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) { classPath.add(entry); }
        }
    }

    private static void stopAllocationProbe(Process probe) throws IOException {
        if (!probe.isAlive()) { return; }
        probe.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (probe.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IOException("allocation probe did not exit after forcible termination");
                }
                try {
                    probe.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static double steadyToneRms(InsertSlot slot, double sampleRate, double frequency) {
        int frames = 512;
        var input = new float[2][frames];
        var output = new float[2][frames];
        double sum = 0;
        for (int block = 0; block < 32; block++) {
            for (int frame = 0; frame < frames; frame++) {
                float value = (float) (0.5 * Math.sin(2 * Math.PI * frequency * (block * frames + frame) / sampleRate));
                input[0][frame] = value;
                input[1][frame] = value;
            }
            slot.getProcessor().process(input, output, frames);
            if (block == 31) {
                for (float sample : output[0]) {
                    sum += sample * sample;
                }
            }
        }
        return Math.sqrt(sum / frames);
    }

    private static PluginContext stubContext() {
        return context(44100);
    }

    private static PluginContext context(double sampleRate) {
        return new PluginContext() {
            @Override public double getSampleRate() { return sampleRate; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
