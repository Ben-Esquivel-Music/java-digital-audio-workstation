package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioSettingsStoreTest {

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path dir) {
        AudioSettingsStore store = new AudioSettingsStore(dir.resolve("audio-settings.json"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) throws IOException {
        AudioSettingsStore store = new AudioSettingsStore(dir.resolve("sub/audio-settings.json"));
        AudioSettingsStore.Settings s = new AudioSettingsStore.Settings(
                "ASIO",
                "Focusrite Scarlett 4i4 (In)",
                "Focusrite Scarlett 4i4 (Out)",
                48_000.0,
                128);
        store.save(s);

        Optional<AudioSettingsStore.Settings> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(s, loaded.get());
    }

    @Test
    void loadCorruptFileReturnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("audio-settings.json");
        Files.writeString(file, "not json at all");
        AudioSettingsStore store = new AudioSettingsStore(file);
        assertTrue(store.load().isEmpty());
        assertTrue(Files.exists(file), "corrupt file must not be deleted");
    }

    @Test
    void loadFileMissingKeysReturnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("audio-settings.json");
        Files.writeString(file, "{ \"backend\": \"ASIO\" }");
        AudioSettingsStore store = new AudioSettingsStore(file);
        assertFalse(store.load().isPresent());
    }

    @Test
    void settingsRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSettingsStore.Settings("", "in", "out", 44_100, 128));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSettingsStore.Settings("ASIO", "in", "out", 0, 128));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSettingsStore.Settings("ASIO", "in", "out", 44_100, 0));
    }

    @Test
    void defaultStoreUsesDotDawUnderUserHome() {
        AudioSettingsStore store = new AudioSettingsStore();
        assertTrue(store.file().toString().endsWith("audio-settings.json"));
        assertTrue(store.file().toString().contains(".daw"));
    }

    @Test
    void jsonEscapesSpecialCharactersInDeviceNames(@TempDir Path dir) throws IOException {
        AudioSettingsStore store = new AudioSettingsStore(dir.resolve("audio-settings.json"));
        AudioSettingsStore.Settings s = new AudioSettingsStore.Settings(
                "Java Sound",
                "Device with \"quotes\"",
                "Device with \\ backslash",
                44_100.0,
                256);
        store.save(s);
        Optional<AudioSettingsStore.Settings> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals(s, loaded.get());
    }

    @Test
    void roundTripPersistsLatencyCompensationToggleAndOverrides(@TempDir Path dir) throws IOException {
        AudioSettingsStore store = new AudioSettingsStore(dir.resolve("audio-settings.json"));
        AudioSettingsStore.Settings s = new AudioSettingsStore.Settings(
                "ASIO",
                "Focusrite Scarlett 4i4 (In)",
                "Focusrite Scarlett 4i4 (Out)",
                48_000.0,
                128,
                java.util.Map.of(),
                false,
                java.util.Map.of(
                        "ASIO|Focusrite Scarlett 4i4 (In)", 240,
                        "ASIO|UAD Apollo Twin", 360));
        store.save(s);

        AudioSettingsStore.Settings loaded = store.load().orElseThrow();
        assertFalse(loaded.applyLatencyCompensation());
        assertEquals(240, loaded.latencyOverrideFramesByDeviceKey()
                .get("ASIO|Focusrite Scarlett 4i4 (In)"));
        assertEquals(360, loaded.latencyOverrideFramesByDeviceKey()
                .get("ASIO|UAD Apollo Twin"));
    }

    @Test
    void legacyFileWithoutLatencyFieldsDefaultsToCompensationOn(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("audio-settings.json");
        // Old-format file written before the latency fields existed.
        Files.writeString(file, "{\n"
                + "  \"backend\": \"ASIO\",\n"
                + "  \"inputDevice\": \"In\",\n"
                + "  \"outputDevice\": \"Out\",\n"
                + "  \"sampleRate\": 48000.0,\n"
                + "  \"bufferFrames\": 128\n"
                + "}\n");
        AudioSettingsStore store = new AudioSettingsStore(file);
        AudioSettingsStore.Settings loaded = store.load().orElseThrow();

        // Default for upgrade: compensation on, no per-device overrides —
        // matches what Pro Tools / Logic / Cubase / Reaper do.
        assertTrue(loaded.applyLatencyCompensation());
        assertTrue(loaded.latencyOverrideFramesByDeviceKey().isEmpty());
    }

    @Test
    void shouldRejectNegativeLatencyOverride() {
        // A corrupt or misuse'd value must not silently shift recorded
        // clips into the future.
        assertThrows(IllegalArgumentException.class,
                () -> new AudioSettingsStore.Settings(
                        "ASIO", "in", "out", 44_100, 128,
                        java.util.Map.of(), true,
                        java.util.Map.of("ASIO|in", -10)));
    }
}
