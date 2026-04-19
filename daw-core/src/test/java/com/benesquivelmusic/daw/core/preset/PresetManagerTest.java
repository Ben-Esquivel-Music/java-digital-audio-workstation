package com.benesquivelmusic.daw.core.preset;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.DelayProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PresetManagerTest {

    private static final double EPS = 1e-9;

    @Test
    void captureAndApplyRoundTrip(@TempDir Path dir) {
        PresetManager mgr = new PresetManager(dir);

        CompressorProcessor comp = new CompressorProcessor(2, 44100.0);
        comp.setThresholdDb(-22.0);
        comp.setRatio(5.5);

        ProcessorPreset preset = mgr.capture(comp, "Test Comp");
        assertThat(preset.processorClassName())
                .isEqualTo(CompressorProcessor.class.getName());
        assertThat(preset.displayName()).isEqualTo("Test Comp");

        CompressorProcessor other = new CompressorProcessor(2, 44100.0);
        int applied = mgr.apply(preset, other);
        assertThat(applied).isGreaterThanOrEqualTo(2);
        assertThat(other.getThresholdDb()).isCloseTo(-22.0, within(EPS));
        assertThat(other.getRatio()).isCloseTo(5.5, within(EPS));
    }

    @Test
    void applyRejectsMismatchedProcessorClass(@TempDir Path dir) {
        PresetManager mgr = new PresetManager(dir);
        ReverbProcessor rev = new ReverbProcessor(2, 44100.0);
        ProcessorPreset p = mgr.capture(rev, "Rev");

        CompressorProcessor comp = new CompressorProcessor(2, 44100.0);
        assertThatThrownBy(() -> mgr.apply(p, comp))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveAndReloadUserPresets(@TempDir Path dir) throws IOException {
        PresetManager mgr = new PresetManager(dir);

        DelayProcessor d = new DelayProcessor(2, 44100.0);
        d.setDelayMs(250.0);
        d.setFeedback(0.5);
        d.setMix(0.4);
        ProcessorPreset preset = mgr.capture(d, "My Tape Delay");

        Path file = mgr.save(preset);
        assertThat(file).exists();
        assertThat(file.getFileName().toString()).endsWith(".preset.json");

        List<ProcessorPreset> loaded = mgr.loadUserPresets();
        assertThat(loaded).hasSize(1);
        ProcessorPreset back = loaded.get(0);
        assertThat(back.displayName()).isEqualTo("My Tape Delay");
        assertThat(back.parameterValues().get("Delay")).isCloseTo(250.0, within(EPS));
        assertThat(back.parameterValues().get("Feedback")).isCloseTo(0.5, within(EPS));
    }

    @Test
    void loadUserPresetsOnMissingDirectoryReturnsEmpty(@TempDir Path parent) throws IOException {
        PresetManager mgr = new PresetManager(parent.resolve("nonexistent"));
        assertThat(mgr.loadUserPresets()).isEmpty();
    }

    @Test
    void malformedPresetFilesAreSkipped(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("broken.preset.json"), "not-json",
                StandardCharsets.UTF_8);
        // also a valid preset
        ReverbProcessor rev = new ReverbProcessor(2, 44100.0);
        PresetManager mgr = new PresetManager(dir);
        mgr.save(mgr.capture(rev, "Valid"));

        List<ProcessorPreset> loaded = mgr.loadUserPresets();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).displayName()).isEqualTo("Valid");
    }

    @Test
    void bundledFactoryPresetsAreLoadable() {
        List<ProcessorPreset> factory = PresetManager.loadFactoryPresets();
        assertThat(factory).hasSizeGreaterThanOrEqualTo(3);
        assertThat(factory).extracting(ProcessorPreset::displayName)
                .contains("Vocal Compressor", "Room Reverb", "Tape Delay");
    }

    @Test
    void factoryPresetsApplyToLiveProcessors() {
        List<ProcessorPreset> factory = PresetManager.loadFactoryPresets();
        ProcessorPreset vocal = factory.stream()
                .filter(p -> p.displayName().equals("Vocal Compressor"))
                .findFirst().orElseThrow();
        CompressorProcessor comp = new CompressorProcessor(2, 44100.0);
        int applied = new PresetManager(Path.of(".")).apply(vocal, comp);
        assertThat(applied).isGreaterThanOrEqualTo(3);
        // The factory preset sets Threshold to -18.0
        assertThat(comp.getThresholdDb()).isCloseTo(-18.0, within(EPS));
    }

    @Test
    void processorPresetJsonRoundTrip() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("Threshold", -24.0);
        values.put("Ratio", 4.0);
        ProcessorPreset preset = new ProcessorPreset(
                "com.example.MyProcessor", "My Preset", values);

        ProcessorPreset back = ProcessorPreset.fromJson(preset.toJson());
        assertThat(back.processorClassName()).isEqualTo("com.example.MyProcessor");
        assertThat(back.displayName()).isEqualTo("My Preset");
        assertThat(back.parameterValues()).containsEntry("Threshold", -24.0);
        assertThat(back.parameterValues()).containsEntry("Ratio", 4.0);
    }

    @Test
    void slugifyProducesFilesystemSafeNames() {
        assertThat(PresetManager.slugify("Vocal Compressor")).isEqualTo("vocal-compressor");
        assertThat(PresetManager.slugify("Tape/Delay 2!")).isEqualTo("tape-delay-2");
        assertThat(PresetManager.slugify("   ")).isEqualTo("preset");
    }
}
