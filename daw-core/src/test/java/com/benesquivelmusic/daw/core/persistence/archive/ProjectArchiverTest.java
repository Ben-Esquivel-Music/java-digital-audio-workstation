package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link ProjectArchiver} can bundle a project together with
 * every referenced asset, restore it to a fresh location, and re-archive
 * the restored project producing byte-identical asset content.
 */
class ProjectArchiverTest {

    @TempDir
    Path tmp;

    @Test
    void shouldRoundTripProjectWithTenAssetsByteIdentical() throws IOException {
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        Path archiveOut = tmp.resolve("archive1.dawz");
        Path extractDir = tmp.resolve("extracted");
        Path archiveOut2 = tmp.resolve("archive2.dawz");

        // Create 10 distinct fake audio files + 1 SoundFont.
        DawProject project = new DawProject("Archive Test", AudioFormat.CD_QUALITY);
        Random rng = new Random(42);
        List<Path> originals = new ArrayList<>();
        List<byte[]> originalBytes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] data = new byte[1024];
            rng.nextBytes(data);
            Path file = mediaDir.resolve("clip-" + i + ".wav");
            Files.write(file, data);
            originals.add(file);
            originalBytes.add(data);

            Track track = project.createAudioTrack("Track " + i);
            AudioClip clip = new AudioClip("Clip " + i, i * 4.0, 4.0, file.toString());
            track.addClip(clip);
        }
        // Add a duplicate clip referencing the same file as track 0 — must
        // dedupe by hash and not double-store the asset.
        Track dupTrack = project.createAudioTrack("Track dup");
        dupTrack.addClip(new AudioClip("Dup", 100, 4.0, originals.get(0).toString()));

        // Add a SoundFont assigned to a MIDI track.
        Path sfFile = mediaDir.resolve("piano.sf2");
        byte[] sfBytes = new byte[2048];
        rng.nextBytes(sfBytes);
        Files.write(sfFile, sfBytes);
        Track midi = project.createMidiTrack("MIDI");
        midi.setSoundFontAssignment(new SoundFontAssignment(sfFile, 0, 0, "Piano"));

        ProjectArchiver archiver = new ProjectArchiver();

        // Save as archive.
        ProjectArchiveSummary summary = archiver.saveAsArchive(project, archiveOut);
        assertThat(archiveOut).exists();
        // 10 distinct audio files + 1 SoundFont == 11 unique assets (the duplicate dedupes).
        assertThat(summary.uniqueAssetCount()).isEqualTo(11);
        assertThat(summary.totalAssetBytes()).isEqualTo(10L * 1024 + 2048);

        // The in-memory project's asset paths must be unchanged after archiving.
        for (int i = 0; i < 10; i++) {
            AudioClip c = project.getTracks().get(i).getClips().get(0);
            assertThat(c.getSourceFilePath()).isEqualTo(originals.get(i).toString());
        }
        assertThat(project.getTracks().get(11).getSoundFontAssignment().soundFontPath())
                .isEqualTo(sfFile);

        // Open the archive into a fresh directory.
        ArchivedProject opened = archiver.openArchive(archiveOut, extractDir, null);
        assertThat(opened.missingAssets()).isEmpty();
        assertThat(opened.header().projectName()).isEqualTo("Archive Test");
        assertThat(opened.header().assetCount()).isEqualTo(11);
        assertThat(opened.header().projectDocSha256()).hasSize(64);
        assertThat(opened.project().getTracks()).hasSize(12);

        // Verify every asset round-tripped to byte-identical content.
        for (int i = 0; i < 10; i++) {
            AudioClip restored = opened.project().getTracks().get(i).getClips().get(0);
            byte[] restoredBytes = Files.readAllBytes(Path.of(restored.getSourceFilePath()));
            assertThat(restoredBytes).containsExactly(originalBytes.get(i));
        }
        Path restoredSf = opened.project().getTracks().get(11)
                .getSoundFontAssignment().soundFontPath();
        assertThat(Files.readAllBytes(restoredSf)).containsExactly(sfBytes);

        // Re-archive the restored project; the asset payload (sorted hashes) must
        // be byte-identical to what came out of the original archive.
        ProjectArchiveSummary summary2 = archiver.saveAsArchive(opened.project(), archiveOut2);
        assertThat(summary2.uniqueAssetCount()).isEqualTo(11);
        Set<String> originalHashes = new HashSet<>();
        for (Path p : originals) originalHashes.add(sha256(Files.readAllBytes(p)));
        originalHashes.add(sha256(sfBytes));

        Set<String> reArchivedHashes = new HashSet<>();
        ArchivedProject opened2 = archiver.openArchive(
                archiveOut2, tmp.resolve("extracted2"), null);
        for (int i = 0; i < 10; i++) {
            reArchivedHashes.add(sha256(Files.readAllBytes(
                    Path.of(opened2.project().getTracks().get(i).getClips().get(0).getSourceFilePath()))));
        }
        reArchivedHashes.add(sha256(Files.readAllBytes(
                opened2.project().getTracks().get(11).getSoundFontAssignment().soundFontPath())));
        assertThat(reArchivedHashes).isEqualTo(originalHashes);
    }

    @Test
    void shouldDedupeAssetsByContentHash() throws IOException {
        // Two files with identical content but different filenames should
        // be stored once in the archive.
        DawProject project = new DawProject("Dedupe", AudioFormat.CD_QUALITY);
        byte[] data = "identical-payload-bytes".getBytes();
        Path a = Files.write(tmp.resolve("a.wav"), data);
        Path b = Files.write(tmp.resolve("b.wav"), data);
        project.createAudioTrack("A").addClip(new AudioClip("A", 0, 4, a.toString()));
        project.createAudioTrack("B").addClip(new AudioClip("B", 0, 4, b.toString()));

        ProjectArchiver archiver = new ProjectArchiver();
        ProjectArchiveSummary summary = archiver.saveAsArchive(project, tmp.resolve("dedupe.dawz"));

        assertThat(summary.uniqueAssetCount()).isEqualTo(1);
        assertThat(summary.totalAssetBytes()).isEqualTo(data.length);
    }

    @Test
    void shouldConsolidateExternalAssetsInPlace() throws IOException {
        Path projectDir = Files.createDirectories(tmp.resolve("MyProject"));
        Path external = Files.createDirectories(tmp.resolve("external"));
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        Path src = Files.write(external.resolve("loop.wav"), data);

        DawProject project = new DawProject("Consolidate", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("T");
        AudioClip clip = new AudioClip("C", 0, 4, src.toString());
        t.addClip(clip);

        ProjectArchiver archiver = new ProjectArchiver();
        ProjectArchiveSummary summary = archiver.consolidateInPlace(
                project, projectDir, ArchiveOptions.defaults());

        assertThat(summary.uniqueAssetCount()).isEqualTo(1);
        // Clip's path must now point inside the project tree.
        Path consolidated = Path.of(clip.getSourceFilePath());
        assertThat(consolidated.toAbsolutePath().normalize())
                .startsWith(projectDir.toAbsolutePath().normalize());
        assertThat(Files.readAllBytes(consolidated)).containsExactly(data);
    }

    @Test
    void shouldInvokeMissingAssetResolverWhenAssetCannotBeLocated() throws IOException {
        // Hand-craft a tiny archive with one project that references an asset
        // path that won't extract to a real file.
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        byte[] bytes = new byte[]{9, 9, 9};
        Path src = Files.write(mediaDir.resolve("kick.wav"), bytes);
        DawProject project = new DawProject("Resolver", AudioFormat.CD_QUALITY);
        project.createAudioTrack("K").addClip(new AudioClip("Kick", 0, 4, src.toString()));

        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tmp.resolve("missing.dawz");
        archiver.saveAsArchive(project, archive);

        // Extract, then delete the asset file from the extracted dir to
        // simulate a partial copy / lost media.
        Path extractDir = tmp.resolve("extracted");
        ArchivedProject opened = archiver.openArchive(archive, extractDir, null);
        assertThat(opened.missingAssets()).isEmpty();

        // Now corrupt: drop the assets folder, re-open with a resolver that
        // points at the original media directory.
        Path extractDir2 = tmp.resolve("extracted2");
        Files.createDirectories(extractDir2);
        // Manually unzip just the project + header so the archive looks "broken".
        Files.copy(extractDir.resolve("archive.properties"),
                extractDir2.resolve("archive.properties"));
        Files.copy(extractDir.resolve("project.daw"),
                extractDir2.resolve("project.daw"));
        // Provide a resolver that finds the file in mediaDir.
        MissingAssetResolver resolver = MissingAssetResolver.smartSiblingSearch(List.of(mediaDir));
        // Build a separate archive whose 'assets/' is empty so resolver fires.
        Path emptyArchive = tmp.resolve("empty.dawz");
        try (var out = Files.newOutputStream(emptyArchive);
             var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("archive.properties"));
            zip.write(Files.readAllBytes(extractDir.resolve("archive.properties")));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("project.daw"));
            zip.write(Files.readAllBytes(extractDir.resolve("project.daw")));
            zip.closeEntry();
        }
        Path extractDir3 = tmp.resolve("extracted3");
        ArchivedProject reopened = archiver.openArchive(emptyArchive, extractDir3, resolver);
        // Resolver should locate it in mediaDir by basename.
        AudioClip restored = reopened.project().getTracks().get(0).getClips().get(0);
        assertThat(Path.of(restored.getSourceFilePath()).toAbsolutePath())
                .isEqualTo(src.toAbsolutePath());
        assertThat(reopened.missingAssets()).isEmpty();
    }

    @Test
    void shouldReportMissingAssetsWhenResolverCannotFindThem() throws IOException {
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        Path src = Files.write(mediaDir.resolve("snare.wav"), new byte[]{1});
        DawProject project = new DawProject("Missing", AudioFormat.CD_QUALITY);
        project.createAudioTrack("S").addClip(new AudioClip("Snare", 0, 4, src.toString()));

        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tmp.resolve("a.dawz");
        archiver.saveAsArchive(project, archive);

        // Hand-build an archive missing the assets folder entry.
        Path bare = tmp.resolve("bare.dawz");
        Path tmpExtract = tmp.resolve("scratch");
        archiver.openArchive(archive, tmpExtract, null);
        try (var out = Files.newOutputStream(bare);
             var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("archive.properties"));
            zip.write(Files.readAllBytes(tmpExtract.resolve("archive.properties")));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("project.daw"));
            zip.write(Files.readAllBytes(tmpExtract.resolve("project.daw")));
            zip.closeEntry();
        }
        ArchivedProject opened = archiver.openArchive(
                bare, tmp.resolve("nope"), MissingAssetResolver.none());
        assertThat(opened.missingAssets()).hasSize(1);
    }

    @Test
    void shouldRejectNonDawzExtension() {
        ProjectArchiver archiver = new ProjectArchiver();
        DawProject project = new DawProject("Bad", AudioFormat.CD_QUALITY);
        assertThatThrownBy(() -> archiver.saveAsArchive(project, tmp.resolve("foo.zip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".dawz");
    }

    @Test
    void shouldRejectArchiveWithZipSlipPaths() throws IOException {
        // Build a malicious archive containing an entry that escapes the target
        // directory via "..". The extractor must refuse to extract it.
        Path malicious = tmp.resolve("evil.dawz");
        try (var out = Files.newOutputStream(malicious);
             var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("../escaped.txt"));
            zip.write(new byte[]{0});
            zip.closeEntry();
        }
        ProjectArchiver archiver = new ProjectArchiver();
        assertThatThrownBy(() -> archiver.openArchive(malicious, tmp.resolve("dest"), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes target directory");
    }

    @Test
    void shouldDetectTamperedProjectDocument() throws IOException {
        Path mediaDir = Files.createDirectories(tmp.resolve("media"));
        Path src = Files.write(mediaDir.resolve("k.wav"), new byte[]{1, 2});
        DawProject project = new DawProject("Tamper", AudioFormat.CD_QUALITY);
        project.createAudioTrack("K").addClip(new AudioClip("K", 0, 4, src.toString()));
        ProjectArchiver archiver = new ProjectArchiver();
        Path archive = tmp.resolve("t.dawz");
        archiver.saveAsArchive(project, archive);
        Path extracted = tmp.resolve("ex");
        archiver.openArchive(archive, extracted, null);

        // Tamper with project.daw inside a re-built archive while keeping the
        // header SHA-256 stale.
        String originalHeader = Files.readString(extracted.resolve("archive.properties"));
        Path tampered = tmp.resolve("tampered.dawz");
        try (var out = Files.newOutputStream(tampered);
             var zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("archive.properties"));
            zip.write(originalHeader.getBytes());
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("project.daw"));
            zip.write("<bogus/>".getBytes());
            zip.closeEntry();
        }
        assertThatThrownBy(() -> archiver.openArchive(tampered, tmp.resolve("ex2"), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity");
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
