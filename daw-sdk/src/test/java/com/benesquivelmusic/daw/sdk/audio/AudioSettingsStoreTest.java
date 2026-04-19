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
}
