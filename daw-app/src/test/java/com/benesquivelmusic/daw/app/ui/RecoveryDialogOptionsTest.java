package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryDialog;
import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;
import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary.BaseKind;
import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary.HeadRelation;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Labeled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — the {@link RecoveryDialog} (Project Manager Design Book §4.6.1)
 * keeps its three recovery options + Cancel, makes Restore checkpoint only
 * the default button exactly when the last clean save is newer than the
 * recovery point, and renders the
 * {@link RecoverySummary} of the open's one recovery plan exactly as the
 * root bundle words it: the rows Last clean save, Recovery point, Changes
 * after it and Last journaled event, then the explanation's independent
 * sentences in their fixed order, then the collapsed technical detail — per
 * recovery situation: (a) a verified checkpoint capture, (b) a session-start
 * point older than a later save, (c) a legacy journal on the saved head,
 * (d) a legacy journal without {@code project.daw}, (e) a replay that cannot
 * start, (f) an interrupted save alone, (g) an interrupted save beside a
 * journal, and (h3) an unsealed journal with nothing after its identical
 * capture. Built on the FX thread (the dialog's {@code DawgDialog} chrome
 * touches the toolkit) but never {@code showAndWait()} — the assertions read
 * the dialog's test accessors and its labels, extracted on the FX thread.
 * Times are formatted with the dialog's {@code yyyy-MM-dd HH:mm} pattern in
 * the system zone.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecoveryDialogOptionsTest {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Instant SESSION_OPENED = Instant.parse("2026-03-21T13:50:00Z");
    private static final Instant HEAD_BEFORE_CHECKPOINT = Instant.parse("2026-03-21T13:40:00Z");
    private static final Instant CHECKPOINT_TIME = Instant.parse("2026-03-21T14:05:00Z");
    private static final Instant HEAD_SAVED = Instant.parse("2026-03-21T14:30:00Z");
    private static final Instant LAST_EVENT_TIME = Instant.parse("2026-03-21T14:48:30Z");
    private static final Instant INTERRUPTED_SAVE_TIME = Instant.parse("2026-03-21T14:52:00Z");
    private static final String TEMP_NAME = ".project.daw.0123456789abcdef.tmp";

    private static final RecoverySummary.Changes NO_CHANGES = new RecoverySummary.Changes(
            0L, 0L, Duration.ZERO, Optional.empty(), Optional.empty(), List.of());

    private static final String RECOVER_EVERYTHING_REPLAYS = "replays";

    /** Where the recover sentences say the backup of the last clean save is kept. */
    private static final String BACKUP_BESIDE_IT =
            ", in a file beside it whose name starts with \"project.daw.recovered\".";

    @Test
    void theFourChoicesTheirButtonDataTheirResultsTheTitleAndTheHeaderAreUnchanged() throws Exception {
        RecoveryDialog dialog = open(checkpointCapture());

        List<ButtonType> buttonTypes = dialog.getDialogPane().getButtonTypes();
        assertThat(buttonTypes).as("three recovery options + Cancel").contains(
                dialog.recoverEverythingButtonType(), dialog.restoreCheckpointButtonType(),
                dialog.inspectEventsButtonType(), dialog.cancelButtonType());
        assertThat(dialog.recoverEverythingButtonType().getText()).isEqualTo("Recover everything");
        assertThat(dialog.recoverEverythingButtonType().getButtonData()).isEqualTo(ButtonBar.ButtonData.OK_DONE);
        assertThat(dialog.restoreCheckpointButtonType().getText()).isEqualTo("Restore checkpoint only");
        assertThat(dialog.restoreCheckpointButtonType().getButtonData()).isEqualTo(ButtonBar.ButtonData.OTHER);
        assertThat(dialog.inspectEventsButtonType().getText()).isEqualTo("Inspect events…");
        assertThat(dialog.inspectEventsButtonType().getButtonData()).isEqualTo(ButtonBar.ButtonData.HELP_2);
        assertThat(dialog.cancelButtonType().getText()).isEqualTo("Cancel");
        assertThat(dialog.cancelButtonType().getButtonData()).isEqualTo(ButtonBar.ButtonData.CANCEL_CLOSE);

        assertThat(dialog.getResultConverter().call(dialog.recoverEverythingButtonType()))
                .isEqualTo(RecoveryChoice.REPLAY_ALL);
        assertThat(dialog.getResultConverter().call(dialog.restoreCheckpointButtonType()))
                .isEqualTo(RecoveryChoice.CHECKPOINT_ONLY);
        assertThat(dialog.getResultConverter().call(dialog.inspectEventsButtonType()))
                .isEqualTo(RecoveryChoice.INSPECT_EVENTS);
        assertThat(dialog.getResultConverter().call(dialog.cancelButtonType()))
                .isEqualTo(RecoveryChoice.CANCEL);

        assertThat(onFx(dialog::getTitle)).isEqualTo("Recover unsaved work");
        assertThat(onFx(dialog::getHeaderText)).isEqualTo("This project did not close cleanly");
        assertThat(onFx(() -> dialog.getDialogPane().isExpanded())).as("the technical detail starts collapsed")
                .isFalse();
    }

    /** Each recovery the dialog describes, and whether the last clean save is newer than its recovery point. */
    static Stream<Arguments> recoveriesAndWhetherTheLastCleanSaveIsNewer() {
        return Stream.of(
                Arguments.of("a replay, project.daw newer", sessionStart(HeadRelation.HEAD_NEWER), true),
                Arguments.of("a replay, project.daw identical", sessionStart(HeadRelation.IDENTICAL), false),
                Arguments.of("a replay, project.daw older", sessionStart(HeadRelation.HEAD_OLDER), false),
                Arguments.of("a replay, project.daw missing", summary(new RecoverySummary.Replay(
                        BaseKind.NEWEST_CHECKPOINT, "checkpoints/checkpoint-003-20260321T140500.daw",
                        OptionalLong.of(3), CHECKPOINT_TIME, HeadRelation.MISSING, NO_CHANGES, Optional.empty()),
                        Optional.empty(), true, true), false),
                Arguments.of("an interrupted save promoted", new RecoverySummary(
                        new RecoverySummary.PromoteNewerHead(INTERRUPTED_SAVE_TIME, TEMP_NAME), Optional.of(HEAD_SAVED),
                        true, true, List.of("segment-000.bin"), 64L, 0L, List.of()), false),
                Arguments.of("a replay that cannot start", summary(new RecoverySummary.ReplayFails(
                        new RecoverySummary.BaseChanged(), NO_CHANGES, Optional.empty()), Optional.of(HEAD_SAVED),
                        true, true), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recoveriesAndWhetherTheLastCleanSaveIsNewer")
    void theDefaultButtonIsRestoreCheckpointOnlyExactlyWhenTheLastCleanSaveIsNewerThanTheRecoveryPoint(
            String situation, RecoverySummary summary, boolean lastCleanSaveIsNewer) throws Exception {
        RecoveryDialog dialog = open(summary);

        assertThat(onFx(() -> isDefault(dialog, dialog.restoreCheckpointButtonType())))
                .as("Restore checkpoint only is the default button").isEqualTo(lastCleanSaveIsNewer);
        assertThat(onFx(() -> isDefault(dialog, dialog.recoverEverythingButtonType())))
                .as("Recover everything is the default button").isEqualTo(!lastCleanSaveIsNewer);
        assertThat(dialog.recoverEverythingButtonType().getButtonData()).isEqualTo(ButtonBar.ButtonData.OK_DONE);
        assertThat(dialog.restoreCheckpointButtonType().getButtonData()).isEqualTo(ButtonBar.ButtonData.OTHER);
    }

    @Test
    void aVerifiedCheckpointCaptureNamesThatCheckpointAndOnlyTheChangeJournaledAfterIt() throws Exception {
        RecoveryDialog dialog = open(checkpointCapture());

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_BEFORE_CHECKPOINT) + " (project.daw)");
        assertThat(dialog.recoveryPointText()).isEqualTo("Checkpoint #1 · " + at(CHECKPOINT_TIME));
        assertThat(dialog.changesText()).isEqualTo("1 change");
        assertThat(dialog.lastEventText()).isEqualTo("ClipEvent.Moved · " + at(LAST_EVENT_TIME));
        assertThat(dialog.explainText()).isEqualTo(
                "Recover everything opens checkpoint #1 and keeps a backup of the last clean save" + BACKUP_BESIDE_IT
                        + " This version cannot rebuild journaled changes, so the change summarized under Inspect"
                        + " events… will not be in the recovered project."
                        + " Restore checkpoint only opens the last clean save and deletes the journal."
                        + " Checkpoint #1 is not deleted.");
        assertThat(dialog.technicalDetail()).containsExactly(
                new RecoveryDialog.Detail("Recovery point file:", "checkpoints/checkpoint-001-20260321T140500.daw"),
                new RecoveryDialog.Detail("Segments:", "segment-000.bin"),
                new RecoveryDialog.Detail("Journal size:", "4096 bytes"),
                new RecoveryDialog.Detail("Change types:", "ClipEvent.Moved"),
                new RecoveryDialog.Detail("Session records:", "$session-start, $checkpoint"));
        assertThat(renderedLabels(dialog)).as("the rows, in order, then the explanation, then the detail")
                .startsWith("Last clean save:", dialog.lastSaveText(),
                        "Recovery point:", dialog.recoveryPointText(),
                        "Changes after it:", dialog.changesText(),
                        "Last journaled event:", dialog.lastEventText(),
                        dialog.explainText(),
                        "Recovery point file:", "checkpoints/checkpoint-001-20260321T140500.daw");
        assertThat(String.join("\n", renderedLabels(dialog))).as("an unrelated newer checkpoint is never shown")
                .doesNotContain("#5").doesNotContain("checkpoint-005");
    }

    @Test
    void aSessionStartPointOlderThanALaterSaveLeadsTheExplanationWithTheWarning() throws Exception {
        RecoveryDialog dialog = open(sessionStart(HeadRelation.HEAD_NEWER));

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_SAVED) + " (project.daw)");
        assertThat(dialog.recoveryPointText())
                .isEqualTo("The project as it was when this session opened · " + at(SESSION_OPENED));
        assertThat(dialog.changesText()).as("3m10s rounds up to 4 min").isEqualTo("2 changes · ~4 min");
        assertThat(dialog.lastEventText()).isEqualTo("ClipEvent.Moved · " + at(LAST_EVENT_TIME));
        assertThat(dialog.explainText()).isEqualTo(
                "The last clean save was made after the recovery point: Recover everything replaces it with an"
                        + " older version of the project."
                        + " Recover everything opens the project as it was when this session opened and keeps a"
                        + " backup of the last clean save" + BACKUP_BESIDE_IT
                        + " This version cannot rebuild journaled changes, so the 2 changes summarized under Inspect"
                        + " events… will not be in the recovered project."
                        + " Restore checkpoint only opens the last clean save and deletes the journal.");
        assertThat(String.join("\n", renderedLabels(dialog))).as("an unrelated checkpoint is never shown")
                .doesNotContain("#9").doesNotContain("checkpoint-009");
        assertThat(detailValue(dialog, "Recovery point file:")).isEqualTo("journal/session-base.daw");
    }

    @Test
    void aLegacyJournalOnTheSavedHeadSaysBothChoicesOpenTheSameProject() throws Exception {
        RecoverySummary.Changes three = changes(3L, 0L, Duration.ofSeconds(50), "TrackEvent.Renamed",
                List.of("MixerEvent.GainChanged", "TrackEvent.Renamed"));
        RecoveryDialog dialog = open(summary(new RecoverySummary.Replay(BaseKind.SAVED_HEAD, "project.daw",
                OptionalLong.empty(), HEAD_SAVED, HeadRelation.IDENTICAL, three, Optional.empty()),
                Optional.of(HEAD_SAVED), true, true));

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_SAVED) + " (project.daw)");
        assertThat(dialog.recoveryPointText())
                .isEqualTo("The last clean save · " + at(HEAD_SAVED) + " (journal written by an older version)");
        assertThat(dialog.changesText()).isEqualTo("3 changes · ~1 min");
        assertThat(dialog.lastEventText()).isEqualTo("TrackEvent.Renamed · " + at(LAST_EVENT_TIME));
        assertThat(dialog.explainText()).isEqualTo(
                "The recovery point is identical to the last clean save, so both choices open the same project."
                        + " Recover everything opens the last clean save again and keeps a backup of it"
                        + BACKUP_BESIDE_IT
                        + " This version cannot rebuild journaled changes, so the 3 changes summarized under Inspect"
                        + " events… will not be in the recovered project."
                        + " Restore checkpoint only opens the last clean save and deletes the journal.");
        assertThat(detailValue(dialog, "Recovery point file:")).isEqualTo("project.daw");
    }

    @Test
    void aLegacyJournalWithoutAProjectFileRecoversTheNewestCheckpointAndRestoreCannotOpenIt() throws Exception {
        RecoverySummary.Changes two = changes(2L, 0L, Duration.ZERO, "ClipEvent.Moved",
                List.of("ClipEvent.Moved"));
        RecoveryDialog dialog = open(summary(new RecoverySummary.Replay(BaseKind.NEWEST_CHECKPOINT,
                "checkpoints/checkpoint-003-20260321T140500.daw", OptionalLong.of(3), CHECKPOINT_TIME,
                HeadRelation.MISSING, two, Optional.empty()), Optional.empty(), true, true));

        assertThat(dialog.lastSaveText()).isEqualTo("Missing (project.daw is not in the project folder)");
        assertThat(dialog.recoveryPointText())
                .isEqualTo("Newest checkpoint #3 · " + at(CHECKPOINT_TIME) + " (journal written by an older version)");
        assertThat(dialog.changesText()).isEqualTo("2 changes");
        assertThat(dialog.lastEventText()).isEqualTo("ClipEvent.Moved · " + at(LAST_EVENT_TIME));
        assertThat(dialog.explainText()).isEqualTo(
                "Recover everything opens checkpoint #3 and saves it as project.daw."
                        + " This version cannot rebuild journaled changes, so the 2 changes summarized under Inspect"
                        + " events… will not be in the recovered project."
                        + " Restore checkpoint only cannot open this project because project.daw is missing; the"
                        + " journal is kept if you choose it."
                        + " Checkpoint #3 is not deleted.");
    }

    @Test
    void aReplayThatCannotStartSaysWhyAndThatChoosingItKeepsTheJournal() throws Exception {
        RecoverySummary.Changes four = changes(4L, 0L, Duration.ofSeconds(30), "TrackEvent.Renamed",
                List.of("TrackEvent.Renamed"));
        RecoveryDialog dialog = open(summary(new RecoverySummary.ReplayFails(
                new RecoverySummary.SegmentDamaged("segment-001.bin"), four, Optional.empty()),
                Optional.of(HEAD_SAVED), true, true));

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_SAVED) + " (project.daw)");
        assertThat(dialog.recoveryPointText())
                .isEqualTo("Unavailable: journal file segment-001.bin is damaged before its end");
        assertThat(dialog.changesText()).isEqualTo("4 changes (cannot be replayed)");
        assertThat(dialog.lastEventText()).isEqualTo("TrackEvent.Renamed · " + at(LAST_EVENT_TIME));
        assertThat(dialog.explainText()).isEqualTo(
                "Recover everything cannot replay this journal: journal file segment-001.bin is damaged before its"
                        + " end. If you choose it, the journal is kept and the last clean save opens read-only."
                        + " Restore checkpoint only opens the last clean save and deletes the journal.");
        assertThat(detailValue(dialog, "Recovery point file:")).as("no file is advertised").isEqualTo("—");
        assertThat(String.join("\n", renderedLabels(dialog))).as("no checkpoint is advertised")
                .doesNotContain("Checkpoint #");
    }

    static Stream<Arguments> reasonsAReplayCannotStart() {
        return Stream.of(
                Arguments.of(new RecoverySummary.BaseMissing(),
                        "the copy of the project kept when this session opened is missing"),
                Arguments.of(new RecoverySummary.BaseChanged(),
                        "the copy of the project kept when this session opened has changed"),
                Arguments.of(new RecoverySummary.SegmentDamaged("segment-002.bin"),
                        "journal file segment-002.bin is damaged before its end"),
                Arguments.of(new RecoverySummary.NoBase(),
                        "there is no saved project or checkpoint to start from"));
    }

    @ParameterizedTest
    @MethodSource("reasonsAReplayCannotStart")
    void eachReasonAReplayCannotStartIsWordedFromTheBundleWithAndWithoutAProjectFile(
            RecoverySummary.CannotReplay reason, String words) throws Exception {
        RecoverySummary.Recovery fails = new RecoverySummary.ReplayFails(reason, NO_CHANGES, Optional.empty());

        RecoveryDialog withHead = open(summary(fails, Optional.of(HEAD_SAVED), true, true));
        assertThat(withHead.recoveryPointText()).isEqualTo("Unavailable: " + words);
        assertThat(withHead.changesText()).isEqualTo("0 changes (cannot be replayed)");
        assertThat(withHead.lastEventText()).isEqualTo("None after the recovery point");
        assertThat(withHead.explainText()).isEqualTo("Recover everything cannot replay this journal: " + words
                + ". If you choose it, the journal is kept and the last clean save opens read-only."
                + " Restore checkpoint only opens the last clean save and deletes the journal.");

        RecoveryDialog withoutHead = open(summary(fails, Optional.empty(), true, true));
        assertThat(withoutHead.explainText()).isEqualTo("Recover everything cannot replay this journal: " + words
                + ". If you choose it, the journal is kept."
                + " Restore checkpoint only cannot open this project because project.daw is missing; the journal"
                + " is kept if you choose it.");
    }

    /** The four temp-only situations, as the summary describes each: whether a journal is present and on. */
    static Stream<Arguments> interruptedSavesWithNoJournalDecisionOwed() {
        String keptByRestore = " Restore checkpoint only keeps the interrupted save in Snapshots, in a file whose name"
                + " contains \"interrupted-save\".";
        String recover = "Recover everything makes the interrupted save the project and keeps a backup of the last"
                + " clean save" + BACKUP_BESIDE_IT;
        return Stream.of(
                Arguments.of("no journal and no checkpoint", false, true, List.of(),
                        recover + " Restore checkpoint only opens the last clean save." + keptByRestore),
                Arguments.of("checkpoint-004 only", false, true, List.of(),
                        recover + " Restore checkpoint only opens the last clean save." + keptByRestore),
                Arguments.of("a sealed clean journal", true, true, List.of("segment-000.bin"),
                        recover + " Restore checkpoint only opens the last clean save and deletes the journal."
                                + keptByRestore),
                Arguments.of("an opted-out unsealed journal", true, false, List.of("segment-000.bin"),
                        recover + " Restore checkpoint only opens the last clean save and keeps the journal, because"
                                + " journaled persistence is off." + keptByRestore));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interruptedSavesWithNoJournalDecisionOwed")
    void anInterruptedSaveAloneIsDescribedAsTheSaveThatIsPromotedNeverAsEvents(String situation,
            boolean journalPresent, boolean journalEnabled, List<String> segments, String explanation)
            throws Exception {
        RecoverySummary summary = new RecoverySummary(
                new RecoverySummary.PromoteNewerHead(INTERRUPTED_SAVE_TIME, TEMP_NAME), Optional.of(HEAD_SAVED),
                journalEnabled, journalPresent, segments, journalPresent ? 2048L : 0L, 0L, List.of());
        RecoveryDialog dialog = open(summary);

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_SAVED) + " (project.daw)");
        assertThat(dialog.recoveryPointText()).isEqualTo("An interrupted save · written " + at(INTERRUPTED_SAVE_TIME));
        assertThat(dialog.changesText()).isEqualTo("None");
        assertThat(dialog.lastEventText()).isEqualTo("None after the recovery point");
        assertThat(dialog.explainText()).isEqualTo(explanation);
        assertThat(detailValue(dialog, "Recovery point file:")).isEqualTo(TEMP_NAME);
        assertThat(detailValue(dialog, "Change types:")).isEqualTo("—");
        assertThat(detailRow(dialog, "Journal:")).as("the journal row only when it is kept and not read")
                .isEqualTo(journalPresent && !journalEnabled
                        ? Optional.of("kept and not read, because journaled persistence is off")
                        : Optional.empty());
        assertThat(String.join("\n", renderedLabels(dialog))).doesNotContain("0 events", "1 event",
                "Atomic writer head", "No checkpoint found", "$session-seal", "Checkpoint #4");
    }

    @Test
    void anInterruptedSaveBesideAJournalIsKeptByEitherChoice() throws Exception {
        RecoverySummary.Changes one = changes(1L, 0L, Duration.ZERO, "MixerEvent.GainChanged",
                List.of("MixerEvent.GainChanged"));
        RecoveryDialog dialog = open(summary(new RecoverySummary.Replay(BaseKind.SESSION_START,
                "journal/session-base.daw", OptionalLong.empty(), SESSION_OPENED, HeadRelation.IDENTICAL, one,
                Optional.of(new RecoverySummary.InterruptedSave(INTERRUPTED_SAVE_TIME, TEMP_NAME))),
                Optional.of(HEAD_SAVED), true, true));

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_SAVED) + " (project.daw)");
        assertThat(dialog.recoveryPointText())
                .isEqualTo("The project as it was when this session opened · " + at(SESSION_OPENED));
        assertThat(dialog.changesText()).isEqualTo("1 change");
        assertThat(dialog.lastEventText()).isEqualTo("MixerEvent.GainChanged · " + at(LAST_EVENT_TIME));
        assertThat(dialog.explainText()).isEqualTo(
                "The recovery point is identical to the last clean save, so both choices open the same project."
                        + " Recover everything opens the project as it was when this session opened and keeps a"
                        + " backup of the last clean save" + BACKUP_BESIDE_IT
                        + " This version cannot rebuild journaled changes, so the change summarized under Inspect"
                        + " events… will not be in the recovered project."
                        + " Restore checkpoint only opens the last clean save and deletes the journal."
                        + " An interrupted save written " + at(INTERRUPTED_SAVE_TIME) + " is newer than the last"
                        + " clean save; either choice keeps it in Snapshots, in a file whose name contains"
                        + " \"interrupted-save\".");
    }

    @Test
    void aReplayThatCannotStartBesideANewerInterruptedSaveSaysWhenItWasWrittenAndWhatEachChoiceDoesWithIt()
            throws Exception {
        RecoveryDialog dialog = open(summary(new RecoverySummary.ReplayFails(new RecoverySummary.BaseChanged(),
                NO_CHANGES, Optional.of(new RecoverySummary.InterruptedSave(INTERRUPTED_SAVE_TIME, TEMP_NAME))),
                Optional.of(HEAD_SAVED), true, true));

        assertThat(dialog.explainText()).isEqualTo(
                "Recover everything cannot replay this journal: the copy of the project kept when this session"
                        + " opened has changed. If you choose it, the journal is kept and the last clean save opens"
                        + " read-only."
                        + " Restore checkpoint only opens the last clean save and deletes the journal."
                        + " An interrupted save written " + at(INTERRUPTED_SAVE_TIME) + " is newer than the last"
                        + " clean save; Restore checkpoint only keeps it in Snapshots, in a file whose name contains"
                        + " \"interrupted-save\", and Recover everything leaves it in place.");
    }

    @Test
    void anUnsealedJournalWithNothingAfterItsIdenticalCaptureHasNoChangesAndNamesNoSessionRecord()
            throws Exception {
        RecoveryDialog dialog = open(summary(new RecoverySummary.Replay(BaseKind.CHECKPOINT_CAPTURE,
                "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), CHECKPOINT_TIME,
                HeadRelation.IDENTICAL, NO_CHANGES, Optional.empty()), Optional.of(HEAD_SAVED), true, true));

        assertThat(dialog.lastSaveText()).isEqualTo(at(HEAD_SAVED) + " (project.daw)");
        assertThat(dialog.recoveryPointText()).isEqualTo("Checkpoint #1 · " + at(CHECKPOINT_TIME));
        assertThat(dialog.changesText()).isEqualTo("None");
        assertThat(dialog.lastEventText()).isEqualTo("None after the recovery point");
        assertThat(dialog.explainText()).isEqualTo(
                "The recovery point is identical to the last clean save, so both choices open the same project."
                        + " Recover everything opens checkpoint #1 and keeps a backup of the last clean save"
                        + BACKUP_BESIDE_IT
                        + " Restore checkpoint only opens the last clean save and deletes the journal."
                        + " Checkpoint #1 is not deleted.");
        assertThat(detailValue(dialog, "Session records:")).isEqualTo("$session-start, $checkpoint");
    }

    @Test
    void tornBytesAfterASealAreShownInTheTechnicalDetailOnlyWhenThereAreSome() throws Exception {
        RecoverySummary.Replay nothingAfter = new RecoverySummary.Replay(BaseKind.CHECKPOINT_CAPTURE,
                "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), CHECKPOINT_TIME,
                HeadRelation.IDENTICAL, NO_CHANGES, Optional.empty());
        RecoverySummary torn = new RecoverySummary(nothingAfter, Optional.of(HEAD_SAVED), true, true,
                List.of("segment-000.bin"), 4099L, 3L, List.of("$session-start", "$checkpoint", "$session-seal"));

        RecoveryDialog dialog = open(torn);
        assertThat(dialog.changesText()).isEqualTo("None");
        assertThat(dialog.technicalDetail()).containsExactly(
                new RecoveryDialog.Detail("Recovery point file:", "checkpoints/checkpoint-001-20260321T140500.daw"),
                new RecoveryDialog.Detail("Segments:", "segment-000.bin"),
                new RecoveryDialog.Detail("Journal size:", "4099 bytes"),
                new RecoveryDialog.Detail("Unreadable bytes at the end:", "3 bytes"),
                new RecoveryDialog.Detail("Change types:", "—"),
                new RecoveryDialog.Detail("Session records:", "$session-start, $checkpoint, $session-seal"));
        assertThat(detailRow(open(checkpointCapture()), "Unreadable bytes at the end:")).isEmpty();
    }

    @Test
    void changesTheReplayRebuildsAreNeverSaidToBeMissingFromTheRecoveredProject() throws Exception {
        RecoverySummary.Changes allRebuilt = changes(2L, 2L, Duration.ZERO, "ClipEvent.Moved",
                List.of("ClipEvent.Moved"));
        RecoveryDialog dialog = open(summary(new RecoverySummary.Replay(BaseKind.CHECKPOINT_CAPTURE,
                "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), CHECKPOINT_TIME,
                HeadRelation.IDENTICAL, allRebuilt, Optional.empty()), Optional.of(HEAD_SAVED), true, true));

        assertThat(dialog.explainText()).as("no notRebuilt sentence, and a rebuilt change makes the two differ")
                .isEqualTo("Recover everything opens checkpoint #1 and keeps a backup of the last clean save"
                        + BACKUP_BESIDE_IT
                        + " Restore checkpoint only opens the last clean save and deletes the journal."
                        + " Checkpoint #1 is not deleted.");
    }

    @Test
    void aLargeChangeCountIsShownWithoutGrouping() throws Exception {
        RecoverySummary.Changes many = changes(1200L, 0L, Duration.ZERO, "ClipEvent.Moved",
                List.of("ClipEvent.Moved"));
        RecoveryDialog dialog = open(summary(new RecoverySummary.Replay(BaseKind.CHECKPOINT_CAPTURE,
                "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), CHECKPOINT_TIME,
                HeadRelation.HEAD_OLDER, many, Optional.empty()), Optional.of(HEAD_BEFORE_CHECKPOINT), true, true));

        assertThat(dialog.changesText()).isEqualTo("1200 changes");
        assertThat(dialog.explainText()).contains("so the 1200 changes summarized under Inspect events…");
    }

    static Stream<RecoverySummary> everySituation() {
        RecoverySummary.Changes four = changes(4L, 0L, Duration.ZERO, "TrackEvent.Renamed",
                List.of("TrackEvent.Renamed"));
        return Stream.of(
                checkpointCapture(),
                sessionStart(HeadRelation.HEAD_NEWER),
                sessionStart(HeadRelation.HEAD_OLDER),
                summary(new RecoverySummary.Replay(BaseKind.SAVED_HEAD, "project.daw", OptionalLong.empty(),
                        HEAD_SAVED, HeadRelation.IDENTICAL, four, Optional.empty()), Optional.of(HEAD_SAVED), true, true),
                summary(new RecoverySummary.Replay(BaseKind.NEWEST_CHECKPOINT,
                        "checkpoints/checkpoint-003-20260321T140500.daw", OptionalLong.of(3), CHECKPOINT_TIME,
                        HeadRelation.MISSING, four, Optional.empty()), Optional.empty(), true, true),
                summary(new RecoverySummary.ReplayFails(new RecoverySummary.BaseChanged(), four,
                        Optional.of(new RecoverySummary.InterruptedSave(INTERRUPTED_SAVE_TIME, TEMP_NAME))),
                        Optional.of(HEAD_SAVED), true, true),
                new RecoverySummary(new RecoverySummary.PromoteNewerHead(INTERRUPTED_SAVE_TIME, TEMP_NAME),
                        Optional.of(HEAD_SAVED), true, true, List.of("segment-000.bin"), 64L, 0L, List.of()),
                summary(new RecoverySummary.Replay(BaseKind.CHECKPOINT_CAPTURE,
                        "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), CHECKPOINT_TIME,
                        HeadRelation.IDENTICAL, NO_CHANGES, Optional.empty()), Optional.of(HEAD_SAVED), true, true));
    }

    @ParameterizedTest
    @MethodSource("everySituation")
    void noSentenceSaysRecoverEverythingReplaysAndNoSessionRecordReachesTheMainBody(RecoverySummary summary)
            throws Exception {
        RecoveryDialog dialog = open(summary);
        List<String> mainBody = List.of(dialog.lastSaveText(), dialog.recoveryPointText(), dialog.changesText(),
                dialog.lastEventText(), dialog.explainText());

        assertThat(dialog.explainText()).doesNotContain(RECOVER_EVERYTHING_REPLAYS);
        assertThat(mainBody).noneMatch(text -> text.contains("$"));
        assertThat(detailValue(dialog, "Change types:")).doesNotContain("$");
        assertThat(renderedLabels(dialog)).as("every figure and sentence is shown, and no key is echoed")
                .contains(mainBody.toArray(String[]::new))
                .noneMatch(text -> text.startsWith("recoveryDialog."));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /** (a): checkpoint #1 certified by its marker, saved after project.daw, one change after its capture. */
    private static RecoverySummary checkpointCapture() {
        RecoverySummary.Changes one = changes(1L, 0L, Duration.ZERO, "ClipEvent.Moved", List.of("ClipEvent.Moved"));
        return summary(new RecoverySummary.Replay(BaseKind.CHECKPOINT_CAPTURE,
                "checkpoints/checkpoint-001-20260321T140500.daw", OptionalLong.of(1), CHECKPOINT_TIME,
                HeadRelation.HEAD_OLDER, one, Optional.empty()), Optional.of(HEAD_BEFORE_CHECKPOINT), true, true);
    }

    /** (b): the session-start copy, two changes over 3m10s, project.daw related as {@code relation}. */
    private static RecoverySummary sessionStart(HeadRelation relation) {
        RecoverySummary.Changes two = changes(2L, 0L, Duration.ofMinutes(3).plusSeconds(10), "ClipEvent.Moved",
                List.of("MixerEvent.GainChanged", "ClipEvent.Moved"));
        return summary(new RecoverySummary.Replay(BaseKind.SESSION_START, "journal/session-base.daw",
                OptionalLong.empty(), SESSION_OPENED, relation, two, Optional.empty()), Optional.of(HEAD_SAVED), true,
                true);
    }

    private static RecoverySummary.Changes changes(long count, long rebuilt, Duration span, String lastType,
                                                   List<String> types) {
        return new RecoverySummary.Changes(count, rebuilt, span, Optional.of(lastType), Optional.of(LAST_EVENT_TIME),
                types);
    }

    /** A summary of {@code recovery} with one segment and the session records a capture leaves. */
    private static RecoverySummary summary(RecoverySummary.Recovery recovery, Optional<Instant> lastCleanSave,
                                           boolean journalEnabled, boolean journalPresent) {
        return new RecoverySummary(recovery, lastCleanSave, journalEnabled, journalPresent,
                List.of("segment-000.bin"), 4096L, 0L, List.of("$session-start", "$checkpoint"));
    }

    private static String at(Instant instant) {
        return CLOCK.format(instant);
    }

    private static String detailValue(RecoveryDialog dialog, String label) {
        return detailRow(dialog, label).orElseThrow(() -> new AssertionError("no detail row " + label));
    }

    private static Optional<String> detailRow(RecoveryDialog dialog, String label) {
        return dialog.technicalDetail().stream().filter(detail -> detail.label().equals(label))
                .map(RecoveryDialog.Detail::value).findFirst();
    }

    /**
     * The text of every label the dialog shows, depth first: its content (the
     * rows and the explanation), then its expandable technical detail. Read
     * on the FX thread.
     */
    private static List<String> renderedLabels(RecoveryDialog dialog) throws Exception {
        return onFx(() -> {
            List<String> texts = new ArrayList<>();
            collectLabels(dialog.getDialogPane().getContent(), texts);
            collectLabels(dialog.getDialogPane().getExpandableContent(), texts);
            return texts;
        });
    }

    private static void collectLabels(Node node, List<String> texts) {
        if (node instanceof Labeled labeled) {
            texts.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabels(child, texts);
            }
        }
    }

    private static RecoveryDialog open(RecoverySummary summary) throws Exception {
        return onFx(() -> new RecoveryDialog(summary));
    }

    /** Whether the dialog's rendered button for {@code type} is its default (Enter) button. FX thread. */
    private static boolean isDefault(RecoveryDialog dialog, ButtonType type) {
        return ((Button) dialog.getDialogPane().lookupButton(type)).isDefaultButton();
    }

    private static <T> T onFx(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.get());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        return result.get();
    }
}
