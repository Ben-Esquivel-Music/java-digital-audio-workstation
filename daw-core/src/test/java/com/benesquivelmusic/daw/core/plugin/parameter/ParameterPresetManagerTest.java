package com.benesquivelmusic.daw.core.plugin.parameter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterPresetManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveAndLoadPreset() throws IOException {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        ParameterPreset preset = ParameterPreset.user("My Preset", Map.of(0, 1.5, 1, 3.0));
        Path saved = manager.savePreset(preset);

        assertThat(saved).exists();
        assertThat(saved.getFileName().toString()).isEqualTo("My_Preset.json");

        ParameterPreset loaded = manager.loadPreset(saved);
        assertThat(loaded.name()).isEqualTo("My Preset");
        assertThat(loaded.factory()).isFalse();
        assertThat(loaded.values()).containsEntry(0, 1.5);
        assertThat(loaded.values()).containsEntry(1, 3.0);
    }

    @Test
    void shouldSaveFactoryPreset() throws IOException {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        ParameterPreset preset = ParameterPreset.factory("Factory Preset", Map.of(0, 10.0));
        Path saved = manager.savePreset(preset);

        ParameterPreset loaded = manager.loadPreset(saved);
        assertThat(loaded.factory()).isTrue();
    }

    @Test
    void shouldRejectNameCollisionWithoutOverwritingLegacyPreset() throws IOException {
        var manager = new ParameterPresetManager(tempDir);
        var original = ParameterPreset.user("Lead/Vocal", Map.of(0, 0.25));
        Path legacyFile = tempDir.resolve("Lead_Vocal.json");
        Files.writeString(legacyFile, ParameterPresetManager.toJson(original));

        assertThatThrownBy(() -> manager.savePreset(ParameterPreset.user("Lead Vocal", Map.of(0, 0.75))))
                .isInstanceOf(FileAlreadyExistsException.class)
                .hasMessageContaining("Lead/Vocal");

        var reopened = new ParameterPresetManager(tempDir);
        assertThat(reopened.loadAllPresets()).containsExactly(original);
        assertThat(reopened.deletePreset("Lead/Vocal")).isTrue();
        assertThat(reopened.loadAllPresets()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lead/Vocal", "Lead \"Vocal\"", "Lead\\Vocal"})
    void shouldUpdateExistingPresetWithTheSameDisplayName(String name) throws IOException {
        var manager = new ParameterPresetManager(tempDir);
        Path saved = manager.savePreset(ParameterPreset.user(name, Map.of(0, 0.25)));
        var updated = ParameterPreset.user(name, Map.of(0, 0.75));

        var reopened = new ParameterPresetManager(tempDir);
        assertThat(reopened.savePreset(updated)).isEqualTo(saved);
        assertThat(reopened.loadAllPresets()).containsExactly(updated);
        assertThat(reopened.deletePreset(name)).isTrue();
        assertThat(reopened.loadAllPresets()).isEmpty();
    }

    @Test
    void shouldNotDeleteDifferentPresetWithTheSameSanitizedFileName() throws IOException {
        var manager = new ParameterPresetManager(tempDir);
        var original = ParameterPreset.user("Lead/Vocal", Map.of(0, 0.25));
        manager.savePreset(original);

        assertThat(manager.deletePreset("Lead Vocal")).isFalse();
        assertThat(manager.loadAllPresets()).containsExactly(original);
    }

    @Test
    void shouldLoadAllPresets() throws IOException {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        manager.savePreset(ParameterPreset.user("Preset A", Map.of(0, 1.0)));
        manager.savePreset(ParameterPreset.user("Preset B", Map.of(0, 2.0)));

        List<ParameterPreset> presets = manager.loadAllPresets();
        assertThat(presets).hasSize(2);
    }

    @Test
    void shouldReturnEmptyListForNonExistentDirectory() throws IOException {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir.resolve("nonexistent"));

        List<ParameterPreset> presets = manager.loadAllPresets();
        assertThat(presets).isEmpty();
    }

    @Test
    void shouldDeletePreset() throws IOException {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        manager.savePreset(ParameterPreset.user("To Delete", Map.of(0, 1.0)));
        assertThat(manager.loadAllPresets()).hasSize(1);

        boolean deleted = manager.deletePreset("To Delete");
        assertThat(deleted).isTrue();
        assertThat(manager.loadAllPresets()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenDeletingNonExistentPreset() throws IOException {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        boolean deleted = manager.deletePreset("Does Not Exist");
        assertThat(deleted).isFalse();
    }

    @Test
    void shouldSanitizeFileNames() {
        assertThat(ParameterPresetManager.sanitizeFileName("Hello World!")).isEqualTo("Hello_World_");
        assertThat(ParameterPresetManager.sanitizeFileName("test/path")).isEqualTo("test_path");
        assertThat(ParameterPresetManager.sanitizeFileName("valid_name-1")).isEqualTo("valid_name-1");
    }

    @Test
    void shouldRoundTripJsonSerialization() throws IOException {
        ParameterPreset original = ParameterPreset.factory("Test Preset", Map.of(0, -3.5, 1, 100.0, 2, 0.0));

        String json = ParameterPresetManager.toJson(original);
        ParameterPreset parsed = ParameterPresetManager.fromJson(json);

        assertThat(parsed.name()).isEqualTo(original.name());
        assertThat(parsed.factory()).isEqualTo(original.factory());
        assertThat(parsed.values()).isEqualTo(original.values());
    }

    @Test
    void shouldHandleEmptyValues() throws IOException {
        ParameterPreset original = ParameterPreset.user("Empty", Map.of());

        String json = ParameterPresetManager.toJson(original);
        ParameterPreset parsed = ParameterPresetManager.fromJson(json);

        assertThat(parsed.values()).isEmpty();
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> ParameterPresetManager.fromJson("not valid json"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void shouldCreateDirectoryOnSave() throws IOException {
        Path nested = tempDir.resolve("sub").resolve("dir");
        ParameterPresetManager manager = new ParameterPresetManager(nested);

        manager.savePreset(ParameterPreset.user("Test", Map.of(0, 1.0)));

        assertThat(nested).isDirectory();
        assertThat(manager.loadAllPresets()).hasSize(1);
    }

    @Test
    void shouldRejectNullDirectory() {
        assertThatThrownBy(() -> new ParameterPresetManager(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPresetOnSave() {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        assertThatThrownBy(() -> manager.savePreset(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPathOnLoad() {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);

        assertThatThrownBy(() -> manager.loadPreset(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnPresetsDirectory() {
        ParameterPresetManager manager = new ParameterPresetManager(tempDir);
        assertThat(manager.getPresetsDirectory()).isEqualTo(tempDir);
    }
}
