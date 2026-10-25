package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.persistence.ProjectDocument.AssetReference;
import com.benesquivelmusic.daw.core.persistence.ProjectDocument.ReferenceKind;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer.AssetReferenceRemap;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deferred-render capture of a project (story 331; Persistence Integrity
 * book §2.3): {@link ProjectSerializer#capture} walks the model once, and the
 * {@link ProjectDocument} it returns renders the same bytes as
 * {@link ProjectSerializer#serialize} later, on any thread, without reading
 * the model again.
 */
class ProjectDocumentTest {

    @TempDir
    Path tmp;

    private final ProjectSerializer serializer = new ProjectSerializer();

    private Path root;
    private Path media;
    private DawProject project;

    @BeforeEach
    void buildTheRichFixture() {
        root = tmp.resolve("Song");
        media = tmp.resolve("media");
        project = richProject(root, media);
    }

    /**
     * Several tracks, a three-segment take, clips under the project directory
     * (written project-relative), outside it (written verbatim) and relative,
     * and a SoundFont assignment.
     */
    static DawProject richProject(Path root, Path media) {
        DawProject project = new DawProject("Rich Fixture", AudioFormat.CD_QUALITY);
        project.setMetadata(new ProjectMetadata("Rich Fixture", Instant.parse("2026-01-02T03:04:05Z"),
                Instant.parse("2026-02-03T04:05:06Z"), root));

        Track vocals = project.createAudioTrack("Vocals");
        AudioClip take = new AudioClip("Take 1", 0.0, 8.0, null);
        take.setSourceSegmentPaths(List.of(
                root.resolve("audio/takes/take-0001/vox/segment-000.wav").toString(),
                root.resolve("audio/takes/take-0001/vox/segment-001.wav").toString(),
                root.resolve("audio/takes/take-0001/vox/segment-002.wav").toString()));
        vocals.addClip(take);
        vocals.addClip(new AudioClip("Ad lib", 8.0, 4.0, root.resolve("audio/adlib.wav").toString()));

        Track drums = project.createAudioTrack("Drums");
        drums.addClip(new AudioClip("Loop", 0.0, 16.0, media.resolve("loop.wav").toString()));
        drums.addClip(new AudioClip("Kick", 16.0, 4.0, "samples/kick.wav"));

        Track keys = project.createMidiTrack("Keys");
        keys.setSoundFontAssignment(new SoundFontAssignment(media.resolve("piano.sf2"), 0, 1, "Grand"));
        return project;
    }

    @Test
    void theCaptureRendersExactlyWhatSerializeWrites() throws Exception {
        String serialized = serializer.serialize(project);

        assertThat(serializer.capture(project).toXml()).isEqualTo(serialized);
        assertThat(serialized)
                .as("a reference under the project directory is written project-relative")
                .contains("path=\"audio/takes/take-0001/vox/segment-001.wav\"")
                .contains("source-file=\"audio/adlib.wav\"")
                .contains("source-file=\"samples/kick.wav\"")
                .contains("path=\"" + media.resolve("piano.sf2").toString().replace('\\', '/') + "\"");
    }

    @Test
    void aRemapRendersExactlyWhatSerializeWritesWithIt() throws Exception {
        AssetReferenceRemap remap = archiveRemap();
        String serialized = serializer.serialize(project, remap);

        assertThat(serializer.capture(project).toXml(remap)).isEqualTo(serialized);
        assertThat(serialized).contains("source-file=\"assets/aa_segment-000.wav\"")
                .contains("path=\"assets/bb_segment-001.wav\"")
                .contains("source-file=\"assets/cc_loop.wav\"")
                .contains("path=\"assets/dd_piano.sf2\"");
    }

    @Test
    void aRemapWritesWhatTheProjectWouldWriteWereItsReferencesTheSubstitutes() throws Exception {
        ProjectDocument captured = serializer.capture(project);
        Track vocals = project.getTracks().get(0);
        vocals.getClips().get(0).setSourceSegmentPaths(List.of("assets/aa_segment-000.wav",
                "assets/bb_segment-001.wav", root.resolve("audio/takes/take-0001/vox/segment-002.wav").toString()));
        project.getTracks().get(1).getClips().get(0).setSourceFilePath("assets/cc_loop.wav");
        project.getTracks().get(2).setSoundFontAssignment(
                new SoundFontAssignment(Path.of("assets/dd_piano.sf2"), 0, 1, "Grand"));

        assertThat(captured.toXml(archiveRemap()))
                .as("the remap-then-relativize rule, applied to the captured references")
                .isEqualTo(serializer.serialize(project));
    }

    @Test
    void anEditAfterTheCaptureNeverReachesTheRenderedDocument() throws Exception {
        String before = serializer.serialize(project);
        ProjectDocument captured = serializer.capture(project);

        project.createAudioTrack("Added after the capture");
        project.getTracks().get(0).getClips().get(1).setSourceFilePath(media.resolve("other.wav").toString());

        assertThat(captured.toXml()).isEqualTo(before);
        assertThat(captured.references()).extracting(AssetReference::reference)
                .doesNotContain(media.resolve("other.wav").toString());
    }

    @Test
    void renderingWithARemapAndThenWithoutGivesTheUnremappedDocumentBack() throws Exception {
        ProjectDocument captured = serializer.capture(project);

        captured.toXml(archiveRemap());

        assertThat(captured.toXml()).isEqualTo(serializer.serialize(project));
    }

    @Test
    void aDocumentCapturedOnTheOwnerRendersOnAnotherThreadAsOnTheOwner() throws Exception {
        try (ExecutorService owner = Executors.newSingleThreadExecutor();
             ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            ProjectDocument captured = CompletableFuture.supplyAsync(() -> captureUnchecked(project), owner)
                    .get(10, TimeUnit.SECONDS);
            String onTheOwner = CompletableFuture.supplyAsync(() -> renderUnchecked(captureUnchecked(project)),
                    owner).get(10, TimeUnit.SECONDS);

            String onAWorker = CompletableFuture.supplyAsync(() -> renderUnchecked(captured), worker)
                    .get(10, TimeUnit.SECONDS);

            assertThat(onAWorker).isEqualTo(onTheOwner).isEqualTo(serializer.serialize(project));
        }
    }

    @Test
    void theReferencesAreEveryAssetSiteInDocumentOrderAsTheLiveProjectHoldsThem() throws Exception {
        String segment0 = root.resolve("audio/takes/take-0001/vox/segment-000.wav").toString();

        List<AssetReference> references = serializer.capture(project).references();

        assertThat(references).containsExactly(
                new AssetReference(ReferenceKind.CLIP_SOURCE_FILE, segment0),
                new AssetReference(ReferenceKind.CLIP_SOURCE_SEGMENT, segment0),
                new AssetReference(ReferenceKind.CLIP_SOURCE_SEGMENT,
                        root.resolve("audio/takes/take-0001/vox/segment-001.wav").toString()),
                new AssetReference(ReferenceKind.CLIP_SOURCE_SEGMENT,
                        root.resolve("audio/takes/take-0001/vox/segment-002.wav").toString()),
                new AssetReference(ReferenceKind.CLIP_SOURCE_FILE, root.resolve("audio/adlib.wav").toString()),
                new AssetReference(ReferenceKind.CLIP_SOURCE_FILE, media.resolve("loop.wav").toString()),
                new AssetReference(ReferenceKind.CLIP_SOURCE_FILE, "samples/kick.wav"),
                new AssetReference(ReferenceKind.SOUND_FONT, media.resolve("piano.sf2").toString()));
    }

    /** What an archive passes: substitutes keyed by the live references, one of them a SoundFont. */
    private AssetReferenceRemap archiveRemap() {
        String segment0 = root.resolve("audio/takes/take-0001/vox/segment-000.wav").toString();
        return new AssetReferenceRemap(
                Map.of(segment0, "assets/aa_segment-000.wav",
                        root.resolve("audio/takes/take-0001/vox/segment-001.wav").toString(),
                        "assets/bb_segment-001.wav",
                        media.resolve("loop.wav").toString(), "assets/cc_loop.wav"),
                Map.of(media.resolve("piano.sf2").toString(), "assets/dd_piano.sf2"));
    }

    private ProjectDocument captureUnchecked(DawProject captured) {
        try {
            return serializer.capture(captured);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String renderUnchecked(ProjectDocument document) {
        try {
            return document.toXml();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
