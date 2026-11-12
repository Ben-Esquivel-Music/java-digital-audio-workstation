package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupRetentionPolicyStoreTest {

    /** The six keys {@link BackupRetentionPolicyStore#toJson} writes, in its order. */
    private static final List<String> KEYS =
            List.of("keepRecent", "keepHourly", "keepDaily", "keepWeekly", "maxAgeSeconds", "maxBytes");

    @Test
    void roundTripsThroughJson() {
        BackupRetentionPolicy original = new BackupRetentionPolicy(
                7, 12, 30, 4, Duration.ofDays(60), 5L * 1024L * 1024L);
        String json = BackupRetentionPolicyStore.toJson(original);
        BackupRetentionPolicy parsed = BackupRetentionPolicyStore.parse(json);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void parseFillsMissingFieldsWithDefaults() {
        BackupRetentionPolicy parsed = BackupRetentionPolicyStore.parse("{ \"keepRecent\": 3 }");
        assertThat(parsed.keepRecent()).isEqualTo(3);
        assertThat(parsed.keepHourly()).isEqualTo(BackupRetentionPolicy.DEFAULT.keepHourly());
        assertThat(parsed.keepDaily()).isEqualTo(BackupRetentionPolicy.DEFAULT.keepDaily());
    }

    @Test
    void globalPathDefaultsToHomeDawDir() {
        Path expected = Path.of(System.getProperty("user.home", "."), ".daw", "backup-retention.json");
        assertThat(BackupRetentionPolicyStore.defaultGlobalPath()).isEqualTo(expected);
    }

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path tempDir) throws IOException {
        assertThat(BackupRetentionPolicyStore.load(tempDir.resolve("missing.json"))).isEmpty();
    }

    @Test
    void saveAndLoadRoundTripsToDisk(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("nested/backup-retention.json");
        BackupRetentionPolicy p = BackupRetentionPolicy.DEFAULT.withKeepDaily(7);
        BackupRetentionPolicyStore.save(file, p);
        assertThat(file).exists();
        BackupRetentionPolicy loaded = BackupRetentionPolicyStore.load(file).orElseThrow();
        assertThat(loaded).isEqualTo(p);
    }

    @Test
    void resolveForProjectPrefersOverrideOverGlobal(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve(".daw").resolve("backup-retention.json");
        BackupRetentionPolicy globalPolicy = BackupRetentionPolicy.DEFAULT.withKeepRecent(2);
        BackupRetentionPolicyStore.save(globalFile, globalPolicy);

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);
        BackupRetentionPolicy projectPolicy = BackupRetentionPolicy.DEFAULT.withKeepRecent(99);
        BackupRetentionPolicyStore.save(projectDir.resolve(BackupRetentionPolicyStore.FILE_NAME),
                projectPolicy);

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.resolveForProject(projectDir).keepRecent()).isEqualTo(99);
    }

    @Test
    void resolveForProjectFallsBackToGlobalWhenNoOverride(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve(".daw").resolve("backup-retention.json");
        BackupRetentionPolicy globalPolicy = BackupRetentionPolicy.DEFAULT.withKeepRecent(2);
        BackupRetentionPolicyStore.save(globalFile, globalPolicy);

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.resolveForProject(projectDir).keepRecent()).isEqualTo(2);
    }

    @Test
    void loadGlobalOrDefaultWhenMissingReturnsDefault(@TempDir Path tempDir) {
        Path globalFile = tempDir.resolve("absent.json");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.loadGlobalOrDefault()).isEqualTo(BackupRetentionPolicy.DEFAULT);
    }

    @Test
    void resolveForProjectFallsBackWhenOverrideIsMalformed(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve(".daw").resolve("backup-retention.json");
        BackupRetentionPolicyStore.save(globalFile, BackupRetentionPolicy.DEFAULT.withKeepRecent(5));

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);
        // Override with a negative bucket count — parse() will produce an
        // IllegalArgumentException via the BackupRetentionPolicy compact
        // constructor. resolveForProject must swallow it and fall back.
        Files.writeString(projectDir.resolve(BackupRetentionPolicyStore.FILE_NAME),
                "{ \"keepRecent\": -1 }");

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.resolveForProject(projectDir).keepRecent()).isEqualTo(5);
    }

    @Test
    void aSaveWhosePublishFailsKeepsThePreviousPolicyAndLeavesNoTemp(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve("backup-retention.json");
        BackupRetentionPolicyStore.save(globalFile, BackupRetentionPolicy.DEFAULT.withKeepRecent(5));
        byte[] previous = Files.readAllBytes(globalFile);
        IOException crash = new IOException("injected failure before the policy's publish");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile, new AtomicFileWriter(
                AtomicFileWriter.TEMP_CHANNEL_OPENER, (temp, target, options) -> {
                    throw crash;
                }));

        assertThatThrownBy(() -> store.saveGlobal(BackupRetentionPolicy.DEFAULT.withKeepRecent(9)))
                .isSameAs(crash);

        assertThat(Files.readAllBytes(globalFile)).isEqualTo(previous);
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertThat(entries).as("no temp beside the policy").containsExactly(globalFile);
        }
    }

    @Test
    void loadGlobalOrDefaultFallsBackWhenJsonIsInvalid(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve("backup-retention.json");
        Files.writeString(globalFile, "{ \"keepDaily\": -3 }");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.loadGlobalOrDefault()).isEqualTo(BackupRetentionPolicy.DEFAULT);
    }

    // ── loadGlobal: what the checkpoint writer is given (story 335) ───────

    @Test
    void loadGlobalIsEmptyWhenNoFileExists(@TempDir Path tempDir) throws IOException {
        assertThat(new BackupRetentionPolicyStore(tempDir.resolve("absent.json")).loadGlobal()).isEmpty();
        assertThat(new BackupRetentionPolicyStore(tempDir.resolve("no-such-folder").resolve("absent.json"))
                .loadGlobal()).as("nor its folder").isEmpty();
    }

    @Test
    void loadGlobalReadsWhatSaveGlobalWrote(@TempDir Path tempDir) throws IOException {
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(tempDir.resolve("backup-retention.json"));
        for (BackupRetentionPolicy policy : List.of(BackupRetentionPolicy.DEFAULT,
                new BackupRetentionPolicy(7, 12, 30, 4, Duration.ofDays(60), 5L * 1024L * 1024L),
                new BackupRetentionPolicy(0, 0, 0, 0, Duration.ZERO, 0L))) {
            store.saveGlobal(policy);
            assertThat(store.loadGlobal()).contains(policy);
        }
    }

    /**
     * Story 335 (studio-engineer ruling on an unreadable retention file) — a
     * file that exists but holds no complete, valid policy is refused, never
     * read as the default or filled in field by field: the checkpoint writer
     * deletes by what this returns.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("filesThatHoldNoPolicy")
    void loadGlobalRefusesAFileThatHoldsNoCompletePolicy(String what, String content, @TempDir Path tempDir)
            throws IOException {
        Path globalFile = Files.writeString(tempDir.resolve("backup-retention.json"), content);
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);

        assertThatThrownBy(store::loadGlobal).as(what).isInstanceOf(IOException.class)
                .hasMessageContaining(globalFile.toString());
    }

    static Stream<Arguments> filesThatHoldNoPolicy() {
        String complete = BackupRetentionPolicyStore.toJson(BackupRetentionPolicy.DEFAULT);
        int maxBytesValue = complete.indexOf("2147483648");
        Stream<Arguments> cutInsideEachValue = KEYS.stream().map(key -> {
            int valueStart = complete.indexOf("\"" + key + "\": ") + key.length() + 4;
            return Arguments.of("cut one character into the value of " + key,
                    complete.substring(0, valueStart + 1));
        });
        return Stream.concat(Stream.of(
                Arguments.of("a zero-byte file", ""),
                Arguments.of("a blank file", "  \n"),
                Arguments.of("garbage", "this is not a retention policy"),
                Arguments.of("a key missing", complete.replaceAll("(?m)^.*\"maxBytes\".*$", "")),
                Arguments.of("a value that does not parse", complete.replace("\"keepDaily\": 14", "\"keepDaily\": many")),
                Arguments.of("a count the policy refuses", complete.replace("\"keepRecent\": 10", "\"keepRecent\": -1")),
                Arguments.of("a size the policy refuses", complete.replaceAll("\"maxBytes\": \\d+", "\"maxBytes\": -5")),
                // A cut inside the last value leaves six keys whose values all parse: "maxBytes": 2147
                // would cap the checkpoints at 2147 bytes and delete almost every one (fix-review SHOULD-1).
                Arguments.of("cut inside the last value (\"maxBytes\": 2147)", complete.substring(0, maxBytesValue + 4)),
                Arguments.of("cut right after the last value", complete.substring(0, maxBytesValue + 10)),
                Arguments.of("the closing brace missing", complete.replace("}", "")),
                Arguments.of("cut inside a key", complete.substring(0, complete.indexOf("maxBytes") + 5)),
                Arguments.of("a key given twice", complete.replace("{", "{\n  \"keepRecent\": 3,")),
                Arguments.of("a second object after the first", complete + complete),
                Arguments.of("zero bytes after the closing brace", complete + "\0".repeat(16)),
                Arguments.of("a leading zero (\"keepRecent\": 010)",
                        complete.replace("\"keepRecent\": 10", "\"keepRecent\": 010"))),
                cutInsideEachValue);
    }

    /**
     * Fix-review SHOULD-1 — however a complete file is cut short, what is left
     * is refused: every prefix that ends before its closing brace.
     */
    @Test
    void loadGlobalRefusesEveryCutBeforeTheClosingBrace(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve("backup-retention.json");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        for (BackupRetentionPolicy policy : List.of(BackupRetentionPolicy.DEFAULT,
                new BackupRetentionPolicy(7, 12, 30, 4, Duration.ofDays(60), 5L * 1024L * 1024L))) {
            String complete = BackupRetentionPolicyStore.toJson(policy);
            for (int length = 0; length <= complete.lastIndexOf('}'); length++) {
                String cut = complete.substring(0, length);
                Files.writeString(globalFile, cut);
                assertThatThrownBy(store::loadGlobal).as("a file cut to %s", cut)
                        .isInstanceOf(IOException.class);
            }
        }
    }

    /** A complete object loads whatever its whitespace, key order, line endings or unknown keys. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("completeFilesHoldingTheDefault")
    void loadGlobalReadsACompleteFileHoweverItIsLaidOut(String what, String content, @TempDir Path tempDir)
            throws IOException {
        Path globalFile = Files.writeString(tempDir.resolve("backup-retention.json"), content);

        assertThat(new BackupRetentionPolicyStore(globalFile).loadGlobal()).as(what)
                .contains(BackupRetentionPolicy.DEFAULT);
    }

    static Stream<Arguments> completeFilesHoldingTheDefault() {
        String complete = BackupRetentionPolicyStore.toJson(BackupRetentionPolicy.DEFAULT);
        return Stream.of(
                Arguments.of("as written", complete),
                Arguments.of("without its last line break", complete.strip()),
                Arguments.of("with Windows line breaks", complete.replace("\n", "\r\n")),
                Arguments.of("on one line, keys in another order",
                        "{\"maxBytes\":2147483648,\"maxAgeSeconds\":2592000,\"keepWeekly\":8,"
                                + "\"keepDaily\":14,\"keepHourly\":24,\"keepRecent\":10}"),
                Arguments.of("with an unknown key", complete.replace("{", "{\n  \"note\": \"set by hand\",")),
                Arguments.of("after a byte order mark", "﻿" + complete));
    }

    /**
     * Fix-round-C review SHOULD-1 — before story 332, {@code toJson} wrote its
     * numbers in the default locale, so a policy saved under a locale with
     * other digits holds those digits, and every earlier reader read them back
     * ({@code Integer.parseInt}, {@code Long.parseLong}). Such a complete file
     * loads as the policy the user chose; cut anywhere before its closing
     * brace, it is still refused, and so is a leading zero, as in ASCII.
     */
    @ParameterizedTest(name = "{0}: {2} digits")
    @MethodSource("localesWithOtherDigits")
    void loadGlobalReadsAPolicySavedBeforeStory332InALocaleWithOtherDigits(
            String languageTag, char zeroDigit, String script, @TempDir Path tempDir) throws IOException {
        BackupRetentionPolicy custom =
                new BackupRetentionPolicy(5, 12, 7, 4, Duration.ofDays(60), 10L * 1024L * 1024L * 1024L);
        String written = toJsonBeforeStory332(custom, Locale.forLanguageTag(languageTag));
        assertThat(written).as("fixture: %s writes every number in %s digits", languageTag, script)
                .isEqualTo(inDigits(zeroDigit, BackupRetentionPolicyStore.toJson(custom)))
                .doesNotContainPattern("[0-9]");
        Path globalFile = Files.writeString(tempDir.resolve("backup-retention.json"), written);
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);

        assertThat(store.loadGlobal()).as("the complete file").contains(custom);

        for (int length = 0; length <= written.lastIndexOf('}'); length++) {
            String cut = written.substring(0, length);
            Files.writeString(globalFile, cut);
            assertThatThrownBy(store::loadGlobal).as("the file cut to %s", cut)
                    .isInstanceOf(IOException.class);
        }
        String five = inDigits(zeroDigit, "5");
        Files.writeString(globalFile, written.replace("\"keepRecent\": " + five,
                "\"keepRecent\": " + inDigits(zeroDigit, "0") + five));
        assertThatThrownBy(store::loadGlobal).as("a leading zero, as in \"keepRecent\": 05")
                .isInstanceOf(IOException.class);
    }

    static Stream<Arguments> localesWithOtherDigits() {
        return Stream.of(
                Arguments.of("ar-SA", '٠', "Arabic-Indic"),
                Arguments.of("fa-IR", '۰', "Extended Arabic-Indic"),
                Arguments.of("th-TH-u-nu-thai", '๐', "Thai"));
    }

    /** {@code toJson} as it was before story 332: {@code .formatted} in the default locale, here {@code locale}. */
    private static String toJsonBeforeStory332(BackupRetentionPolicy p, Locale locale) {
        return String.format(locale, """
                {
                  "keepRecent": %d,
                  "keepHourly": %d,
                  "keepDaily": %d,
                  "keepWeekly": %d,
                  "maxAgeSeconds": %d,
                  "maxBytes": %d
                }
                """,
                p.keepRecent(),
                p.keepHourly(),
                p.keepDaily(),
                p.keepWeekly(),
                p.maxAge() == null ? 0L : p.maxAge().getSeconds(),
                p.maxBytes());
    }

    /** {@code text} with each ASCII digit replaced by the digit of the same value whose zero is {@code zeroDigit}. */
    private static String inDigits(char zeroDigit, String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.chars().forEach(c -> result.append(c >= '0' && c <= '9' ? (char) (zeroDigit + c - '0') : (char) c));
        return result.toString();
    }

    @Test
    void loadGlobalRefusesAFileItCannotRead(@TempDir Path tempDir) throws IOException {
        Path folderInItsPlace = Files.createDirectories(tempDir.resolve("backup-retention.json"));
        assertThatThrownBy(new BackupRetentionPolicyStore(folderInItsPlace)::loadGlobal)
                .as("a folder where the file should be").isInstanceOf(IOException.class);

        Path binary = Files.write(tempDir.resolve("binary.json"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x7B});
        assertThatThrownBy(new BackupRetentionPolicyStore(binary)::loadGlobal)
                .as("bytes that are no text").isInstanceOf(IOException.class);
    }
}
