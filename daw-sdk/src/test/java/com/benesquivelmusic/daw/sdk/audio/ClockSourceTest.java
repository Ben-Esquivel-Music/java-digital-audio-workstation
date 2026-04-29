package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the hardware clock-source-selection surface area added to
 * {@link AudioBackend}. Covers:
 * <ul>
 *   <li>The {@link ClockSource} / {@link ClockKind} value types.</li>
 *   <li>{@link MockAudioBackend}'s configurable clock-source list and
 *       {@link AudioBackend#selectClockSource(DeviceId, int)} routing.</li>
 *   <li>{@link AudioBackend#clockLockEvents()} delivery on simulated
 *       lock loss.</li>
 *   <li>Per-device persistence in {@link AudioSettingsStore} so a
 *       selection survives a restart.</li>
 * </ul>
 */
class ClockSourceTest {

    @Test
    void clockSourceRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClockSource(-1, "Internal", true, new ClockKind.Internal()));
        assertThrows(IllegalArgumentException.class,
                () -> new ClockSource(0, " ", true, new ClockKind.Internal()));
        assertThrows(NullPointerException.class,
                () -> new ClockSource(0, "Internal", true, null));
        assertThrows(NullPointerException.class,
                () -> new ClockSource(0, null, true, new ClockKind.Internal()));
    }

    @Test
    void clockKindShortLabelsMatchUiContract() {
        assertEquals("INT", new ClockKind.Internal().shortLabel());
        assertEquals("W/C", new ClockKind.WordClock().shortLabel());
        assertEquals("SPDIF", new ClockKind.Spdif().shortLabel());
        assertEquals("ADAT", new ClockKind.Adat().shortLabel());
        assertEquals("AES", new ClockKind.Aes().shortLabel());
        assertEquals("EXT", new ClockKind.External().shortLabel());
    }

    @Test
    void backendsWithoutClockSelectionReturnEmptyByDefault() {
        DeviceId device = new DeviceId("Java Sound", "Default Device");
        // The interface default is the right behaviour for WASAPI / JACK /
        // the JDK mixer — they all run at the OS / server clock.
        AudioBackend javax = new JavaxSoundBackend();
        assertTrue(javax.clockSources(device).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> javax.selectClockSource(device, 0));
    }

    @Test
    void mockBackendReportsThreeClockSourcesAndRoutesSelection() {
        // The fake backend carries the three sources studio interfaces
        // typically expose: internal crystal, word-clock BNC, S/PDIF coax.
        ClockSource internal = new ClockSource(0, "Internal", true, new ClockKind.Internal());
        ClockSource wordClock = new ClockSource(1, "Word Clock In", false, new ClockKind.WordClock());
        ClockSource spdif = new ClockSource(2, "S/PDIF Coax", false, new ClockKind.Spdif());

        try (MockAudioBackend backend = new MockAudioBackend()) {
            backend.setClockSources(List.of(internal, wordClock, spdif));

            DeviceId device = new DeviceId(MockAudioBackend.NAME, "Mock Device");
            List<ClockSource> reported = backend.clockSources(device);
            assertEquals(3, reported.size());
            assertTrue(reported.get(0).current(), "internal must be the initial current");

            backend.selectClockSource(device, wordClock.id());
            assertEquals(List.of(wordClock.id()), backend.recordedClockSourceSelections());

            // After selection the {@code current} flag follows the chosen source.
            ClockSource newCurrent = backend.clockSources(device).stream()
                    .filter(ClockSource::current)
                    .findFirst()
                    .orElseThrow();
            assertEquals(wordClock.id(), newCurrent.id());

            // Unknown ids are rejected the same way an ASIO driver returns
            // ASE_InvalidParameter.
            assertThrows(IllegalArgumentException.class,
                    () -> backend.selectClockSource(device, 99));
        }
    }

    @Test
    void mockBackendPublishesClockLockEvents() throws InterruptedException {
        try (MockAudioBackend backend = new MockAudioBackend()) {
            DeviceId device = new DeviceId(MockAudioBackend.NAME, "Mock Device");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<ClockLockEvent> received = new AtomicReference<>();

            backend.clockLockEvents().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ClockLockEvent e) {
                    received.set(e);
                    latch.countDown();
                }
                @Override public void onError(Throwable t) { /* unused */ }
                @Override public void onComplete() { /* unused */ }
            });

            backend.simulateClockLock(device, 1, false);
            assertTrue(latch.await(2, TimeUnit.SECONDS), "subscriber must observe lock-loss event");
            ClockLockEvent event = received.get();
            assertNotNull(event);
            assertSame(device, event.device());
            assertEquals(1, event.sourceId());
            assertFalse(event.locked());
        }
    }

    @Test
    void clockSourceSelectionIsRememberedAcrossRestarts(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("audio-settings.json");
        DeviceId device = new DeviceId("ASIO", "Focusrite Scarlett 4i4");

        // Session 1: user picks Word Clock and the dialog persists it.
        AudioSettingsStore writer = new AudioSettingsStore(file);
        AudioSettingsStore.Settings persisted = new AudioSettingsStore.Settings(
                "ASIO",
                "Focusrite Scarlett 4i4",
                "Focusrite Scarlett 4i4",
                48_000.0,
                128,
                Map.of(AudioSettingsStore.Settings.deviceKey(device), 1));
        writer.save(persisted);

        // Session 2: a fresh store reads the file and recovers the selection.
        AudioSettingsStore reader = new AudioSettingsStore(file);
        Optional<AudioSettingsStore.Settings> loaded = reader.load();
        assertTrue(loaded.isPresent());
        assertEquals(persisted, loaded.get());
        Integer recovered = loaded.get()
                .clockSourceByDeviceKey()
                .get(AudioSettingsStore.Settings.deviceKey(device));
        assertEquals(1, recovered);
    }

    @Test
    void clockSourcePersistenceSurvivesDeviceNamesWithCommasAndEquals(@TempDir Path dir)
            throws IOException {
        // ASIO / CoreAudio device names occasionally contain '=' (e.g.
        // "Aggregate Device, ch=8") — the encoding must round-trip them
        // without merging entries. Use URL-encoding so ',' / '=' / '|'
        // survive intact.
        Path file = dir.resolve("audio-settings.json");
        DeviceId odd = new DeviceId("CoreAudio", "Aggregate, ch=8");
        DeviceId regular = new DeviceId("CoreAudio", "MacBook Pro Speakers");

        AudioSettingsStore store = new AudioSettingsStore(file);
        AudioSettingsStore.Settings persisted = new AudioSettingsStore.Settings(
                "CoreAudio",
                "MacBook Pro Microphone",
                "MacBook Pro Speakers",
                48_000.0,
                256,
                Map.of(
                        AudioSettingsStore.Settings.deviceKey(odd), 3,
                        AudioSettingsStore.Settings.deviceKey(regular), 0));
        store.save(persisted);

        AudioSettingsStore.Settings loaded = store.load().orElseThrow();
        assertEquals(2, loaded.clockSourceByDeviceKey().size());
        assertEquals(3,
                loaded.clockSourceByDeviceKey().get(AudioSettingsStore.Settings.deviceKey(odd)));
        assertEquals(0,
                loaded.clockSourceByDeviceKey().get(AudioSettingsStore.Settings.deviceKey(regular)));
    }

    @Test
    void legacySettingsFileWithoutClockSourceFieldStillLoads(@TempDir Path dir) throws IOException {
        // The historical 5-arg constructor remains compatible: a settings
        // file written before this story still loads with an empty map.
        Path file = dir.resolve("audio-settings.json");
        java.nio.file.Files.writeString(file, """
                {
                  "backend": "Java Sound",
                  "inputDevice": "Default",
                  "outputDevice": "Default",
                  "sampleRate": 44100.0,
                  "bufferFrames": 256
                }
                """);
        AudioSettingsStore store = new AudioSettingsStore(file);
        AudioSettingsStore.Settings loaded = store.load().orElseThrow();
        assertEquals("Java Sound", loaded.backend());
        assertEquals(Map.of(), loaded.clockSourceByDeviceKey());
    }
}
