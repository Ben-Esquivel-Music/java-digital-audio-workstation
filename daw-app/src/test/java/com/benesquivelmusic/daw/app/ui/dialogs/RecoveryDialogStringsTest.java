package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ResourceBundle;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recovery dialog's, Inspect events' and the interrupted-save and
 * not-rebuilt journal-replay announcements' words are root-bundle keys
 * holding exactly their ruled text:
 * each key is present in the {@link Locale#ROOT} bundle (so
 * {@link DawgDialog#msg}'s key-echo fallback never stands in for one), no
 * value holds an apostrophe (which {@code MessageFormat} would swallow), and
 * the dialog's pure builders — toolkit-free — format counts without grouping
 * and times with the {@code yyyy-MM-dd HH:mm} pattern in the zone they are
 * given.
 */
class RecoveryDialogStringsTest {

    private static final ResourceBundle ROOT = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    /** Every key the recovery wording names, with its exact root value. */
    private static final Map<String, String> RULED = Map.ofEntries(
            entry("recoveryDialog.label.lastCleanSave", "Last clean save:"),
            entry("recoveryDialog.label.recoveryPoint", "Recovery point:"),
            entry("recoveryDialog.label.changes", "Changes after it:"),
            entry("recoveryDialog.label.lastEvent", "Last journaled event:"),
            entry("recoveryDialog.lastCleanSave", "{0} (project.daw)"),
            entry("recoveryDialog.lastCleanSave.missing", "Missing (project.daw is not in the project folder)"),
            entry("recoveryDialog.point.checkpoint", "Checkpoint #{0} · {1}"),
            entry("recoveryDialog.point.sessionStart", "The project as it was when this session opened · {0}"),
            entry("recoveryDialog.point.savedHead", "The last clean save · {0} (journal written by an older version)"),
            entry("recoveryDialog.point.newestCheckpoint",
                    "Newest checkpoint #{0} · {1} (journal written by an older version)"),
            entry("recoveryDialog.point.interruptedSave", "An interrupted save · written {0}"),
            entry("recoveryDialog.point.unavailable", "Unavailable: {0}"),
            entry("recoveryDialog.changes", "{0,choice,0#{1} changes|1#{1} change|1<{1} changes}"),
            entry("recoveryDialog.changes.span", "{0,choice,0#{1} changes|1#{1} change|1<{1} changes} · ~{2} min"),
            entry("recoveryDialog.changes.none", "None"),
            entry("recoveryDialog.changes.cannotReplay",
                    "{0,choice,0#{1} changes|1#{1} change|1<{1} changes} (cannot be replayed)"),
            entry("recoveryDialog.lastEvent", "{0} · {1}"),
            entry("recoveryDialog.lastEvent.none", "None after the recovery point"),
            entry("recoveryDialog.reason.baseMissing", "the copy of the project kept when this session opened is missing"),
            entry("recoveryDialog.reason.baseChanged", "the copy of the project kept when this session opened has changed"),
            entry("recoveryDialog.reason.segmentDamaged", "journal file {0} is damaged before its end"),
            entry("recoveryDialog.reason.noBase", "there is no saved project or checkpoint to start from"),
            entry("recoveryDialog.phrase.checkpoint", "checkpoint #{0}"),
            entry("recoveryDialog.phrase.sessionStart", "the project as it was when this session opened"),
            entry("recoveryDialog.explain.headNewer", "The last clean save was made after the recovery point:"
                    + " Recover everything replaces it with an older version of the project."),
            entry("recoveryDialog.explain.sameProject",
                    "The recovery point is identical to the last clean save, so both choices open the same project."),
            entry("recoveryDialog.explain.cannotReplay", "Recover everything cannot replay this journal: {0}."
                    + " If you choose it, the journal is kept and the last clean save opens read-only."),
            entry("recoveryDialog.explain.cannotReplayNoHead",
                    "Recover everything cannot replay this journal: {0}. If you choose it, the journal is kept."),
            entry("recoveryDialog.explain.recoverInterruptedSave", "Recover everything makes the interrupted save the"
                    + " project and keeps a backup of the last clean save, in a file beside it whose name starts with"
                    + " \"project.daw.recovered\"."),
            entry("recoveryDialog.explain.recoverSavedHead", "Recover everything opens the last clean save again and"
                    + " keeps a backup of it, in a file beside it whose name starts with \"project.daw.recovered\"."),
            entry("recoveryDialog.explain.recoverNoHead", "Recover everything opens {0} and saves it as project.daw."),
            entry("recoveryDialog.explain.recover", "Recover everything opens {0} and keeps a backup of the last clean"
                    + " save, in a file beside it whose name starts with \"project.daw.recovered\"."),
            entry("recoveryDialog.explain.notRebuilt", "This version cannot rebuild journaled changes, so"
                    + " {0,choice,0#no changes|1#the change|1<the {1} changes} summarized under Inspect events… will"
                    + " not be in the recovered project."),
            entry("recoveryDialog.explain.restoreNoHead", "Restore checkpoint only cannot open this project because"
                    + " project.daw is missing; the journal is kept if you choose it."),
            entry("recoveryDialog.explain.restoreKeepsJournal", "Restore checkpoint only opens the last clean save and"
                    + " keeps the journal, because journaled persistence is off."),
            entry("recoveryDialog.explain.restoreDeletesJournal",
                    "Restore checkpoint only opens the last clean save and deletes the journal."),
            entry("recoveryDialog.explain.restore", "Restore checkpoint only opens the last clean save."),
            entry("recoveryDialog.explain.checkpointKept", "Checkpoint #{0} is not deleted."),
            entry("recoveryDialog.explain.interruptedSaveKeptByRestore", "Restore checkpoint only keeps the"
                    + " interrupted save in Snapshots, in a file whose name contains \"interrupted-save\"."),
            entry("recoveryDialog.explain.interruptedSaveKeptByBoth", "An interrupted save written {0} is newer than"
                    + " the last clean save; either choice keeps it in Snapshots, in a file whose name contains"
                    + " \"interrupted-save\"."),
            entry("recoveryDialog.explain.interruptedSaveKeptAside", "An interrupted save written {0} is newer than"
                    + " the last clean save; Restore checkpoint only keeps it in Snapshots, in a file whose name"
                    + " contains \"interrupted-save\", and Recover everything leaves it in place."),
            entry("recoveryDialog.detail.recoveryPointFile", "Recovery point file:"),
            entry("recoveryDialog.detail.segments", "Segments:"),
            entry("recoveryDialog.detail.journalSize", "Journal size:"),
            entry("recoveryDialog.detail.bytes", "{0,choice,0#{1} bytes|1#{1} byte|1<{1} bytes}"),
            entry("recoveryDialog.detail.unreadable", "Unreadable bytes at the end:"),
            entry("recoveryDialog.detail.changeTypes", "Change types:"),
            entry("recoveryDialog.detail.sessionRecords", "Session records:"),
            entry("recoveryDialog.detail.journal", "Journal:"),
            entry("recoveryDialog.detail.journalNotRead", "kept and not read, because journaled persistence is off"),
            entry("inspectEvents.summary", "{0,choice,0#No changes|1#{1} change|1<{1} changes} journaled after the"
                    + " recovery point; the journal has {2,choice,0#no segment files|1#{3} segment file|1<{3} segment"
                    + " files}."),
            entry("inspectEvents.cannotReplay", "{0,choice,0#No changes|1#{1} change|1<{1} changes} journaled; this"
                    + " journal cannot be replayed: {2}."),
            entry("inspectEvents.interruptedSave", "No journaled changes are part of this recovery: the interrupted"
                    + " save written {0} is a complete, newer copy of the project."),
            entry("inspectEvents.changeTypes", "Change types:"),
            entry("inspectEvents.none", "(none)"),
            entry("projectOpen.interruptedSave", "Recovered an interrupted save"),
            entry("projectOpen.journal.notRebuilt", "Recovered the project from its recovery point;"
                    + " {0,choice,0#{1} journaled changes after it|1#the journaled change after it"
                    + "|1<the {1} journaled changes after it} could not be rebuilt"));

    @Test
    void everyRecoveryKeyIsInTheRootBundleWithExactlyItsRuledTextNeverTheKeyEcho() {
        assertThat(RULED).hasSize(57);
        RULED.forEach((key, value) -> {
            assertThat(ROOT.containsKey(key)).as("%s is in the root bundle", key).isTrue();
            assertThat(DawgDialog.msg(key)).as("%s resolves, not to its key", key).isNotEqualTo(key).isEqualTo(value);
        });
    }

    @Test
    void noRecoveryValueHoldsAnApostrophe() {
        assertThat(RULED.keySet()).allSatisfy(key ->
                assertThat(ROOT.getString(key)).as(key).doesNotContain("'").doesNotContain("’"));
    }

    @Test
    void theBuildersFormatTimesWithTheHousePatternInTheZoneTheyAreGivenAndCountsWithoutGrouping() {
        DateTimeFormatter utc = RecoveryDialog.clock(ZoneOffset.UTC);
        Instant saved = Instant.parse("2026-03-21T14:05:59Z");
        RecoverySummary.Changes many = new RecoverySummary.Changes(1200L, 0L, Duration.ofSeconds(61),
                Optional.of("ClipEvent.Moved"), Optional.of(saved), List.of("ClipEvent.Moved"));
        RecoverySummary summary = new RecoverySummary(new RecoverySummary.Replay(
                RecoverySummary.BaseKind.CHECKPOINT_CAPTURE, "checkpoints/checkpoint-1500-20260321T140500.daw",
                OptionalLong.of(1500), saved, RecoverySummary.HeadRelation.HEAD_OLDER, many, Optional.empty()),
                Optional.of(Instant.parse("2026-03-21T09:00:00Z")), true, true, List.of("segment-000.bin"),
                123456L, 0L, List.of());

        assertThat(RecoveryDialog.CLOCK_PATTERN).isEqualTo("yyyy-MM-dd HH:mm");
        assertThat(RecoveryDialog.buildLastSaveText(summary, utc)).isEqualTo("2026-03-21 09:00 (project.daw)");
        assertThat(RecoveryDialog.buildRecoveryPointText(summary, utc)).isEqualTo("Checkpoint #1500 · 2026-03-21 14:05");
        assertThat(RecoveryDialog.buildChangesText(summary)).isEqualTo("1200 changes · ~2 min");
        assertThat(RecoveryDialog.buildLastEventText(summary, utc)).isEqualTo("ClipEvent.Moved · 2026-03-21 14:05");
        assertThat(RecoveryDialog.buildExplainText(summary, utc))
                .contains("so the 1200 changes summarized under Inspect events…")
                .endsWith("Checkpoint #1500 is not deleted.");
        assertThat(RecoveryDialog.buildTechnicalDetail(summary))
                .contains(new RecoveryDialog.Detail("Journal size:", "123456 bytes"));
    }

    @Test
    void aByteCountChoosesItsPluralFromTheCount() {
        RecoverySummary.Changes none = new RecoverySummary.Changes(0L, 0L, Duration.ZERO, Optional.empty(),
                Optional.empty(), List.of());
        RecoverySummary torn = new RecoverySummary(new RecoverySummary.Replay(
                RecoverySummary.BaseKind.SESSION_START, "journal/session-base.daw", OptionalLong.empty(),
                Instant.parse("2026-03-21T13:50:00Z"), RecoverySummary.HeadRelation.IDENTICAL, none, Optional.empty()),
                Optional.of(Instant.parse("2026-03-21T13:50:00Z")), true, true, List.of("segment-000.bin"),
                4096L, 1L, List.of("$session-start"));

        assertThat(RecoveryDialog.buildTechnicalDetail(torn)).contains(
                new RecoveryDialog.Detail("Journal size:", "4096 bytes"),
                new RecoveryDialog.Detail("Unreadable bytes at the end:", "1 byte"));
    }
}
