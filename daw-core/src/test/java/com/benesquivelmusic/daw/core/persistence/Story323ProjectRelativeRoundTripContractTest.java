package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.SegmentFile;
import com.benesquivelmusic.daw.core.recording.SegmentWriter;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 contract — project-relative persistence of recorded-take clip
 * references (Recording Reliability book §3.3, §5.3 "Relative paths", §9.5;
 * Persistence Integrity book §3.3 "Path semantics").
 *
 * <p>Fixture: a real project directory holding two segments written and
 * sealed by {@link SegmentWriter} under
 * {@code audio/takes/2026-09-28T10-00-00_take-0001/<trackId>/}, a
 * {@link DawProject} whose metadata path is that directory, and one clip
 * carrying both segments' absolute paths.</p>
 */
class Story323ProjectRelativeRoundTripContractTest {

    private static final String TAKE_DIR_NAME = "2026-09-28T10-00-00_take-0001";
    private static final double SAMPLE_RATE = 48_000.0;
    private static final int FRAMES_SEGMENT_0 = 100;
    private static final int FRAMES_SEGMENT_1 = 50;

    @TempDir
    Path tempDir;

    private final ProjectSerializer serializer = new ProjectSerializer();

    private Path projectDir;
    private Path segment0;
    private Path segment1;
    private String trackId;
    private DawProject project;

    @BeforeEach
    void buildProjectWithARecordedTake() throws IOException {
        projectDir = Files.createDirectories(tempDir.resolve("My Project"));
        project = new DawProject("Take Test", new AudioFormat(SAMPLE_RATE, 1, 16, 512));
        project.setMetadata(project.getMetadata().withPath(projectDir));
        Track track = project.createAudioTrack("Vocals");
        trackId = track.getId();

        assertThat(TakeDirectories.isTakeDirectoryName(TAKE_DIR_NAME))
                .as("fixture: the take directory name follows the §3.3 grammar")
                .isTrue();
        Path trackDir = Files.createDirectories(ProjectManager.audioDirectory(projectDir)
                .resolve(TakeDirectories.TAKES_DIR_NAME).resolve(TAKE_DIR_NAME).resolve(trackId));
        segment0 = writeSealedSegment(trackDir.resolve("segment-000.wav.part"), FRAMES_SEGMENT_0);
        segment1 = writeSealedSegment(trackDir.resolve("segment-001.wav.part"), FRAMES_SEGMENT_1);

        AudioClip clip = new AudioClip("Take 1", 0.0, 4.0, null);
        clip.setSourceSegmentPaths(List.of(segment0.toString(), segment1.toString()));
        assertThat(clip.getSourceFilePath()).isEqualTo(segment0.toString());
        track.addClip(clip);
    }

    @Test
    void serializedReferencesAreProjectRelativeWithForwardSlashesAndCarryEverySegment() throws IOException {
        String xml = serializer.serialize(project);

        assertThat(xml).contains("source-file=\"" + relativeSegment(0) + "\"");
        int first = xml.indexOf("<source-segment path=\"" + relativeSegment(0) + "\"/>");
        int second = xml.indexOf("<source-segment path=\"" + relativeSegment(1) + "\"/>");
        assertThat(first).as("first segment child present").isNotNegative();
        assertThat(second).as("second segment child follows the first").isGreaterThan(first);
        assertThat(xml.split("<source-segment ", -1)).hasSize(3);
        assertThat(xml)
                .doesNotContain(projectDir.toString())
                .doesNotContain(projectDir.toString().replace('\\', '/'))
                .doesNotContain(tempDir.toString())
                .doesNotContain("\\");
    }

    @Test
    void deserializingWithTheProjectDirectoryYieldsAbsoluteExistingPathsInOrderAndNothingMissing()
            throws IOException {
        String xml = serializer.serialize(project);
        ProjectDeserializer deserializer = new ProjectDeserializer();

        AudioClip loaded = onlyClip(deserializer.deserialize(xml, projectDir));

        Path head = Path.of(loaded.getSourceFilePath());
        assertThat(head.isAbsolute()).as("in-memory head is absolute").isTrue();
        assertThat(head.startsWith(projectDir.toAbsolutePath())).isTrue();
        assertThat(head).isRegularFile();
        assertThat(loaded.getSourceSegmentPaths())
                .containsExactly(canonical(segment0), canonical(segment1));
        assertThat(loaded.getSourceFilePath()).isEqualTo(loaded.getSourceSegmentPaths().getFirst());
        assertThat(SegmentFile.describe(Path.of(loaded.getSourceSegmentPaths().get(1))).frameCount())
                .isEqualTo(FRAMES_SEGMENT_1);
        assertThat(deserializer.getMissingFiles()).isEmpty();
    }

    @Test
    void aCopiedProjectDirectoryResolvesInsideTheCopyAndReportsExactlyTheDeletedSegments()
            throws IOException {
        String xml = serializer.serialize(project);
        Path copyDir = tempDir.resolve("Copy Of Project");
        copyTree(projectDir, copyDir);
        Path copiedSegment0 = copyDir.resolve(relativeSegment(0));
        Path copiedSegment1 = copyDir.resolve(relativeSegment(1));
        assertThat(copiedSegment1).as("fixture: the copy holds both segments").isRegularFile();

        ProjectDeserializer deserializer = new ProjectDeserializer();
        AudioClip moved = onlyClip(deserializer.deserialize(xml, copyDir));
        assertThat(Path.of(moved.getSourceFilePath()).startsWith(copyDir.toAbsolutePath())).isTrue();
        assertThat(moved.getSourceSegmentPaths())
                .containsExactly(canonical(copiedSegment0), canonical(copiedSegment1));
        assertThat(deserializer.getMissingFiles()).isEmpty();

        Files.delete(copiedSegment1);
        deserializer.deserialize(xml, copyDir);
        assertThat(deserializer.getMissingFiles())
                .as("only the deleted segment is reported, by its resolved path")
                .containsExactly(canonical(copiedSegment1));

        Files.delete(copiedSegment0);
        deserializer.deserialize(xml, copyDir);
        assertThat(deserializer.getMissingFiles())
                .as("the head segment is reported once, not again through source-file")
                .containsExactly(canonical(copiedSegment0), canonical(copiedSegment1));
    }

    @Test
    void anAbsoluteReferenceOutsideTheProjectRootStaysVerbatimOnWriteAndRead() throws IOException {
        Path library = Files.createDirectories(tempDir.resolve("library"));
        Path sample = Files.writeString(library.resolve("sample.wav"), "not really a wav");
        assertThat(sample.toAbsolutePath().startsWith(projectDir.toAbsolutePath()))
                .as("fixture: the sample lives outside the project root")
                .isFalse();
        Track track = project.createAudioTrack("Library");
        track.addClip(new AudioClip("Library sample", 0.0, 1.0, sample.toString()));

        String xml = serializer.serialize(project);
        assertThat(xml).contains("source-file=\"" + sample + "\"");

        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject loaded = deserializer.deserialize(xml, projectDir);
        AudioClip libraryClip = loaded.getTracks().get(1).getClips().getFirst();
        assertThat(libraryClip.getSourceFilePath()).isEqualTo(sample.toString());
        assertThat(libraryClip.getSourceSegmentPaths()).isEmpty();
        assertThat(deserializer.getMissingFiles()).isEmpty();
    }

    @Test
    void aLegacyAbsoluteReferenceInsideTheProjectRootLoadsAndIsRewrittenRelativeOnTheNextSave()
            throws IOException {
        // An old build had no project root to relativise against: it wrote
        // the absolute path verbatim. Reproduce that file exactly.
        project.setMetadata(project.getMetadata().withPath(null));
        String legacyXml = serializer.serialize(project);
        assertThat(legacyXml).contains("source-file=\"" + segment0 + "\"");

        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject loaded = deserializer.deserialize(legacyXml, projectDir);
        AudioClip clip = onlyClip(loaded);
        assertThat(clip.getSourceFilePath()).isEqualTo(segment0.toString());
        assertThat(clip.getSourceSegmentPaths()).containsExactly(segment0.toString(), segment1.toString());
        assertThat(deserializer.getMissingFiles()).isEmpty();

        // ProjectManager.openProject stamps the directory onto the loaded
        // project; the next save writes the reference project-relative.
        loaded.setMetadata(loaded.getMetadata().withPath(projectDir));
        String resaved = serializer.serialize(loaded);
        assertThat(resaved)
                .contains("source-file=\"" + relativeSegment(0) + "\"")
                .contains("<source-segment path=\"" + relativeSegment(1) + "\"/>")
                .doesNotContain(projectDir.toString());
    }

    @Test
    void oneArgDeserializeKeepsARelativeReferenceVerbatimAndChecksItAgainstTheWorkingDirectory()
            throws IOException {
        // "Today": ProjectDeserializer.deserialize(String) has no base, so a
        // relative reference is neither rebased nor resolved — it stays the
        // relative string, and Files.exists() judges it from the JVM's
        // working directory (where the take does not exist).
        String xml = serializer.serialize(project);
        assertThat(Files.exists(Path.of(relativeSegment(0))))
                .as("fixture: the working directory must not contain the take")
                .isFalse();

        ProjectDeserializer deserializer = new ProjectDeserializer();
        AudioClip clip = onlyClip(deserializer.deserialize(xml));

        assertThat(clip.getSourceFilePath()).isEqualTo(relativeSegment(0));
        assertThat(clip.getSourceSegmentPaths()).containsExactly(relativeSegment(0), relativeSegment(1));
        assertThat(deserializer.getMissingFiles()).containsExactly(relativeSegment(0), relativeSegment(1));
    }

    private String relativeSegment(int index) {
        return "audio/takes/" + TAKE_DIR_NAME + "/" + trackId + "/segment-00" + index + ".wav";
    }

    private static String canonical(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static AudioClip onlyClip(DawProject loaded) {
        assertThat(loaded.getTracks()).hasSize(1);
        assertThat(loaded.getTracks().getFirst().getClips()).hasSize(1);
        return loaded.getTracks().getFirst().getClips().getFirst();
    }

    private static Path writeSealedSegment(Path partPath, int frames) throws IOException {
        float[][] block = new float[1][frames];
        for (int i = 0; i < frames; i++) {
            block[0][i] = (i % 200 - 100) / 128f;
        }
        Path sealed;
        try (SegmentWriter writer = SegmentWriter.open(partPath, SAMPLE_RATE, 1, 16,
                Duration.ZERO, System::nanoTime)) {
            writer.append(block, 1, frames);
            sealed = writer.seal();
        }
        SegmentFile.Description description = SegmentFile.describe(sealed);
        assertThat(description.sealed()).as("fixture: %s is sealed", sealed).isTrue();
        assertThat(description.frameCount()).isEqualTo(frames);
        return sealed;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
