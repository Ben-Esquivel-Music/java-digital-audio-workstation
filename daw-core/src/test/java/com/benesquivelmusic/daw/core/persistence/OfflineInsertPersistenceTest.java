package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.mixer.OfflineInsert;
import com.benesquivelmusic.daw.core.mixer.PassThroughProcessor;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.DEGRADED_BACKUP;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.VOX;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.backupsIn;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.insertNamed;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.voxInserts;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An insert the open cannot load keeps its place (story 334; Persistence
 * Integrity book §4.5): it opens offline at its chain position — passing
 * audio through, holding its element as saved — and the next save writes it
 * back unchanged, so no load path shortens a chain silently. The open says
 * so with the exact lines of studio rulings E6 and E12; an insert a strip
 * cannot hold is skipped and reported, and an unresolvable sidechain key on
 * a working insert is a removed, reported mixer assignment.
 */
class OfflineInsertPersistenceTest {

    private static final String COMPRESSOR =
            "<insert bypassed=\"false\" effect-type=\"COMPRESSOR\" expensive=\"false\" name=\"Compressor\""
                    + " sidechain-source=\"channel:0\"/>";
    private static final String DELAY =
            "<insert bypassed=\"false\" effect-type=\"DELAY\" expensive=\"false\" name=\"Delay\""
                    + " sidechain-source=\"return:0\"/>";
    private static final String OLD_FX =
            "<insert bypassed=\"false\" effect-type=\"NOT_A_TYPE\" expensive=\"false\" name=\"Old FX\"/>";
    private static final String OLD_METER =
            "<insert analyzer-plugin-id=\"com.example.gone\" bypassed=\"false\" expensive=\"false\""
                    + " name=\"Old meter\"/>";

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
    void anUnknownBuiltInTypeKeepsItsPlaceBetweenTwoBuiltInsAndIsWrittenBackUnchanged() throws Exception {
        String xml = voxInserts(COMPRESSOR + OLD_FX + DELAY);
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(xml);
            warnings = log.at(Level.WARNING);
        }

        List<InsertSlot> slots = vox(project).getInsertSlots();
        assertThat(slots).extracting(InsertSlot::getName).containsExactly("Compressor", "Old FX", "Delay");
        assertThat(slots.get(0).getEffectType()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(slots.get(0).getSidechainSource()).as("the neighbours keep their sidechain keys")
                .isSameAs(project.getMixer().getChannels().getFirst());
        assertThat(slots.get(2).getEffectType()).isEqualTo(InsertEffectType.DELAY);
        assertThat(slots.get(2).getSidechainSource()).isSameAs(project.getMixer().getReturnBuses().getFirst());
        InsertSlot offline = slots.get(1);
        assertThat(offline.offline()).map(OfflineInsert::cause)
                .contains(new OfflineCause.UnknownEffectType("NOT_A_TYPE"));
        assertThat(offline.getProcessor()).as("audibly bypassed: a pass-through").isInstanceOf(PassThroughProcessor.class);
        assertThat(offline.getPlugin()).isNull();
        assertThat(warnings).containsExactly("Track 2 'Vox': insert 2 'Old FX' not loaded (bypassed, kept as saved)"
                + " — effect-type 'NOT_A_TYPE' is not an effect this build knows");
        assertThat(deserializer.getUnreadItems()).containsExactly(UnreadProjectItem.atInsert(
                UnreadProjectItem.Kind.INSERT_NOT_LOADED, VOX, 1, "Old FX",
                "effect-type 'NOT_A_TYPE' is not an effect this build knows"));
        assertThat(deserializer.losesSavedContent()).isFalse();

        Path directory = projectHolding(xml);
        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());
        assertThat(backupsIn(directory)).as("the offline insert is written back, so nothing is backed up").isEmpty();
        assertThat(Files.readString(directory.resolve("project.daw"))).as("written back unchanged")
                .contains(OLD_FX);
    }

    @Test
    void anUnknownAnalyzerKeepsItsPlaceIsLoggedOnlyAndIsWrittenBackUnchanged() throws Exception {
        String xml = voxInserts(COMPRESSOR + OLD_METER + DELAY);
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project;
        List<String> analyzerWarnings;
        List<String> readerWarnings;
        try (InsertFixtures.CapturedLog analyzerLog = InsertFixtures.CapturedLog.of(AnalyzerInsertPersistence.class);
             InsertFixtures.CapturedLog readerLog = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(xml);
            analyzerWarnings = analyzerLog.at(Level.WARNING);
            readerWarnings = readerLog.at(Level.WARNING);
        }

        List<InsertSlot> slots = vox(project).getInsertSlots();
        assertThat(slots).extracting(InsertSlot::getName).containsExactly("Compressor", "Old meter", "Delay");
        assertThat(slots.get(1).offline()).map(OfflineInsert::cause)
                .contains(new OfflineCause.UnknownAnalyzer("com.example.gone"));
        assertThat(analyzerWarnings).containsExactly("Track 2 'Vox': insert 2 'Old meter' analyzer"
                + " 'com.example.gone' is not an analyzer this build knows; it is kept as saved and passes audio"
                + " through");
        assertThat(readerWarnings).as("not counted, no clause").isEmpty();
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();

        Path directory = projectHolding(xml);
        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());
        assertThat(backupsIn(directory)).isEmpty();
        assertThat(Files.readString(directory.resolve("project.daw"))).contains(OLD_METER);
    }

    /** Each row: what the stub is, the stub, the name its line quotes. */
    static Stream<Arguments> stubsWithNoIdentity() {
        return Stream.of(
                Arguments.of("a name-only stub of a build before story 334",
                        "<insert bypassed=\"false\" expensive=\"false\" name=\"Comp X\"/>", "Comp X"),
                Arguments.of("a CLAP_PLUGIN effect type",
                        "<insert bypassed=\"false\" effect-type=\"CLAP_PLUGIN\" expensive=\"false\"/>", "CLAP_PLUGIN"),
                Arguments.of("a plugin source this build does not know",
                        "<insert name=\"Comp X\" plugin-id=\"com.example.comp\" plugin-source=\"vst3\""
                                + " plugin-version=\"1.2.0\"/>", "Comp X"),
                Arguments.of("a blank plugin id",
                        "<insert plugin-id=\" \" plugin-source=\"jar\" plugin-version=\"1.2.0\"/>", "Insert 1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stubsWithNoIdentity")
    void aStubWithNoIdentityOpensOfflineAndIsCounted(String holding, String stub, String name) throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        List<String> warnings;
        DawProject project;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(voxInserts(stub));
            warnings = log.at(Level.WARNING);
        }

        assertThat(vox(project).getInsertSlots()).singleElement().satisfies(slot -> {
            assertThat(slot.getName()).isEqualTo(name);
            assertThat(slot.offline()).map(OfflineInsert::cause).contains(new OfflineCause.NoIdentity());
        });
        assertThat(warnings).as(holding).containsExactly("Track 2 'Vox': insert 1 '" + name
                + "' not loaded (bypassed, kept as saved) — the file records no plugin identity for it");
        assertThat(deserializer.getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind).isEqualTo(UnreadProjectItem.Kind.INSERT_NOT_LOADED);
        assertThat(deserializer.losesSavedContent()).isFalse();
        assertThat(new ProjectSerializer().serialize(project)).as("written back as saved").contains(stub);
    }

    @Test
    void aStereoOnlyEffectInAMonoProjectOpensOfflineAndLoadsInAStereoProject() throws Exception {
        String imager = "<insert bypassed=\"false\" effect-type=\"STEREO_IMAGER\" expensive=\"false\""
                + " name=\"Stereo Imager\"/>";
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject mono;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            mono = deserializer.deserialize(InsertFixtures.file(1, "", imager));
            warnings = log.at(Level.WARNING);
        }

        assertThat(vox(mono).getInsertSlots()).singleElement().satisfies(slot ->
                assertThat(slot.offline()).map(OfflineInsert::cause)
                        .contains(new OfflineCause.FormatNotSupported("STEREO_IMAGER", 1, 48_000.0)));
        assertThat(warnings).containsExactly("Track 2 'Vox': insert 1 'Stereo Imager' not loaded (bypassed, kept"
                + " as saved) — effect-type 'STEREO_IMAGER' cannot run in this project's audio format (channels 1,"
                + " sample rate 48000.0 Hz)");

        String written = new ProjectSerializer().serialize(mono);
        assertThat(written).contains(imager);
        DawProject stereo = new ProjectDeserializer().deserialize(written.replace("channels=\"1\"", "channels=\"2\""));
        assertThat(vox(stereo).getInsertSlots()).singleElement().satisfies(slot -> {
            assertThat(slot.offline()).as("it loads in a format it accepts").isEmpty();
            assertThat(slot.getEffectType()).isEqualTo(InsertEffectType.STEREO_IMAGER);
        });
    }

    @Test
    void childContentNoWriterProducesIsLoggedFirstAndArmsTheBackup() throws Exception {
        String xml = voxInserts("<insert effect-type=\"NOT_A_TYPE\" name=\"Old FX\">"
                + "<parameter name=\"mix\" value=\"0.5\"/><preset name=\"Warm\"/></insert>");
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(xml);
            warnings = log.at(Level.WARNING);
        }

        assertThat(warnings).containsExactly(
                "Track 2 'Vox': insert 1 has 1 child element no writer produces; it is dropped",
                "Track 2 'Vox': insert 1 'Old FX' not loaded (bypassed, kept as saved) — effect-type 'NOT_A_TYPE'"
                        + " is not an effect this build knows");
        assertThat(deserializer.losesSavedContent()).isTrue();
        String written = new ProjectSerializer().serialize(project);
        assertThat(written).as("the parameter is kept, the other child is dropped")
                .contains("<parameter name=\"mix\" value=\"0.5\"/>").doesNotContain("preset");

        Path directory = projectHolding(xml);
        Path original = Files.writeString(tempDir.resolve("original.daw"), xml);
        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());
        List<Path> backups = backupsIn(directory);
        assertThat(backups).singleElement()
                .satisfies(backup -> assertThat(backup.getFileName().toString()).matches(DEGRADED_BACKUP));
        assertThat(Files.mismatch(backups.getFirst(), original)).isEqualTo(-1L);
    }

    @Test
    void aNinthInsertIsSkippedReportedAndTheOpenSucceeds() throws Exception {
        String eight = "<insert effect-type=\"COMPRESSOR\" name=\"Compressor\"/>".repeat(8);
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(voxInserts(eight + "<insert effect-type=\"PARAMETRIC_EQ\" name=\"EQ\"/>"));
            warnings = log.at(Level.WARNING);
        }

        assertThat(vox(project).getInsertSlots()).hasSize(MixerChannel.MAX_INSERT_SLOTS);
        assertThat(warnings).containsExactly("Track 2 'Vox': insert 9 'EQ' skipped — the strip already holds 8 inserts");
        assertThat(deserializer.getUnreadItems()).containsExactly(UnreadProjectItem.atInsert(
                UnreadProjectItem.Kind.INSERT_SKIPPED, VOX, 8, "EQ", "the strip already holds 8 inserts"));
        assertThat(deserializer.losesSavedContent()).isTrue();
    }

    @Test
    void anUnresolvableSidechainOnAWorkingInsertIsAReportedRemovedAssignment() throws Exception {
        String compressor = "<insert effect-type=\"COMPRESSOR\" name=\"Compressor\"/>";
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(voxInserts(compressor + compressor
                    + "<insert effect-type=\"COMPRESSOR\" name=\"Compressor\" sidechain-source=\"return:4\"/>"));
            warnings = log.at(Level.WARNING);
        }

        assertThat(vox(project).getInsertSlots()).hasSize(3);
        assertThat(vox(project).getInsertSlots().get(2).getSidechainSource()).isNull();
        assertThat(warnings).containsExactly("Track 2 'Vox': mixer assignment 3 removed — insert 3 'Compressor'"
                + " sidechain-source 'return:4' names no strip in this project; the insert detects on its own input");
        assertThat(deserializer.getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind).isEqualTo(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED);
        assertThat(deserializer.losesSavedContent()).isTrue();
    }

    @Test
    void anOfflineSidechainKeyFollowsAStripMove() throws Exception {
        DawProject project = new ProjectDeserializer().deserialize(voxInserts(
                "<insert effect-type=\"NOT_A_TYPE\" name=\"Old FX\" sidechain-source=\"channel:0\"/>"));
        InsertSlot offline = vox(project).getInsertSlots().getFirst();
        MixerChannel drums = project.getMixer().getChannels().getFirst();
        assertThat(offline.offline()).map(OfflineInsert::sidechainResolvedAtOpen).contains(true);
        assertThat(offline.getSidechainSource()).isSameAs(drums);

        project.moveTrack(0, 1);
        assertThat(project.getMixer().getChannels().indexOf(drums)).as("fixture: Drums moved").isEqualTo(1);

        assertThat(insertNamed(new ProjectSerializer().serialize(project), "Old FX").getAttribute("sidechain-source"))
                .as("the key names the same strip").isEqualTo("channel:1");
    }

    @Test
    void aKeyThatDidNotResolveIsWrittenBackVerbatim() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project = deserializer.deserialize(voxInserts(
                "<insert effect-type=\"NOT_A_TYPE\" name=\"Old FX\" sidechain-source=\"return:9\"/>"));

        InsertSlot offline = vox(project).getInsertSlots().getFirst();
        assertThat(offline.offline()).map(OfflineInsert::sidechainResolvedAtOpen).contains(false);
        assertThat(deserializer.getUnreadItems()).as("an offline insert's key is never reported")
                .extracting(UnreadProjectItem::kind).containsExactly(UnreadProjectItem.Kind.INSERT_NOT_LOADED);
        assertThat(insertNamed(new ProjectSerializer().serialize(project), "Old FX").getAttribute("sidechain-source"))
                .isEqualTo("return:9");
    }

    @ParameterizedTest(name = "bypassed=\"{0}\"")
    @ValueSource(booleans = {true, false})
    void theStoredBypassDecisionIsWrittenBackAsSaved(boolean bypassed) throws Exception {
        DawProject project = new ProjectDeserializer().deserialize(voxInserts(
                "<insert bypassed=\"" + bypassed + "\" effect-type=\"NOT_A_TYPE\" name=\"Old FX\"/>"));

        assertThat(insertNamed(new ProjectSerializer().serialize(project), "Old FX").getAttribute("bypassed"))
                .isEqualTo(String.valueOf(bypassed));
    }

    /**
     * A mixer snapshot recall may set an offline insert's stored bypass
     * decision (studio ruling-1: a user action, by index); the next save
     * writes the decision in force, not the one the file saved (design §4.2).
     */
    @ParameterizedTest(name = "saved bypassed=\"{0}\"")
    @ValueSource(booleans = {true, false})
    void aBypassDecisionARecallSetsIsWrittenBack(boolean savedBypassed) throws Exception {
        DawProject project = new ProjectDeserializer().deserialize(voxInserts(
                "<insert bypassed=\"" + savedBypassed + "\" effect-type=\"NOT_A_TYPE\" name=\"Old FX\"/>"));
        MixerChannel vox = vox(project);
        vox.setInsertBypassed(0, !savedBypassed);
        project.getMixerSnapshotManager().addSnapshot(MixerSnapshot.capture(project.getMixer(), "Verse"));
        vox.setInsertBypassed(0, savedBypassed);

        project.getMixerSnapshotManager().getSnapshots().getFirst().applyTo(project.getMixer());

        assertThat(vox.getInsertSlots().getFirst().isBypassed()).as("the recall set it").isEqualTo(!savedBypassed);
        assertThat(insertNamed(new ProjectSerializer().serialize(project), "Old FX").getAttribute("bypassed"))
                .isEqualTo(String.valueOf(!savedBypassed));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static MixerChannel vox(DawProject project) {
        return project.getMixer().getChannels().get(1);
    }

    /** A project folder whose {@code project.daw} holds {@code xml}, not open in the manager. */
    private Path projectHolding(String xml) throws IOException {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        manager.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), xml);
        return directory;
    }
}
