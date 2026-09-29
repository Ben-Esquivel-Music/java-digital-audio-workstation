package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TakeDirectories} — the {@code audio/takes/<stamp>_take-NNNN}
 * allocator of story 323 (book §3.3; context D6).
 */
class TakeDirectoriesTest {

    private static final Instant NOW = Instant.parse("2026-08-03T14:22:05Z");
    private static final String STAMP = "2026-08-03T14-22-05";

    @TempDir
    Path tempDir;

    private Path audio() {
        return tempDir.resolve("audio");
    }

    @Test
    void allocatesTheFirstTakeUnderAnEmptyAudioDirectory() throws IOException {
        Path take = TakeDirectories.allocate(audio(), NOW);

        assertThat(take).isEqualTo(audio().resolve("takes").resolve(STAMP + "_take-0001"));
        assertThat(take).isDirectory();
        assertThat(TakeDirectories.takesDirectory(audio())).isEqualTo(audio().resolve("takes"));
        try (var entries = Files.list(take)) {
            assertThat(entries).as("a fresh take directory is empty").isEmpty();
        }
    }

    @Test
    void nextAllocationAtTheSameInstantYieldsTheNextOrdinal() throws IOException {
        Path first = TakeDirectories.allocate(audio(), NOW);
        Path second = TakeDirectories.allocate(audio(), NOW);

        assertThat(first.getFileName().toString()).isEqualTo(STAMP + "_take-0001");
        assertThat(second.getFileName().toString()).isEqualTo(STAMP + "_take-0002");
        assertThat(second).isDirectory();
    }

    @Test
    void anExistingTake0042MakesTheNextTake0043() throws IOException {
        Path takes = TakeDirectories.takesDirectory(audio());
        Files.createDirectories(takes.resolve("2026-01-01T00-00-00_take-0042"));
        Files.createDirectories(takes.resolve("2026-01-02T00-00-00_take-0007"));

        Path take = TakeDirectories.allocate(audio(), NOW);

        assertThat(take.getFileName().toString()).isEqualTo(STAMP + "_take-0043");
    }

    @Test
    void stampIsUtcRegardlessOfTheDefaultTimeZone() throws IOException {
        TimeZone previous = TimeZone.getDefault();
        try {
            // UTC+14: the local date would already be the 4th.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Path take = TakeDirectories.allocate(audio(), Instant.parse("2026-08-03T23:59:59Z"));

            assertThat(take.getFileName().toString()).isEqualTo("2026-08-03T23-59-59_take-0001");
            assertThat(TakeDirectories.stamp(Instant.parse("2026-12-31T23:00:00Z"))).isEqualTo("2026-12-31T23-00-00");
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void ordinalDigitsAreAsciiRegardlessOfTheDefaultFormatLocale() throws IOException {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale arabicEgypt = Locale.forLanguageTag("ar-EG");
        try {
            Locale.setDefault(Locale.Category.FORMAT, arabicEgypt);
            assertThat(String.format("%04d", 7))
                    .as("fixture: this locale really formats %%d with non-ASCII digits")
                    .isNotEqualTo("0007");

            assertThat(TakeDirectories.takeDirectoryName(NOW, 7)).isEqualTo(STAMP + "_take-0007");
            Path first = TakeDirectories.allocate(audio(), NOW);
            Path second = TakeDirectories.allocate(audio(), NOW);

            assertThat(first.getFileName().toString()).isEqualTo(STAMP + "_take-0001");
            assertThat(TakeDirectories.isTakeDirectoryName(first.getFileName().toString())).isTrue();
            assertThat(second.getFileName().toString())
                    .as("the scan recognises the first take, so the ordinal advances")
                    .isEqualTo(STAMP + "_take-0002");
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test
    void nonTakeEntriesUnderTakesAreIgnored() throws IOException {
        Path takes = TakeDirectories.takesDirectory(audio());
        Files.createDirectories(takes.resolve("old-recording-7"));
        Files.createDirectories(takes.resolve("2026-01-01T00-00-00_take-abcd"));
        Files.createDirectories(takes.resolve("2026-01-01T00-00-00_take-99"));
        Files.writeString(takes.resolve("notes.txt"), "not a take");
        Files.writeString(takes.resolve("2026-01-01_take-0050"), "wrong stamp shape");

        Path take = TakeDirectories.allocate(audio(), NOW);

        assertThat(take.getFileName().toString()).isEqualTo(STAMP + "_take-0001");
    }

    @Test
    void aPreExistingDirectoryWithTheExactCandidateNameBumpsTheOrdinal() throws Exception {
        Path takes = TakeDirectories.takesDirectory(audio());
        Files.createDirectories(takes.resolve(STAMP + "_take-0001"));
        Files.createDirectories(takes.resolve(STAMP + "_take-0002"));

        // Public path: the scan sees both and skips to 3.
        assertThat(TakeDirectories.allocate(audio(), NOW).getFileName().toString())
                .isEqualTo(STAMP + "_take-0003");
        // Collision loop proper: start below what exists and let createDirectory bump past 1, 2 and 3.
        // The loop ends only because the ordinal moves on, so it runs under the guard: a bump that
        // regressed fails this test instead of retrying the same name on the JUnit thread forever.
        AtomicReference<Path> allocated = new AtomicReference<>();
        Throwable thrown = Story323TestSupport.outcomeWithinTheGuard("take-directories-test-collision",
                () -> allocated.set(TakeDirectories.allocate(takes, NOW, 1)));
        assertThat(thrown).isNull();
        Path claimed = allocated.get();
        assertThat(claimed.getFileName().toString()).isEqualTo(STAMP + "_take-0004");
        assertThat(claimed).isDirectory();
        assertThat(takes.resolve(STAMP + "_take-0001")).as("the existing take is untouched").isDirectory();
    }

    @Test
    void namesAreRecognisedAndOrdinalsParsed() {
        assertThat(TakeDirectories.isTakeDirectoryName(STAMP + "_take-0001")).isTrue();
        assertThat(TakeDirectories.isTakeDirectoryName(STAMP + "_take-12345")).isTrue();
        assertThat(TakeDirectories.isTakeDirectoryName(STAMP + "_take-1")).isFalse();
        assertThat(TakeDirectories.isTakeDirectoryName("recording-1")).isFalse();
        assertThat(TakeDirectories.isTakeDirectoryName(null)).isFalse();
        assertThat(TakeDirectories.ordinalOf(STAMP + "_take-0042")).hasValue(42);
        assertThat(TakeDirectories.ordinalOf(STAMP + "_take-12345")).hasValue(12345);
        assertThat(TakeDirectories.ordinalOf("junk")).isEmpty();
        assertThat(TakeDirectories.takeDirectoryName(NOW, 7)).isEqualTo(STAMP + "_take-0007");
        assertThat(TakeDirectories.takeDirectoryName(NOW, 12345)).isEqualTo(STAMP + "_take-12345");
        assertThatThrownBy(() -> TakeDirectories.takeDirectoryName(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TakeDirectories.TAKES_DIR_NAME).isEqualTo("takes");
    }
}
