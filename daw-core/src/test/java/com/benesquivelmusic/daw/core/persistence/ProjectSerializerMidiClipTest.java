package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.MidiCcEvent;
import com.benesquivelmusic.daw.core.midi.MidiCcLane;
import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A track's {@link MidiClip} — notes (pitch, timing, velocity, channel, order,
 * duplicates), the lock flag, and CC editing lanes with their breakpoints —
 * survives a project save and reopen through {@link ProjectSerializer} /
 * {@link ProjectDeserializer}, inside the track's {@code <midi-clip>}
 * element; a clip with nothing to save writes no element; and a note, lane or
 * event the open cannot read is skipped and reported without refusing the
 * project.
 */
class ProjectSerializerMidiClipTest {

    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void roundTripsNotesAndCcLanes() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track keys = project.createMidiTrack("Keys");
        MidiClip clip = keys.getMidiClip();
        clip.addNote(new MidiNoteData(0, 0, 1, 0, 0));
        clip.addNote(new MidiNoteData(127, 4, 2, 127, 15));
        clip.addNote(new MidiNoteData(60, 8, 4, 100, 3));
        clip.addNote(new MidiNoteData(60, 8, 4, 100, 3));
        clip.addNote(new MidiNoteData(62, 9, 4, 90, 3));
        clip.setLocked(true);

        MidiCcLane velocity = MidiCcLane.velocity();
        velocity.setHeightRatio(0.6);
        clip.addCcLane(velocity);
        MidiCcLane volume = new MidiCcLane(MidiCcLaneType.ARBITRARY_CC, 7, false, 3);
        volume.addEvent(new MidiCcEvent(2, 100));
        volume.addEvent(new MidiCcEvent(6, 64));
        clip.addCcLane(volume);
        MidiCcLane modWheel = new MidiCcLane(MidiCcLaneType.MOD_WHEEL, -1, true, 2);
        modWheel.addEvent(new MidiCcEvent(0, 0));
        modWheel.addEvent(new MidiCcEvent(8, 16383));
        clip.addCcLane(modWheel);
        MidiCcLane pitchBend = new MidiCcLane(MidiCcLaneType.PITCH_BEND, -1, true, 1);
        pitchBend.addEvent(new MidiCcEvent(0, 0));
        pitchBend.addEvent(new MidiCcEvent(4, MidiCcEvent.PITCH_BEND_CENTRE));
        pitchBend.addEvent(new MidiCcEvent(8, 16383));
        clip.addCcLane(pitchBend);
        MidiCcLane sustain = new MidiCcLane(MidiCcLaneType.SUSTAIN, 64, false, 0);
        sustain.setHeightRatio(0.5);
        sustain.addEvent(new MidiCcEvent(0, 127));
        sustain.addEvent(new MidiCcEvent(4, 0));
        sustain.addEvent(new MidiCcEvent(12, 127));
        clip.addCcLane(sustain);

        String xml = new ProjectSerializer().serialize(project);
        DawProject reopened = deserializer.deserialize(xml);

        assertThat(xml).contains("<midi-clip locked=\"true\">");
        assertThat(xml.split("cc-number=", -1))
                .as("only the ARBITRARY_CC lane writes its CC number")
                .hasSize(2);
        MidiClip restored = reopened.getTracks().getFirst().getMidiClip();
        assertThat(restored.getOwningTrackId())
                .as("the restored clip is owned by the reopened track")
                .isEqualTo(UUID.fromString(keys.getId()));
        assertThat(restored.isLocked()).as("locked").isTrue();
        assertThat(restored.getNotes())
                .as("every note field, file order and the duplicate")
                .containsExactlyElementsOf(clip.getNotes());
        assertThat(restored.getCcLanes()).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertSameLane(restored.getCcLanes().get(i), clip.getCcLanes().get(i), i);
        }
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void roundTripPreservesEmptyClip() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        project.createMidiTrack("Keys");

        String xml = new ProjectSerializer().serialize(project);
        MidiClip restored = deserializer.deserialize(xml).getTracks().getFirst().getMidiClip();

        assertThat(xml).as("a clip with nothing to save writes no element").doesNotContain("<midi-clip");
        assertThat(restored.getNotes()).isEmpty();
        assertThat(restored.getCcLanes()).isEmpty();
        assertThat(restored.isLocked()).isFalse();
    }

    @Test
    void lockedClipWithoutNotesIsWritten() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        project.createMidiTrack("Keys").getMidiClip().setLocked(true);

        String xml = new ProjectSerializer().serialize(project);
        MidiClip restored = deserializer.deserialize(xml).getTracks().getFirst().getMidiClip();

        assertThat(xml).contains("<midi-clip locked=\"true\">");
        assertThat(restored.isLocked()).isTrue();
        assertThat(restored.getNotes()).isEmpty();
    }

    @Test
    void midiClipOnAnAudioTrackRoundTrips() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track vocals = project.createAudioTrack("Vocals");
        vocals.getMidiClip().addNote(new MidiNoteData(48, 2, 3, 70, 9));
        MidiCcLane expression = MidiCcLane.preset(MidiCcLaneType.EXPRESSION, false);
        expression.addEvent(new MidiCcEvent(1, 33));
        vocals.getMidiClip().addCcLane(expression);

        MidiClip restored = deserializer.deserialize(new ProjectSerializer().serialize(project))
                .getTracks().getFirst().getMidiClip();

        assertThat(restored.getNotes()).containsExactly(new MidiNoteData(48, 2, 3, 70, 9));
        assertSameLane(restored.getCcLanes().getFirst(), expression, 0);
    }

    @Test
    void unreadableMidiIsSkippedAndReportedWithoutRefusingTheProject() throws Exception {
        // Unreadable MIDI never refuses the project: the open skips what it
        // cannot read, keeps the rest of the clip and every other track, and
        // reports each skipped item with exact counts.
        String xml = """
                <daw-project version="%d">
                  <metadata><name>Corrupt</name></metadata>
                  <tracks>
                    <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI">
                      <midi-clip locked="false">
                        <notes>
                          <note note-number="60" start-column="0" duration-columns="4" velocity="100" channel="0"/>
                          <note note-number="60" start-column="0" duration-columns="4" velocity="128" channel="0"/>
                          <note note-number="-1" start-column="0" duration-columns="4" velocity="100"/>
                          <note note-number="64" start-column="-4" duration-columns="4" velocity="100"/>
                          <note note-number="67" start-column="8" duration-columns="2" velocity="0"/>
                          <note note-number="x" start-column="8" duration-columns="2" velocity="10"/>
                          <note start-column="8" duration-columns="2" velocity="10"/>
                        </notes>
                        <cc-lanes>
                          <cc-lane type="AFTERTOUCH" high-resolution="false" channel="0">
                            <event column="0" value="1"/>
                            <event column="2" value="3"/>
                          </cc-lane>
                          <cc-lane type="MOD_WHEEL" high-resolution="false" channel="0" height-ratio="1.0">
                            <event column="0" value="10"/>
                            <event column="-1" value="10"/>
                            <event column="4" value="20000"/>
                            <event column="8" value="abc"/>
                            <event column="12" value="40"/>
                          </cc-lane>
                          <cc-lane type="SUSTAIN" high-resolution="true" channel="0"/>
                          <cc-lane type="ARBITRARY_CC" high-resolution="false" channel="0"/>
                          <cc-lane type="ARBITRARY_CC" cc-number="7" high-resolution="false" channel="16"/>
                          <cc-lane type="EXPRESSION" high-resolution="false" channel="4" height-ratio="-2">
                            <event column="3" value="90"/>
                          </cc-lane>
                        </cc-lanes>
                      </midi-clip>
                    </track>
                    <track id="00000000-0000-0000-0000-0000000000b2" name="Bass" type="MIDI">
                      <midi-clip locked="true">
                        <notes>
                          <note note-number="36" start-column="0" duration-columns="8" velocity="110" channel="1"/>
                        </notes>
                        <cc-lanes/>
                      </midi-clip>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION);

        DawProject project = deserializer.deserialize(xml);

        MidiClip keys = project.getTracks().get(0).getMidiClip();
        assertThat(keys.getNotes()).containsExactly(
                new MidiNoteData(60, 0, 4, 100, 0),
                new MidiNoteData(67, 8, 2, 0, 0));
        assertThat(keys.getCcLanes()).extracting(MidiCcLane::getType)
                .containsExactly(MidiCcLaneType.MOD_WHEEL, MidiCcLaneType.EXPRESSION);
        assertThat(keys.getCcLanes().get(0).getEvents())
                .containsExactly(new MidiCcEvent(0, 10), new MidiCcEvent(12, 40));
        MidiCcLane expression = keys.getCcLanes().get(1);
        assertThat(expression.getHeightRatio())
                .as("an unusable height ratio is view layout: logged, default kept, not reported")
                .isEqualTo(MidiCcLane.DEFAULT_HEIGHT_RATIO);
        assertThat(expression.getChannel()).isEqualTo(4);
        assertThat(expression.getEvents()).containsExactly(new MidiCcEvent(3, 90));

        MidiClip bass = project.getTracks().get(1).getMidiClip();
        assertThat(bass.isLocked()).isTrue();
        assertThat(bass.getNotes()).containsExactly(new MidiNoteData(36, 0, 8, 110, 1));

        List<UnreadProjectItem> unread = deserializer.getUnreadItems();
        assertThat(unread).allSatisfy(item ->
                assertThat(item.location()).isEqualTo(new UnreadProjectItem.OnTrack(0, "Keys")));
        assertThat(unread).filteredOn(item -> item.kind() == UnreadProjectItem.Kind.MIDI_NOTE_SKIPPED)
                .extracting(UnreadProjectItem::index)
                .containsExactly(1, 2, 3, 5, 6);
        assertThat(unread).filteredOn(item -> item.kind() == UnreadProjectItem.Kind.CC_LANE_SKIPPED)
                .as("each skipped lane stands for the events it was saved with, none for an empty one")
                .extracting(UnreadProjectItem::index, UnreadProjectItem::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 2),
                        org.assertj.core.groups.Tuple.tuple(2, 0),
                        org.assertj.core.groups.Tuple.tuple(3, 0),
                        org.assertj.core.groups.Tuple.tuple(4, 0));
        assertThat(unread).filteredOn(item -> item.kind() == UnreadProjectItem.Kind.CC_EVENT_SKIPPED)
                .extracting(UnreadProjectItem::index)
                .containsExactly(1, 2, 3);
        assertThat(unread).hasSize(12);
        // Each detail is the reason, the item as saved (the writer's
        // attributes in the writer's order, an absent one left out) and, for
        // a note or an event at a column >= 0, its transport beat.
        assertThat(unread.get(0).detail()).isEqualTo("velocity must be 0–127: 128; saved as <note"
                + " note-number=\"60\" start-column=\"0\" duration-columns=\"4\" velocity=\"128\" channel=\"0\"/>,"
                + " at transport beat 0");
        assertThat(unread.get(4).detail()).isEqualTo("note-number is missing; saved as <note start-column=\"8\""
                + " duration-columns=\"2\" velocity=\"10\"/>, at transport beat 2");
        assertThat(unread.get(5).detail()).isEqualTo("type 'AFTERTOUCH' is not a CC lane type; saved as"
                + " <cc-lane type=\"AFTERTOUCH\" high-resolution=\"false\" channel=\"0\"/>");
        assertThat(unread.get(6).detail()).as("a negative column has no transport beat")
                .isEqualTo("column must be >= 0: -1; saved as <event column=\"-1\" value=\"10\"/>");
        assertThat(unread.get(7).detail()).isEqualTo("value must be 0–16383: 20000; saved as"
                + " <event column=\"4\" value=\"20000\"/>, at transport beat 1");
        assertThat(unread.get(7).lane()).as("the lane a skipped event was saved in")
                .isEqualTo(new UnreadProjectItem.EventLane(1, MidiCcLaneType.MOD_WHEEL, 1));
        assertThat(unread.get(11).detail()).as("the lane's CC number and channel as saved")
                .isEqualTo("channel must be 0–15: 16; saved as <cc-lane type=\"ARBITRARY_CC\" cc-number=\"7\""
                        + " high-resolution=\"false\" channel=\"16\"/>");
    }

    @Test
    void midiClipAbsentFromAFileRestoresAnEmptyClip() throws IOException {
        String xml = """
                <daw-project version="%d">
                  <tracks><track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI"/></tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION);

        MidiClip restored = deserializer.deserialize(xml).getTracks().getFirst().getMidiClip();

        assertThat(restored.getNotes()).isEmpty();
        assertThat(restored.getCcLanes()).isEmpty();
        assertThat(restored.isLocked()).isFalse();
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    /** {@link MidiCcLane} has no {@code equals}: compares it field by field. */
    private static void assertSameLane(MidiCcLane actual, MidiCcLane expected, int index) {
        String lane = "lane " + index + " (" + expected.getType() + ")";
        assertThat(actual.getType()).as(lane + " type").isEqualTo(expected.getType());
        assertThat(actual.getCcNumber()).as(lane + " CC number").isEqualTo(expected.getCcNumber());
        assertThat(actual.isHighResolution()).as(lane + " 14-bit").isEqualTo(expected.isHighResolution());
        assertThat(actual.getChannel()).as(lane + " channel").isEqualTo(expected.getChannel());
        assertThat(actual.getHeightRatio()).as(lane + " height ratio").isEqualTo(expected.getHeightRatio());
        assertThat(actual.getEvents()).as(lane + " breakpoints").containsExactlyElementsOf(expected.getEvents());
    }
}
