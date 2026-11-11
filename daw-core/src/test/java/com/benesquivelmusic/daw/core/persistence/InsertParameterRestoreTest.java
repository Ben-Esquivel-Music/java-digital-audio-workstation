package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.DEGRADED_BACKUP;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.PluginIdentityKey;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.VOX;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.backupsIn;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.resolving;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.voxInserts;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An insert parameter the open cannot apply as saved skips that parameter,
 * never the insert, and never fails the open (story 334, studio rulings E6
 * (e)-(g), E12 R6-R12): a value the effect rejects, one outside its declared
 * range (clamped), one that is not a finite number, an unknown or repeated
 * name or id, a parameter with neither — each logged with its exact line,
 * counted, and backed up, because the next save writes the value in force;
 * the parameters after it are still applied.
 */
class InsertParameterRestoreTest {

    @TempDir
    Path tempDir;

    private ProjectManager manager;

    @BeforeEach
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void releaseTheManager() {
        manager.closeProject().join();
    }

    @Test
    void aRejectedLegacyParameterIsSkippedReportedAndBackedUp() throws Exception {
        // CompressorProcessor: id 0 Threshold, id 1 Ratio (its setter refuses < 1.0), id 2 Attack.
        String xml = voxInserts("<insert effect-type=\"COMPRESSOR\" name=\"Compressor\">"
                + "<parameter id=\"0\" value=\"-30.0\"/><parameter id=\"1\" value=\"0.5\"/>"
                + "<parameter id=\"2\" value=\"25.0\"/></insert>");
        Opened opened = open(xml, PluginResolver.NONE);

        CompressorProcessor compressor = (CompressorProcessor) firstVoxInsert(opened.project()).getProcessor();
        assertThat(compressor.getRatio()).as("the rejected parameter keeps its default").isEqualTo(4.0);
        assertThat(compressor.getThresholdDb()).as("the one before it is restored").isEqualTo(-30.0);
        assertThat(compressor.getAttackMs()).as("and the one after it").isEqualTo(25.0);
        assertThat(opened.warnings()).containsExactly("Track 2 'Vox': insert 1 'Compressor' parameter adjusted —"
                + " parameter id 1 value '0.5' is rejected by the insert; 4.0 is used");
        assertThat(opened.deserializer().getUnreadItems()).containsExactly(UnreadProjectItem.atInsert(
                UnreadProjectItem.Kind.INSERT_PARAMETER_ADJUSTED, VOX, 0, "Compressor",
                "parameter id 1 value '0.5' is rejected by the insert; 4.0 is used"));
        assertThat(opened.deserializer().losesSavedContent()).isTrue();

        assertTheFirstSaveBacksTheFileUp(xml);
    }

    @Test
    void aNamedParameterOutOfRangeOpensClampedReportedAndBackedUp() throws Exception {
        String xml = voxInserts("<insert effect-type=\"COMPRESSOR\" name=\"Compressor\">"
                + "<parameter name=\"Threshold\" value=\"-90.0\"/><parameter name=\"Attack\" value=\"25.0\"/>"
                + "</insert>");
        Opened opened = open(xml, PluginResolver.NONE);

        CompressorProcessor compressor = (CompressorProcessor) firstVoxInsert(opened.project()).getProcessor();
        assertThat(compressor.getThresholdDb()).as("clamped").isEqualTo(-60.0);
        assertThat(compressor.getAttackMs()).isEqualTo(25.0);
        assertThat(opened.warnings()).containsExactly("Track 2 'Vox': insert 1 'Compressor' parameter adjusted —"
                + " parameter 'Threshold' value '-90.0' is outside -60.0–0.0; -60.0 is used");
        assertThat(opened.deserializer().getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind).isEqualTo(UnreadProjectItem.Kind.INSERT_PARAMETER_ADJUSTED);
        assertThat(opened.deserializer().losesSavedContent()).isTrue();

        assertTheFirstSaveBacksTheFileUp(xml);
    }

    /** Each row: the problem, the resolver, the {@code <insert>}, and its one exact line. */
    static Stream<Arguments> problems() {
        String compressor = "<insert effect-type=\"COMPRESSOR\" name=\"Compressor\">%s</insert>";
        String adjusted = "Track 2 'Vox': insert 1 'Compressor' parameter adjusted — ";
        PluginResolver annotated = resolving(Map.of(
                new PluginIdentityKey(PluginSourceKind.JAR, AnnotatedJarTestPlugin.ID), AnnotatedJarTestPlugin::new));
        PluginResolver store = resolving(Map.of(
                new PluginIdentityKey(PluginSourceKind.JAR, StoreParameterJarTestPlugin.ID),
                StoreParameterJarTestPlugin::new));
        return Stream.of(
                Arguments.of("a repeated name", PluginResolver.NONE, compressor.formatted(
                                "<parameter name=\"Ratio\" value=\"2.0\"/><parameter name=\"Ratio\" value=\"8.0\"/>"),
                        adjusted + "parameter 'Ratio' value '8.0' repeats an earlier parameter; it is not applied"),
                Arguments.of("a repeated id", PluginResolver.NONE, compressor.formatted(
                                "<parameter id=\"1\" value=\"2.0\"/><parameter id=\"1\" value=\"8.0\"/>"),
                        adjusted + "parameter id 1 value '8.0' repeats an earlier parameter; it is not applied"),
                Arguments.of("an unknown name", PluginResolver.NONE,
                        compressor.formatted("<parameter name=\"oldKnob\" value=\"0.3\"/>"),
                        adjusted + "parameter 'oldKnob' value '0.3' is not a parameter of this insert;"
                                + " it is not applied"),
                Arguments.of("an unknown name that would split the line", PluginResolver.NONE,
                        compressor.formatted("<parameter name=\"old&#10;&lt;Knob\" value=\"0.3\"/>"),
                        adjusted + "parameter 'old&#xA;&lt;Knob' value '0.3' is not a parameter of this insert;"
                                + " it is not applied"),
                Arguments.of("an unknown id", PluginResolver.NONE,
                        compressor.formatted("<parameter id=\"7\" value=\"0.3\"/>"),
                        adjusted + "parameter id 7 value '0.3' is not a parameter of this insert; it is not applied"),
                Arguments.of("a value that is not a number", PluginResolver.NONE,
                        compressor.formatted("<parameter name=\"Threshold\" value=\"abc\"/>"),
                        adjusted + "parameter 'Threshold' value 'abc' is not a finite number; -20.0 is used"),
                Arguments.of("a NaN value", PluginResolver.NONE,
                        compressor.formatted("<parameter name=\"Threshold\" value=\"NaN\"/>"),
                        adjusted + "parameter 'Threshold' value 'NaN' is not a finite number; -20.0 is used"),
                Arguments.of("an infinite id-keyed value", PluginResolver.NONE,
                        compressor.formatted("<parameter id=\"1\" value=\"Infinity\"/>"),
                        adjusted + "parameter id 1 value 'Infinity' is not a finite number; 4.0 is used"),
                Arguments.of("neither a name nor an id", PluginResolver.NONE,
                        compressor.formatted("<parameter value=\"0.3\"/>"),
                        adjusted + "a parameter with no name or id (value '0.3') is not applied"),
                Arguments.of("a named value the plugin rejects", annotated,
                        "<insert name=\"Annotated Gain\" plugin-id=\"" + AnnotatedJarTestPlugin.ID + "\""
                                + " plugin-source=\"jar\" plugin-version=\"1.0.0\">"
                                + "<parameter name=\"Gain\" value=\"0.25\"/></insert>",
                        "Track 2 'Vox': insert 1 'Annotated Gain' parameter adjusted — parameter 'Gain' value '0.25'"
                                + " is rejected by the insert; 1.0 is used"),
                Arguments.of("an id-keyed value outside the store's range", store,
                        "<insert name=\"Knob Box\" plugin-id=\"" + StoreParameterJarTestPlugin.ID + "\""
                                + " plugin-source=\"jar\" plugin-version=\"2.0\">"
                                + "<parameter id=\"0\" value=\"1.5\"/></insert>",
                        "Track 2 'Vox': insert 1 'Knob Box' parameter adjusted — parameter id 0 value '1.5' is"
                                + " outside 0.0–1.0; 1.0 is used"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("problems")
    void everyParameterProblemLogsItsExactLine(String problem, PluginResolver resolver, String insert, String line)
            throws Exception {
        Opened opened = open(voxInserts(insert), resolver);

        assertThat(opened.warnings()).as(problem).containsExactly(line);
        assertThat(opened.deserializer().getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind).isEqualTo(UnreadProjectItem.Kind.INSERT_PARAMETER_ADJUSTED);
        assertThat(firstVoxInsert(opened.project()).offline()).as("the insert is kept working").isEmpty();
        assertThat(opened.deserializer().losesSavedContent()).isTrue();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A project read from a file, the deserializer that read it, and the WARNINGs it logged. */
    private record Opened(DawProject project, ProjectDeserializer deserializer, List<String> warnings) {
    }

    private static Opened open(String xml, PluginResolver resolver) throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver(resolver);
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            DawProject project = deserializer.deserialize(xml);
            return new Opened(project, deserializer, log.at(Level.WARNING));
        }
    }

    private static InsertSlot firstVoxInsert(DawProject project) {
        return project.getMixer().getChannels().get(1).getInsertSlots().getFirst();
    }

    /** The first save of {@code xml} through the manager writes one degraded backup holding its bytes. */
    private void assertTheFirstSaveBacksTheFileUp(String xml) throws IOException {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        manager.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), xml);
        Path original = Files.writeString(tempDir.resolve("original.daw"), xml);
        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());

        List<Path> backups = backupsIn(directory);
        assertThat(backups).singleElement()
                .satisfies(backup -> assertThat(backup.getFileName().toString()).matches(DEGRADED_BACKUP));
        assertThat(Files.mismatch(backups.getFirst(), original)).as("byte-equal to the fixture").isEqualTo(-1L);
    }
}
