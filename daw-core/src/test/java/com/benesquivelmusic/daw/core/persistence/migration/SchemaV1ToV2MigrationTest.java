package com.benesquivelmusic.daw.core.persistence.migration;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.persistence.UnresolvedMidiEffect;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden test of the production schema 1 → 2 step
 * ({@link MidiEffectPluginIdMigration}): a MIDI-effect chain entry keyed by
 * the arpeggiator's schema 1 class name is re-keyed by its plugin id, an
 * unknown class name is left untouched, nothing else in the document changes,
 * and the step is recorded as format-only. Then the step end to end: a
 * schema 1 file opens with its chain and pre/post-roll restored and an empty
 * MIDI clip, its first save backs up the original once and writes version 2,
 * a newer file is refused, and a class named in the file is never loaded.
 */
class SchemaV1ToV2MigrationTest {

    /** A schema 1 file as the schema 1 serializer wrote it: chain keyed by class, pre/post-roll set, no MIDI clip. */
    private static final String V1_WITH_CHAIN_AND_PRE_ROLL = """
            <daw-project version="1">
              <metadata>
                <name>Old Song</name>
              </metadata>
              <transport loop-enabled="false" loop-end="16.0" loop-start="0.0" position="0.0" post-roll-bars="1" pre-roll-bars="2" pre-roll-enabled="true" tempo="96.0" time-sig-denominator="4" time-sig-numerator="4"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI" volume="1.0">
                  <midi-effect-chain>
                    <midi-effect class="com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin" gate="80.0" latch="true" octave-range="2" pattern="UP_DOWN" rate="EIGHTH" swing="12.5"/>
                    <midi-effect class="com.example.midi.ChordGenerator" voicing="drop2"/>
                  </midi-effect-chain>
                </track>
              </tracks>
              <mixer>
                <master name="Master" volume="1.0"/>
                <channels>
                  <channel name="Keys" volume="0.75"/>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """;

    private static final String V2_WITH_CHAIN_AND_PRE_ROLL = """
            <daw-project version="2">
              <metadata>
                <name>Old Song</name>
              </metadata>
              <transport loop-enabled="false" loop-end="16.0" loop-start="0.0" position="0.0" post-roll-bars="1" pre-roll-bars="2" pre-roll-enabled="true" tempo="96.0" time-sig-denominator="4" time-sig-numerator="4"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI" volume="1.0">
                  <midi-effect-chain>
                    <midi-effect gate="80.0" latch="true" octave-range="2" pattern="UP_DOWN" plugin-id="com.benesquivelmusic.daw.builtin.arpeggiator" rate="EIGHTH" swing="12.5"/>
                    <midi-effect class="com.example.midi.ChordGenerator" voicing="drop2"/>
                  </midi-effect-chain>
                </track>
              </tracks>
              <mixer>
                <master name="Master" volume="1.0"/>
                <channels>
                  <channel name="Keys" volume="0.75"/>
                </channels>
                <return-buses/>
              </mixer>
            </daw-project>
            """;

    /** An older schema 1 shape: neither a chain nor pre/post-roll. */
    private static final String V1_WITHOUT_EITHER = """
            <daw-project version="1">
              <metadata>
                <name>Older Song</name>
              </metadata>
              <transport loop-enabled="false" position="0.0" tempo="120.0"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000b1" name="Drums" type="AUDIO"/>
              </tracks>
            </daw-project>
            """;

    @TempDir
    Path tempDir;

    @Test
    void goldenSchemaOneFileMigratesToTheExpectedSchemaTwoDocument() throws Exception {
        MigrationRegistry.MigrationResult result =
                MigrationRegistry.defaultRegistry().migrate(parse(V1_WITH_CHAIN_AND_PRE_ROLL));

        assertThat(serialize(result.document())).isEqualTo(serialize(parse(V2_WITH_CHAIN_AND_PRE_ROLL)));
        assertThat(result.report().fromVersion()).isEqualTo(1);
        assertThat(result.report().toVersion()).isEqualTo(2);
        assertThat(result.report().applied()).containsExactly(new MigrationReport.AppliedMigration(
                1, 2, "add per-track MIDI clips; key MIDI effects by id", false));
        assertThat(result.report().wasMigrated()).isTrue();
        assertThat(result.report().changesAuthoredContent()).isFalse();
    }

    @Test
    void anOlderShapeOnlyChangesItsVersion() throws Exception {
        Document migrated = MigrationRegistry.defaultRegistry().migrate(parse(V1_WITHOUT_EITHER)).document();

        assertThat(serialize(migrated)).isEqualTo(serialize(parse(
                V1_WITHOUT_EITHER.replace("version=\"1\"", "version=\"2\""))));
    }

    @Test
    void theTableNamesTheArpeggiatorAsItIsKeyedToday() {
        assertThat(MidiEffectPluginIdMigration.apply(parseUnchecked("""
                <daw-project><tracks><track><midi-effect-chain>
                <midi-effect class="com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin"/>
                </midi-effect-chain></track></tracks></daw-project>
                """)).getElementsByTagName("midi-effect").item(0).getAttributes()
                .getNamedItem("plugin-id").getNodeValue())
                .isEqualTo(ArpeggiatorPlugin.PLUGIN_ID)
                .isEqualTo(new ArpeggiatorPlugin().getDescriptor().id());
    }

    @Test
    void theDeserializerRestoresTheChainAndPreRollAndAnEmptyMidiClip() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(V1_WITH_CHAIN_AND_PRE_ROLL);

        Track keys = project.getTracks().getFirst();
        assertThat(keys.getMidiClip().getNotes()).isEmpty();
        assertThat(keys.getMidiClip().getCcLanes()).isEmpty();
        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(new PreRollPostRoll(2, 1, true));
        List<MidiEffectPlugin> chain = keys.getMidiEffectChain();
        assertThat(chain).hasSize(2);
        ArpeggiatorPlugin arp = (ArpeggiatorPlugin) chain.get(0);
        assertThat(arp.getRate()).isEqualTo(ArpeggiatorPlugin.Rate.EIGHTH);
        assertThat(arp.getPattern()).isEqualTo(ArpeggiatorPlugin.Pattern.UP_DOWN);
        assertThat(arp.getOctaveRange()).isEqualTo(2);
        assertThat(arp.getGate()).isEqualTo(80.0);
        assertThat(arp.getSwing()).isEqualTo(12.5);
        assertThat(arp.isLatch()).isTrue();
        UnresolvedMidiEffect unknown = (UnresolvedMidiEffect) chain.get(1);
        assertThat(unknown.attributes())
                .containsExactly(java.util.Map.entry("class", "com.example.midi.ChordGenerator"),
                        java.util.Map.entry("voicing", "drop2"));
        assertThat(deserializer.getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind)
                .isEqualTo(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED);
        assertThat(new ProjectSerializer().serialize(project))
                .as("the unknown schema 1 entry is written back as it was")
                .contains("<midi-effect class=\"com.example.midi.ChordGenerator\" voicing=\"drop2\"/>");

        DawProject older = deserializer.deserialize(V1_WITHOUT_EITHER);

        Track drums = older.getTracks().getFirst();
        assertThat(drums.getMidiClip().getNotes()).isEmpty();
        assertThat(drums.getMidiEffectChain()).isEmpty();
        assertThat(older.getTransport().getPreRollPostRoll()).isEqualTo(PreRollPostRoll.DISABLED);
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.getLastMigrationReport().wasMigrated()).isTrue();
    }

    @Test
    void firstSaveBacksUpTheSchemaOneFileOnceAndWritesVersionTwo() throws Exception {
        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        ProjectMetadata metadata = manager.createProject("Old Song", tempDir);
        Path projectFile = metadata.projectPath().resolve("project.daw");
        Files.writeString(projectFile, V1_WITH_CHAIN_AND_PRE_ROLL);
        byte[] original = Files.readAllBytes(projectFile);
        manager.abandonProject();

        manager.openProject(metadata.projectPath());
        MigrationReport report = manager.getLastMigrationReport();
        assertThat(report.wasMigrated()).isTrue();
        assertThat(report.changesAuthoredContent()).isFalse();

        DawProject project = manager.getCurrentDawProject();
        saved(manager, project);
        List<Path> backups = backupsIn(metadata.projectPath());
        assertThat(backups).hasSize(1);
        assertThat(backups.getFirst().getFileName().toString()).startsWith("project.daw.v1.");
        assertThat(Files.readAllBytes(backups.getFirst())).isEqualTo(original);
        assertThat(Files.readString(projectFile)).contains("<daw-project version=\"2\">");

        saved(manager, project);
        assertThat(backupsIn(metadata.projectPath())).as("a second save writes no new backup").hasSize(1);
        manager.abandonProject();

        manager.openProject(metadata.projectPath());
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("reopening is a no-op").isFalse();
        manager.abandonProject();
    }

    @Test
    void aNewerSchemaIsRefused() {
        assertThatThrownBy(() -> new ProjectDeserializer().deserialize("<daw-project version=\"3\"/>"))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("newer than this build's version 2");
    }

    @Test
    void aClassNamedInTheFileIsNeverLoaded() throws Exception {
        // Naming the sentinel through its class literal does not initialise it.
        String sentinel = InitializationSentinel.class.getName();
        assertThat(SENTINEL_INITIALIZED).as("the sentinel has not run yet").isFalse();

        DawProject project = new ProjectDeserializer().deserialize("""
                <daw-project version="1">
                  <tracks>
                    <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI">
                      <midi-effect-chain><midi-effect class="%s"/></midi-effect-chain>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(sentinel));

        assertThat(project.getTracks().getFirst().getMidiEffectChain())
                .singleElement().isInstanceOf(UnresolvedMidiEffect.class);
        assertThat(SENTINEL_INITIALIZED).as("the class named in the file was not loaded").isFalse();

        // Non-vacuity: the sentinel's initializer does run once the class is used.
        InitializationSentinel.touch();
        assertThat(SENTINEL_INITIALIZED).isTrue();
    }

    private static final AtomicBoolean SENTINEL_INITIALIZED = new AtomicBoolean();

    /** A class whose static initializer has a visible side effect. */
    static final class InitializationSentinel {
        static {
            SENTINEL_INITIALIZED.set(true);
        }

        private InitializationSentinel() {
        }

        static void touch() {
        }
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

    private static Document parseUnchecked(String xml) {
        try {
            return parse(xml);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }
}
