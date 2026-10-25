package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

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

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An open that could not honour everything its file saved — a skipped note,
 * lane or event, a removed mixer assignment (a self-link included), an
 * adjusted arpeggiator setting, a malformed or duplicate track id, an
 * unrecognised MIDI effect's child elements or text, a VCA group whose id
 * repeats an earlier group's — loses that content on the next save, so
 * {@link ProjectManager} backs the original file up once, just before the
 * first save, as {@code project.daw.degraded.<stamp>.bak} (Persistence
 * Integrity book §9.4: never a quiet deletion). An open that loses nothing on
 * save — a placeholder written back as saved, a missing or merely
 * non-canonical track id, a missing asset, a reset CC lane height, a
 * malformed pre/post-roll, a VCA gain that is not a number, an analyzer
 * setting the reader cannot use (a view preference) — owes no backup.
 * A migrated open that is also degraded takes the one migration backup. The
 * owed backup is settled by the first write even when there is no file to
 * copy, so no later save backs up that write's own output. The backup's INFO
 * line says why the backup exists and points to the open's WARNING lines; it
 * counts nothing (studio-engineer ruling Q6).
 */
class DegradedOpenBackupTest {

    /** A degraded open's backup, as {@code ProjectManager} names it. */
    private static final String DEGRADED_BACKUP = "project\\.daw\\.degraded\\.\\d{8}-\\d{6}(-\\d+)?\\.bak";

    /** The INFO line of a degraded open's backup, before {@code ": <path>"}. */
    private static final String DEGRADED_INFO = "Wrote a backup of the project file before this save, which drops"
            + " content the open could not read as written (the open's WARNING lines name each item)";

    /** The INFO line of a migrated open's backup that also keeps lost content, before {@code ": <path>"}. */
    private static final String MIGRATED_AND_DEGRADED_INFO = "Wrote the migration backup, which also keeps content"
            + " this save drops because the open could not read it as written (the open's WARNING lines name each"
            + " item)";

    private static final String A = "00000000-0000-0000-0000-0000000000a1";
    private static final String B = "00000000-0000-0000-0000-0000000000b1";
    private static final String ABSENT = "00000000-0000-0000-0000-0000000000ff";

    /** A readable note (velocity 100) and an unreadable one (velocity 128) on the only track. */
    private static final String ONE_BAD_NOTE_TRACK = """
            <track id="%s" name="Keys" type="MIDI">
              <midi-clip locked="false">
                <notes>
                  <note note-number="60" start-column="0" duration-columns="4" velocity="100" channel="0"/>
                  <note note-number="62" start-column="4" duration-columns="4" velocity="128" channel="0"/>
                </notes>
              </midi-clip>
            </track>
            """.formatted(A);

    @TempDir
    Path tempDir;

    private ProjectManager manager;
    private final List<String> infoLines = new CopyOnWriteArrayList<>();
    private final Logger managerLog = Logger.getLogger(ProjectManager.class.getName());
    private final Handler infoHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.INFO) {
                infoLines.add(record.getMessage());
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
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
        managerLog.addHandler(infoHandler);
    }

    @AfterEach
    void releaseTheManager() {
        managerLog.removeHandler(infoHandler);
        manager.abandonProject();
        assertThat(infoLines).as("a backup's INFO line counts nothing")
                .noneMatch(line -> line.contains("unread item"));
    }

    @Test
    void aDegradedOpenBacksUpTheOriginalOnceBeforeItsFirstSave() throws Exception {
        String damaged = file(ONE_BAD_NOTE_TRACK);
        Path directory = projectHolding(damaged);
        Path original = Files.writeString(tempDir.resolve("original.daw"), damaged);

        manager.openProject(directory);
        assertThat(manager.getLastUnreadItems()).as("fixture: the open skipped the note").hasSize(1);
        assertThat(backupsIn(directory)).as("opening alone writes nothing").isEmpty();
        saved(manager, manager.getCurrentDawProject());

        List<Path> backups = backupsIn(directory);
        assertThat(backups).singleElement().satisfies(backup ->
                assertThat(backup.getFileName().toString()).matches(DEGRADED_BACKUP));
        assertThat(Files.mismatch(backups.getFirst(), original)).as("the backup holds the original bytes")
                .isEqualTo(-1L);
        assertThat(Files.readString(directory.resolve("project.daw")))
                .as("the save dropped the unreadable note and kept the other")
                .doesNotContain("velocity=\"128\"")
                .contains("velocity=\"100\"");
        assertThat(infoLines).as("the backup is logged at INFO with its path")
                .containsExactly(DEGRADED_INFO + ": " + backups.getFirst());

        saved(manager, manager.getCurrentDawProject());
        assertThat(backupsIn(directory)).as("a second save writes no new backup").hasSize(1);
        manager.abandonProject();

        manager.openProject(directory);
        assertThat(manager.getLastUnreadItems()).as("the saved file reopens clean").isEmpty();
        saved(manager, manager.getCurrentDawProject());
        assertThat(backupsIn(directory)).as("saving the clean reopen writes no backup").hasSize(1);
    }

    /** Each row: what the file holds, the file, and whether its next save drops saved content. */
    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("a skipped MIDI note", file(ONE_BAD_NOTE_TRACK), true),
                Arguments.of("a skipped CC lane", file(midiTrack(A, """
                        <midi-clip locked="false"><cc-lanes>
                          <cc-lane type="AFTERTOUCH" high-resolution="false" channel="0" height-ratio="1.0">
                            <event column="0" value="10"/>
                          </cc-lane>
                        </cc-lanes></midi-clip>
                        """)), true),
                Arguments.of("a skipped CC event", file(midiTrack(A, """
                        <midi-clip locked="false"><cc-lanes>
                          <cc-lane type="SUSTAIN" high-resolution="false" channel="0" height-ratio="1.0">
                            <event column="-1" value="10"/>
                            <event column="0" value="64"/>
                          </cc-lane>
                        </cc-lanes></midi-clip>
                        """)), true),
                Arguments.of("a mixer assignment to a missing track", file(midiTrack(A, ""), """
                        <mixer>
                          <master volume="1.0"/>
                          <channels/>
                          <return-buses/>
                          <cue-buses>
                            <cue-bus id="00000000-0000-0000-0000-0000000000c1" label="Singer" hardware-output-index="1" master-gain="1.0">
                              <cue-send track-id="%s" gain="0.5" pan="0.0" pre-fader="true"/>
                            </cue-bus>
                          </cue-buses>
                        </mixer>
                        """.formatted(ABSENT)), true),
                Arguments.of("an adjusted arpeggiator setting", file(midiTrack(A, """
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.benesquivelmusic.daw.builtin.arpeggiator" gate="250"/>
                        </midi-effect-chain>
                        """)), true),
                Arguments.of("an unrecognized MIDI effect with attributes only", file(midiTrack(A, """
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.example.chords" voicing="drop2"/>
                        </midi-effect-chain>
                        """)), false),
                Arguments.of("an unrecognized MIDI effect with a child element", file(midiTrack(A, """
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.example.chords"><voicing name="drop2"/></midi-effect>
                        </midi-effect-chain>
                        """)), true),
                Arguments.of("an unrecognized MIDI effect with text", file(midiTrack(A, """
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.example.chords">BASE64STATE</midi-effect>
                        </midi-effect-chain>
                        """)), true),
                Arguments.of("an unrecognized MIDI effect with CDATA", file(midiTrack(A, """
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.example.chords"><![CDATA[state]]></midi-effect>
                        </midi-effect-chain>
                        """)), true),
                Arguments.of("a channel linked to itself", file(midiTrack(A, ""), """
                        <mixer>
                          <master volume="1.0"/>
                          <channels/>
                          <return-buses/>
                          <channel-links>
                            <channel-link left-channel-id="%s" right-channel-id="%s" mode="RELATIVE"/>
                          </channel-links>
                        </mixer>
                        """.formatted(A, A)), true),
                Arguments.of("a VCA group whose id repeats an earlier group's", file(midiTrack(A, ""), """
                        <mixer>
                          <master volume="1.0"/>
                          <channels/>
                          <return-buses/>
                          <vca-groups>
                            <vca-group id="00000000-0000-0000-0000-0000000000d1" label="Drums" master-gain-db="0.0"/>
                            <vca-group id="00000000-0000-0000-0000-0000000000d1" label="Drums copy" master-gain-db="0.0"/>
                          </vca-groups>
                        </mixer>
                        """), true),
                Arguments.of("a VCA gain that is not a number", file(midiTrack(A, ""), """
                        <mixer>
                          <master volume="1.0"/>
                          <channels/>
                          <return-buses/>
                          <vca-groups>
                            <vca-group id="00000000-0000-0000-0000-0000000000d1" label="Drums" master-gain-db="NaN">
                              <vca-member channel-id="%s"/>
                            </vca-group>
                          </vca-groups>
                        </mixer>
                        """.formatted(A)), false),
                Arguments.of("a malformed track id", file(midiTrack("not-a-uuid", "")), true),
                Arguments.of("a duplicate track id", file(midiTrack(A, "") + midiTrack(A, "")), true),
                Arguments.of("a missing track id", file("<track name=\"Keys\" type=\"MIDI\"/>"), false),
                Arguments.of("a non-canonical track id", file(midiTrack("1-2-3-4-5", "")), false),
                Arguments.of("a missing asset", file("""
                        <track id="%s" name="Drums" type="AUDIO">
                          <clips>
                            <clip name="Take" start-beat="0.0" duration-beats="4.0" source-file="missing.wav"/>
                          </clips>
                        </track>
                        """.formatted(A)), false),
                Arguments.of("an unusable CC lane height", file(midiTrack(A, """
                        <midi-clip locked="false"><cc-lanes>
                          <cc-lane type="SUSTAIN" high-resolution="false" channel="0" height-ratio="tall">
                            <event column="0" value="64"/>
                          </cc-lane>
                        </cc-lanes></midi-clip>
                        """)), false),
                Arguments.of("a malformed pre/post-roll", file(midiTrack(B, ""),
                        "<transport pre-roll-bars=\"-3\" post-roll-bars=\"x\" pre-roll-enabled=\"true\"/>"), false),
                Arguments.of("an unusable analyzer setting", file(midiTrack(A, ""), """
                        <mixer>
                          <master volume="1.0">
                            <inserts>
                              <insert analyzer-plugin-id="com.benesquivelmusic.daw.spectrum-analyzer"
                                      window-type="FLAT_TOP"/>
                            </inserts>
                          </master>
                          <channels/>
                          <return-buses/>
                        </mixer>
                        """), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void aFileOwesABackupExactlyWhenItsNextSaveDropsSavedContent(String holding, String xml,
                                                                boolean losesSavedContent) throws Exception {
        Path directory = projectHolding(xml);
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.deserialize(xml, directory);
        assertThat(deserializer.losesSavedContent()).as("the predicate for %s", holding)
                .isEqualTo(losesSavedContent);

        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());

        List<String> backups = backupsIn(directory).stream().map(p -> p.getFileName().toString()).toList();
        if (losesSavedContent) {
            assertThat(backups).as("%s: one degraded backup", holding).singleElement()
                    .satisfies(name -> assertThat(name).matches(DEGRADED_BACKUP));
        } else {
            assertThat(backups).as("%s: nothing is lost, so no backup", holding).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(UnreadProjectItem.Kind.class)
    void everyUnreadKindButAPlaceholderLosesItsSavedContent(UnreadProjectItem.Kind kind) {
        boolean expected = switch (kind) {
            case MIDI_NOTE_SKIPPED, CC_LANE_SKIPPED, CC_EVENT_SKIPPED, MIDI_EFFECT_SETTING_ADJUSTED,
                 MIXER_REFERENCE_REMOVED -> true;
            case MIDI_EFFECT_NOT_RECOGNIZED -> false;
        };

        assertThat(kind.losesSavedContent()).isEqualTo(expected);
    }

    @Test
    void thePredicateIsTheMostRecentLoadsAndResetsBeforeTheParse() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        String malformedId = file(midiTrack("not-a-uuid", ""));

        deserializer.deserialize(malformedId);
        assertThat(deserializer.getUnreadItems()).as("a track id loss is logged only").isEmpty();
        assertThat(deserializer.losesSavedContent()).isTrue();

        deserializer.deserialize(new ProjectSerializer().serialize(new DawProject("Clean", AudioFormat.CD_QUALITY)));
        assertThat(deserializer.losesSavedContent()).as("the next load's answer").isFalse();

        deserializer.deserialize(malformedId);
        assertThatThrownBy(() -> deserializer.deserialize("<daw-project")).isInstanceOf(IOException.class);
        assertThat(deserializer.losesSavedContent()).as("reset even when the file does not parse").isFalse();
    }

    @Test
    void aMigratedOpenThatIsAlsoDegradedTakesTheOneMigrationBackup() throws Exception {
        String damagedV1 = file(1, ONE_BAD_NOTE_TRACK, "");
        Path directory = projectHolding(damagedV1);
        Path original = Files.writeString(tempDir.resolve("original-v1.daw"), damagedV1);

        manager.openProject(directory);
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("fixture: a schema 1 file").isTrue();
        assertThat(manager.getLastUnreadItems()).as("fixture: the open skipped the note").hasSize(1);
        saved(manager, manager.getCurrentDawProject());

        List<Path> backups = backupsIn(directory);
        assertThat(backups).singleElement().satisfies(backup -> {
            assertThat(backup.getFileName().toString()).matches("project\\.daw\\.v1\\.\\d{8}-\\d{6}\\.bak");
            assertThat(backup.getFileName().toString()).doesNotContain(".degraded.");
        });
        assertThat(Files.mismatch(backups.getFirst(), original)).isEqualTo(-1L);
        assertThat(infoLines).as("the log says the migration backup also keeps what the save drops")
                .containsExactly(MIGRATED_AND_DEGRADED_INFO + ": " + backups.getFirst());
    }

    /** Each row: what the v2 file holds that makes its next save drop content, and the file. */
    static Stream<Arguments> degradedOnly() {
        return Stream.of(
                Arguments.of("a malformed track id only", file(midiTrack("not-a-uuid", ""))),
                Arguments.of("a placeholder effect with a child element only", file(midiTrack(A, """
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.example.chords"><voicing name="drop2"/></midi-effect>
                        </midi-effect-chain>
                        """))),
                Arguments.of("velocity 128 and an attributes-only unrecognized effect", file(midiTrack(A, """
                        <midi-clip locked="false"><notes>
                          <note note-number="62" start-column="4" duration-columns="4" velocity="128" channel="0"/>
                        </notes></midi-clip>
                        <midi-effect-chain>
                          <midi-effect plugin-id="com.example.chords" voicing="drop2"/>
                        </midi-effect-chain>
                        """))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("degradedOnly")
    void aDegradedOpenLogsOneInfoLineSayingWhyTheBackupExistsAndWhereItIs(String holding, String xml)
            throws Exception {
        Path directory = projectHolding(xml);

        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());

        List<Path> backups = backupsIn(directory);
        assertThat(backups).as("%s: one degraded backup", holding).hasSize(1);
        assertThat(infoLines).as(holding).containsExactly(DEGRADED_INFO + ": " + backups.getFirst());
    }

    @Test
    void aCleanMigratedOpenLogsThePlainMigrationBackupLine() throws Exception {
        Path directory = projectHolding(file(1, midiTrack(A, ""), ""));

        manager.openProject(directory);
        assertThat(manager.getLastMigrationReport().wasMigrated()).as("fixture: a schema 1 file").isTrue();
        saved(manager, manager.getCurrentDawProject());

        List<Path> backups = backupsIn(directory);
        assertThat(backups).hasSize(1);
        assertThat(infoLines).containsExactly("Wrote the migration backup: " + backups.getFirst());
    }

    @Test
    void aPlaceholderThatLosesNothingWritesNoBackupAndNoInfoLine() throws Exception {
        Path directory = projectHolding(file(midiTrack(A, """
                <midi-effect-chain>
                  <midi-effect plugin-id="com.example.chords" voicing="drop2"/>
                </midi-effect-chain>
                """)));

        manager.openProject(directory);
        assertThat(manager.getLastUnreadItems()).as("fixture: the effect is reported").hasSize(1);
        saved(manager, manager.getCurrentDawProject());

        assertThat(backupsIn(directory)).isEmpty();
        assertThat(infoLines).isEmpty();
    }

    @Test
    void aCleanOpenOwesNoBackup() throws Exception {
        DawProject clean = new DawProject("Clean", AudioFormat.CD_QUALITY);
        clean.createMidiTrack("Keys");
        Path directory = projectHolding(new ProjectSerializer().serialize(clean));

        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());
        saved(manager, manager.getCurrentDawProject());

        assertThat(backupsIn(directory)).isEmpty();
    }

    @Test
    void aBackupOwedWhenTheFileIsGoneIsSettledByTheFirstSave() throws Exception {
        Path directory = projectHolding(file(ONE_BAD_NOTE_TRACK));
        manager.openProject(directory);
        Files.delete(directory.resolve("project.daw"));

        saved(manager, manager.getCurrentDawProject());
        assertThat(directory.resolve("project.daw")).as("the first save wrote the file").isRegularFile();
        saved(manager, manager.getCurrentDawProject());

        assertThat(backupsIn(directory)).as("the second save does not back up the first save's output").isEmpty();
    }

    @Test
    void creatingAProjectDropsTheBackupTheOpenOneOwed() throws Exception {
        manager.openProject(projectHolding(file(ONE_BAD_NOTE_TRACK)));

        Path fresh = manager.createProject("Fresh", tempDir).projectPath();
        saved(manager, new DawProject("Fresh", AudioFormat.CD_QUALITY));

        assertThat(backupsIn(fresh)).as("the new project's first save owes nothing").isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A project folder whose {@code project.daw} holds {@code xml}, not open in the manager. */
    private Path projectHolding(String xml) throws IOException {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        manager.abandonProject();
        Files.writeString(directory.resolve("project.daw"), xml);
        return directory;
    }

    private static String midiTrack(String id, String content) {
        return "<track id=\"" + id + "\" name=\"Keys\" type=\"MIDI\">\n" + content + "</track>\n";
    }

    private static String file(String tracks) {
        return file(tracks, "");
    }

    private static String file(String tracks, String rest) {
        return file(MigrationRegistry.CURRENT_VERSION, tracks, rest);
    }

    private static String file(int version, String tracks, String rest) {
        return "<daw-project version=\"" + version + "\">\n"
                + "  <metadata><name>Song</name></metadata>\n"
                + "  <tracks>\n" + tracks + "  </tracks>\n"
                + rest
                + "</daw-project>\n";
    }

    private static List<Path> backupsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).sorted().toList();
        }
    }
}
