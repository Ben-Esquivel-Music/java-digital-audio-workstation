package com.benesquivelmusic.daw.core.persistence.migration;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.TempoChangeEvent;
import com.benesquivelmusic.daw.core.transport.TempoMap;
import com.benesquivelmusic.daw.core.transport.TimeSignatureChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
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

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden test of the production schema 2 → 3 step (story 334): schema 3 only
 * adds plugin insert descriptors, take lanes, comp regions and the tempo map,
 * which a schema 2 file simply lacks, so the step leaves the document as it
 * was but for its version and is recorded as format-only. Then the step end
 * to end: a schema 2 file opens with every new item at its default — a tempo
 * map of beat 0 only, no take lanes — and nothing reported, its first save
 * backs up the original once and writes the current version, and a newer
 * file is refused.
 */
class SchemaV2ToV3MigrationTest {

    /**
     * A schema 2 file as the schema 2 serializer wrote it: transport
     * attributes, a track with a clip and its fold state, a built-in insert
     * with a named parameter.
     */
    private static final String V2_SONG = """
            <daw-project version="2">
              <metadata>
                <name>Old Song</name>
              </metadata>
              <transport loop-enabled="true" loop-end="24.0" loop-start="8.0" position="4.0" tempo="96.0" time-sig-denominator="8" time-sig-numerator="6"/>
              <tracks>
                <track automation-folded="false" id="00000000-0000-0000-0000-0000000000a1" midi-folded="false" name="Drums" takes-folded="true" type="AUDIO" volume="0.8">
                  <clips>
                    <clip duration-beats="4.0" name="Take 1" source-file="audio/drums.wav" start-beat="0.0"/>
                  </clips>
                </track>
              </tracks>
              <mixer>
                <master name="Master" volume="1.0"/>
                <channels>
                  <channel name="Drums" volume="0.75">
                    <inserts>
                      <insert bypassed="false" effect-type="COMPRESSOR" expensive="false" name="Compressor">
                        <parameter name="Threshold" value="-18.0"/>
                      </insert>
                    </inserts>
                  </channel>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """;

    /**
     * The production steps up to schema 3 only, so the golden test keeps
     * pinning the 2 → 3 step once a later step is registered (as
     * {@code SchemaV1ToV2MigrationTest} does for 1 → 2).
     */
    private static final MigrationRegistry UP_TO_THREE = upToVersionThree();

    @TempDir
    Path tempDir;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final Logger deserializerLog = Logger.getLogger(ProjectDeserializer.class.getName());
    private final Handler warningHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
                warnings.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listenToTheDeserializer() {
        deserializerLog.addHandler(warningHandler);
    }

    @AfterEach
    void stopListening() {
        deserializerLog.removeHandler(warningHandler);
    }

    @Test
    void aSchemaTwoFileMigratesToTheSameDocumentAtVersionThree() throws Exception {
        MigrationRegistry.MigrationResult result = UP_TO_THREE.migrate(parse(V2_SONG));

        assertThat(serialize(result.document()))
                .isEqualTo(serialize(parse(V2_SONG.replace("version=\"2\"", "version=\"3\""))));
        assertThat(result.report().fromVersion()).isEqualTo(2);
        assertThat(result.report().toVersion()).isEqualTo(3);
        assertThat(result.report().applied()).containsExactly(new MigrationReport.AppliedMigration(
                2, 3, "add plugin insert descriptors, take lanes, comp regions and the tempo map", false));
        assertThat(result.report().wasMigrated()).isTrue();
        assertThat(result.report().changesAuthoredContent()).as("no migration dialog").isFalse();
    }

    @Test
    void aSchemaTwoFileOpensWithEveryNewItemDefaulted() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(V2_SONG);

        TempoMap map = project.getTransport().getTempoMap();
        assertThat(map.getTempoChanges()).as("one tempo entry: beat 0")
                .containsExactly(TempoChangeEvent.instant(0.0, 96.0));
        assertThat(map.getTimeSignatureChanges()).as("one meter entry: beat 0")
                .containsExactly(new TimeSignatureChangeEvent(0.0, 6, 8));
        Track drums = project.getTracks().getFirst();
        assertThat(drums.getTakeComping().getTakeLanes()).as("no take lanes").isEmpty();
        assertThat(drums.getTakeComping().getCompRegions()).as("no comp regions").isEmpty();
        assertThat(drums.getFoldState().takesFolded()).as("the fold state still reads").isTrue();
        List<InsertSlot> inserts = project.getMixer().getChannels().getFirst().getInsertSlots();
        assertThat(inserts).as("the built-in insert reads as before").singleElement()
                .satisfies(slot -> assertThat(slot.getName()).isEqualTo("Compressor"));
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();
        assertThat(warnings).as("no WARNING").isEmpty();
        assertThat(deserializer.getLastMigrationReport().wasMigrated()).isTrue();
    }

    @Test
    void firstSaveBacksUpTheSchemaTwoFileOnceAndWritesVersionThree() throws Exception {
        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        ProjectMetadata metadata = manager.createProject("Old Song", tempDir);
        Path projectFile = metadata.projectPath().resolve("project.daw");
        Files.writeString(projectFile, V2_SONG);
        byte[] original = Files.readAllBytes(projectFile);
        manager.closeProject().join();
        try {
            manager.openProject(metadata.projectPath());
            MigrationReport report = manager.getLastMigrationReport();
            assertThat(report.wasMigrated()).isTrue();
            assertThat(report.changesAuthoredContent()).isFalse();

            DawProject project = manager.getCurrentDawProject();
            saved(manager, project);
            List<Path> backups = backupsIn(metadata.projectPath());
            assertThat(backups).singleElement().satisfies(backup -> {
                assertThat(backup.getFileName().toString()).startsWith("project.daw.v2.");
                assertThat(Files.readAllBytes(backup)).as("the original bytes").isEqualTo(original);
            });
            assertThat(Files.readString(projectFile))
                    .contains("<daw-project version=\"" + MigrationRegistry.CURRENT_VERSION + "\">");

            saved(manager, project);
            assertThat(backupsIn(metadata.projectPath())).as("a second save writes no new backup").hasSize(1);
            manager.closeProject().join();

            manager.openProject(metadata.projectPath());
            assertThat(manager.getLastMigrationReport().wasMigrated()).as("reopening is a no-op").isFalse();
        } finally {
            manager.closeProject().join();
        }
    }

    @Test
    void aNewerSchemaIsRefused() {
        assertThatThrownBy(() -> new ProjectDeserializer().deserialize(
                "<daw-project version=\"" + (MigrationRegistry.CURRENT_VERSION + 1) + "\"/>"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("newer than this build's version " + MigrationRegistry.CURRENT_VERSION);
    }

    private static MigrationRegistry upToVersionThree() {
        MigrationRegistry.Builder builder = MigrationRegistry.builder(3);
        for (ProjectMigration step : MigrationRegistry.defaultRegistry().migrations()) {
            if (step.toVersion() <= 3) {
                builder.add(step);
            }
        }
        return builder.build();
    }

    private static List<Path> backupsIn(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).toList();
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }
}
