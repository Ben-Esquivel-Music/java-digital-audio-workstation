package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link MetronomeSettingsStore} — global-default persistence for the
 * metronome, including the story-136 {@link ClickOutput} routing.
 *
 * <p>Uses {@link TempDir} so tests never touch the real {@code ~/.daw/}
 * directory and can run safely in parallel.</p>
 */
class MetronomeSettingsStoreTest {

    // ── Missing file ────────────────────────────────────────────────────────

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path dir) {
        MetronomeSettingsStore store = new MetronomeSettingsStore(
                dir.resolve("metronome-settings.json"));
        assertThat(store.load()).isEmpty();
    }

    // ── Round-trip ──────────────────────────────────────────────────────────

    @Test
    void saveAndLoadRoundTripDefaultSettings(@TempDir Path dir) throws IOException {
        MetronomeSettingsStore store = new MetronomeSettingsStore(
                dir.resolve("metronome-settings.json"));
        MetronomeSettingsStore.Settings defaults = MetronomeSettingsStore.Settings.defaults();

        store.save(defaults);

        Optional<MetronomeSettingsStore.Settings> loaded = store.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(defaults);
    }

    @Test
    void saveAndLoadRoundTripClickOutputRouting(@TempDir Path dir) throws IOException {
        MetronomeSettingsStore store = new MetronomeSettingsStore(
                dir.resolve("sub/metronome-settings.json"));
        MetronomeSettingsStore.Settings s = new MetronomeSettingsStore.Settings(
                true,
                0.75,
                ClickSound.COWBELL,
                Subdivision.EIGHTH,
                new ClickOutput(5, 0.42, false, true));

        store.save(s);

        Optional<MetronomeSettingsStore.Settings> loaded = store.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(s);
    }

    @Test
    void saveAndLoadRoundTripDisabledMetronome(@TempDir Path dir) throws IOException {
        MetronomeSettingsStore store = new MetronomeSettingsStore(
                dir.resolve("metronome-settings.json"));
        MetronomeSettingsStore.Settings s = new MetronomeSettingsStore.Settings(
                false,
                0.0,
                ClickSound.WOODBLOCK,
                Subdivision.SIXTEENTH,
                ClickOutput.MAIN_MIX_ONLY);

        store.save(s);
        MetronomeSettingsStore.Settings loaded = store.load().orElseThrow();

        assertThat(loaded.enabled()).isFalse();
        assertThat(loaded.volume()).isZero();
        assertThat(loaded.clickSound()).isEqualTo(ClickSound.WOODBLOCK);
        assertThat(loaded.subdivision()).isEqualTo(Subdivision.SIXTEENTH);
        assertThat(loaded.clickOutput()).isEqualTo(ClickOutput.MAIN_MIX_ONLY);
    }

    // ── Corruption tolerance ────────────────────────────────────────────────

    @Test
    void loadCorruptFileReturnsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("metronome-settings.json");
        Files.writeString(file, "definitely not json");
        MetronomeSettingsStore store = new MetronomeSettingsStore(file);

        assertThat(store.load()).isEmpty();
        assertThat(Files.exists(file)).as("corrupt file must not be deleted").isTrue();
    }

    @Test
    void loadUnknownEnumValuesFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("metronome-settings.json");
        Files.writeString(file, """
                {
                  "enabled": true,
                  "volume": 0.5,
                  "clickSound": "NOT_A_SOUND",
                  "subdivision": "NOT_A_SUBDIVISION",
                  "clickOutput.hardwareChannelIndex": 2,
                  "clickOutput.gain": 0.8,
                  "clickOutput.mainMixEnabled": true,
                  "clickOutput.sideOutputEnabled": true
                }
                """);
        MetronomeSettingsStore store = new MetronomeSettingsStore(file);

        MetronomeSettingsStore.Settings loaded = store.load().orElseThrow();
        assertThat(loaded.clickSound()).isEqualTo(ClickSound.WOODBLOCK);
        assertThat(loaded.subdivision()).isEqualTo(Subdivision.QUARTER);
        assertThat(loaded.volume()).isEqualTo(0.5);
        assertThat(loaded.clickOutput().hardwareChannelIndex()).isEqualTo(2);
        assertThat(loaded.clickOutput().sideOutputEnabled()).isTrue();
    }

    @Test
    void loadMissingKeysFallsBackToDefaults(@TempDir Path dir) throws IOException {
        // A minimal, otherwise-valid document missing every optional key. The
        // store treats every field as optional with safe fall-back values so
        // upgrades never orphan older settings files.
        Path file = dir.resolve("metronome-settings.json");
        Files.writeString(file, "{ }");
        MetronomeSettingsStore store = new MetronomeSettingsStore(file);

        MetronomeSettingsStore.Settings loaded = store.load().orElseThrow();
        assertThat(loaded.enabled()).isTrue();
        assertThat(loaded.volume()).isEqualTo(1.0);
        assertThat(loaded.clickSound()).isEqualTo(ClickSound.WOODBLOCK);
        assertThat(loaded.subdivision()).isEqualTo(Subdivision.QUARTER);
        assertThat(loaded.clickOutput()).isEqualTo(ClickOutput.MAIN_MIX_ONLY);
    }

    @Test
    void loadVolumeOutOfRangeIsClamped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("metronome-settings.json");
        Files.writeString(file, "{ \"volume\": 2.5 }");
        MetronomeSettingsStore store = new MetronomeSettingsStore(file);

        MetronomeSettingsStore.Settings loaded = store.load().orElseThrow();
        assertThat(loaded.volume()).isEqualTo(1.0);
    }

    // ── Settings record validation ──────────────────────────────────────────

    @Test
    void settingsRecordRejectsOutOfRangeVolume() {
        assertThrows(IllegalArgumentException.class, () -> new MetronomeSettingsStore.Settings(
                true, -0.1, ClickSound.WOODBLOCK, Subdivision.QUARTER, ClickOutput.MAIN_MIX_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new MetronomeSettingsStore.Settings(
                true, 1.5, ClickSound.WOODBLOCK, Subdivision.QUARTER, ClickOutput.MAIN_MIX_ONLY));
    }

    @Test
    void settingsRecordRejectsNullClickSound() {
        assertThrows(NullPointerException.class, () -> new MetronomeSettingsStore.Settings(
                true, 1.0, null, Subdivision.QUARTER, ClickOutput.MAIN_MIX_ONLY));
    }

    @Test
    void settingsRecordRejectsNullSubdivision() {
        assertThrows(NullPointerException.class, () -> new MetronomeSettingsStore.Settings(
                true, 1.0, ClickSound.WOODBLOCK, null, ClickOutput.MAIN_MIX_ONLY));
    }

    @Test
    void settingsRecordRejectsNullClickOutput() {
        assertThrows(NullPointerException.class, () -> new MetronomeSettingsStore.Settings(
                true, 1.0, ClickSound.WOODBLOCK, Subdivision.QUARTER, null));
    }

    // ── Location defaults ───────────────────────────────────────────────────

    @Test
    void defaultStoreUsesDotDawUnderUserHome() {
        MetronomeSettingsStore store = new MetronomeSettingsStore();
        assertThat(store.file().toString()).contains(".daw");
        assertThat(store.file().toString()).endsWith("metronome-settings.json");
    }

    // ── save() argument validation ──────────────────────────────────────────

    @Test
    void saveRejectsNullSettings(@TempDir Path dir) {
        MetronomeSettingsStore store = new MetronomeSettingsStore(
                dir.resolve("metronome-settings.json"));
        assertThrows(NullPointerException.class, () -> store.save(null));
    }

    // ── Constructor validation ──────────────────────────────────────────────

    @Test
    void constructorRejectsNullPath() {
        assertThrows(NullPointerException.class, () -> new MetronomeSettingsStore(null));
    }
}
