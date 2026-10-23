package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The open report of saved items a load could not honour as written
 * ({@link ProjectDeserializer#getUnreadItems()},
 * {@link ProjectManager#getLastUnreadItems()}): empty for a clean file,
 * reset on every load — before the parse — returned as a copy, and reset by
 * every {@code ProjectManager} transition that resets the missing-assets
 * report. Each item is logged once at WARNING as
 * {@code <where>: <what> — <reason>[; saved as <snippet>][, at transport beat <b>]},
 * tracks and items numbered from 1, every string from the file quoted so it
 * can neither split nor forge the line. The open's other per-track WARNINGs
 * — a track id, a lane's height ratio, a placeholder effect's dropped
 * content, a clip's unusable segment or reference — start with the same
 * {@code Track <n> '<name>':}; a clip is named {@code clip <c> '<name>'},
 * numbered from 1 and named as restored.
 */
class UnreadProjectItemsTest {

    /** One unreadable note (velocity 128) on the only track. */
    private static final String ONE_BAD_NOTE = """
            <daw-project version="%d">
              <tracks>
                <track id="00000000-0000-0000-0000-00000000000a" name="Keys" type="MIDI">
                  <midi-clip locked="false">
                    <notes>
                      <note note-number="60" start-column="0" duration-columns="4" velocity="128" channel="0"/>
                    </notes>
                  </midi-clip>
                </track>
              </tracks>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION);

    @TempDir
    Path tempDir;

    @Test
    void aCleanFileHasNoUnreadItems() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject project = new DawProject("Clean", AudioFormat.CD_QUALITY);
        project.createMidiTrack("Keys");

        deserializer.deserialize(new ProjectSerializer().serialize(project));

        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void theListResetsOnTheNextLoadAndIsACopy() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.deserialize(ONE_BAD_NOTE);
        List<UnreadProjectItem> first = deserializer.getUnreadItems();

        assertThat(first).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED);
            assertThat(item.location()).isEqualTo(new UnreadProjectItem.OnTrack(0, "Keys"));
            assertThat(item.index()).isZero();
            assertThat(item.count()).isEqualTo(1);
        });
        assertThatThrownBy(() -> first.add(first.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);

        deserializer.deserialize(new ProjectSerializer().serialize(
                new DawProject("Clean", AudioFormat.CD_QUALITY)));

        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(first).as("an earlier result is not a live view").hasSize(1);
    }

    @Test
    void aFileThatDoesNotParseStillLeavesAFreshList() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.deserialize(ONE_BAD_NOTE);

        assertThatThrownBy(() -> deserializer.deserialize("<daw-project"))
                .isInstanceOf(IOException.class);

        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void projectManagerKeepsTheOpensItemsAndResetsThemWithTheMissingAssets() throws Exception {
        ProjectManager manager = new ProjectManager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));

        ProjectMetadata metadata = manager.createProject("Damaged", tempDir);
        assertThat(manager.getLastUnreadItems()).as("after create").isEmpty();
        Path projectFile = metadata.projectPath().resolve("project.daw");
        String legacyMetadataFile = Files.readString(projectFile);
        Files.writeString(projectFile, ONE_BAD_NOTE);
        manager.abandonProject();

        manager.openProject(metadata.projectPath());
        assertThat(manager.getLastUnreadItems()).as("after the damaged open").hasSize(1);
        assertThat(manager.getLastUnreadItems().getFirst().kind())
                .isEqualTo(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED);
        manager.closeProject();
        assertThat(manager.getLastUnreadItems()).as("after close").isEmpty();

        // closeProject saved the project without the unreadable note.
        Files.writeString(projectFile, ONE_BAD_NOTE);
        manager.openProject(metadata.projectPath());
        assertThat(manager.getLastUnreadItems()).hasSize(1);
        manager.abandonProject();
        assertThat(manager.getLastUnreadItems()).as("after abandon").isEmpty();

        manager.openProject(metadata.projectPath());
        assertThat(manager.getLastUnreadItems()).hasSize(1);
        manager.abandonProject();
        Files.writeString(projectFile, legacyMetadataFile);
        manager.openProject(metadata.projectPath());
        assertThat(manager.getCurrentDawProject()).as("a legacy metadata-only open").isNull();
        assertThat(manager.getLastUnreadItems()).as("after a legacy metadata-only open").isEmpty();
        manager.abandonProject();

        Files.writeString(projectFile, ONE_BAD_NOTE);
        manager.openProject(metadata.projectPath());
        assertThat(manager.getLastUnreadItems()).hasSize(1);
        manager.createProject("Fresh", tempDir);
        assertThat(manager.getLastUnreadItems()).as("after creating another project").isEmpty();
        manager.abandonProject();
    }

    @Test
    void anItemIsLocatedWhereItsKindLivesAndLogsOneLineNumberedFromOne() {
        UnreadProjectItem note = new UnreadProjectItem(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED,
                new UnreadProjectItem.OnTrack(1, "Keys"), 4, "velocity must be 0–127: 128", 1);
        UnreadProjectItem lane = new UnreadProjectItem(UnreadProjectItem.Kind.CC_LANE_SKIPPED,
                new UnreadProjectItem.OnTrack(1, "Keys"), 0, "type 'X' is not a CC lane type", 3);
        UnreadProjectItem event = new UnreadProjectItem(UnreadProjectItem.Kind.CC_EVENT_SKIPPED,
                new UnreadProjectItem.OnTrack(0, "Keys"), 0, "column must be >= 0: -1", 1,
                new UnreadProjectItem.EventLane(1, MidiCcLaneType.VELOCITY, -1));
        UnreadProjectItem send = new UnreadProjectItem(UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED,
                new UnreadProjectItem.InMixer("cue bus 'Singer'"), 2, "cue-send@track-id 'x' is not a track id", 1);

        assertThat(note.logLine())
                .isEqualTo("Track 2 'Keys': MIDI note 5 skipped — velocity must be 0–127: 128");
        assertThat(note.index()).as("the record stays zero-based").isEqualTo(4);
        assertThat(lane.logLine()).contains("CC lane 1 skipped with its 3 saved events");
        assertThat(event.logLine())
                .isEqualTo("Track 1 'Keys': CC event 1 of CC lane 2 (VELOCITY) skipped — column must be >= 0: -1");
        assertThat(send.logLine())
                .isEqualTo("Mixer cue bus 'Singer': mixer assignment 3 removed — cue-send@track-id 'x' is not a track id");
        assertThatIllegalArgumentException().isThrownBy(() -> new UnreadProjectItem(
                UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED, new UnreadProjectItem.InMixer("bed bus"), 0, "", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new UnreadProjectItem(
                UnreadProjectItem.Kind.MIXER_REFERENCE_REMOVED, new UnreadProjectItem.OnTrack(0, "A"), 0, "", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new UnreadProjectItem(
                UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED, new UnreadProjectItem.OnTrack(0, "A"), 0, "", 2));
        assertThatIllegalArgumentException().as("only a skipped lane stands for no saved item").isThrownBy(() ->
                new UnreadProjectItem(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED,
                        new UnreadProjectItem.OnTrack(0, "A"), 0, "", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new UnreadProjectItem(
                UnreadProjectItem.Kind.CC_LANE_SKIPPED, new UnreadProjectItem.OnTrack(0, "A"), 0, "", -1));
        assertThatIllegalArgumentException().as("a skipped event names its lane").isThrownBy(() ->
                new UnreadProjectItem(UnreadProjectItem.Kind.CC_EVENT_SKIPPED,
                        new UnreadProjectItem.OnTrack(0, "A"), 0, "", 1));
        assertThatIllegalArgumentException().as("only a skipped event is saved in a lane").isThrownBy(() ->
                new UnreadProjectItem(UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED,
                        new UnreadProjectItem.OnTrack(0, "A"), 0, "", 1,
                        new UnreadProjectItem.EventLane(0, MidiCcLaneType.SUSTAIN, 64)));
    }

    @Test
    void fileTextIsQuotedSoItCanNeitherSplitNorForgeALogLine() {
        assertThat(UnreadProjectItem.logText("Keys & <Pads> \"2\"")).isEqualTo("Keys &amp; &lt;Pads> &quot;2&quot;");
        assertThat(UnreadProjectItem.logText("a\nb\rc\td\u001Fe\u007Ff")).isEqualTo("a&#xA;b&#xD;c&#x9;d&#x1F;e&#x7F;f");
        assertThat(UnreadProjectItem.logText("x".repeat(64))).as("64 characters are kept whole")
                .isEqualTo("x".repeat(64));
        assertThat(UnreadProjectItem.logText("x".repeat(65))).isEqualTo("x".repeat(64) + "…");
        assertThat(UnreadProjectItem.logText("🎹".repeat(70)))
                .as("cut by characters, never inside a surrogate pair")
                .isEqualTo("🎹".repeat(64) + "…");
    }

    // ── the WARNING line of each skipped item (studio-engineer ruling Q5) ────

    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Logger deserializerLog = Logger.getLogger(ProjectDeserializer.class.getName());
    private final Handler warningHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
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

    @BeforeEach
    void listenToTheDeserializer() {
        deserializerLog.addHandler(warningHandler);
    }

    @AfterEach
    void stopListening() {
        deserializerLog.removeHandler(warningHandler);
    }

    /** Track 1 is clean; track 2 'Keys' holds note 5, CC lane 3 and event 7 of CC lane 1 the open cannot read. */
    private static final String SECOND_TRACK_DAMAGED = """
            <daw-project version="%d">
              <tracks>
                <track id="00000000-0000-0000-0000-00000000000a" name="Piano" type="MIDI">
                  <midi-clip locked="false">
                    <notes>
                      <note note-number="48" start-column="0" duration-columns="4" velocity="90" channel="0"/>
                    </notes>
                  </midi-clip>
                </track>
                <track id="00000000-0000-0000-0000-00000000000b" name="Keys" type="MIDI">
                  <midi-clip locked="false">
                    <notes>
                      <note note-number="60" start-column="0" duration-columns="4" velocity="100" channel="0"/>
                      <note note-number="62" start-column="4" duration-columns="4" velocity="100" channel="0"/>
                      <note note-number="64" start-column="8" duration-columns="4" velocity="100" channel="0"/>
                      <note note-number="65" start-column="12" duration-columns="4" velocity="100" channel="0"/>
                      <note note-number="60" start-column="17" duration-columns="4" velocity="128" channel="0"/>
                    </notes>
                    <cc-lanes>
                      <cc-lane type="MOD_WHEEL" high-resolution="false" channel="0" height-ratio="1.0">
                        <event column="0" value="10"/>
                        <event column="4" value="20"/>
                        <event column="8" value="30"/>
                        <event column="12" value="40"/>
                        <event column="16" value="50"/>
                        <event column="20" value="60"/>
                        <event column="32" value="20000"/>
                      </cc-lane>
                      <cc-lane type="SUSTAIN" high-resolution="false" channel="0" height-ratio="1.0">
                        <event column="0" value="127"/>
                      </cc-lane>
                      <cc-lane type="BREATH" high-resolution="false" channel="0" height-ratio="1.0">
                        %s
                      </cc-lane>
                    </cc-lanes>
                  </midi-clip>
                </track>
              </tracks>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION, "<event column=\"0\" value=\"1\"/>".repeat(12));

    @Test
    void theWarningNamesTheTrackFromOneTheItemFromOneAndWhatWasSaved() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        deserializer.deserialize(SECOND_TRACK_DAMAGED);

        assertThat(messages()).containsExactly(
                "Track 2 'Keys': MIDI note 5 skipped — velocity must be 0–127: 128; saved as <note"
                        + " note-number=\"60\" start-column=\"17\" duration-columns=\"4\" velocity=\"128\""
                        + " channel=\"0\"/>, at transport beat 4.25",
                "Track 2 'Keys': CC event 7 of CC lane 1 (MOD_WHEEL, CC 1) skipped — value must be 0–16383:"
                        + " 20000; saved as <event column=\"32\" value=\"20000\"/>, at transport beat 8",
                "Track 2 'Keys': CC lane 3 skipped with its 12 saved events — type 'BREATH' is not a CC lane"
                        + " type; saved as <cc-lane type=\"BREATH\" high-resolution=\"false\" channel=\"0\""
                        + " height-ratio=\"1.0\"/>");
    }

    @Test
    void theFirstNoteOfTheFirstTrackIsNumberOneInTheLogAndZeroInTheRecord() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        deserializer.deserialize(ONE_BAD_NOTE);

        assertThat(messages()).singleElement().asString().startsWith("Track 1 'Keys': MIDI note 1 skipped");
        assertThat(deserializer.getUnreadItems()).singleElement()
                .satisfies(item -> assertThat(item.index()).isZero());
    }

    @Test
    void aMissingVelocityIsLeftOutOfTheSnippetAndTheBeatIsStillGiven() throws Exception {
        new ProjectDeserializer().deserialize(oneNote(
                "<note note-number=\"60\" start-column=\"2\" duration-columns=\"4\" channel=\"3\"/>"));

        assertThat(messages()).containsExactly("Track 1 'Keys': MIDI note 1 skipped — velocity is missing;"
                + " saved as <note note-number=\"60\" start-column=\"2\" duration-columns=\"4\" channel=\"3\"/>,"
                + " at transport beat 0.5");
    }

    @Test
    void aStartColumnThatIsNotAnIntegerGivesNoBeat() throws Exception {
        new ProjectDeserializer().deserialize(oneNote(
                "<note note-number=\"60\" start-column=\"x\" duration-columns=\"4\" velocity=\"100\" channel=\"0\"/>"));

        assertThat(messages()).singleElement().asString()
                .contains("start-column 'x' is not an integer")
                .doesNotContain(", at transport beat");
    }

    @Test
    void textFromTheFileCannotSplitTheLineAndALongValueIsCut() throws Exception {
        String velocity = "1".repeat(200);
        new ProjectDeserializer().deserialize("""
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Ke&#10;ys" type="MIDI">
                      <midi-clip locked="false"><notes>
                        <note note-number="60" start-column="0" duration-columns="4" velocity="%s" channel="0"/>
                      </notes></midi-clip>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, velocity));

        String cut = "1".repeat(64) + "…";
        assertThat(messages()).singleElement().asString()
                .doesNotContain("\n")
                .startsWith("Track 1 'Ke&#xA;ys': MIDI note 1 skipped — velocity '" + cut + "' is not an integer;")
                .contains(" velocity=\"" + cut + "\" ")
                .doesNotContain("1".repeat(65));
    }

    @Test
    void theOpenLogsOneWarningPerItem() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        deserializer.deserialize(SECOND_TRACK_DAMAGED);

        assertThat(warnings).hasSameSizeAs(deserializer.getUnreadItems()).hasSize(3);
    }

    @Test
    void aMalformedTrackIdIsLoggedWhereTheTrackIs() throws Exception {
        new ProjectDeserializer().deserialize("""
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Piano" type="MIDI"/>
                    <track id="not-a-uuid" name="Keys" type="MIDI"/>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION));

        assertThat(messages()).containsExactly("Track 2 'Keys': id 'not-a-uuid' is not a UUID; a new id is used");
    }

    @Test
    void aDuplicateTrackIdIsLoggedNamingTheTrackThatKeepsIt() throws Exception {
        new ProjectDeserializer().deserialize("""
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="A" type="MIDI"/>
                    <track id="00000000-0000-0000-0000-00000000000a" name="B" type="MIDI"/>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION));

        assertThat(messages()).containsExactly("Track 2 'B': id 00000000-0000-0000-0000-00000000000a is already"
                + " used by Track 1 'A'; a new id is used");
    }

    @Test
    void anUnusableHeightRatioIsLoggedWithItsLaneNumberedFromOne() throws Exception {
        new ProjectDeserializer().deserialize(keysTrackHolding("""
                <midi-clip locked="false"><cc-lanes>
                  <cc-lane type="MOD_WHEEL" high-resolution="false" channel="0" height-ratio="1.0"/>
                  <cc-lane type="EXPRESSION" high-resolution="false" channel="0" height-ratio="-2"/>
                </cc-lanes></midi-clip>"""));

        assertThat(messages()).containsExactly(
                "Track 1 'Keys': CC lane 2 height-ratio '-2' is not a positive number; 1.0 is used");
    }

    @Test
    void aPlaceholderEffectsTextIsLoggedAsDroppedBeforeTheEffectItself() throws Exception {
        new ProjectDeserializer().deserialize(keysTrackHolding("""
                <midi-effect-chain>
                  <midi-effect plugin-id="com.example.unknown">loose text</midi-effect>
                </midi-effect-chain>"""));

        assertThat(messages()).containsExactly(
                "Track 1 'Keys': MIDI effect 1 has text no writer produces; it is dropped",
                "Track 1 'Keys': MIDI effect 1 not recognized (kept as saved) — plugin-id 'com.example.unknown'"
                        + " is not a MIDI effect this build knows");
    }

    @Test
    void anUnreadableLaneSavedWithNoEventsLogsNoSavedEventsAndStillLosesTheLane() throws Exception {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        deserializer.deserialize(keysTrackHolding("""
                <midi-clip locked="false"><cc-lanes>
                  <cc-lane type="BREATH" high-resolution="false" channel="0" height-ratio="1.0"/>
                </cc-lanes></midi-clip>"""));

        assertThat(messages()).containsExactly("Track 1 'Keys': CC lane 1 skipped with no saved events — type"
                + " 'BREATH' is not a CC lane type; saved as <cc-lane type=\"BREATH\" high-resolution=\"false\""
                + " channel=\"0\" height-ratio=\"1.0\"/>");
        assertThat(deserializer.getUnreadItems()).singleElement()
                .satisfies(item -> assertThat(item.count()).as("the events the file holds").isZero());
        assertThat(deserializer.losesSavedContent()).as("the next save drops the lane element").isTrue();
    }

    @Test
    void aClipNameOrPathFromTheFileCannotSplitItsLineWhichNamesTheTrackAndTheSegmentFromOne() throws Exception {
        new ProjectDeserializer().deserialize("""
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Piano" type="MIDI"/>
                    <track id="00000000-0000-0000-0000-00000000000b" name="Vox" type="AUDIO">
                      <clips>
                        <clip name="Take&#10;WARNING: forged" start-beat="0.0" duration-beats="1.0">
                          <source-segment path="audio/a.wav"/>
                          <source-segment path=""/>
                        </clip>
                        <clip name="Take&#13;2" start-beat="1.0" duration-beats="1.0">
                          <source-segment path="audio/b.wav"/>
                          <source-segment path="../b&#10;WARNING: forged.wav"/>
                          <source-segment path="audio/c.wav"/>
                        </clip>
                        <clip name="Single" start-beat="2.0" duration-beats="1.0" source-file="../single.wav"/>
                      </clips>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION), tempDir);

        // Windows refuses a control character in a path; elsewhere the path parses and escapes.
        String forgedPathReason = OS.WINDOWS.isCurrentOs()
                ? "is not a valid path on this platform" : "escapes the project directory";
        assertThat(messages()).containsExactly(
                "Track 2 'Vox': clip 1 'Take&#xA;WARNING: forged' source segment 2 of 2 skipped — the path is"
                        + " missing",
                "Track 2 'Vox': clip 2 'Take&#xD;2' source segment 2 of 3 not resolved — the path"
                        + " '../b&#xA;WARNING: forged.wav' " + forgedPathReason + " (project directory " + tempDir
                        + "); it is kept as written and reported missing",
                "Track 2 'Vox': clip 3 'Single' source file not resolved — the path '../single.wav' escapes the"
                        + " project directory (project directory " + tempDir + "); it is kept as written and"
                        + " reported missing");
        assertThat(messages()).allSatisfy(message -> assertThat(message).doesNotContain("\n", "\r"));
    }

    @Test
    void aClipIsNumberedFromOneAndNamedAsRestoredSoRepeatedAndEmptyNamesStayApart() throws Exception {
        // A duplicate or a paste keeps the clip's name; an empty name is
        // restored, and shown on the timeline, as 'Untitled Clip'.
        new ProjectDeserializer().deserialize("""
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Vox" type="AUDIO">
                      <clips>
                        <clip name="Take 1" start-beat="0.0" duration-beats="1.0" source-file="../take.wav"/>
                        <clip name="Take 1" start-beat="1.0" duration-beats="1.0" source-file="../take.wav"/>
                        <clip name="" start-beat="2.0" duration-beats="1.0" source-file="../take.wav"/>
                      </clips>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION), tempDir);

        String unresolved = " source file not resolved — the path '../take.wav' escapes the project directory"
                + " (project directory " + tempDir + "); it is kept as written and reported missing";
        assertThat(messages()).containsExactly(
                "Track 1 'Vox': clip 1 'Take 1'" + unresolved,
                "Track 1 'Vox': clip 2 'Take 1'" + unresolved,
                "Track 1 'Vox': clip 3 'Untitled Clip'" + unresolved);
    }

    private List<String> messages() {
        return warnings.stream().map(LogRecord::getMessage).toList();
    }

    /** A file whose only track, the MIDI track 'Keys', holds {@code children}. */
    private static String keysTrackHolding(String children) {
        return """
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Keys" type="MIDI">
                %s
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, children);
    }

    /** A file whose only track, 'Keys', holds the one note {@code note}. */
    private static String oneNote(String note) {
        return """
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-00000000000a" name="Keys" type="MIDI">
                      <midi-clip locked="false"><notes>%s</notes></midi-clip>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, note);
    }
}
