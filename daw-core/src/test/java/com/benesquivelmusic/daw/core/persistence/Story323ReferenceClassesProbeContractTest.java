package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 323 probe, PR #978 Copilot round 2: one hand-edited project file that
 * holds every class of clip reference {@link ProjectPaths} names, single-file
 * references and the elements of one segment list interleaved.
 *
 * <p>Pins, on the platform running the test and with concrete expectations for
 * Windows and for Linux: after a load with the project directory every
 * in-memory reference is absolute or is unresolvable — kept as written,
 * reported missing and named in a WARNING; the WARNINGs and the missing list
 * follow the document order across clips and segments, one WARNING per
 * unresolvable reference, each with its own reason; and the next save writes
 * every reference back exactly as the file wrote it, so a second load gives
 * the same in-memory references and the same missing list. On Windows that
 * includes a drive-relative reference on the project directory's own drive,
 * which a save that resolved it against the project directory would rewrite
 * as an in-project name. On Linux the Windows forms are ordinary names under
 * the directory and nothing but the escaping references is unresolvable.</p>
 */
class Story323ReferenceClassesProbeContractTest {

    private static final boolean WINDOWS = OS.WINDOWS.isCurrentOs();
    private static final String ESCAPES = "escapes the project directory";
    private static final String ITSELF = "is the directory itself";
    private static final String ROOTED = "is drive- or root-relative on this platform";
    private static final String INVALID = "is not a valid path on this platform";

    @TempDir
    Path tempDir;

    private final ProjectSerializer serializer = new ProjectSerializer();

    @Test
    void everyClassLoadsAbsoluteOrUnresolvableWarnsInDocumentOrderAndIsSavedBackAsWritten() throws IOException {
        Path projectDir = Files.createDirectories(tempDir.resolve("outer").resolve("Session"));
        Files.writeString(Files.createDirectories(projectDir.resolve("audio")).resolve("present.wav"), "present");
        Files.writeString(tempDir.resolve("outer").resolve("outside.wav"), "where ../outside.wav leads");
        String library = Files.writeString(Files.createDirectories(tempDir.resolve("library")).resolve("lib.wav"),
                "an asset outside the project").toAbsolutePath().toString();
        // On Windows: drive-relative on the project directory's own drive.
        String projectRoot = projectDir.toAbsolutePath().getRoot().toString();
        assumeTrue(!WINDOWS || projectRoot.matches("[A-Za-z]:.*"),
                "fixture: on Windows the temp directory is on a drive letter, which a drive-relative reference"
                        + " can name; its root is " + projectRoot);
        String drive = WINDOWS ? projectRoot.substring(0, 2) : "C:";
        String driveRelative = drive + "x.wav";
        String rootRelative = "\\x.wav";
        String unparseable = "bad|name.wav";

        DawProject edited = new DawProject("Hand edited", AudioFormat.CD_QUALITY);
        Track track = edited.createAudioTrack("Mixed");
        track.addClip(new AudioClip("Relative present", 0.0, 1.0, "audio/present.wav"));
        AudioClip take = new AudioClip("Take", 1.0, 1.0, null);
        take.setSourceSegmentPaths(List.of("audio/present.wav", driveRelative, "../outside.wav", "audio/..", library));
        track.addClip(take);
        track.addClip(new AudioClip("Unparseable", 2.0, 1.0, unparseable));
        track.addClip(new AudioClip("Root relative", 3.0, 1.0, rootRelative));
        track.addClip(new AudioClip("Absolute library", 4.0, 1.0, library));
        track.addClip(new AudioClip("Directory itself", 5.0, 1.0, "."));
        String xml = serializer.serialize(edited);
        assertThat(references(xml)).as("fixture: a hand-edited file holds every reference as written")
                .containsExactly("audio/present.wav",
                        "audio/present.wav", "audio/present.wav", driveRelative, "../outside.wav", "audio/..", library,
                        unparseable, rootRelative, library, ".");

        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        DawProject loaded = deserialize(deserializer, xml, projectDir, warnings);

        Path rootAbs = projectDir.toAbsolutePath().normalize();
        String present = rootAbs.resolve("audio").resolve("present.wav").toString();
        List<AudioClip> clips = loaded.getTracks().getFirst().getClips();
        String inMemoryDriveRelative = WINDOWS ? driveRelative : rootAbs.resolve(driveRelative).toString();
        String inMemoryUnparseable = WINDOWS ? unparseable : rootAbs.resolve(unparseable).toString();
        String inMemoryRootRelative = WINDOWS ? rootRelative : rootAbs.resolve(rootRelative).toString();
        assertThat(clips).extracting(AudioClip::getSourceFilePath).containsExactly(
                present, present, inMemoryUnparseable, inMemoryRootRelative, library, ".");
        assertThat(clips.get(1).getSourceSegmentPaths())
                .containsExactly(present, inMemoryDriveRelative, "../outside.wav", "audio/..", library);
        assertThat(deserializer.getMissingFiles())
                .as("unresolvable references unconditionally, absent ones by their in-memory path, in document order")
                .containsExactly(inMemoryDriveRelative, "../outside.wav", "audio/..", inMemoryUnparseable,
                        inMemoryRootRelative, ".");

        assertThat(warnings).extracting(LogRecord::getLevel).containsOnly(Level.WARNING);
        List<String> expectedWarnings = new ArrayList<>();
        if (WINDOWS) {
            expectedWarnings.add("'Take': <source-segment> 1 of 5 '" + driveRelative + "' " + ROOTED);
        }
        expectedWarnings.add("'Take': <source-segment> 2 of 5 '../outside.wav' " + ESCAPES);
        expectedWarnings.add("'Take': <source-segment> 3 of 5 'audio/..' " + ITSELF);
        if (WINDOWS) {
            expectedWarnings.add("'Unparseable': source-file '" + unparseable + "' " + INVALID);
            expectedWarnings.add("'Root relative': source-file '" + rootRelative + "' " + ROOTED);
        }
        expectedWarnings.add("'Directory itself': source-file '.' " + ITSELF);
        assertThat(warnings).hasSameSizeAs(expectedWarnings);
        for (int i = 0; i < expectedWarnings.size(); i++) {
            assertThat(warnings.get(i).getMessage()).as("WARNING %d", i).contains(expectedWarnings.get(i));
        }

        for (String reference : inMemoryReferences(loaded)) {
            if (!ProjectPaths.isAbsoluteReference(reference)) {
                assertThat(references(xml)).as("kept as written: [%s]", reference).contains(reference);
                assertThat(deserializer.getMissingFiles()).as("reported missing: [%s]", reference).contains(reference);
                assertThat(warnings).as("named in a WARNING: [%s]", reference)
                        .anySatisfy(w -> assertThat(w.getMessage()).contains("'" + reference + "' "));
            }
        }

        // ProjectManager.openProject stamps the directory; the next save
        // writes every reference back exactly as the file wrote it.
        loaded.setMetadata(loaded.getMetadata().withPath(projectDir));
        String resaved = serializer.serialize(loaded);
        assertThat(references(resaved)).containsExactlyElementsOf(references(xml));

        ProjectDeserializer again = new ProjectDeserializer();
        DawProject reloaded = deserialize(again, resaved, projectDir, new CopyOnWriteArrayList<>());
        assertThat(inMemoryReferences(reloaded)).containsExactlyElementsOf(inMemoryReferences(loaded));
        assertThat(again.getMissingFiles()).containsExactlyElementsOf(deserializer.getMissingFiles());
    }

    private static List<String> inMemoryReferences(DawProject project) {
        List<String> references = new ArrayList<>();
        for (AudioClip clip : project.getTracks().getFirst().getClips()) {
            references.add(clip.getSourceFilePath());
            references.addAll(clip.getSourceSegmentPaths());
        }
        return references;
    }

    /**
     * Every clip reference the file holds — each {@code source-file} attribute and each
     * {@code <source-segment path>} — in document order, read the way {@link ProjectDeserializer} reads
     * them: through an XML parser, so a character the file escapes (a temp path's {@code &}, written
     * {@code &amp;}) comes back as itself.
     */
    private static List<String> references(String xml) throws IOException {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("fixture: the project file does not parse as XML", e);
        }
        List<String> references = new ArrayList<>();
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.hasAttribute("source-file")) {
                references.add(element.getAttribute("source-file"));
            }
            if (element.getTagName().equals("source-segment") && element.hasAttribute("path")) {
                references.add(element.getAttribute("path"));
            }
        }
        return references;
    }

    private static DawProject deserialize(ProjectDeserializer deserializer, String xml, Path projectDir,
                                          List<LogRecord> warnings) throws IOException {
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
}
