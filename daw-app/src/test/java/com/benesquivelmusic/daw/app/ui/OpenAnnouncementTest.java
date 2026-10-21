package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;

import org.junit.jupiter.api.Test;

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
 */
class OpenAnnouncementTest {

    private static final Path SONG = Path.of("projects", "Song").toAbsolutePath();

    @Test
    void aCleanOpenIsThePlainSuccessWithNothingToReview() {
        OpenAnnouncement announcement = OpenAnnouncement.of(OpenDoor.PLAIN, SONG, MissingAssetsReport.EMPTY);

        assertThat(announcement).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                "Opened project: Song", false));
    }

    @Test
    void missingAssetsQualifyTheOpenCountingAssetsNotClipsWithBothPluralsRight() {
        MissingAssetsReport one = new MissingAssetsReport(List.of(row("a.wav", "1")), List.of());
        MissingAssetsReport splitHalves = new MissingAssetsReport(List.of(row("a.wav", "1", "2")), List.of());
        MissingAssetsReport two = new MissingAssetsReport(List.of(row("a.wav", "1", "2"), row("b.wav", "3")),
                List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, one)).isEqualTo(new OpenAnnouncement(
                NotificationLevel.WARNING, "Opened project: Song — 1 missing asset (1 clip silent)", true));
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, splitHalves).message())
                .isEqualTo("Opened project: Song — 1 missing asset (2 clips silent)");
        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, two).message())
                .isEqualTo("Opened project: Song — 2 missing assets (3 clips silent)");
    }

    @Test
    void everyDoorKeepsItsOwnWordsAndAFailureDoorKeepsItsError() {
        MissingAssetsReport one = new MissingAssetsReport(List.of(row("a.wav", "1")), List.of());

        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(1), SONG, MissingAssetsReport.EMPTY))
                .isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                        "Recovered 1 change from the journal", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.JournalReplay(1200), SONG, MissingAssetsReport.EMPTY).message())
                .as("a count is shown without grouping, as before").isEqualTo("Recovered 1200 changes from the journal");
        assertThat(OpenAnnouncement.of(new OpenDoor.SnapshotRestore("Take 3's comp"), SONG, MissingAssetsReport.EMPTY))
                .as("an apostrophe in an argument is kept").isEqualTo(new OpenAnnouncement(NotificationLevel.INFO,
                        "Restored snapshot: Take 3's comp", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.FailedReplayFallback("bad {frame}"), SONG, one))
                .isEqualTo(new OpenAnnouncement(NotificationLevel.ERROR, "Recovery failed (bad {frame});"
                        + " opened the last clean save instead — 1 missing asset (1 clip silent)", true));
        assertThat(OpenAnnouncement.of(new OpenDoor.RecoveredWriteFailed("disk full"), SONG,
                MissingAssetsReport.EMPTY)).isEqualTo(new OpenAnnouncement(NotificationLevel.ERROR,
                "Could not write recovered project: disk full; opened the last clean save", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.MigrationRollback(7), SONG, MissingAssetsReport.EMPTY))
                .isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                        "Restored on-disk project file to v7 (in-memory migrations were re-applied)", false));
    }

    @Test
    void theArchiveDoorCountsTheUnionOfItsOwnListAndTheOpensEachAssetOnce() {
        Path destination = Path.of("restored", "Song-20261020").toAbsolutePath();
        String inDestination = destination.resolve("assets").resolve("0a_kick.wav").toString();
        MissingAssetsReport open = new MissingAssetsReport(List.of(new MissingAsset(
                new AssetReference.SingleFile(inDestination), MissingAssetReason.NOT_FOUND, "not found",
                List.of(new MissingAsset.Holder("1", "Drums", "Kick")))), List.of());

        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of()), destination,
                MissingAssetsReport.EMPTY)).isEqualTo(new OpenAnnouncement(NotificationLevel.SUCCESS,
                "Restored archive: Song", false));
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song",
                List.of("assets/0a_kick.wav", "assets/0b_strings.sf2", "assets/0b_strings.sf2")), destination, open))
                .as("the kick, both lists name it; the SoundFont only the archive does, twice")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored archive: Song (2 missing assets, 1 clip silent)", true));
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of("assets/0b_strings.sf2")),
                destination, MissingAssetsReport.EMPTY))
                .as("nothing of the open's to review: no Review")
                .isEqualTo(new OpenAnnouncement(NotificationLevel.WARNING,
                        "Restored archive: Song (1 missing asset)", false));
    }

    @Test
    void aFileShorterThanItsClipsIsNeitherMissingNorSilent() {
        MissingAssetsReport shorter = new MissingAssetsReport(List.of(shorterRow("long.wav", "1", "2")), List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, shorter)).isEqualTo(new OpenAnnouncement(
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

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, both)).isEqualTo(new OpenAnnouncement(
                NotificationLevel.WARNING,
                "Opened project: Song — 2 missing assets (3 clips silent), 1 asset shorter than its clips", true));
        assertThat(OpenAnnouncement.of(new OpenDoor.FailedReplayFallback("bad frame"), SONG, both).level())
                .as("an error door stays an error").isEqualTo(NotificationLevel.ERROR);
        assertThat(OpenAnnouncement.summary(both))
                .isEqualTo("2 missing assets (3 clips silent), 1 asset shorter than its clips");
        assertThat(OpenAnnouncement.followUp(both))
                .contains("2 assets could not be loaded as saved; 1 asset shorter than its clips");
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of()), SONG, both).message())
                .as("the archive door puts every clause in its parentheses")
                .isEqualTo("Restored archive: Song (2 missing assets, 3 clips silent, 1 asset shorter than its clips)");
    }

    @Test
    void aMissingAssetThatSilencesNoClipSaysNothingOfSilentClips() {
        MissingAsset noneSilent = new MissingAsset(new AssetReference.SingleFile(SONG.resolve("a.wav").toString()),
                MissingAssetReason.ADM_OBJECT_UNKNOWN, "", List.of(new MissingAsset.Holder("1", "Track", "Clip 1")),
                List.of());
        MissingAssetsReport report = new MissingAssetsReport(List.of(noneSilent), List.of());

        assertThat(OpenAnnouncement.of(OpenDoor.PLAIN, SONG, report).message())
                .isEqualTo("Opened project: Song — 1 missing asset");
        assertThat(OpenAnnouncement.of(new OpenDoor.ArchiveRestore("Song", List.of()), SONG, report).message())
                .isEqualTo("Restored archive: Song (1 missing asset)");
        assertThat(OpenAnnouncement.summary(report)).isEqualTo("1 missing asset");
        assertThat(OpenAnnouncement.followUp(MissingAssetsReport.EMPTY)).as("nothing to follow up on").isEmpty();
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
