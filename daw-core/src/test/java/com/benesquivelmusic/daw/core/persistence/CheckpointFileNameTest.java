package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.CountingClock;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryScanner;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.WorkingSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checkpoint name grammar every writer and reader of {@code checkpoints/}
 * shares (story 331, ruling F5), in ASCII digits whatever the user's format
 * locale.
 */
class CheckpointFileNameTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant TEN_PAST_TEN = Instant.parse("2026-03-21T22:10:05Z");

    /** A checkpoint name, in ASCII digits only. */
    private static final String ASCII_NAME = "checkpoint-[0-9]{3,}-[0-9]{8}T[0-9]{6}\\.daw";

    @TempDir
    Path projectDir;

    @Test
    void aFormattedNameParsesBackToItsIndexAndStamp() {
        String name = CheckpointFileName.format(7, TEN_PAST_TEN, UTC);

        assertThat(name).isEqualTo("checkpoint-007-20260321T221005.daw");
        assertThat(CheckpointFileName.parse(name)).hasValueSatisfying(parsed -> {
            assertThat(parsed.index()).isEqualTo(7);
            assertThat(parsed.stamp()).isEqualTo(LocalDateTime.of(2026, 3, 21, 22, 10, 5));
            assertThat(parsed.instantIn(UTC)).isEqualTo(TEN_PAST_TEN);
            assertThat(parsed.fileName()).isEqualTo(name);
        });
    }

    @Test
    void anIndexAboveNineHundredNinetyNineKeepsAllItsDigits() {
        String name = CheckpointFileName.format(1000, TEN_PAST_TEN, UTC);

        assertThat(name).isEqualTo("checkpoint-1000-20260321T221005.daw");
        assertThat(CheckpointFileName.parse(name)).map(CheckpointFileName::index).hasValue(1000L);
    }

    @Test
    void anIndexTooLargeForAnIntParsesAndOneTooLargeForALongIsNoCheckpoint() {
        assertThat(CheckpointFileName.parse("checkpoint-3000000000-20260321T221005.daw"))
                .map(CheckpointFileName::index).hasValue(3_000_000_000L);
        assertThat(CheckpointFileName.parse("checkpoint-99999999999999999999-20260321T221005.daw")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".checkpoint-001-20260321T221005.daw.0123456789abcdef.tmp",
            "checkpoint-001-20260321T221005.daw.0123456789abcdef.tmp",
            "checkpoint-001-20260321T221005.daw.tmp",
            "checkpoint-001-20260321T2210.daw",
            "checkpoint-001-20260321T221005.DAW",
            "checkpoint--20260321T221005.daw",
            "checkpoint-x01-20260321T221005.daw",
            "Checkpoint-001-20260321T221005.daw",
            "checkpoint-001-20261399T251005.daw",
            "checkpoint-001-20260230T221005.daw",
            "project.daw.v1.20260321-221005.bak",
            "autosave-001.daw",
            ""})
    void aNameThatIsNotExactlyACheckpointIsNoCheckpoint(String fileName) {
        assertThat(CheckpointFileName.parse(fileName)).isEmpty();
    }

    @Test
    void checkpointsAreOrderedByTheStampInTheNameThenByIndex() {
        CheckpointFileName yesterdaysFiftieth = parse("checkpoint-050-20260320T235959.daw");
        CheckpointFileName todaysThird = parse("checkpoint-003-20260321T090000.daw");
        CheckpointFileName todaysFourthSameSecond = parse("checkpoint-004-20260321T090000.daw");
        List<CheckpointFileName> names = new ArrayList<>(List.of(todaysFourthSameSecond, yesterdaysFiftieth,
                todaysThird));

        names.sort(CheckpointFileName.ORDER);

        assertThat(names).containsExactly(yesterdaysFiftieth, todaysThird, todaysFourthSameSecond);
    }

    @Test
    void anIndexBelowOneIsRefused() {
        assertThatThrownBy(() -> CheckpointFileName.format(0, TEN_PAST_TEN, UTC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNameAWriterAllocatesIsExactlyWhatParsingItsFileNameGivesBack() {
        Instant withNanos = TEN_PAST_TEN.plusNanos(987_654_321);

        CheckpointFileName allocated = CheckpointFileName.of(12, withNanos, UTC);

        assertThat(CheckpointFileName.parse(allocated.fileName())).hasValueSatisfying(parsed -> {
            assertThat(parsed.fileName()).isEqualTo(allocated.fileName());
            assertThat(parsed.index()).isEqualTo(allocated.index());
            assertThat(parsed.stamp()).as("the stamp to the second, as the name holds it")
                    .isEqualTo(allocated.stamp());
            assertThat(CheckpointFileName.ORDER.compare(parsed, allocated)).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ar-SA", "fa-IR", "mr-IN", "th-TH-u-ca-buddhist-nu-thai"})
    void aNameIsWrittenInAsciiDigitsWhateverTheFormatLocale(String languageTag) {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(languageTag));
            assertThat(String.format("%03d", 7))
                    .as("fixture: this locale really formats %%d with non-ASCII digits")
                    .isNotEqualTo("007");

            String name = CheckpointFileName.format(7, TEN_PAST_TEN, UTC);

            assertThat(name).isEqualTo("checkpoint-007-20260321T221005.daw");
            assertThat(CheckpointFileName.parse(name)).hasValueSatisfying(parsed -> {
                assertThat(parsed.index()).isEqualTo(7);
                assertThat(parsed.stamp()).isEqualTo(LocalDateTime.of(2026, 3, 21, 22, 10, 5));
            });
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ar-SA", "fa-IR", "mr-IN"})
    void aCheckpointWrittenUnderANonAsciiFormatLocaleIsOneEveryReaderRecognizes(String languageTag)
            throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(languageTag));
            CheckpointManager writer = manager();
            Recorder recorder = Recorder.on(writer);
            writer.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> written = new ArrayList<>();
            for (long revision = 1; revision <= 3; revision++) {
                Path file = writer.writeCheckpoint(document(revision), revision, CheckpointOrigin.AFTER_SAVE)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                written.add(file.getFileName().toString());
            }
            writer.stop();
            List<String> kept = written.subList(1, 3);

            assertThat(written).allSatisfy(name -> assertThat(name).matches(ASCII_NAME));
            assertThat(recorder.events(CheckpointEvent.Written.class)).as("one outcome per write").hasSize(3)
                    .allSatisfy(event -> assertThat(event.checkpointId()).matches("chk-[0-9]+-[0-9]{8}T[0-9]{6}"));
            assertThat(recorder.events(CheckpointEvent.Failed.class)).isEmpty();
            assertThat(checkpointNames(projectDir)).as("the prune counted them and deleted the first")
                    .containsExactlyElementsOf(kept);
            assertThat(new RecoveryScanner().newestCheckpoint(ProjectContext.forProject(projectDir).checkpointDirectory()))
                    .hasValueSatisfying(newest -> {
                        assertThat(newest.getFileName().toString()).isEqualTo(written.get(2));
                        assertThat(CheckpointFileName.parse(newest.getFileName().toString()).orElseThrow().index())
                                .isEqualTo(3);
                    });
            assertThat(new SessionManager().history(projectDir)).flatExtracting(WorkingSession::checkpointIds)
                    .containsExactlyElementsOf(kept);

            CheckpointManager reopened = manager();
            reopened.start(projectDir).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            reopened.stop();
            assertThat(reopened.getCheckpointCount()).as("seeding reads the highest index").isEqualTo(3);
            assertThat(reopened.getCheckpointFiles()).extracting(file -> file.getFileName().toString())
                    .containsExactlyElementsOf(kept);
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    /** A manager keeping two checkpoints, whose names all carry one stamp. */
    private static CheckpointManager manager() {
        return CheckpointTestSupport.manager(new AutoSaveConfig(Duration.ofHours(1), 2, true),
                new AtomicFileWriter(), new CountingClock(), Files::delete, CheckpointManager.STOP_WAIT);
    }

    private static CheckpointFileName parse(String name) {
        return CheckpointFileName.parse(name).orElseThrow();
    }
}
