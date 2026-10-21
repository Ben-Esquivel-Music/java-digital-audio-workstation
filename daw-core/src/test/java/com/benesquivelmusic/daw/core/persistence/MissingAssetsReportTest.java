package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The missing-assets model: rows, counting, and the open-time classification (decisions D6–D9). */
class MissingAssetsReportTest {

    @TempDir
    Path tempDir;

    @Test
    void rowsOfOneAssetAndReasonMergeAndWithoutRemovesEveryRowOfTheAsset() {
        AssetReference file = new AssetReference.SingleFile(tempDir.resolve("a.wav").toString());
        MissingAsset.Holder first = new MissingAsset.Holder("c1", "Drums", "a");
        MissingAsset.Holder second = new MissingAsset.Holder("c2", "Drums", "a (split)");

        MissingAssetsReport report = MissingAssetsReport.EMPTY
                .with(new MissingAsset(file, MissingAssetReason.UNREADABLE, "bad", List.of(first)))
                .with(new MissingAsset(file, MissingAssetReason.UNREADABLE, "bad", List.of(second, first)))
                .with(new MissingAsset(file, MissingAssetReason.CHANGED_ON_DISK, "short", List.of(second)));

        assertThat(report.assets()).hasSize(2);
        assertThat(report.assets().getFirst().holders()).containsExactly(first, second);
        assertThat(report.assetCount()).isEqualTo(1);
        assertThat(report.silentClipCount()).isEqualTo(2);
        assertThat(report.contains(file)).isTrue();
        assertThat(report.rowsFor(file)).hasSize(2);
        MissingAssetsReport relinked = report.without(file);
        assertThat(relinked.isEmpty()).isTrue();
        assertThat(report.isEmpty()).as("reports are immutable").isFalse();
    }

    @Test
    void silentClipsCountDistinctClipsOfSilencingRowsOnly() {
        AssetReference a = new AssetReference.SingleFile(tempDir.resolve("a.wav").toString());
        AssetReference b = new AssetReference.SingleFile(tempDir.resolve("b.wav").toString());
        MissingAssetsReport report = new MissingAssetsReport(List.of(
                new MissingAsset(a, MissingAssetReason.NOT_FOUND, "not found",
                        List.of(new MissingAsset.Holder("c1", "T", "a"))),
                new MissingAsset(b, MissingAssetReason.CHANGED_ON_DISK, "short",
                        List.of(new MissingAsset.Holder("c2", "T", "b")))), List.of());

        assertThat(report.assetCount()).isEqualTo(2);
        assertThat(report.silentClipCount()).isEqualTo(1);
        assertThat(report.missingAssetCount()).isEqualTo(1);
        assertThat(report.shorterAssetCount()).isEqualTo(1);
    }

    @Test
    void aRowCountsAsSilentOnlyTheHoldersWithoutAudio() {
        AssetReference adm = new AssetReference.SingleFile(tempDir.resolve("scene.wav").toString());
        AssetReference shorter = new AssetReference.SingleFile(tempDir.resolve("short.wav").toString());
        MissingAsset.Holder bed = new MissingAsset.Holder("bed", "Bed (Stereo)", "Bed (Stereo)");
        MissingAsset.Holder renamed = new MissingAsset.Holder("renamed", "Object 1", "Lead vocal");
        MissingAsset.Holder object = new MissingAsset.Holder("object", "Object 2", "Object 2");
        MissingAsset.Holder loop = new MissingAsset.Holder("loop", "Loop", "short");

        MissingAssetsReport report = MissingAssetsReport.EMPTY
                .with(new MissingAsset(adm, MissingAssetReason.ADM_OBJECT_UNKNOWN, "unknown",
                        List.of(bed, renamed, object), List.of(renamed)))
                .with(new MissingAsset(shorter, MissingAssetReason.CHANGED_ON_DISK, "short", List.of(loop)));

        assertThat(report.assets().getFirst().clipCount()).isEqualTo(3);
        assertThat(report.assets().getFirst().silentClipCount()).isEqualTo(1);
        assertThat(report.assets().get(1).silentHolders()).as("a shorter file's clip plays").isEmpty();
        assertThat(report.silentClipCount()).isEqualTo(1);
        assertThat(report.missingAssetCount()).isEqualTo(1);
        assertThat(report.shorterAssetCount()).isEqualTo(1);
        MissingAssetsReport onlyTheShorter = report.restrictedTo(List.of(shorter));
        assertThat(onlyTheShorter.assets()).extracting(MissingAsset::reference).containsExactly(shorter);
        assertThat(onlyTheShorter.missingAssetCount()).isZero();
        assertThat(onlyTheShorter.shorterAssetCount()).isEqualTo(1);
        assertThatThrownBy(() -> new MissingAsset(shorter, MissingAssetReason.CHANGED_ON_DISK, "short",
                List.of(loop), List.of(loop))).as("a shorter file silences nothing")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MissingAsset(adm, MissingAssetReason.ADM_OBJECT_UNKNOWN, "unknown",
                List.of(bed), List.of(object))).as("a silent clip is one of the holders")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atOpenNamesEveryHolderOfAMissingFileAndCountsOnlyThoseWithoutAudioAsSilent() {
        String missingFile = tempDir.resolve("library").resolve("missing.wav").toString();
        DawProject project = new DawProject("Open", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("T");
        AudioClip silent = add(track, new AudioClip("missing", 0.0, 1.0, missingFile));
        AudioClip playing = add(track, new AudioClip("missing copy", 2.0, 1.0, missingFile));
        playing.setAudioData(new float[1][8]);

        MissingAssetsReport report = MissingAssetsReport.atOpen(project, List.of(missingFile), tempDir);

        assertThat(report.assets()).singleElement().satisfies(row -> {
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(silent.getId(), playing.getId());
            assertThat(row.silentHolders()).extracting(MissingAsset.Holder::clipId).containsExactly(silent.getId());
        });
        assertThat(report.silentClipCount()).isEqualTo(1);
    }

    @Test
    void atOpenClassifiesFromStringsAloneAndPartitionsReferenceTracksOut() {
        Path projectDir = tempDir.resolve("Project");
        String missingFile = tempDir.resolve("library").resolve("missing.wav").toString();
        String presentFile = tempDir.resolve("library").resolve("present.wav").toString();
        String seg0 = projectDir.resolve("audio").resolve("segment-000.wav").toString();
        String seg1 = projectDir.resolve("audio").resolve("segment-001.wav").toString();
        String referenceTrackFile = tempDir.resolve("reference.wav").toString();
        DawProject project = new DawProject("Open", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("T");
        AudioClip missing = add(track, new AudioClip("missing", 0.0, 1.0, missingFile));
        AudioClip duplicate = add(track, missing.duplicate());
        add(track, new AudioClip("present", 2.0, 1.0, presentFile));
        AudioClip escaping = add(track, new AudioClip("escaping", 3.0, 1.0, "../outside.wav"));
        AudioClip take = new AudioClip("take", 4.0, 1.0, null);
        take.setSourceSegmentPaths(List.of(seg0, seg1));
        add(track, take);
        project.getReferenceTrackManager().addReferenceTrack(new ReferenceTrack("Ref", referenceTrackFile));
        // As the deserializer lists them: once per clip, the reference track too.
        List<String> missingFiles = List.of(missingFile, missingFile, "../outside.wav", seg1, referenceTrackFile);

        MissingAssetsReport report = MissingAssetsReport.atOpen(project, missingFiles, projectDir);

        assertThat(report.assets()).extracting(MissingAsset::reason).containsExactly(
                MissingAssetReason.NOT_FOUND, MissingAssetReason.UNRESOLVABLE, MissingAssetReason.NOT_FOUND);
        MissingAsset missingRow = report.assets().get(0);
        assertThat(missingRow.reference()).isEqualTo(new AssetReference.SingleFile(missingFile));
        assertThat(missingRow.holders()).extracting(MissingAsset.Holder::clipId)
                .containsExactly(missing.getId(), duplicate.getId());
        MissingAsset escapingRow = report.assets().get(1);
        assertThat(escapingRow.holders()).extracting(MissingAsset.Holder::clipId).containsExactly(escaping.getId());
        assertThat(escapingRow.detail()).isEqualTo("escapes the project directory");
        MissingAsset takeRow = report.assets().get(2);
        assertThat(takeRow.reference()).isEqualTo(new AssetReference.Take(List.of(seg0, seg1)));
        assertThat(takeRow.detail()).as("the whole take is silent, and its segment is missing, not unreadable")
                .isEqualTo("1 of 2 segments is missing — the take is not loaded: " + seg1 + " (not found)");
        assertThat(report.assetCount()).isEqualTo(3);
        assertThat(report.silentClipCount()).isEqualTo(4);
        assertThat(report.missingReferenceTrackPaths()).containsExactly(referenceTrackFile);
    }

    @Test
    void aClipReferenceThatIsNotAnAbsolutePathIsUnresolvableEvenWithoutAProjectDirectory() {
        DawProject project = new DawProject("No dir", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("T");
        AudioClip relative = add(track, new AudioClip("relative", 0.0, 1.0, "audio/x.wav"));

        MissingAssetsReport report = MissingAssetsReport.atOpen(project, List.of(), null);

        assertThat(report.assets()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.UNRESOLVABLE);
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId).containsExactly(relative.getId());
        });
    }

    @Test
    void aProjectWhoseReferencesAllResolvedHasAnEmptyReport() {
        DawProject project = new DawProject("Clean", AudioFormat.CD_QUALITY);
        add(project.createAudioTrack("T"),
                new AudioClip("present", 0.0, 1.0, tempDir.resolve("present.wav").toString()));
        add(project.createAudioTrack("U"), new AudioClip("buffer", 0.0, 1.0, null));

        assertThat(MissingAssetsReport.atOpen(project, List.of(), tempDir)).isEqualTo(MissingAssetsReport.EMPTY);
    }

    private static AudioClip add(Track track, AudioClip clip) {
        track.addClip(clip);
        return clip;
    }
}
