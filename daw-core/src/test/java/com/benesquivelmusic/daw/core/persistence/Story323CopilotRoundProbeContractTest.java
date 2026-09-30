package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 probe, PR #978 Copilot round, findings F2 (an empty segment path
 * cannot survive a save) and F3 (a relative reference must not escape the
 * project directory).
 *
 * <p>Pins what the round's own tests leave open. {@link ProjectPaths} judges
 * containment name by name, so a reference into a sibling whose name merely
 * starts with the project directory's name escapes, and an absolute path in
 * such a sibling is written verbatim; a non-normalised project directory
 * judges exactly like its normal form. The deserializer reports escaping and
 * absent references across several clips in one list, in document order — an
 * escaping head segment and an escaping last segment in their places, a real
 * file at an escaping target notwithstanding — with one WARNING per escaping
 * reference naming its clip and its position. And a whitespace-only segment,
 * which {@code AudioClip.setSourceSegmentPaths} accepts, survives a save and a
 * load in its place, because the reader skips only an empty path.</p>
 */
class Story323CopilotRoundProbeContractTest {

    private static final Pattern REFERENCE = Pattern.compile("(?:source-file|<source-segment path)=\"([^\"]*)\"");
    private static final String ESCAPING_SINGLE = "a/b/../../../stray.wav";
    private static final String ESCAPING_HEAD = "../../stray-head.wav";
    private static final String ESCAPING_LAST = "audio/./../../stray-tail.wav";

    @TempDir
    Path tempDir;

    private final ProjectSerializer serializer = new ProjectSerializer();

    @Test
    void containmentIsJudgedNameByNameSoASiblingSharingTheRootNamePrefixEscapes() {
        Path root = tempDir.resolve("Session");
        for (String reference : List.of("../Session-old/audio/x.wav", "../Session2/x.wav", "../Sessionx.wav")) {
            assertThat(ProjectPaths.unresolvable(root, reference)).as(reference)
                    .contains(ProjectPaths.Unresolvable.ESCAPES_THE_DIRECTORY);
            assertThat(ProjectPaths.resolve(root, reference)).as(reference).isEqualTo(reference);
        }

        String inTheSibling = tempDir.resolve("Session-old").resolve("audio").resolve("x.wav")
                .toAbsolutePath().normalize().toString();
        assertThat(inTheSibling).as("fixture: a string-prefix test would call it inside")
                .startsWith(root.toAbsolutePath().normalize().toString());
        assertThat(ProjectPaths.relativize(root, inTheSibling)).isEqualTo(inTheSibling);
    }

    @Test
    void aNonNormalisedRootJudgesExactlyLikeItsNormalForm() {
        Path normal = tempDir.resolve("Session").toAbsolutePath().normalize();
        Path unnormalised = tempDir.resolve("scratch").resolve("..").resolve(".").resolve("Session");
        assertThat(unnormalised.toAbsolutePath().normalize()).as("fixture").isEqualTo(normal);
        assertThat(unnormalised.toAbsolutePath()).as("fixture").isNotEqualTo(normal);

        assertThat(ProjectPaths.unresolvable(unnormalised, "audio/takes/x.wav")).isEmpty();
        assertThat(ProjectPaths.resolve(unnormalised, "audio/takes/x.wav"))
                .isEqualTo(normal.resolve("audio").resolve("takes").resolve("x.wav").toString());
        assertThat(ProjectPaths.unresolvable(unnormalised, "../x.wav"))
                .contains(ProjectPaths.Unresolvable.ESCAPES_THE_DIRECTORY);
        assertThat(ProjectPaths.resolve(unnormalised, "../x.wav")).isEqualTo("../x.wav");
        assertThat(ProjectPaths.unresolvable(unnormalised, "audio/.."))
                .contains(ProjectPaths.Unresolvable.IS_THE_DIRECTORY);
        assertThat(ProjectPaths.relativize(unnormalised, normal.resolve("audio").resolve("x.wav").toString()))
                .isEqualTo("audio/x.wav");
    }

    @Test
    void severalClipsReportEscapingAndAbsentReferencesInDocumentOrderAndPresentOnesNot() throws IOException {
        Mixed mixed = mixedProject();
        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();

        DawProject loaded = deserializeRecordingWarnings(deserializer, mixed.xml(), mixed.projectDir(), warnings);

        Path rootAbs = mixed.projectDir().toAbsolutePath().normalize();
        String present = rootAbs.resolve("audio").resolve("present.wav").toString();
        String absent = rootAbs.resolve("audio").resolve("absent.wav").toString();
        String seg1 = rootAbs.resolve("audio").resolve("seg-1.wav").toString();
        String seg2Absent = rootAbs.resolve("audio").resolve("seg-2-absent.wav").toString();
        assertThat(deserializer.getMissingFiles())
                .as("escaping references in place, a file at the target notwithstanding; absent ones resolved")
                .containsExactly(ESCAPING_SINGLE, absent, ESCAPING_HEAD, seg2Absent, ESCAPING_LAST);

        List<AudioClip> singles = loaded.getTracks().get(0).getClips();
        assertThat(singles).extracting(AudioClip::getSourceFilePath).containsExactly(present, ESCAPING_SINGLE, absent);
        List<AudioClip> takes = loaded.getTracks().get(1).getClips();
        assertThat(takes.get(0).getSourceSegmentPaths()).containsExactly(ESCAPING_HEAD, seg1, seg2Absent);
        assertThat(takes.get(0).getSourceFilePath()).as("the escaping head, as written").isEqualTo(ESCAPING_HEAD);
        assertThat(takes.get(1).getSourceSegmentPaths()).containsExactly(seg1, ESCAPING_LAST);
        assertThat(takes.get(1).getSourceFilePath()).isEqualTo(seg1);

        assertThat(warnings).extracting(LogRecord::getLevel).containsOnly(Level.WARNING);
        assertThat(warnings).hasSize(3);
        assertThat(warnings.get(0).getMessage()).contains("'Escaping single'").contains("source-file")
                .contains("'" + ESCAPING_SINGLE + "'");
        assertThat(warnings.get(1).getMessage()).contains("'Escaping head take'").contains("<source-segment> 0 of 3")
                .contains("'" + ESCAPING_HEAD + "'");
        assertThat(warnings.get(2).getMessage()).contains("'Escaping last take'").contains("<source-segment> 1 of 2")
                .contains("'" + ESCAPING_LAST + "'");
    }

    @Test
    void aWhitespaceOnlySegmentTheSetterAcceptsSurvivesSaveAndLoadInItsPlace() throws IOException {
        Path projectDir = Files.createDirectories(tempDir.resolve("Session"));
        DawProject project = new DawProject("Blank segment", new AudioFormat(44_100.0, 1, 16, 256));
        project.setMetadata(project.getMetadata().withPath(projectDir));
        AudioClip take = new AudioClip("Blank middle", 0.0, 2.0, null);
        take.setSourceSegmentPaths(List.of("audio/seg-0.wav", "  ", "audio/seg-2.wav"));
        project.createAudioTrack("Take").addClip(take);
        String xml = serializer.serialize(project);
        assertThat(xml).contains("<source-segment path=\"  \"/>");

        DawProject loaded = new ProjectDeserializer().deserialize(xml, projectDir);
        AudioClip clip = loaded.getTracks().getFirst().getClips().getFirst();
        assertThat(clip.getSourceSegmentPaths()).as("count and place").hasSize(3);
        assertThat(clip.getSourceSegmentPaths().get(0)).endsWith("seg-0.wav");
        assertThat(clip.getSourceSegmentPaths().get(1)).endsWith("  ");
        assertThat(clip.getSourceSegmentPaths().get(2)).endsWith("seg-2.wav");
        assertThat(clip.getSourceFilePath()).isEqualTo(clip.getSourceSegmentPaths().getFirst());

        loaded.setMetadata(loaded.getMetadata().withPath(projectDir));
        assertThat(references(serializer.serialize(loaded))).containsExactlyElementsOf(references(xml));
    }

    private record Mixed(Path projectDir, String xml) {
    }

    /**
     * A project directory two levels below the temp directory, so a two-level
     * escape lands inside the temp directory; real files at two of the three
     * escaping targets; a hand-edited file (serialised with no directory, so
     * every reference is written as held).
     */
    private Mixed mixedProject() throws IOException {
        Path projectDir = Files.createDirectories(tempDir.resolve("outer").resolve("Session"));
        Path audio = Files.createDirectories(projectDir.resolve("audio"));
        Files.writeString(audio.resolve("present.wav"), "present");
        Files.writeString(audio.resolve("seg-1.wav"), "segment one");
        Path strayTarget = projectDir.resolve(ESCAPING_SINGLE).normalize();
        Path strayHeadTarget = projectDir.resolve(ESCAPING_HEAD).normalize();
        assertThat(strayTarget).as("fixture").isEqualTo(tempDir.resolve("outer").resolve("stray.wav"));
        assertThat(strayHeadTarget).as("fixture").isEqualTo(tempDir.resolve("stray-head.wav"));
        Files.writeString(strayTarget, "outside the project");
        Files.writeString(strayHeadTarget, "outside the project, two levels up");

        DawProject edited = new DawProject("Mixed", new AudioFormat(44_100.0, 1, 16, 256));
        Track singles = edited.createAudioTrack("Singles");
        singles.addClip(new AudioClip("Present single", 0.0, 1.0, "audio/present.wav"));
        singles.addClip(new AudioClip("Escaping single", 1.0, 1.0, ESCAPING_SINGLE));
        singles.addClip(new AudioClip("Absent single", 2.0, 1.0, "audio/absent.wav"));
        Track takes = edited.createAudioTrack("Takes");
        AudioClip escapingHead = new AudioClip("Escaping head take", 0.0, 3.0, null);
        escapingHead.setSourceSegmentPaths(List.of(ESCAPING_HEAD, "audio/seg-1.wav", "audio/seg-2-absent.wav"));
        takes.addClip(escapingHead);
        AudioClip escapingLast = new AudioClip("Escaping last take", 4.0, 2.0, null);
        escapingLast.setSourceSegmentPaths(List.of("audio/seg-1.wav", ESCAPING_LAST));
        takes.addClip(escapingLast);
        return new Mixed(projectDir, serializer.serialize(edited));
    }

    private static DawProject deserializeRecordingWarnings(ProjectDeserializer deserializer, String xml,
                                                           Path projectDir, List<LogRecord> warnings)
            throws IOException {
        Logger logger = Logger.getLogger(ProjectDeserializer.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            return deserializer.deserialize(xml, projectDir);
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static List<String> references(String xml) {
        List<String> references = new ArrayList<>();
        Matcher matcher = REFERENCE.matcher(xml);
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
        return references;
    }
}
