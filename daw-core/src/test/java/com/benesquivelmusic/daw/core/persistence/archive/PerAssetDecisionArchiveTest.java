package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class PerAssetDecisionArchiveTest {

    @TempDir
    Path tmp;

    @Test
    void appliesSkipLocateAndStubDecisionsIndependentlyForMissingAssets()
            throws IOException {
        String skippedOriginal = tmp.resolve("missing-skip.wav").toString();
        String locatedOriginal = tmp.resolve("missing-locate.wav").toString();
        String stubOriginal = tmp.resolve("missing-stub.wav").toString();
        byte[] locatedBytes = "replacement audio".getBytes(StandardCharsets.UTF_8);
        Path locatedFile = Files.write(tmp.resolve("located.wav"), locatedBytes);

        DawProject project = new DawProject("Per Asset", AudioFormat.CD_QUALITY);
        AudioClip skippedClip = new AudioClip("skip", 0, 1, skippedOriginal);
        AudioClip locatedClip = new AudioClip("locate", 1, 1, locatedOriginal);
        AudioClip stubClip = new AudioClip("stub", 2, 1, stubOriginal);
        project.createAudioTrack("Skip").addClip(skippedClip);
        project.createAudioTrack("Locate").addClip(locatedClip);
        project.createAudioTrack("Stub").addClip(stubClip);

        Path archive = tmp.resolve("per-asset.dawz");
        ProjectArchiveSummary summary = new ProjectArchiver().saveAsArchive(
                project,
                archive,
                ArchiveOptions.defaults(),
                List.of(
                        ArchiveAssetDecision.skip(skippedOriginal),
                        ArchiveAssetDecision.locate(locatedOriginal, locatedFile),
                        ArchiveAssetDecision.useStub(stubOriginal)));

        assertThat(summary.uniqueAssetCount()).isEqualTo(2);
        assertThat(summary.totalAssetBytes())
                .isEqualTo(locatedBytes.length + stubBytes(stubOriginal).length);
        assertThat(skippedClip.getSourceFilePath()).isEqualTo(skippedOriginal);
        assertThat(locatedClip.getSourceFilePath()).isEqualTo(locatedOriginal);
        assertThat(stubClip.getSourceFilePath()).isEqualTo(stubOriginal);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String projectXml = new String(
                    zip.getInputStream(zip.getEntry(ArchiveHeader.PROJECT_DOC_NAME)).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(projectXml).contains(skippedOriginal);
            assertThat(projectXml).doesNotContain(locatedOriginal, stubOriginal);
            assertThat(projectXml)
                    .contains("assets/")
                    .contains("_located.wav")
                    .contains("_missing-stub.wav");

            List<String> assetEntries = zip.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith(ArchiveHeader.ASSETS_DIR + "/"))
                    .toList();
            assertThat(assetEntries).hasSize(2);
            String locatedEntry = assetEntries.stream()
                    .filter(name -> name.endsWith("_located.wav"))
                    .findFirst()
                    .orElseThrow();
            String stubEntry = assetEntries.stream()
                    .filter(name -> name.endsWith("_missing-stub.wav"))
                    .findFirst()
                    .orElseThrow();
            assertThat(zip.getInputStream(zip.getEntry(locatedEntry)).readAllBytes())
                    .containsExactly(locatedBytes);
            assertThat(zip.getInputStream(zip.getEntry(stubEntry)).readAllBytes())
                    .containsExactly(stubBytes(stubOriginal));
        }
    }

    private static byte[] stubBytes(String originalPath) {
        return ("DAW missing asset stub\noriginal=" + originalPath + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
