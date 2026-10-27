package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decisions D8, D14 and D24 — the words of an open's one
 * announcement: plain when nothing is missing; otherwise qualified by the
 * distinct missing assets and the clips they silence (left out when none
 * is), then the assets shorter than their clips, singular and plural right,
 * a WARNING (an ERROR door stays an ERROR) with {@code Review…} when the
 * report has rows; the archive door counts the union of its own list and
 * the open's, each asset once. The report's summary and the run's follow-up
 * use the same clauses.
 *
 * <p>Story 330 (studio-engineer ruling R4) — the saved items the open could
 * not honour as written qualify the same sentence, after the asset clauses:
 * one clause per kind of damage in a fixed order, counts only, singular and
 * plural right, absent at zero, never offering {@code Review…}; the archive
 * and journal-replay doors announce the items their own read found instead
 * of the reopen's.</p>
 */
class OpenAnnouncementTest {

    private static final Path SONG = Path.of("projects", "Song").toAbsolutePath();

    @Test
    void aCleanOpenIsThePlainSuccessWithNothingToReview() {
        OpenAnnouncement announcement = OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY,
                List.of());

        assertThat(announcement).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                "Opened project: Song", false));
    }

    @Test
    void missingAssetsQualifyTheOpenCountingAssetsNotClipsWithBothPluralsRight() {
        MissingAssetsReport one = new MissingAssetsReport(List.of(row("a.wav", "1")), List.of());
        MissingAssetsReport splitHalves = new MissingAssetsReport(List.of(row("a.wav", "1", "2")), List.of());
        MissingAssetsReport two = new MissingAssetsReport(List.of(row("a.wav", "1", "2"), row("b.wav", "3")),
                List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, one, List.of())).isEqualTo(new OpenAnnouncement(
                NotificationLevel.WARNING, "Opened project: Song — 1 missing asset (1 clip silent)", true));
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, splitHalves, List.of()).message())
                .isEqualTo("Opened project: Song — 1 missing asset (2 clips silent)");
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, two, List.of()).message())
                .isEqualTo("Opened project: Song — 2 missing assets (3 clips silent)");
    }

    @Test
    void everyDoorKeepsItsOwnWordsAndAFailureDoorKeepsItsError() {
        MissingAssetsReport one = new MissingAssetsReport(List.of(row("a.wav", "1")), List.of());

        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(1, 0, List.of()), SONG, MissingAssetsReport.EMPTY,
                List.of())).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                        "Recovered 1 change from the journal", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(1200, 0, List.of()), SONG, MissingAssetsReport.EMPTY,
                List.of()).message())
                .as("a count is shown without grouping, as before").isEqualTo("Recovered 1200 changes from the journal");
        assertThat(OpenAnnouncement.of(new OpenDoor.SnapshotRestore("Take 3's comp"), SONG, MissingAssetsReport.EMPTY,
                List.of())).as("an apostrophe in an argument is kept").isEqualTo(new OpenAnnouncement(
                        NotificationLevel.INFO, "Restored snapshot: Take 3's comp", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.FailedReplayFallback("bad {frame}"), SONG, one, List.of()))
                .isEqualTo(new OpenAnnouncement(NotificationLevel.ERROR, "Recovery failed (bad {frame});"
                        + " opened the last clean save instead — 1 missing asset (1 clip silent)", true));
        assertThat(OpenAnnouncement.of(new OpenDoor.RecoveredWriteFailed("disk full"), SONG,
                MissingAssetsReport.EMPTY, List.of())).isEqualTo(new OpenAnnouncement(NotificationLevel.ERROR,
                "Could not write recovered project: disk full; opened the last clean save", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.MigrationRollback(7), SONG, MissingAssetsReport.EMPTY,
                List.of())).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                        "Restored on-disk project file to v7 (in-memory migrations were re-applied)", false));
    }

    @Test
    void theArchiveDoorCountsTheUnionOfItsOwnListAndTheOpensEachAssetOnce() {
        Path destination = Path.of("restored", "Song-20261020").toAbsolutePath();
        String inDestination = destination.resolve("assets").resolve("0a_kick.wav").toString();
        MissingAssetsReport open = new MissingAssetsReport(List.of(new MissingAsset(
                new AssetReference.SingleFile(inDestination), MissingAssetReason.NOT_FOUND, "not found",
                List.of(new MissingAsset.Holder("1", "Drums", "Kick")))), List.of());

        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of(), List.of()), destination,
                MissingAssetsReport.EMPTY, List.of())).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                "Restored archive: Song", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song",
                List.of("assets/0a_kick.wav", "assets/0b_strings.sf2", "assets/0b_strings.sf2"), List.of()),
                destination, open, List.of()))
                .as("the kick, both lists name it; the SoundFont only the archive does, twice")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored archive: Song (2 missing assets, 1 clip silent)", true));
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of("assets/0b_strings.sf2"),
                List.of()), destination, MissingAssetsReport.EMPTY, List.of()))
                .as("nothing of the open's to review: no Review")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored archive: Song (1 missing asset)", false));
    }

    @Test
    void aFileShorterThanItsClipsIsNeitherMissingNorSilent() {
        MissingAssetsReport shorter = new MissingAssetsReport(List.of(shorterRow("long.wav", "1", "2")), List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, shorter, List.of())).isEqualTo(new OpenAnnouncement(
                NotificationLevel.WARNING, "Opened project: Song — 1 asset shorter than its clips", true));
        assertThat(OpenAnnouncement.summary(shorter)).isEqualTo("1 asset shorter than its clips");
        assertThat(OpenAnnouncement.followUp(shorter)).contains("1 asset shorter than its clips");
        assertThat(OpenAnnouncement.followUp(new MissingAssetsReport(List.of(shorterRow("a.wav", "1"),
                shorterRow("b.wav", "2")), List.of()))).contains("2 assets shorter than their clips");
    }

    @Test
    void missingAndShorterAssetsAreTwoClausesMissingFirst() {
        MissingAssetsReport both = new MissingAssetsReport(List.of(row("a.wav", "1", "2"), row("b.wav", "3"),
                shorterRow("long.wav", "4")), List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, both, List.of())).isEqualTo(new OpenAnnouncement(
                NotificationLevel.WARNING,
                "Opened project: Song — 2 missing assets (3 clips silent), 1 asset shorter than its clips", true));
        assertThat(OpenAnnouncement.of(new OpenDoor.FailedReplayFallback("bad frame"), SONG, both, List.of())
                .level()).as("an error door stays an error").isEqualTo(NotificationLevel.ERROR);
        assertThat(OpenAnnouncement.summary(both))
                .isEqualTo("2 missing assets (3 clips silent), 1 asset shorter than its clips");
        assertThat(OpenAnnouncement.followUp(both))
                .contains("2 assets could not be loaded as saved; 1 asset shorter than its clips");
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of(), List.of()), SONG, both,
                List.of()).message())
                .as("the archive door puts every clause in its parentheses")
                .isEqualTo("Restored archive: Song (2 missing assets, 3 clips silent, 1 asset shorter than its clips)");
    }

    @Test
    void aMissingAssetThatSilencesNoClipSaysNothingOfSilentClips() {
        MissingAssetsReport report = new MissingAssetsReport(List.of(silencingNoClip("a.wav")), List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, report, List.of()).message())
                .isEqualTo("Opened project: Song — 1 missing asset");
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of(), List.of()), SONG, report,
                List.of()).message())
                .isEqualTo("Restored archive: Song (1 missing asset)");
        assertThat(OpenAnnouncement.summary(report)).isEqualTo("1 missing asset");
        assertThat(OpenAnnouncement.followUp(MissingAssetsReport.EMPTY)).as("nothing to follow up on").isEmpty();
    }

    // ── story 330: the saved items the open could not honour as written ──

    @ParameterizedTest
    @EnumSource(UnreadProjectItem.Kind.class)
    void everyUnreadKindHasItsOneAndItsManyFormAndNeverShowsAKey(UnreadProjectItem.Kind kind) {
        String one = switch (kind) {
            case MIDI_NOTE_SKIPPED -> "1 MIDI note skipped";
            case CC_LANE_SKIPPED, CC_EVENT_SKIPPED -> "1 CC event skipped";
            case MIDI_EFFECT_SETTING_ADJUSTED -> "1 arpeggiator setting adjusted";
            case MIDI_EFFECT_NOT_RECOGNIZED -> "1 MIDI effect not recognized (kept as saved)";
            case MIXER_REFERENCE_REMOVED -> "1 mixer assignment removed";
        };
        String two = switch (kind) {
            case MIDI_NOTE_SKIPPED -> "2 MIDI notes skipped";
            case CC_LANE_SKIPPED, CC_EVENT_SKIPPED -> "2 CC events skipped";
            case MIDI_EFFECT_SETTING_ADJUSTED -> "2 arpeggiator settings adjusted";
            case MIDI_EFFECT_NOT_RECOGNIZED -> "2 MIDI effects not recognized (kept as saved)";
            case MIXER_REFERENCE_REMOVED -> "2 mixer assignments removed";
        };

        OpenAnnouncement single = OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY,
                List.of(item(kind)));
        OpenAnnouncement plural = OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY,
                List.of(item(kind), item(kind)));

        assertThat(single).isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                "Opened project: Song — " + one, false));
        assertThat(plural.message()).isEqualTo("Opened project: Song — " + two);
        assertThat(List.of(single.message(), plural.message()))
                .as("every kind has its key in the bundle").noneMatch(message -> message.contains("projectOpen."));
    }

    @Test
    void aSkippedLaneCountsAsItsEventsInTheOneCcClause() {
        List<UnreadProjectItem> lanesAndEvents = List.of(
                item(UnreadProjectItem.Kind.CC_LANE_SKIPPED, 3),
                item(UnreadProjectItem.Kind.CC_EVENT_SKIPPED),
                item(UnreadProjectItem.Kind.CC_LANE_SKIPPED, 1));

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY, lanesAndEvents).message())
                .isEqualTo("Opened project: Song — 5 CC events skipped");
    }

    @Test
    void aSkippedLaneSavedWithNoEventsCountsAsOneCcEvent() {
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY,
                List.of(item(UnreadProjectItem.Kind.CC_LANE_SKIPPED, 0))).message())
                .isEqualTo("Opened project: Song — 1 CC event skipped");
    }

    @Test
    void theUnreadClausesKeepAFixedOrderAndAreAbsentAtZero() {
        List<UnreadProjectItem> reversed = List.of(
                item(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED),
                item(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED),
                item(UnreadProjectItem.Kind.MIDI_EFFECT_SETTING_ADJUSTED),
                item(UnreadProjectItem.Kind.CC_EVENT_SKIPPED),
                item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED),
                item(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED));

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY, reversed).message())
                .isEqualTo("Opened project: Song — 1 MIDI note skipped, 1 CC event skipped,"
                        + " 1 arpeggiator setting adjusted, 1 MIDI effect not recognized (kept as saved),"
                        + " 2 mixer assignments removed");
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY, List.of(
                item(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED),
                item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED))).message())
                .as("a kind with no item has no clause")
                .isEqualTo("Opened project: Song — 1 MIDI note skipped, 1 mixer assignment removed");
    }

    @Test
    void theUnreadClausesFollowTheAssetClausesInOneSentence() {
        MissingAssetsReport twoSilencingNothing = new MissingAssetsReport(
                List.of(silencingNoClip("a.wav"), silencingNoClip("b.wav")), List.of());
        List<UnreadProjectItem> unread = List.of(
                item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED),
                item(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED),
                item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED),
                item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED));
        MissingAssetsReport both = new MissingAssetsReport(List.of(row("a.wav", "1", "2"), row("b.wav", "3"),
                shorterRow("long.wav", "4")), List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, twoSilencingNothing, unread)).as("the ruling's example")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING, "Opened project: Song — 2 missing assets,"
                        + " 3 MIDI notes skipped, 1 MIDI effect not recognized (kept as saved)", true));
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, both, unread).message())
                .isEqualTo("Opened project: Song — 2 missing assets (3 clips silent), 1 asset shorter than its clips,"
                        + " 3 MIDI notes skipped, 1 MIDI effect not recognized (kept as saved)");
        assertThat(OpenAnnouncement.summary(both)).as("the report's summary stays assets only")
                .isEqualTo("2 missing assets (3 clips silent), 1 asset shorter than its clips");
    }

    @Test
    void unreadItemsWarnWithoutReviewAndAnErrorDoorStaysAnError() {
        List<UnreadProjectItem> unread = List.of(item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED));

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY, unread))
                .as("a SUCCESS door warns; Review… opens the asset report, which has no rows")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Opened project: Song — 1 MIDI note skipped", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.SnapshotRestore("Before"), SONG, MissingAssetsReport.EMPTY,
                unread)).as("an INFO door warns too").isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored snapshot: Before — 1 MIDI note skipped", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.FailedReplayFallback("bad frame"), SONG,
                MissingAssetsReport.EMPTY, unread)).isEqualTo(new OpenAnnouncement(NotificationLevel.ERROR,
                        "Recovery failed (bad frame); opened the last clean save instead — 1 MIDI note skipped",
                        false));
        assertThat(OpenAnnouncement.of(new OpenDoor.MigrationRollback(1), SONG, MissingAssetsReport.EMPTY, unread)
                .message()).isEqualTo("Restored on-disk project file to v1 (in-memory migrations were re-applied)"
                        + " — 1 MIDI note skipped");
    }

    @Test
    void theArchiveDoorAnnouncesItsOwnReadsItemsLastInItsParenthesesNeverTheReopens() {
        Path destination = Path.of("restored", "Song-20261021").toAbsolutePath();
        List<UnreadProjectItem> archiveUnread = List.of(item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED),
                item(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED));
        List<UnreadProjectItem> reopenUnread = List.of(item(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED));

        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of(), archiveUnread), destination,
                MissingAssetsReport.EMPTY, reopenUnread))
                .as("the placeholder the archive wrote back is counted once")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored archive: Song (1 MIDI note skipped, 1 MIDI effect not recognized (kept as saved))",
                        false));
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of("assets/0b_strings.sf2"),
                List.of(item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED))), destination, MissingAssetsReport.EMPTY,
                List.of()).message()).isEqualTo("Restored archive: Song (1 missing asset, 1 MIDI note skipped)");
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of(), List.of()), destination,
                MissingAssetsReport.EMPTY, reopenUnread))
                .as("the reopen's items are not the archive door's")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS, "Restored archive: Song", false));
    }

    @Test
    void theMixerAssignmentClauseIsNeutralAndSitsInsideTheArchiveParentheses() {
        Path destination = Path.of("restored", "Song-20261021").toAbsolutePath();

        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of(),
                List.of(item(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED))), destination,
                MissingAssetsReport.EMPTY, List.of()))
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored archive: Song (1 mixer assignment removed)", false));
    }

    @Test
    void theJournalReplayDoorAnnouncesItsOwnReadsItemsNeverTheReopens() {
        List<UnreadProjectItem> replayUnread = List.of(item(UnreadProjectItem.Kind.MIDI_EFFECT_SETTING_ADJUSTED),
                item(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED));
        List<UnreadProjectItem> reopenUnread = List.of(item(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED));

        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(2, 0, replayUnread), SONG,
                MissingAssetsReport.EMPTY, reopenUnread)).isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Recovered 2 changes from the journal — 1 arpeggiator setting adjusted,"
                                + " 1 MIDI effect not recognized (kept as saved)", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(2, 0, List.of()), SONG, MissingAssetsReport.EMPTY,
                reopenUnread)).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                        "Recovered 2 changes from the journal", false));
    }

    @Test
    void aJournalReplayThatCouldNotRebuildItsChangesIsAWarningThatSaysSoNeverASuccessThatCountsThemAsRecovered() {
        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(3, 3, List.of()), SONG, MissingAssetsReport.EMPTY,
                List.of())).isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Recovered the project from its recovery point; the 3 journaled changes after it could not"
                                + " be rebuilt", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(1, 1, List.of()), SONG, MissingAssetsReport.EMPTY,
                List.of())).isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Recovered the project from its recovery point; the journaled change after it could not be"
                                + " rebuilt", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(3, 3, List.of()), SONG, MissingAssetsReport.EMPTY,
                List.of()).message()).doesNotContain("from the journal");
    }

    @Test
    void aJournalReplayThatCouldNotRebuildItsChangesIsQualifiedByItsOwnReadsItems() {
        List<UnreadProjectItem> replayUnread = List.of(item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED));
        List<UnreadProjectItem> reopenUnread = List.of(item(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED));

        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(2, 2, replayUnread), SONG,
                MissingAssetsReport.EMPTY, reopenUnread)).isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Recovered the project from its recovery point; the 2 journaled changes after it could not"
                                + " be rebuilt — 1 MIDI note skipped", false));
    }

    @Test
    void aPromotedInterruptedSaveIsAnnouncedAsItselfWithTheLoadsUnreadItemsNeverAsZeroJournalChanges() {
        OpenDoor promoted = OpenRecoveryGate.Result.INTERRUPTED_SAVE.door(OpenDoor.PLAIN);
        List<UnreadProjectItem> loadUnread = List.of(item(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED));

        assertThat(promoted).isEqualTo(new OpenDoor.InterruptedSavePromotion());
        assertThat(OpenAnnouncement.of(promoted, SONG, MissingAssetsReport.EMPTY, List.of()))
                .isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS, "Recovered an interrupted save", false));
        assertThat(OpenAnnouncement.of(promoted, SONG, MissingAssetsReport.EMPTY, loadUnread))
                .as("the promoted file is the one the load read, so the load's items qualify it")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Recovered an interrupted save — 1 MIDI note skipped", false));
        assertThat(OpenAnnouncement.of(promoted, SONG, MissingAssetsReport.EMPTY, loadUnread).message())
                .doesNotContain("from the journal");

        assertThat(new OpenRecoveryGate.Result(0, 0, List.of(), false).door(OpenDoor.PLAIN))
                .as("a journal replay that applied nothing keeps its own door")
                .isEqualTo(new OpenDoor.JournalReplay(0, 0, List.of()));
        assertThat(new OpenRecoveryGate.Result(3, 3, List.of(), false).door(OpenDoor.PLAIN))
                .as("a journal replay's door carries how many of its changes it could not rebuild")
                .isEqualTo(new OpenDoor.JournalReplay(3, 3, List.of()));
        assertThat(OpenRecoveryGate.Result.PLAIN.door(OpenDoor.PLAIN)).as("nothing recovered: the selected door")
                .isSameAs(OpenDoor.PLAIN);
    }

    /** An unread item of {@code kind} standing for one saved item, where its kind lives. */
    private static UnreadProjectItem item(UnreadProjectItem.Kind kind) {
        return item(kind, 1);
    }

    /** An unread item of {@code kind} standing for {@code count} saved items (other than 1 only for a lane). */
    private static UnreadProjectItem item(UnreadProjectItem.Kind kind, int count) {
        UnreadProjectItem.Location location = kind.isTrackScoped()
                ? new UnreadProjectItem.OnTrack(0, "Keys")
                : new UnreadProjectItem.InMixer("cue bus 'Singer'");
        UnreadProjectItem.EventLane lane = kind == UnreadProjectItem.Kind.CC_EVENT_SKIPPED
                ? new UnreadProjectItem.EventLane(0, MidiCcLaneType.MOD_WHEEL, 1)
                : null;
        return new UnreadProjectItem(kind, location, 0, "for the test", count, lane);
    }

    private static MissingAsset silencingNoClip(String file) {
        return new MissingAsset(new AssetReference.SingleFile(SONG.resolve(file).toString()),
                MissingAssetReason.ADM_OBJECT_UNKNOWN, "", List.of(new MissingAsset.Holder("1", "Track", "Clip 1")),
                List.of());
    }

    private static MissingAsset shorterRow(String file, String... clipIds) {
        List<MissingAsset.Holder> holders = new java.util.ArrayList<>();
        for (String clipId : clipIds) {
            holders.add(new MissingAsset.Holder(clipId, "Track", "Clip " + clipId));
        }
        return new MissingAsset(new AssetReference.SingleFile(SONG.resolve(file).toString()),
                MissingAssetReason.CHANGED_ON_DISK, "holds 0.50 s; the clip spans 1.50 s at the current tempo;"
                + " loaded as it is", holders);
    }

    private static MissingAsset row(String file, String... clipIds) {
        List<MissingAsset.Holder> holders = new java.util.ArrayList<>();
        for (String clipId : clipIds) {
            holders.add(new MissingAsset.Holder(clipId, "Track", "Clip " + clipId));
        }
        return new MissingAsset(new AssetReference.SingleFile(SONG.resolve(file).toString()),
                MissingAssetReason.NOT_FOUND, "not found", holders);
    }
}
