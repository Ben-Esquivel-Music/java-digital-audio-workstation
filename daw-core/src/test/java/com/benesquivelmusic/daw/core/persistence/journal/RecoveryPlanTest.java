package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.RecoveryTemps;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@link RecoveryPlan} is the one reading of a project's recovery evidence:
 * its {@link RecoveryPlan#summary() summary} names the recovery point and the
 * changes that {@link JournalReplayer#recover(RecoveryPlan)} then replays from
 * that same plan, so the dialog and the replay can never disagree. Crash-shaped
 * fixtures (Persistence Integrity book §6.4), written with the real journal
 * writer or, for an older version's journal, with raw segments; fixed clocks and
 * explicit file times, no platform gating.
 */
class RecoveryPlanTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant MARK = NOW.plusSeconds(60);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final RecoveryTemps NO_TEMPS = new RecoveryTemps(List.of(), null);
    private static final String TEMP_NAME = ".project.daw.0123456789abcdef.tmp";

    @TempDir Path folder;
    private ProjectContext context;
    private final RecoveryScanner scanner = new RecoveryScanner(UTC);

    @BeforeEach
    void context() {
        context = ProjectContext.forProject(folder, InstantSource.fixed(NOW));
    }

    // ---- situations -------------------------------------------------------

    @Test
    void aVerifiedCaptureIsTheRecoveryPointEvenWhenANewerUnrelatedCheckpointIsOnDisk() throws Exception {
        arrange("a");
        RecoveryPlan plan = owed();
        RecoverySummary.Replay replay = replayOf(plan);

        assertThat(replay.baseKind()).isEqualTo(RecoverySummary.BaseKind.CHECKPOINT_CAPTURE);
        assertThat(replay.baseFileName()).isEqualTo("checkpoints/" + CheckpointFileName.format(1, MARK, UTC));
        assertThat(replay.checkpointIndex()).isEqualTo(OptionalLong.of(1));
        assertThat(replay.baseTime()).as("the marker's time").isEqualTo(MARK);
        assertThat(replay.changes().count()).as("pre-boundary mutations are not counted").isEqualTo(1);
        assertThat(replay.changes().types()).containsExactly("ClipEvent.Moved");
        assertThat(replay.changes().lastType()).contains("ClipEvent.Moved");
        assertThat(plan.summary().toString()).as("checkpoint #5 appears nowhere")
                .doesNotContain("checkpoint-005").doesNotContain("checkpointIndex=OptionalLong[5]");
        assertThat(replay.changes().types()).noneMatch(type -> type.startsWith("$"));

        RecoveryResult result = new JournalReplayer().recover(plan);
        assertThat(result.appliedRecordCount()).isEqualTo(1);
        assertThat(result.recoveredProject().getName()).isEqualTo("Captured base");
    }

    @Test
    void aSessionStartedFromAHeadWithoutACheckpointIsDescribedAsTheProjectAsOpened() throws Exception {
        arrange("b");
        RecoveryPlan plan = owed();
        RecoverySummary.Replay replay = replayOf(plan);

        assertThat(replay.baseKind()).isEqualTo(RecoverySummary.BaseKind.SESSION_START);
        assertThat(replay.baseFileName()).isEqualTo("journal/session-base.daw");
        assertThat(replay.checkpointIndex()).isEmpty();
        assertThat(replay.baseTime()).as("the $session-start time").isEqualTo(NOW);
        assertThat(replay.headRelation()).isEqualTo(RecoverySummary.HeadRelation.IDENTICAL);
        assertThat(replay.changes().count()).isEqualTo(2);
        assertThat(plan.summary().toString()).as("checkpoint #9 appears nowhere").doesNotContain("checkpoint-009");
        assertThat(plan.summary().sessionRecordTypes()).containsExactly(JournalRecord.SESSION_START_TYPE);
        assertThat(new JournalReplayer().recover(plan).appliedRecordCount()).isEqualTo(2);
    }

    @Test
    void aJournalWithoutProvenanceCountsWhatReplayDispatchesOntoTheSavedHead() throws Exception {
        arrange("c");
        RecoveryPlan plan = owed();
        RecoverySummary.Replay replay = replayOf(plan);

        assertThat(replay.baseKind()).isEqualTo(RecoverySummary.BaseKind.SAVED_HEAD);
        assertThat(replay.baseFileName()).isEqualTo("project.daw");
        assertThat(replay.baseTime()).as("the head's last-modified time").isEqualTo(NOW.minusSeconds(3600));
        assertThat(replay.headRelation()).isEqualTo(RecoverySummary.HeadRelation.IDENTICAL);
        assertThat(replay.changes().count()).as("the legacy marker is not counted").isEqualTo(2);
        assertThat(new JournalReplayer().recover(plan).appliedRecordCount()).isEqualTo(2);
    }

    @Test
    void aLegacyJournalWithoutAHeadNamesTheNewestCheckpointAndNoSavedProject() throws Exception {
        arrange("d");
        RecoveryPlan plan = owed();
        RecoverySummary.Replay replay = replayOf(plan);

        assertThat(replay.baseKind()).isEqualTo(RecoverySummary.BaseKind.NEWEST_CHECKPOINT);
        assertThat(replay.checkpointIndex()).isEqualTo(OptionalLong.of(3));
        assertThat(replay.baseTime()).as("the file-name stamp").isEqualTo(NOW.plusSeconds(300));
        assertThat(replay.headRelation()).isEqualTo(RecoverySummary.HeadRelation.MISSING);
        assertThat(plan.summary().lastCleanSave()).isEmpty();
        assertThat(new JournalReplayer().recover(plan).recoveredProject().getName()).isEqualTo("Newest legacy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "changed", "damaged"})
    void aReplayThatCannotStartIsDescribedWithItsTypedReasonAndTheReplayRefusesIt(String damage) throws Exception {
        head("Session head");
        checkpoint(3, NOW.plusSeconds(300), "Stale three");
        try (JournalWriter writer = writer()) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW.plusSeconds(20));
            writer.offer("TrackEvent.Renamed", UUID.randomUUID(), null, NOW.plusSeconds(30));
            writer.forceFlush();
        }
        Path base = context.journalDirectory().resolve(JournalState.BASE_FILE);
        RecoverySummary.CannotReplay expected = switch (damage) {
            case "missing" -> {
                Files.delete(base);
                yield new RecoverySummary.BaseMissing();
            }
            case "changed" -> {
                Files.writeString(base, xml("Changed baseline"));
                yield new RecoverySummary.BaseChanged();
            }
            default -> {
                flipAByteInsideTheSecondFrame(context.journalDirectory().resolve("segment-000.bin"));
                yield new RecoverySummary.SegmentDamaged("segment-000.bin");
            }
        };

        RecoveryPlan plan = owed();
        RecoverySummary summary = plan.summary();
        assertThat(summary.recovery()).isInstanceOfSatisfying(RecoverySummary.ReplayFails.class, fails -> {
            assertThat(fails.reason()).isEqualTo(expected);
            if (!damage.equals("damaged")) assertThat(fails.changes().count()).isEqualTo(3);
        });
        assertThat(summary.toString()).as("no checkpoint is advertised").doesNotContain("checkpoint-003");
        assertThatThrownBy(() -> new JournalReplayer().recover(plan)).isInstanceOf(JournalRecoveryException.class)
                .as("a damaged segment is named; the verified recovery point is not blamed")
                .hasMessage(damage.equals("damaged")
                        ? "journal segment failed checksum verification: "
                                + context.journalDirectory().resolve("segment-000.bin")
                        : "Cannot establish the session replay base");
    }

    @ParameterizedTest
    @ValueSource(strings = {"noJournalNoCheckpoint", "checkpointOnly", "sealedCleanJournal", "optedOutUnsealedJournal"})
    void aNewerHeadTempWithNoOwedDecisionIsWhatRecoverEverythingWrites(String situation) throws Exception {
        head("Clean head");
        boolean enabled = !situation.equals("optedOutUnsealedJournal");
        switch (situation) {
            case "checkpointOnly" -> checkpoint(4, NOW.plusSeconds(400), "Checkpoint four");
            case "sealedCleanJournal" -> {
                JournalWriter writer = writer();
                writer.start();
                writer.sealAndClose(NOW.plusSeconds(5));
            }
            case "optedOutUnsealedJournal" -> {
                try (JournalWriter writer = writer()) {
                    writer.start();
                    writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
                    writer.forceFlush();
                }
            }
            default -> { }
        }
        Path temp = temp("Interrupted save", NOW.plusSeconds(30));

        RecoveryPlan plan = scanner.plan(context, RecoveryTemps.inspect(folder), enabled).orElseThrow();
        RecoverySummary summary = plan.summary();

        assertThat(summary.recovery()).isEqualTo(new RecoverySummary.PromoteNewerHead(NOW.plusSeconds(30), TEMP_NAME));
        assertThat(summary.sessionRecordTypes()).as("the journal is not part of this recovery").isEmpty();
        assertThat(summary.toString()).doesNotContain("$session-seal").doesNotContain("$session-start")
                .doesNotContain("checkpoint-004");
        long size = Files.size(temp);
        assertThat(plan.interruptedSave()).hasValueSatisfying(save -> {
            assertThat(save.temp()).isEqualTo(temp);
            assertThat(save.size()).isEqualTo(size);
        });
        assertThat(summary.journalEnabled()).isEqualTo(enabled);
        if (!enabled) {
            assertThat(summary.journalPresent()).as("an opted-out journal is kept, listed and not read").isTrue();
            assertThat(summary.unreadableJournalBytes()).isZero();
        }
    }

    @Test
    void aNewerHeadTempBesideAnOwedJournalIsRetainedAndTheJournalReplaysFromItsVerifiedBase() throws Exception {
        arrange("g");
        RecoveryPlan plan = scanner.plan(context, RecoveryTemps.inspect(folder), true).orElseThrow();
        RecoverySummary.Replay replay = replayOf(plan);

        assertThat(replay.baseKind()).isEqualTo(RecoverySummary.BaseKind.SESSION_START);
        assertThat(replay.retainedInterruptedSave())
                .contains(new RecoverySummary.InterruptedSave(NOW.plusSeconds(30), TEMP_NAME));
        assertThat(plan.interruptedSave()).map(RecoveryPlan.NewerHead::temp).contains(folder.resolve(TEMP_NAME));
    }

    @Test
    void anUnsealedJournalWithNothingAfterItsCaptureHasNoChangesToReplay() throws Exception {
        arrange("h3");
        RecoveryPlan plan = owed();
        RecoverySummary.Replay replay = replayOf(plan);

        assertThat(replay.changes().count()).isZero();
        assertThat(replay.changes().lastType()).as("never $checkpoint").isEmpty();
        assertThat(replay.changes().lastTime()).isEmpty();
        assertThat(replay.headRelation()).isEqualTo(RecoverySummary.HeadRelation.IDENTICAL);
        assertThat(new JournalReplayer().recover(plan).appliedRecordCount()).isZero();
    }

    @Test
    void tornBytesAfterASealAreUnreadableBytesNotChanges() throws Exception {
        head("Head");
        JournalWriter writer = writer();
        writer.start();
        writer.sealAndClose(NOW.plusSeconds(5));
        Files.write(context.journalDirectory().resolve("segment-000.bin"), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);

        RecoveryPlan plan = owed();
        assertThat(plan.summary().unreadableJournalBytes()).isEqualTo(3);
        assertThat(replayOf(plan).changes().count()).isZero();
        assertThat(plan.summary().sessionRecordTypes())
                .containsExactly(JournalRecord.SESSION_START_TYPE, JournalRecord.SESSION_SEAL_TYPE);
    }

    @Test
    void aSealedJournalWithOnlyControlRecordsAfterItsCaptureOwesNothing() throws Exception {
        head("Head");
        Path captured = checkpoint(1, MARK, "Captured");
        JournalWriter writer = writer();
        writer.start();
        writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
        writer.onCheckpointSucceeded(writer.captureSequence(), captured, MARK);
        writer.sealAndClose(NOW.plusSeconds(100));

        assertThat(scanner.plan(context, NO_TEMPS, true)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "sessionStartThenAChangedSaveAMinuteLater, HEAD_NEWER",
            "sessionStartThenAChangedSaveInTheSameMinute, HEAD_NEWER",
            "sessionStartThenAChangedSaveAtTheSameInstant, HEAD_NEWER",
            "sessionStartWithTheSameBytesSavedLater, IDENTICAL",
            "checkpointWrittenAfterTheSave, HEAD_OLDER",
            "checkpointThenAChangedSaveAfterIt, HEAD_NEWER",
            "checkpointThenAChangedSaveAtTheSameInstant, HEAD_NEWER",
            "checkpointWithNoProjectFile, MISSING"})
    void theHeadRelationComparesDigestsFirstAndCallsTheHeadOlderOnlyWhenItWasSavedStrictlyBeforeTheRecoveryPoint(
            String situation, RecoverySummary.HeadRelation expected) throws Exception {
        Path head = head("Opened");
        Path captured = checkpoint(1, MARK, "Autosaved later");
        try (JournalWriter writer = writer()) {
            writer.start();
            writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
            if (situation.startsWith("checkpoint")) {
                writer.onCheckpointSucceeded(writer.captureSequence(), captured, MARK);
            }
            writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW.plusSeconds(90));
            writer.forceFlush();
        }
        switch (situation) {
            case "sessionStartThenAChangedSaveAMinuteLater" -> save(head, "Saved after opening", NOW.plusSeconds(60));
            case "sessionStartThenAChangedSaveInTheSameMinute" -> save(head, "Saved after opening", NOW.plusSeconds(20));
            case "sessionStartThenAChangedSaveAtTheSameInstant" -> save(head, "Saved after opening", NOW);
            case "sessionStartWithTheSameBytesSavedLater" -> Files.setLastModifiedTime(head, FileTime.from(NOW.plusSeconds(300)));
            case "checkpointThenAChangedSaveAfterIt" -> save(head, "Saved after the checkpoint", MARK.plusSeconds(540));
            case "checkpointThenAChangedSaveAtTheSameInstant" -> save(head, "Saved after the checkpoint", MARK);
            case "checkpointWithNoProjectFile" -> Files.delete(head);
            default -> { }
        }

        RecoverySummary summary = owed().summary();
        assertThat(replayOf(summary).headRelation()).isEqualTo(expected);
        assertThat(replayOf(summary).baseKind()).isEqualTo(situation.startsWith("checkpoint")
                ? RecoverySummary.BaseKind.CHECKPOINT_CAPTURE : RecoverySummary.BaseKind.SESSION_START);
        assertThat(summary.lastCleanSave().isEmpty()).isEqualTo(expected == RecoverySummary.HeadRelation.MISSING);
    }

    @Test
    void noFamilyTheRecorderJournalsIsRebuiltSoEveryChangeIsCountedAsNotInTheRecoveredProject() throws Exception {
        List<String> tags = recorderTypeTags();
        assertThat(tags).contains("ClipEvent.Moved", "MixerEvent.GainChanged", "TransportEvent.Seeked",
                "XrunEvent.BufferLate", "BackendFallbackEvent");
        assertThat(journaledTypesOf(new MixerEvent.GainChanged(UUID.randomUUID(), NOW),
                new ClipEvent.Moved(UUID.randomUUID(), UUID.randomUUID(), NOW)))
                .as("the tags are the ones the recorder writes")
                .containsExactly("MixerEvent.GainChanged", "ClipEvent.Moved")
                .allSatisfy(tag -> assertThat(tags).contains(tag));

        head("Head");
        List<JournalRecord> records = new ArrayList<>();
        for (String tag : tags) {
            records.add(JournalRecord.ofMutation(records.size(), NOW.plusSeconds(records.size()), tag,
                    UUID.randomUUID(), null));
        }
        legacyJournal(records);
        RecoverySummary.Changes changes = replayOf(owed()).changes();

        assertThat(tags).allSatisfy(tag -> assertThat(JournalReplayer.rebuilds(tag)).as(tag).isFalse());
        assertThat(changes.count()).isEqualTo(tags.size());
        assertThat(changes.rebuilt()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "b", "c", "d", "g", "h3"})
    void theReplayAppliesExactlyTheChangesThePlanDescribesOntoTheFileItNames(String situation) throws Exception {
        arrange(situation);
        RecoveryPlan plan = scanner.plan(context, RecoveryTemps.inspect(folder), true).orElseThrow();
        RecoverySummary.Replay replay = replayOf(plan);

        RecoveryResult result = new JournalReplayer().recover(plan);

        assertThat(result.appliedRecordCount()).isEqualTo(replay.changes().count());
        assertThat(result.recoveredProject().getName())
                .as("the recovered project is the %s the summary names, %s", replay.baseKind(), replay.baseFileName())
                .isEqualTo(projectNameIn(folder.resolve(replay.baseFileName())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "b"})
    void aRecoveryPointChangedAfterThePlanFailsTheReplayInsteadOfReplayingOntoOtherBytes(String situation)
            throws Exception {
        arrange(situation);
        RecoveryPlan plan = owed();
        Files.writeString(folder.resolve(replayOf(plan).baseFileName()), xml("Replaced after the dialog"));

        assertThatThrownBy(() -> new JournalReplayer().recover(plan)).isInstanceOf(JournalRecoveryException.class)
                .hasRootCauseMessage("The recovery point changed after the recovery was planned: "
                        + folder.resolve(replayOf(plan).baseFileName()));
    }

    @Test
    void aPromotionPlanIsNotAJournalReplay() throws Exception {
        head("Clean head");
        temp("Interrupted save", NOW.plusSeconds(30));
        RecoveryPlan plan = scanner.plan(context, RecoveryTemps.inspect(folder), true).orElseThrow();

        assertThatThrownBy(() -> new JournalReplayer().recover(plan)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The promotion decodes as strictly as the inspection that called the save complete: bytes that
     * are not UTF-8 are refused, never written to the head with U+FFFD in their place.
     */
    @Test
    void anInterruptedSaveThatIsNotStrictUtf8IsRefusedAtItsPromotion() throws Exception {
        byte[] bytes = withInvalidUtf8(xml("Interrupted MARK"), "MARK");
        Path temp = Files.write(folder.resolve(TEMP_NAME), bytes);
        Files.setLastModifiedTime(temp, FileTime.from(NOW.plusSeconds(30)));
        RecoveryPlan.NewerHead save = new RecoveryPlan.NewerHead(temp, NOW.plusSeconds(30), bytes.length);

        assertThatThrownBy(save::readUnchanged).isInstanceOf(MalformedInputException.class);
    }

    // ---- fixtures -----------------------------------------------------------

    /** {@code xml} as UTF-8, with {@code mark} replaced by a lead byte that lacks its continuation. */
    private static byte[] withInvalidUtf8(String xml, String mark) {
        int at = xml.indexOf(mark);
        assertThat(at).isPositive();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(xml.substring(0, at).getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(new byte[] {(byte) 0xC3, (byte) 0x28});
        bytes.writeBytes(xml.substring(at + mark.length()).getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    /**
     * (a) a verified capture of checkpoint #1 with two mutations before and one after it, and an
     * unrelated newer checkpoint #5; (b) a session started from the head, two mutations, an
     * unrelated checkpoint #9; (c) an older version's journal with a legacy marker and the head;
     * (d) the same without the head; (g) (b) plus a newer complete head temp; (h3) a capture
     * identical to the head with nothing after it.
     */
    private void arrange(String situation) throws IOException {
        switch (situation) {
            case "a" -> {
                head("Saved head");
                Path captured = checkpoint(1, MARK, "Captured base");
                checkpoint(5, NOW.plusSeconds(600), "Stale unrelated five");
                try (JournalWriter writer = writer()) {
                    writer.start();
                    writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
                    writer.offer("TrackEvent.Renamed", UUID.randomUUID(), null, NOW.plusSeconds(20));
                    writer.onCheckpointSucceeded(writer.captureSequence(), captured, MARK);
                    writer.offer("ClipEvent.Moved", UUID.randomUUID(), null, NOW.plusSeconds(90));
                    writer.forceFlush();
                }
            }
            case "b", "g" -> {
                head("Session head");
                checkpoint(9, NOW.plusSeconds(900), "Unrelated nine");
                try (JournalWriter writer = writer()) {
                    writer.start();
                    writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
                    writer.offer("MixerEvent.MuteChanged", UUID.randomUUID(), null, NOW.plusSeconds(20));
                    writer.forceFlush();
                }
                if (situation.equals("g")) temp("Interrupted save", NOW.plusSeconds(30));
            }
            case "c" -> {
                head("Legacy head");
                checkpoint(7, NOW.plusSeconds(700), "Unused seven");
                legacyJournal(legacyRecords());
            }
            case "d" -> {
                checkpoint(2, NOW.plusSeconds(200), "Older legacy");
                checkpoint(3, NOW.plusSeconds(300), "Newest legacy");
                legacyJournal(legacyRecords());
            }
            case "h3" -> {
                Path head = head("Identical");
                Path captured = Files.copy(head, Files.createDirectories(context.checkpointDirectory())
                        .resolve(CheckpointFileName.format(1, MARK, UTC)));
                try (JournalWriter writer = writer()) {
                    writer.start();
                    writer.offer("MixerEvent.GainChanged", UUID.randomUUID(), null, NOW.plusSeconds(10));
                    writer.onCheckpointSucceeded(writer.captureSequence(), captured, MARK);
                    writer.forceFlush();
                }
            }
            default -> throw new IllegalArgumentException(situation);
        }
    }

    private static List<JournalRecord> legacyRecords() {
        return List.of(
                JournalRecord.ofMutation(0, NOW.plusSeconds(10), "MixerEvent.GainChanged", UUID.randomUUID(), null),
                JournalRecord.checkpointMarker(1, NOW.plusSeconds(20)),
                JournalRecord.ofMutation(2, NOW.plusSeconds(30), "TrackEvent.Renamed", UUID.randomUUID(), null));
    }

    private RecoveryPlan owed() throws IOException {
        return scanner.plan(context, NO_TEMPS, true).orElseThrow();
    }

    private static RecoverySummary.Replay replayOf(RecoveryPlan plan) {
        return replayOf(plan.summary());
    }

    private static RecoverySummary.Replay replayOf(RecoverySummary summary) {
        assertThat(summary.recovery()).isInstanceOf(RecoverySummary.Replay.class);
        return (RecoverySummary.Replay) summary.recovery();
    }

    private static String xml(String name) throws IOException {
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        return new ProjectSerializer().serialize(project);
    }

    private String projectNameIn(Path file) throws IOException {
        return new ProjectDeserializer().deserialize(Files.readString(file), folder).getName();
    }

    private Path head(String name) throws IOException {
        Path head = Files.writeString(context.projectFile(), xml(name));
        Files.setLastModifiedTime(head, FileTime.from(NOW.minusSeconds(3600)));
        return head;
    }

    private static void save(Path head, String name, Instant savedAt) throws IOException {
        Files.writeString(head, xml(name));
        Files.setLastModifiedTime(head, FileTime.from(savedAt));
    }

    private Path checkpoint(long index, Instant stamp, String name) throws IOException {
        Path directory = Files.createDirectories(context.checkpointDirectory());
        return Files.writeString(directory.resolve(CheckpointFileName.format(index, stamp, UTC)), xml(name));
    }

    private Path temp(String name, Instant writtenAt) throws IOException {
        Path temp = Files.writeString(folder.resolve(TEMP_NAME), xml(name));
        Files.setLastModifiedTime(temp, FileTime.from(writtenAt));
        return temp;
    }

    private JournalWriter writer() {
        return new JournalWriter(context, JournalConfig.DEFAULT, true, null);
    }

    private void legacyJournal(List<JournalRecord> records) throws IOException {
        Files.createDirectories(context.journalDirectory());
        try (JournalSegment segment = JournalSegment.open(context.journalDirectory().resolve("segment-000.bin"))) {
            segment.appendGroup(records);
        }
    }

    private void flipAByteInsideTheSecondFrame(Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        int corruptAt = JournalState.records(context).getFirst().serializedSize() + 6;
        assertThat(bytes.length).isGreaterThan(corruptAt);
        bytes[corruptAt] ^= (byte) 0xFF;
        Files.write(segment, bytes);
    }

    /**
     * The type tags {@link JournalEventRecorder} writes: one per leaf of the sealed
     * {@link DawEvent} hierarchy its exhaustive switch maps, named by its rule (the family,
     * qualified with the leaf's simple name unless the family is its own leaf).
     */
    private static List<String> recorderTypeTags() {
        List<String> tags = new ArrayList<>();
        for (Class<?> family : DawEvent.class.getPermittedSubclasses()) {
            collectLeaves(family, family, tags);
        }
        return tags;
    }

    private static void collectLeaves(Class<?> family, Class<?> type, List<String> tags) {
        if (!type.isSealed()) {
            String leaf = type.getSimpleName();
            tags.add(family.getSimpleName().equals(leaf) ? leaf : family.getSimpleName() + "." + leaf);
            return;
        }
        for (Class<?> subtype : type.getPermittedSubclasses()) {
            collectLeaves(family, subtype, tags);
        }
    }

    /** Publishes {@code events} through a real recorder into a scratch journal and reads back the tags. */
    private List<String> journaledTypesOf(DawEvent... events) throws IOException {
        ProjectContext scratch = ProjectContext.forProject(Files.createDirectories(folder.resolve("recorded")),
                InstantSource.fixed(NOW));
        DefaultEventBus bus = new DefaultEventBus();
        try (JournalWriter writer = new JournalWriter(scratch, JournalConfig.DEFAULT, true, null);
             JournalEventRecorder recorder = new JournalEventRecorder(bus, writer, InstantSource.fixed(NOW))) {
            writer.start();
            recorder.start();
            for (DawEvent event : events) bus.publish(event);
            recorder.close();
            writer.forceFlush();
        } finally {
            bus.close();
        }
        return JournalState.records(scratch).stream().filter(record -> !record.isControl())
                .map(JournalRecord::type).toList();
    }
}
