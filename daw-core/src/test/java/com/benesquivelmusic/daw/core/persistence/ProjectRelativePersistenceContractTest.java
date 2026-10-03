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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
class ProjectRelativePersistenceContractTest {

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

    @Test
    void aReferenceThatEscapesTheProjectDirectoryIsKeptAsWrittenWarnedAboutAndReportedMissing()
            throws IOException {
        String escaping = "../outside.wav";
        // A real file exactly where a resolver that followed the reference out
        // of the project would land: such a resolver would find it and report
        // nothing missing, which is what makes this test discriminating.
        Path outside = Files.writeString(projectDir.getParent().resolve("outside.wav"), "not really a wav");
        assertThat(projectDir.resolve(escaping).normalize())
                .as("fixture: the escaping reference names a file that exists")
                .isEqualTo(outside.normalize());

        // A hand-edited project.daw: serialised with no project directory, so
        // every reference is written exactly as held.
        DawProject edited = new DawProject("Hand edited", new AudioFormat(SAMPLE_RATE, 1, 16, 512));
        Track editedTrack = edited.createAudioTrack("Edited");
        editedTrack.addClip(new AudioClip("Escaping head", 0.0, 1.0, escaping));
        AudioClip take = new AudioClip("Escaping middle segment", 1.0, 1.0, null);
        take.setSourceSegmentPaths(List.of(relativeSegment(0), escaping, relativeSegment(1)));
        editedTrack.addClip(take);
        String xml = serializer.serialize(edited);
        assertThat(xml).contains("source-file=\"" + escaping + "\"")
                .contains("<source-segment path=\"" + escaping + "\"/>");

        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(ProjectDeserializer.class.getName());
        Handler handler = recordingHandler(records);
        logger.addHandler(handler);
        DawProject loaded;
        try {
            loaded = deserializer.deserialize(xml, projectDir);
        } finally {
            logger.removeHandler(handler);
        }

        List<AudioClip> clips = loaded.getTracks().getFirst().getClips();
        assertThat(clips).hasSize(2);
        assertThat(clips.get(0).getSourceFilePath()).as("kept as written").isEqualTo(escaping);
        assertThat(clips.get(0).getSourceSegmentPaths()).isEmpty();
        AudioClip segmented = clips.get(1);
        assertThat(segmented.getSourceSegmentPaths())
                .as("three segments, in order, the escaping one as written in its place")
                .containsExactly(canonical(segment0), escaping, canonical(segment1));
        assertThat(segmented.getSourceFilePath()).isEqualTo(segmented.getSourceSegmentPaths().getFirst());
        assertThat(deserializer.getMissingFiles())
                .as("each escaping reference is reported, although a file exists where it points")
                .containsExactly(escaping, escaping);
        assertThat(records).hasSize(2).allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("'" + escaping + "' escapes the project directory");
        });
        assertThat(records.get(0).getMessage()).contains("Escaping head").contains("source-file");
        assertThat(records.get(1).getMessage()).contains("Escaping middle segment")
                .contains("<source-segment> 1 of 3");

        // ProjectManager.openProject stamps the directory; the next save
        // writes the escaping reference back unchanged, in its place.
        loaded.setMetadata(loaded.getMetadata().withPath(projectDir));
        String resaved = serializer.serialize(loaded);
        assertThat(resaved)
                .contains("source-file=\"" + escaping + "\"")
                .containsSubsequence(
                        "<source-segment path=\"" + relativeSegment(0) + "\"/>",
                        "<source-segment path=\"" + escaping + "\"/>",
                        "<source-segment path=\"" + relativeSegment(1) + "\"/>")
                .doesNotContain(outside.toString());
    }

    @Test
    void anEscapingReferenceIsReportedMissingWithoutAnExistenceCheckFromTheWorkingDirectory()
            throws IOException {
        // ".." and "." name directories that exist from ANY working directory,
        // so Files.exists on the string as written would find both and report
        // neither. An escaping reference is never resolved, so it is reported
        // missing unconditionally.
        assertThat(Files.exists(Path.of(".."))).as("fixture").isTrue();
        assertThat(Files.exists(Path.of("."))).as("fixture").isTrue();
        DawProject edited = new DawProject("Hand edited", new AudioFormat(SAMPLE_RATE, 1, 16, 512));
        Track editedTrack = edited.createAudioTrack("Edited");
        editedTrack.addClip(new AudioClip("Parent directory", 0.0, 1.0, ".."));
        editedTrack.addClip(new AudioClip("Project directory itself", 1.0, 1.0, "."));
        String xml = serializer.serialize(edited);

        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        DawProject loaded = deserializeRecording(deserializer, xml, records);

        assertThat(loaded.getTracks().getFirst().getClips())
                .extracting(AudioClip::getSourceFilePath)
                .containsExactly("..", ".");
        assertThat(deserializer.getMissingFiles()).containsExactly("..", ".");
        assertThat(records).extracting(LogRecord::getLevel).containsExactly(Level.WARNING, Level.WARNING);
        assertThat(records.get(0).getMessage()).contains("'Parent directory'")
                .contains("'..' escapes the project directory");
        assertThat(records.get(1).getMessage()).contains("'Project directory itself'")
                .contains("'.' is the directory itself");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsDriveRelativeRootRelativeAndUnparseableReferencesAreKeptWarnedAboutAndReportedMissing()
            throws IOException {
        // A real file, named drive-relative and root-relative from the JVM's
        // working directory: an existence check on either string as written
        // would find it and report nothing, so only the unconditional report
        // lists them.
        Path found = Files.writeString(tempDir.resolve("found-from-the-working-directory.wav"), "not a wav");
        Path cwd = Path.of("").toAbsolutePath();
        assumeTrue(cwd.getRoot().equals(found.toAbsolutePath().getRoot()),
                "fixture: the temp directory and the working directory share a drive");
        String driveRelative = cwd.getRoot().toString().substring(0, 2) + cwd.relativize(found.toAbsolutePath());
        String rootRelative = found.toAbsolutePath().toString().substring(2);
        String unparseable = "bad|name.wav";
        assertThat(Files.isRegularFile(Path.of(driveRelative))).as("fixture: %s", driveRelative).isTrue();
        assertThat(Files.isRegularFile(Path.of(rootRelative))).as("fixture: %s", rootRelative).isTrue();

        DawProject edited = new DawProject("Hand edited", new AudioFormat(SAMPLE_RATE, 1, 16, 512));
        Track editedTrack = edited.createAudioTrack("Edited");
        editedTrack.addClip(new AudioClip("Drive relative", 0.0, 1.0, driveRelative));
        AudioClip take = new AudioClip("Root relative take", 1.0, 1.0, null);
        take.setSourceSegmentPaths(List.of(relativeSegment(0), rootRelative));
        editedTrack.addClip(take);
        editedTrack.addClip(new AudioClip("Unparseable", 2.0, 1.0, unparseable));
        String xml = serializer.serialize(edited);

        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        DawProject loaded = deserializeRecording(deserializer, xml, records);

        List<AudioClip> clips = loaded.getTracks().getFirst().getClips();
        assertThat(clips.get(0).getSourceFilePath()).isEqualTo(driveRelative);
        assertThat(clips.get(1).getSourceSegmentPaths()).containsExactly(canonical(segment0), rootRelative);
        assertThat(clips.get(2).getSourceFilePath()).isEqualTo(unparseable);
        assertThat(deserializer.getMissingFiles())
                .as("reported although a file exists where the first two point, in document order")
                .containsExactly(driveRelative, rootRelative, unparseable);
        assertThat(records).extracting(LogRecord::getLevel).containsOnly(Level.WARNING);
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getMessage()).contains("'Drive relative'").contains("source-file")
                .contains("'" + driveRelative + "' is drive- or root-relative on this platform");
        assertThat(records.get(1).getMessage()).contains("'Root relative take'").contains("<source-segment> 1 of 2")
                .contains("'" + rootRelative + "' is drive- or root-relative on this platform");
        assertThat(records.get(2).getMessage()).contains("'Unparseable'")
                .contains("'" + unparseable + "' is not a valid path on this platform");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void onLinuxTheWindowsFormsAreFileNamesUnderTheProjectDirectoryAndALeadingSlashIsAbsolute()
            throws IOException {
        List<String> names = List.of("C:x.wav", "\\x.wav", "bad|name.wav");
        DawProject edited = new DawProject("Hand edited", new AudioFormat(SAMPLE_RATE, 1, 16, 512));
        Track editedTrack = edited.createAudioTrack("Edited");
        for (int i = 0; i < names.size(); i++) {
            editedTrack.addClip(new AudioClip("Clip " + i, i, 1.0, names.get(i)));
        }
        editedTrack.addClip(new AudioClip("Leading slash", 3.0, 1.0, "/x.wav"));
        String xml = serializer.serialize(edited);

        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        DawProject loaded = deserializeRecording(deserializer, xml, records);

        Path rootAbs = projectDir.toAbsolutePath().normalize();
        List<String> expected = List.of(rootAbs.resolve("C:x.wav").toString(),
                rootAbs.resolve("\\x.wav").toString(), rootAbs.resolve("bad|name.wav").toString(), "/x.wav");
        assertThat(loaded.getTracks().getFirst().getClips())
                .extracting(AudioClip::getSourceFilePath)
                .containsExactlyElementsOf(expected);
        assertThat(deserializer.getMissingFiles())
                .as("each is missing only because no file is there")
                .containsExactlyElementsOf(expected);
        assertThat(records).as("nothing is unresolvable on Linux here").isEmpty();
    }

    /** Deserializes {@code xml} against {@link #projectDir}, recording what the deserializer logs. */
    private DawProject deserializeRecording(ProjectDeserializer deserializer, String xml, List<LogRecord> records)
            throws IOException {
        Logger logger = Logger.getLogger(ProjectDeserializer.class.getName());
        Handler handler = recordingHandler(records);
        logger.addHandler(handler);
        try {
            return deserializer.deserialize(xml, projectDir);
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static Handler recordingHandler(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
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
