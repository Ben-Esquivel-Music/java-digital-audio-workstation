package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.transport.TempoChangeEvent;
import com.benesquivelmusic.daw.core.transport.TempoMap;
import com.benesquivelmusic.daw.core.transport.TimeDisplayMode;
import com.benesquivelmusic.daw.core.transport.TimeSignatureChangeEvent;
import com.benesquivelmusic.daw.core.transport.TimelineRulerModel;
import com.benesquivelmusic.daw.core.transport.Transport;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport's tempo map in {@code project.daw} (story 334, studio-engineer
 * rulings E7 and E19): the tempo changes and meter changes after beat 0 are
 * written as {@code <tempo-change>} and {@code <meter-change>} children of
 * {@code <transport>}, beat 0 staying in its {@code tempo} and
 * {@code time-sig-*} attributes, and come back value by value. The reader
 * takes exactly what the model takes, finite values only: every unusable
 * entry — and an unusable beat-0 attribute that is present — is skipped
 * whole, logged once with the exact line, counted, and backs the original up
 * on the first save; an absent attribute stays a silent default, and an
 * entry at beat 0 that repeats the beat-0 attributes is logged only.
 */
class TempoMapPersistenceTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    /** A degraded open's backup, as {@code ProjectManager} names it. */
    private static final String DEGRADED_BACKUP = "project\\.daw\\.degraded\\.\\d{8}-\\d{6}(-\\d+)?\\.bak";

    /** The beat-0 attributes every fixture holds unless it is about them. */
    private static final String FOUR_FOUR_AT_120 = "tempo=\"120.0\" time-sig-numerator=\"4\" time-sig-denominator=\"4\"";

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
    void midSongTempoAndMeterChangesRoundTripValueByValue() throws IOException {
        DawProject project = songWithAMidSongMap();
        String xml = new ProjectSerializer().serialize(project);
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject reopened = deserializer.deserialize(xml);

        TempoMap saved = project.getTransport().getTempoMap();
        TempoMap restored = reopened.getTransport().getTempoMap();
        assertThat(restored.getTempoChanges()).as("every tempo change, beat 0 included, record by record")
                .containsExactlyElementsOf(saved.getTempoChanges())
                .hasSize(4);
        assertThat(restored.getTimeSignatureChanges()).as("every meter change, beat 0 included")
                .containsExactlyElementsOf(saved.getTimeSignatureChanges())
                .hasSize(3);
        assertThat(xml.split("<tempo-change ", -1)).as("one element per tempo change after beat 0").hasSize(4);
        assertThat(xml.split("<meter-change ", -1)).as("one element per meter change after beat 0").hasSize(3);
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    /**
     * The ruler reads the same {@link TempoMap} before and after the reopen,
     * so it gives the same output: the TIME labels and the beat/second
     * conversions integrate the tempo changes, ramps included. The bar grid
     * ({@code BARS_BEATS_TICKS}) reads only the beat-0 meter today (studio
     * ruling E19: making it follow the meter map is a later story), so its
     * labels are compared too, at the same positions.
     */
    @Test
    void theRulerModelGivesIdenticalOutputBeforeAndAfterReopen() throws IOException {
        DawProject project = songWithAMidSongMap();
        DawProject reopened = new ProjectDeserializer().deserialize(new ProjectSerializer().serialize(project));
        // Beat 0, inside the LINEAR ramp into beat 32, inside the CURVED ramp
        // into beat 64, just after each change, and the end.
        double[] beats = {0.0, 20.0, 50.0, 32.5, 48.25, 64.5, 80.25, 96.5, 128.0};
        double[] seconds = {0.0, 7.5, 30.0, 61.25, 75.0};

        assertThat(rulerOutput(reopened.getTransport(), beats, seconds))
                .as("labels in both modes and both conversions")
                .containsExactlyElementsOf(rulerOutput(project.getTransport(), beats, seconds));

        Transport beatZeroOnly = new Transport();
        beatZeroOnly.setTempo(97.5);
        beatZeroOnly.setTimeSignature(7, 8);
        TimelineRulerModel withoutTheChanges = new TimelineRulerModel(beatZeroOnly);
        withoutTheChanges.setDisplayMode(TimeDisplayMode.TIME);
        TimelineRulerModel reopenedRuler = new TimelineRulerModel(reopened.getTransport());
        reopenedRuler.setDisplayMode(TimeDisplayMode.TIME);
        assertThat(reopenedRuler.formatPosition(128.0))
                .as("non-vacuous: the TIME labels depend on the restored changes")
                .isNotEqualTo(withoutTheChanges.formatPosition(128.0));
    }

    @Test
    void entriesOutOfOrderAreTakenAndSortedWithNoWarning() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(file(FOUR_FOUR_AT_120, """
                <tempo-change position="64.0" bpm="100.0" transition="CURVED"/>
                <tempo-change position="32.0" bpm="140.0" transition="LINEAR"/>
                <meter-change position="80.0" numerator="5" denominator="8"/>
                <meter-change position="48.0" numerator="3" denominator="4"/>
                """));

        TempoMap map = project.getTransport().getTempoMap();
        assertThat(map.getTempoChanges()).containsExactly(TempoChangeEvent.instant(0.0, 120.0),
                TempoChangeEvent.linear(32.0, 140.0), TempoChangeEvent.curved(64.0, 100.0));
        assertThat(map.getTimeSignatureChanges()).containsExactly(new TimeSignatureChangeEvent(0.0, 4, 4),
                new TimeSignatureChangeEvent(48.0, 3, 4), new TimeSignatureChangeEvent(80.0, 5, 8));
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    /** Each row: what the file holds, the transport's attributes and children, the entry, and the exact line. */
    static Stream<Arguments> unusableEntries() {
        return Stream.of(
                Arguments.of("a bpm above 999", FOUR_FOUR_AT_120, """
                        <tempo-change position="16.0" bpm="140.0" transition="INSTANT"/>
                        <tempo-change position="32.0" bpm="1200" transition="INSTANT"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 2 skipped — bpm '1200' is not a number from 20 to 999; saved as"
                                + " <tempo-change position=\"32.0\" bpm=\"1200\" transition=\"INSTANT\"/>"),
                Arguments.of("an entry at beat 0 that disagrees with the tempo attribute", FOUR_FOUR_AT_120, """
                        <tempo-change position="8.0" bpm="100.0" transition="INSTANT"/>
                        <tempo-change position="16.0" bpm="110.0" transition="INSTANT"/>
                        <tempo-change position="0.0" bpm="140.0" transition="INSTANT"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 3 skipped — position '0.0' is not a beat position after beat 0;"
                                + " saved as <tempo-change position=\"0.0\" bpm=\"140.0\" transition=\"INSTANT\"/>"),
                Arguments.of("an entry at -0.0 that disagrees with the tempo attribute", FOUR_FOUR_AT_120, """
                        <tempo-change position="-0.0" bpm="140.0" transition="LINEAR"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 1 skipped — position '-0.0' is not a beat position after beat 0;"
                                + " saved as <tempo-change position=\"-0.0\" bpm=\"140.0\" transition=\"LINEAR\"/>"),
                Arguments.of("a repeated position", FOUR_FOUR_AT_120, """
                        <tempo-change position="8.0" bpm="100.0" transition="INSTANT"/>
                        <tempo-change position="16.0" bpm="110.0" transition="INSTANT"/>
                        <tempo-change position="32.0" bpm="140.0" transition="LINEAR"/>
                        <tempo-change position="32.0" bpm="90.0" transition="INSTANT"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 4 skipped — position '32.0' repeats tempo change 3; saved as"
                                + " <tempo-change position=\"32.0\" bpm=\"90.0\" transition=\"INSTANT\"/>"),
                Arguments.of("an unknown transition", FOUR_FOUR_AT_120, """
                        <tempo-change position="8.0" bpm="100.0" transition="INSTANT"/>
                        <tempo-change position="16.0" bpm="110.0" transition="LINEAR"/>
                        <tempo-change position="24.0" bpm="120.0" transition="CURVED"/>
                        <tempo-change position="32.0" bpm="125.0" transition="INSTANT"/>
                        <tempo-change position="40.0" bpm="130.0" transition="EXPONENTIAL"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 5 skipped — transition 'EXPONENTIAL' is not INSTANT, LINEAR or"
                                + " CURVED; saved as <tempo-change position=\"40.0\" bpm=\"130.0\""
                                + " transition=\"EXPONENTIAL\"/>"),
                Arguments.of("a transition spelled in another case", FOUR_FOUR_AT_120, """
                        <tempo-change position="40.0" bpm="130.0" transition="linear"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 1 skipped — transition 'linear' is not INSTANT, LINEAR or CURVED;"
                                + " saved as <tempo-change position=\"40.0\" bpm=\"130.0\" transition=\"linear\"/>"),
                Arguments.of("a negative position", FOUR_FOUR_AT_120, """
                        <tempo-change position="-4.0" bpm="100.0" transition="LINEAR"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 1 skipped — position '-4.0' is not a beat position after beat 0;"
                                + " saved as <tempo-change position=\"-4.0\" bpm=\"100.0\" transition=\"LINEAR\"/>"),
                Arguments.of("an infinite position", FOUR_FOUR_AT_120, """
                        <tempo-change position="Infinity" bpm="100.0" transition="INSTANT"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 1 skipped — position 'Infinity' is not a beat position after beat"
                                + " 0; saved as <tempo-change position=\"Infinity\" bpm=\"100.0\""
                                + " transition=\"INSTANT\"/>"),
                Arguments.of("a NaN bpm", FOUR_FOUR_AT_120, """
                        <tempo-change position="16.0" bpm="NaN" transition="INSTANT"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 1 skipped — bpm 'NaN' is not a number from 20 to 999; saved as"
                                + " <tempo-change position=\"16.0\" bpm=\"NaN\" transition=\"INSTANT\"/>"),
                Arguments.of("a bpm whose text holds a newline and '<'", FOUR_FOUR_AT_120, """
                        <tempo-change position="32.0" bpm="1&#10;&lt;2" transition="INSTANT"/>
                        """, UnreadProjectItem.TransportEntry.TEMPO_CHANGES,
                        "Transport: tempo change 1 skipped — bpm '1&#xA;&lt;2' is not a number from 20 to 999;"
                                + " saved as <tempo-change position=\"32.0\" bpm=\"1&#xA;&lt;2\""
                                + " transition=\"INSTANT\"/>"),
                Arguments.of("a meter denominator of 0", FOUR_FOUR_AT_120, """
                        <meter-change position="48.0" numerator="3" denominator="0"/>
                        """, UnreadProjectItem.TransportEntry.METER_CHANGES,
                        "Transport: meter change 1 skipped — denominator '0' is not a positive whole number; saved"
                                + " as <meter-change position=\"48.0\" numerator=\"3\" denominator=\"0\"/>"),
                Arguments.of("a meter numerator that is not a whole number", FOUR_FOUR_AT_120, """
                        <meter-change position="48.0" numerator="3.5" denominator="0"/>
                        """, UnreadProjectItem.TransportEntry.METER_CHANGES,
                        "Transport: meter change 1 skipped — numerator '3.5' is not a positive whole number; saved"
                                + " as <meter-change position=\"48.0\" numerator=\"3.5\" denominator=\"0\"/>"),
                Arguments.of("a meter numerator whose text holds a newline and '<'", FOUR_FOUR_AT_120, """
                        <meter-change position="48.0" numerator="3&#10;&lt;4" denominator="4"/>
                        """, UnreadProjectItem.TransportEntry.METER_CHANGES,
                        "Transport: meter change 1 skipped — numerator '3&#xA;&lt;4' is not a positive whole number;"
                                + " saved as <meter-change position=\"48.0\" numerator=\"3&#xA;&lt;4\""
                                + " denominator=\"4\"/>"),
                Arguments.of("a meter denominator whose text holds a newline and '<'", FOUR_FOUR_AT_120, """
                        <meter-change position="48.0" numerator="3" denominator="4&#10;&lt;"/>
                        """, UnreadProjectItem.TransportEntry.METER_CHANGES,
                        "Transport: meter change 1 skipped — denominator '4&#xA;&lt;' is not a positive whole"
                                + " number; saved as <meter-change position=\"48.0\" numerator=\"3\""
                                + " denominator=\"4&#xA;&lt;\"/>"),
                Arguments.of("a repeated meter position", FOUR_FOUR_AT_120, """
                        <meter-change position="48.0" numerator="3" denominator="4"/>
                        <meter-change position="48.0" numerator="5" denominator="8"/>
                        """, UnreadProjectItem.TransportEntry.METER_CHANGES,
                        "Transport: meter change 2 skipped — position '48.0' repeats meter change 1; saved as"
                                + " <meter-change position=\"48.0\" numerator=\"5\" denominator=\"8\"/>"),
                Arguments.of("a meter entry at beat 0 that disagrees with the meter attributes", FOUR_FOUR_AT_120, """
                        <meter-change position="0" numerator="3" denominator="4"/>
                        """, UnreadProjectItem.TransportEntry.METER_CHANGES,
                        "Transport: meter change 1 skipped — position '0' is not a beat position after beat 0;"
                                + " saved as <meter-change position=\"0\" numerator=\"3\" denominator=\"4\"/>"),
                Arguments.of("a beat-0 tempo above 999",
                        "tempo=\"1200\" time-sig-numerator=\"4\" time-sig-denominator=\"4\"", "",
                        UnreadProjectItem.TransportEntry.BEAT_ZERO_TEMPO,
                        "Transport: tempo at beat 0 skipped — tempo '1200' is not a number from 20 to 999; 120.0 is"
                                + " used"),
                Arguments.of("a beat-0 meter denominator of 0",
                        "tempo=\"120.0\" time-sig-numerator=\"4\" time-sig-denominator=\"0\"", "",
                        UnreadProjectItem.TransportEntry.BEAT_ZERO_METER,
                        "Transport: meter at beat 0 skipped — time-sig-denominator '0' is not a positive whole"
                                + " number; 4/4 is used"),
                Arguments.of("a beat-0 numerator that is not a whole number",
                        "tempo=\"120.0\" time-sig-numerator=\"x\" time-sig-denominator=\"0\"", "",
                        UnreadProjectItem.TransportEntry.BEAT_ZERO_METER,
                        "Transport: meter at beat 0 skipped — time-sig-numerator 'x' is not a positive whole"
                                + " number; 4/4 is used"),
                Arguments.of("a beat-0 numerator whose text holds a newline and '<'",
                        "tempo=\"120.0\" time-sig-numerator=\"3&#10;&lt;4\" time-sig-denominator=\"4\"", "",
                        UnreadProjectItem.TransportEntry.BEAT_ZERO_METER,
                        "Transport: meter at beat 0 skipped — time-sig-numerator '3&#xA;&lt;4' is not a positive"
                                + " whole number; 4/4 is used"),
                Arguments.of("a beat-0 denominator whose text holds a newline and '<'",
                        "tempo=\"120.0\" time-sig-numerator=\"3\" time-sig-denominator=\"4&#10;&lt;\"", "",
                        UnreadProjectItem.TransportEntry.BEAT_ZERO_METER,
                        "Transport: meter at beat 0 skipped — time-sig-denominator '4&#xA;&lt;' is not a positive"
                                + " whole number; 4/4 is used"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableEntries")
    void everyUnusableEntryIsSkippedCountedAndBackedUp(String holding, String attributes, String children,
                                                       UnreadProjectItem.TransportEntry entry, String line)
            throws Exception {
        String xml = file(attributes, children);
        Path directory = projectHolding(xml);
        ProjectDeserializer deserializer = new ProjectDeserializer();

        deserializer.deserialize(xml, directory);

        assertThat(warnings).as("%s: the one WARNING", holding).containsExactly(line);
        assertThat(deserializer.getUnreadItems()).as("%s: counted once", holding).singleElement()
                .satisfies(item -> {
                    assertThat(item.kind()).isEqualTo(UnreadProjectItem.Kind.TEMPO_MAP_ENTRY_SKIPPED);
                    assertThat(item.location()).isEqualTo(new UnreadProjectItem.InTransport(entry));
                    assertThat(item.logLine()).isEqualTo(line);
                });
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
    void aNaNTempoAttributeIsReportedAsTempoAtBeatZero() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(file(
                "tempo=\"NaN\" time-sig-numerator=\"7\" time-sig-denominator=\"8\"", ""));

        assertThat(warnings).containsExactly("Transport: tempo at beat 0 skipped — tempo 'NaN' is not a number"
                + " from 20 to 999; 120.0 is used");
        assertThat(deserializer.getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::location)
                .isEqualTo(new UnreadProjectItem.InTransport(UnreadProjectItem.TransportEntry.BEAT_ZERO_TEMPO));
        assertThat(project.getTransport().getTempo()).as("the default stays").isEqualTo(120.0);
        assertThat(project.getTransport().getTimeSignatureNumerator()).as("the meter is read on its own")
                .isEqualTo(7);
    }

    @Test
    void anUnusableBeatZeroMeterAttributeLeavesThePairAtFourFour() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(file(
                "tempo=\"97.5\" time-sig-numerator=\"x\" time-sig-denominator=\"8\"", ""));

        assertThat(project.getTransport().getTimeSignatureNumerator()).isEqualTo(4);
        assertThat(project.getTransport().getTimeSignatureDenominator())
                .as("the usable denominator falls back with the pair").isEqualTo(4);
        assertThat(project.getTransport().getTempo()).isEqualTo(97.5);
        assertThat(deserializer.getUnreadItems()).hasSize(1);
    }

    /** Studio ruling-2 G2: an absent attribute is still a silent default, even beside a present one. */
    @Test
    void aNumeratorAloneOpensOverAQuarterNoteSilently() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(file("tempo=\"120.0\" time-sig-numerator=\"3\"", ""));

        assertThat(project.getTransport().getTimeSignatureNumerator()).isEqualTo(3);
        assertThat(project.getTransport().getTimeSignatureDenominator()).isEqualTo(4);
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aFileSavedBeforeTheTempoMapOpensWithEmptyListsAndNoWarning() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize("""
                <daw-project version="2">
                  <metadata><name>Old Song</name></metadata>
                  <transport tempo="96.0" time-sig-numerator="6" time-sig-denominator="8" loop-enabled="false"\
                 loop-start="0.0" loop-end="16.0" position="0.0"/>
                  <tracks/>
                </daw-project>
                """);

        TempoMap map = project.getTransport().getTempoMap();
        assertThat(map.getTempoChanges()).as("beat 0 only").containsExactly(TempoChangeEvent.instant(0.0, 96.0));
        assertThat(map.getTimeSignatureChanges()).containsExactly(new TimeSignatureChangeEvent(0.0, 6, 8));
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();
        assertThat(warnings).isEmpty();

        DawProject absent = deserializer.deserialize(file("", ""));
        assertThat(absent.getTransport().getTempo()).as("no attributes: 120 BPM, silently").isEqualTo(120.0);
        assertThat(absent.getTransport().getTimeSignatureNumerator()).isEqualTo(4);
        assertThat(absent.getTransport().getTimeSignatureDenominator()).isEqualTo(4);
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aBeatZeroEntryEqualToTheAttributesIsLoggedOnly() throws Exception {
        String xml = file("tempo=\"97.5\" time-sig-numerator=\"7\" time-sig-denominator=\"8\"", """
                <tempo-change position="0.0" bpm="97.5" transition="INSTANT"/>
                <tempo-change position="32.0" bpm="140.0" transition="LINEAR"/>
                <meter-change position="-0.0" numerator="7" denominator="8"/>
                """);
        Path directory = projectHolding(xml);
        ProjectDeserializer deserializer = new ProjectDeserializer();

        DawProject project = deserializer.deserialize(xml, directory);

        assertThat(warnings).containsExactly(
                "Transport: tempo change 1 at position '0.0' repeats the tempo at beat 0; it is not added",
                "Transport: meter change 1 at position '-0.0' repeats the meter at beat 0; it is not added");
        assertThat(deserializer.getUnreadItems()).as("logged only, not counted").isEmpty();
        assertThat(deserializer.losesSavedContent()).isFalse();
        TempoMap map = project.getTransport().getTempoMap();
        assertThat(map.getTempoChanges()).containsExactly(TempoChangeEvent.instant(0.0, 97.5),
                TempoChangeEvent.linear(32.0, 140.0));
        assertThat(map.getTimeSignatureChanges()).as("-0.0 never shadows beat 0")
                .containsExactly(new TimeSignatureChangeEvent(0.0, 7, 8));

        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());
        assertThat(backupsIn(directory)).as("nothing the save drops is content").isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Beat 0 at 97.5 BPM in 7/8, ramps LINEAR into 32, CURVED into 64, a step at 96; 3/4 at 48, 5/8 at 80. */
    private static DawProject songWithAMidSongMap() {
        DawProject project = new DawProject("Song", FORMAT);
        Transport transport = project.getTransport();
        transport.setTempo(97.5);
        transport.setTimeSignature(7, 8);
        transport.getTempoMap().addTempoChange(TempoChangeEvent.linear(32.0, 140.0));
        transport.getTempoMap().addTempoChange(TempoChangeEvent.curved(64.0, 100.0));
        transport.getTempoMap().addTempoChange(TempoChangeEvent.instant(96.0, 120.0));
        transport.getTempoMap().addTimeSignatureChange(new TimeSignatureChangeEvent(48.0, 3, 4));
        transport.getTempoMap().addTimeSignatureChange(new TimeSignatureChangeEvent(80.0, 5, 8));
        return project;
    }

    /** What the ruler shows for {@code transport}: labels in both modes, then both conversions. */
    private static List<String> rulerOutput(Transport transport, double[] beats, double[] seconds) {
        TimelineRulerModel ruler = new TimelineRulerModel(transport);
        List<String> output = new ArrayList<>();
        for (TimeDisplayMode mode : TimeDisplayMode.values()) {
            ruler.setDisplayMode(mode);
            for (double beat : beats) {
                output.add(mode + " at beat " + beat + ": " + ruler.formatPosition(beat));
            }
        }
        for (double beat : beats) {
            output.add("beat " + beat + " in seconds: " + ruler.beatsToSeconds(beat));
        }
        for (double second : seconds) {
            output.add("second " + second + " in beats: " + ruler.secondsToBeats(second));
        }
        return output;
    }

    /** A current-schema file whose {@code <transport>} has {@code attributes} and {@code children}. */
    private static String file(String attributes, String children) {
        return "<daw-project version=\"" + MigrationRegistry.CURRENT_VERSION + "\">\n"
                + "  <metadata><name>Song</name></metadata>\n"
                + "  <transport " + attributes + ">\n" + children + "  </transport>\n"
                + "  <tracks/>\n"
                + "</daw-project>\n";
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
