package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A track's take stack in {@code project.daw} (story 334; Persistence
 * Integrity book §4.6; studio rulings E8, E13-E15 and G9):
 * every take lane is written as a {@code <take>} with its id, name and mute
 * flag and its clips, after the track's {@code <clips>}; every comp region
 * as a {@code <comp-region>} naming its lane by id; the main-lane clips that
 * are comp output carry {@code comp-output="true"}. The open restores the
 * lanes and regions and adopts the marked clips, never compiling. A region
 * the open cannot honour is skipped, logged with the exact line, counted and
 * backs the original up; a take id that is missing, not a canonical UUID or
 * repeated gets a fresh id, logged only, and the regions resolve by the id
 * as saved.
 */
class TakeStackPersistenceTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    /** A degraded open's backup, as {@code ProjectManager} names it. */
    private static final String DEGRADED_BACKUP = "project\\.daw\\.degraded\\.\\d{8}-\\d{6}(-\\d+)?\\.bak";

    private static final String DRUMS_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String VOX_ID = "00000000-0000-0000-0000-0000000000a2";
    private static final String TAKE_1 = "00000000-0000-0000-0000-00000000000a";
    private static final String TAKE_2 = "00000000-0000-0000-0000-00000000000b";

    @TempDir
    Path tempDir;

    private ProjectManager manager;
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
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        deserializerLog.addHandler(warningHandler);
    }

    @AfterEach
    void stopListening() {
        deserializerLog.removeHandler(warningHandler);
        manager.closeProject().join();
    }

    @Test
    void aTakeStackRoundTripsWithItsIdsRegionsAndAdoptedCompOutput() throws IOException {
        DawProject project = new DawProject("Song", FORMAT);
        project.createAudioTrack("Drums");
        Track vox = project.createAudioTrack("Vox");
        TakeComping comping = vox.getTakeComping();
        TakeLane first = lane("Take 1", 0.0, 8.0, media("take-1/segment-000.wav"), media("take-1/segment-001.wav"));
        TakeLane second = lane("Take 2", 0.0, 8.0, media("take-2/segment-000.wav"));
        second.setMuted(true);
        comping.addTakeLane(first);
        comping.addTakeLane(second);
        comping.addCompRegion(new CompRegion(0, 1.0, 3.0));
        comping.addCompRegion(new CompRegion(1, 4.0, 2.0));
        new CompileCompAction(vox).execute();
        AudioClip user = new AudioClip("Overdub", 6.0, 1.0, media("overdub.wav"));
        vox.addClip(user);
        comping.compOutputOn(vox).getFirst().setFadeInBeats(0.5);

        String xml = new ProjectSerializer().serialize(project);
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject reopened = deserializer.deserialize(xml);

        assertThat(xml).as("the comp output is marked on the main lane only")
                .containsOnlyOnce("<takes>")
                .doesNotContain("soloed");
        assertThat(xml.split("comp-output=\"true\"", -1)).as("two comp clips are marked").hasSize(3);
        assertThat(xml.indexOf("<takes>")).as("<takes> follows <clips>").isGreaterThan(xml.indexOf("</clips>"));
        Track restoredVox = reopened.getTracks().get(1);
        TakeComping restored = restoredVox.getTakeComping();
        assertThat(restored.getTakeLanes()).extracting(TakeLane::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(restored.getTakeLanes()).extracting(TakeLane::getName).containsExactly("Take 1", "Take 2");
        assertThat(restored.getTakeLanes()).extracting(TakeLane::isMuted).containsExactly(false, true);
        assertThat(restored.getTakeLane(0).getClips()).singleElement().satisfies(clip -> {
            assertThat(clip.getSourceSegmentPaths()).isEqualTo(first.getClips().getFirst().getSourceSegmentPaths());
            assertThat(clip.compOutputOwner()).as("a lane clip is never comp output").isEmpty();
        });
        assertThat(restored.getCompRegions()).containsExactlyElementsOf(comping.getCompRegions());
        List<AudioClip> output = restored.compOutputOn(restoredVox);
        assertThat(output).hasSize(2);
        assertThat(output.getFirst().getFadeInBeats()).as("the hand edit survives").isEqualTo(0.5);
        assertThat(restoredVox.getClips()).hasSize(3);
        assertThat(restored.isCompOutput(restoredVox.getClips().get(2))).as("the user clip stays a user clip")
                .isFalse();
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();
        assertThat(warnings).isEmpty();
    }

    static Stream<Arguments> unusableRegions() {
        return Stream.of(
                Arguments.of("a take id that names no take of the track",
                        region("5b0e0000-0000-0000-0000-000000000000", "4.0", "2.0"),
                        "Track 2 'Vox': comp region 2 skipped — take '5b0e0000-0000-0000-0000-000000000000' is not a"
                                + " take on this track; saved as <comp-region take-id=\"5b0e0000-0000-0000-0000-000000000000\""
                                + " start-beat=\"4.0\" duration-beats=\"2.0\"/>"),
                Arguments.of("a region with no take id",
                        "<comp-region start-beat=\"4.0\" duration-beats=\"2.0\"/>",
                        "Track 2 'Vox': comp region 2 skipped — take '' is not a take on this track;"
                                + " saved as <comp-region start-beat=\"4.0\" duration-beats=\"2.0\"/>"),
                Arguments.of("a take id holding a newline and a '<'",
                        region("a&#10;&lt;b", "4.0", "2.0"),
                        "Track 2 'Vox': comp region 2 skipped — take 'a&#xA;&lt;b' is not a take on this track;"
                                + " saved as <comp-region take-id=\"a&#xA;&lt;b\" start-beat=\"4.0\""
                                + " duration-beats=\"2.0\"/>"),
                Arguments.of("a negative start",
                        region(TAKE_1, "-4.0", "2.0"),
                        "Track 2 'Vox': comp region 2 skipped — start-beat '-4.0' is not a beat position ≥ 0;"
                                + " saved as <comp-region take-id=\"" + TAKE_1 + "\" start-beat=\"-4.0\""
                                + " duration-beats=\"2.0\"/>"),
                Arguments.of("a start that is not a number",
                        region(TAKE_1, "NaN", "2.0"),
                        "Track 2 'Vox': comp region 2 skipped — start-beat 'NaN' is not a beat position ≥ 0;"
                                + " saved as <comp-region take-id=\"" + TAKE_1 + "\" start-beat=\"NaN\""
                                + " duration-beats=\"2.0\"/>"),
                Arguments.of("a duration of 0",
                        region(TAKE_1, "4.0", "0"),
                        "Track 2 'Vox': comp region 2 skipped — duration-beats '0' is not a positive number of"
                                + " beats; saved as <comp-region take-id=\"" + TAKE_1 + "\" start-beat=\"4.0\""
                                + " duration-beats=\"0\"/>"),
                Arguments.of("an infinite duration",
                        region(TAKE_2, "4.0", "Infinity"),
                        "Track 2 'Vox': comp region 2 skipped — duration-beats 'Infinity' is not a positive number"
                                + " of beats; saved as <comp-region take-id=\"" + TAKE_2 + "\" start-beat=\"4.0\""
                                + " duration-beats=\"Infinity\"/>"),
                Arguments.of("a region overlapping an earlier one",
                        region(TAKE_2, "1.0", "2.0"),
                        "Track 2 'Vox': comp region 2 skipped — it overlaps comp region 1; saved as <comp-region"
                                + " take-id=\"" + TAKE_2 + "\" start-beat=\"1.0\" duration-beats=\"2.0\"/>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableRegions")
    void everyUnusableCompRegionIsSkippedCountedAndBackedUp(String holding, String badRegion, String line)
            throws Exception {
        String xml = file("""
                <takes>
                  <take id="%s" name="Take 1" muted="false"/>
                  <take id="%s" name="Take 2" muted="false"/>
                  %s
                  %s
                </takes>
                """.formatted(TAKE_1, TAKE_2, region(TAKE_1, "0.0", "2.0"), badRegion));
        Path directory = projectHolding(xml);
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(xml, directory);

        assertThat(warnings).as("%s: the one WARNING", holding).containsExactly(line);
        assertThat(deserializer.getUnreadItems()).as("%s: counted once", holding).singleElement()
                .satisfies(item -> {
                    assertThat(item.kind()).isEqualTo(UnreadProjectItem.Kind.COMP_REGION_SKIPPED);
                    assertThat(item.location()).isEqualTo(new UnreadProjectItem.OnTrack(1, "Vox"));
                    assertThat(item.index()).isEqualTo(1);
                    assertThat(item.count()).isEqualTo(1);
                    assertThat(item.logLine()).isEqualTo(line);
                });
        assertThat(project.getTracks().get(1).getTakeComping().getCompRegions())
                .as("%s: the region before it is kept", holding)
                .containsExactly(new CompRegion(0, 0.0, 2.0));
        assertThat(deserializer.losesSavedContent()).as("%s: the next save drops it", holding).isTrue();

        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());
        List<Path> backups = backupsIn(directory);
        assertThat(backups).as("%s: one degraded backup", holding).singleElement()
                .satisfies(backup -> assertThat(backup.getFileName().toString()).matches(DEGRADED_BACKUP));
        assertThat(Files.readString(backups.getFirst())).as("%s: the backup holds the original", holding)
                .isEqualTo(xml);
    }

    @Test
    void takeIdProblemsGetAFreshIdAndRegionsResolveByTheIdAsSaved() throws IOException {
        String xml = file("""
                <takes>
                  <take id="%s" name="Take 1" muted="false"/>
                  <take id="abc" name="Take 2" muted="false"/>
                  <take id="%s" name="Take 3" muted="false"/>
                  <take name="Take 4" muted="false"/>
                  <take id="%s" muted="true"/>
                  %s
                  %s
                </takes>
                """.formatted(TAKE_1, TAKE_1, TAKE_2, region("abc", "0.0", "2.0"), region(TAKE_1, "4.0", "2.0")));
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(xml);

        assertThat(warnings).containsExactly(
                "Track 2 'Vox': take 2 'Take 2' id 'abc' is not a UUID; a new id is used",
                "Track 2 'Vox': take 3 'Take 3' id " + TAKE_1 + " is already used by take 1 'Take 1'; a new id is used",
                "Track 2 'Vox': take 4 'Take 4' has no id; a new id is used");
        TakeComping comping = project.getTracks().get(1).getTakeComping();
        List<TakeLane> lanes = comping.getTakeLanes();
        assertThat(lanes).extracting(TakeLane::getName)
                .as("a take with no name is named by its number, silently")
                .containsExactly("Take 1", "Take 2", "Take 3", "Take 4", "Take 5");
        assertThat(lanes.getFirst().getId()).as("the first lane keeps its id").isEqualTo(TAKE_1);
        assertThat(lanes.get(4).getId()).isEqualTo(TAKE_2);
        assertThat(lanes.get(4).isMuted()).isTrue();
        Set<String> ids = new HashSet<>();
        for (TakeLane lane : lanes) {
            assertThat(UUID.fromString(lane.getId())).as("every id is a canonical UUID").hasToString(lane.getId());
            ids.add(lane.getId());
        }
        assertThat(ids).as("no two lanes share an id").hasSize(5);
        assertThat(comping.getCompRegions())
                .as("'abc' resolves to the lane saved with it; the repeated id to the first lane carrying it")
                .containsExactly(new CompRegion(1, 0.0, 2.0), new CompRegion(0, 4.0, 2.0));
        assertThat(deserializer.getUnreadItems()).as("take ids are logged only").isEmpty();
        assertThat(deserializer.losesSavedContent()).as("a malformed or repeated id is dropped by the next save")
                .isTrue();

        deserializer.deserialize(file("<takes><take name=\"Take 1\" muted=\"false\"/></takes>"));
        assertThat(deserializer.losesSavedContent()).as("a missing id loses nothing").isFalse();
    }

    @Test
    void aCompOutputMarkOnATrackWithoutTakesIsKeptAsAnOrdinaryClipAndLoggedOnly() throws IOException {
        String xml = file("""
                <clips>
                  <clip name="Intro" start-beat="0.0" duration-beats="1.0"/>
                  <clip name="Verse" start-beat="1.0" duration-beats="1.0"/>
                  <clip name="Chorus" start-beat="2.0" duration-beats="1.0"/>
                  <clip name="Take — Vox" start-beat="3.0" duration-beats="1.0" comp-output="true"/>
                </clips>
                """);
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(xml);

        assertThat(warnings).containsExactly("Track 2 'Vox': clip 4 'Take — Vox' is saved as comp output, but the"
                + " track has no takes; it is kept as an ordinary clip");
        Track vox = project.getTracks().get(1);
        assertThat(vox.getClips()).hasSize(4);
        assertThat(vox.getClips().get(3).compOutputOwner()).as("an ordinary clip").isEmpty();
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).as("the mark means nothing there: no backup").isFalse();
        assertThat(new ProjectSerializer().serialize(project)).doesNotContain("comp-output");
    }

    @Test
    void aLaneWithNoClipsIsKeptSilently() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(file("""
                <takes>
                  <take id="%s" name="Take 1" muted="false"/>
                </takes>
                """.formatted(TAKE_1)));

        TakeComping comping = project.getTracks().get(1).getTakeComping();
        assertThat(comping.getTakeLanes()).singleElement().satisfies(lane -> {
            assertThat(lane.getId()).isEqualTo(TAKE_1);
            assertThat(lane.getClips()).isEmpty();
        });
        assertThat(warnings).isEmpty();
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();
    }

    /** Engineer ruling G9: a lane clip's WARNING names its take, never the main lane's clip of that number. */
    @Test
    void aLaneClipsWarningNamesItsTake() throws IOException {
        new ProjectDeserializer().deserialize(file("""
                <clips>
                  <clip name="Comp" start-beat="0.0" duration-beats="4.0" source-file="/media/comp.wav"/>
                </clips>
                <takes>
                  <take id="%s" name="Take 1" muted="false"/>
                  <take id="%s" name="Take 2" muted="false"/>
                  <take id="00000000-0000-0000-0000-00000000000c" name="Take 3" muted="false">
                    <clip name="Take — Vox" start-beat="0.0" duration-beats="4.0">
                      <source-segment path=""/>
                      <source-segment path="/media/take-3/segment-001.wav"/>
                    </clip>
                  </take>
                </takes>
                """.formatted(TAKE_1, TAKE_2)));

        assertThat(warnings).containsExactly("Track 2 'Vox': take 3 'Take 3' clip 1 'Take — Vox' source segment 1"
                + " of 2 skipped — the path is missing");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static String region(String takeId, String start, String duration) {
        return "<comp-region take-id=\"" + takeId + "\" start-beat=\"" + start + "\" duration-beats=\"" + duration
                + "\"/>";
    }

    /** A file whose second track, Vox, holds {@code voxContent}. */
    private static String file(String voxContent) {
        return "<daw-project version=\"" + MigrationRegistry.CURRENT_VERSION + "\">\n"
                + "  <metadata><name>Song</name></metadata>\n"
                + "  <tracks>\n"
                + "    <track id=\"" + DRUMS_ID + "\" name=\"Drums\" type=\"AUDIO\"/>\n"
                + "    <track id=\"" + VOX_ID + "\" name=\"Vox\" type=\"AUDIO\">\n" + voxContent + "    </track>\n"
                + "  </tracks>\n"
                + "</daw-project>\n";
    }

    private String media(String relative) {
        return tempDir.resolve("media").resolve(relative).toAbsolutePath().toString();
    }

    private static TakeLane lane(String name, double startBeat, double beats, String... segments) {
        AudioClip take = new AudioClip("Take — Vox", startBeat, beats, null);
        take.setSourceSegmentPaths(List.of(segments));
        TakeLane lane = new TakeLane(name);
        lane.addClip(take);
        return lane;
    }

    /** A project folder whose {@code project.daw} holds {@code xml}, not open in the manager. */
    private Path projectHolding(String xml) throws IOException {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        manager.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), xml);
        return directory;
    }

    private static List<Path> backupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).sorted().toList();
        }
    }
}
