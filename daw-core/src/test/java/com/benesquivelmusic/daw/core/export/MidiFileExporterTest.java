package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiFileExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteValidMidiFile() throws Exception {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 0),
                new MidiFileExporter.NoteDescriptor(64, 4, 4, 90, 0)
        );
        Path outputPath = tempDir.resolve("test.mid");

        MidiFileExporter.write(notes, 120.0, outputPath);

        assertThat(outputPath).exists();

        // Read back and verify
        Sequence sequence = MidiSystem.getSequence(outputPath.toFile());
        assertThat(sequence.getResolution()).isEqualTo(MidiFileExporter.getTicksPerQuarter());
        assertThat(sequence.getTracks()).hasSize(1);
    }

    @Test
    void shouldWriteCorrectNumberOfNoteEvents() throws Exception {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 0),
                new MidiFileExporter.NoteDescriptor(64, 4, 4, 90, 0),
                new MidiFileExporter.NoteDescriptor(67, 8, 4, 80, 0)
        );
        Path outputPath = tempDir.resolve("three_notes.mid");

        MidiFileExporter.write(notes, 120.0, outputPath);

        Sequence sequence = MidiSystem.getSequence(outputPath.toFile());
        Track track = sequence.getTracks()[0];

        // Count note-on and note-off events
        int noteOnCount = 0;
        int noteOffCount = 0;
        for (int i = 0; i < track.size(); i++) {
            javax.sound.midi.MidiEvent event = track.get(i);
            if (event.getMessage() instanceof ShortMessage shortMsg) {
                if (shortMsg.getCommand() == ShortMessage.NOTE_ON) {
                    noteOnCount++;
                } else if (shortMsg.getCommand() == ShortMessage.NOTE_OFF) {
                    noteOffCount++;
                }
            }
        }

        assertThat(noteOnCount).isEqualTo(3);
        assertThat(noteOffCount).isEqualTo(3);
    }

    @Test
    void shouldPlaceNotesAtCorrectTicks() throws Exception {
        int ticksPerColumn = MidiFileExporter.getTicksPerQuarter() / 4; // 120 ticks
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 2, 100, 0),
                new MidiFileExporter.NoteDescriptor(64, 4, 2, 90, 0)
        );
        Path outputPath = tempDir.resolve("ticks.mid");

        MidiFileExporter.write(notes, 120.0, outputPath);

        Sequence sequence = MidiSystem.getSequence(outputPath.toFile());
        Track track = sequence.getTracks()[0];

        // Find note-on events and verify tick positions
        boolean foundFirstNoteOn = false;
        boolean foundSecondNoteOn = false;
        for (int i = 0; i < track.size(); i++) {
            javax.sound.midi.MidiEvent event = track.get(i);
            if (event.getMessage() instanceof ShortMessage shortMsg) {
                if (shortMsg.getCommand() == ShortMessage.NOTE_ON) {
                    if (shortMsg.getData1() == 60) {
                        assertThat(event.getTick()).isEqualTo(0);
                        foundFirstNoteOn = true;
                    } else if (shortMsg.getData1() == 64) {
                        assertThat(event.getTick()).isEqualTo(4L * ticksPerColumn);
                        foundSecondNoteOn = true;
                    }
                }
            }
        }

        assertThat(foundFirstNoteOn).isTrue();
        assertThat(foundSecondNoteOn).isTrue();
    }

    @Test
    void shouldPreserveNoteVelocity() throws Exception {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 0),
                new MidiFileExporter.NoteDescriptor(64, 4, 4, 50, 0)
        );
        Path outputPath = tempDir.resolve("velocity.mid");

        MidiFileExporter.write(notes, 120.0, outputPath);

        Sequence sequence = MidiSystem.getSequence(outputPath.toFile());
        Track track = sequence.getTracks()[0];

        boolean foundNote60 = false;
        boolean foundNote64 = false;
        for (int i = 0; i < track.size(); i++) {
            javax.sound.midi.MidiEvent event = track.get(i);
            if (event.getMessage() instanceof ShortMessage shortMsg) {
                if (shortMsg.getCommand() == ShortMessage.NOTE_ON) {
                    if (shortMsg.getData1() == 60) {
                        assertThat(shortMsg.getData2()).isEqualTo(100);
                        foundNote60 = true;
                    } else if (shortMsg.getData1() == 64) {
                        assertThat(shortMsg.getData2()).isEqualTo(50);
                        foundNote64 = true;
                    }
                }
            }
        }

        assertThat(foundNote60).isTrue();
        assertThat(foundNote64).isTrue();
    }

    @Test
    void shouldIncludeTempoMetaEvent() throws Exception {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 0)
        );
        Path outputPath = tempDir.resolve("tempo.mid");

        MidiFileExporter.write(notes, 120.0, outputPath);

        Sequence sequence = MidiSystem.getSequence(outputPath.toFile());
        Track track = sequence.getTracks()[0];

        // Look for tempo meta-event (type 0x51)
        boolean foundTempo = false;
        for (int i = 0; i < track.size(); i++) {
            javax.sound.midi.MidiEvent event = track.get(i);
            if (event.getMessage() instanceof javax.sound.midi.MetaMessage meta) {
                if (meta.getType() == 0x51) {
                    foundTempo = true;
                    byte[] tempoData = meta.getData();
                    int microsPerBeat = ((tempoData[0] & 0xFF) << 16)
                            | ((tempoData[1] & 0xFF) << 8)
                            | (tempoData[2] & 0xFF);
                    // 120 BPM = 500000 microseconds per beat
                    assertThat(microsPerBeat).isEqualTo(500000);
                }
            }
        }

        assertThat(foundTempo).isTrue();
    }

    @Test
    void shouldWriteToSpecifiedChannel() throws Exception {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 9)
        );
        Path outputPath = tempDir.resolve("channel9.mid");

        MidiFileExporter.write(notes, 120.0, outputPath);

        Sequence sequence = MidiSystem.getSequence(outputPath.toFile());
        Track track = sequence.getTracks()[0];

        boolean foundChannelNote = false;
        for (int i = 0; i < track.size(); i++) {
            javax.sound.midi.MidiEvent event = track.get(i);
            if (event.getMessage() instanceof ShortMessage shortMsg) {
                if (shortMsg.getCommand() == ShortMessage.NOTE_ON) {
                    assertThat(shortMsg.getChannel()).isEqualTo(9);
                    foundChannelNote = true;
                }
            }
        }

        assertThat(foundChannelNote).isTrue();
    }

    @Test
    void shouldRejectNullNotes() {
        assertThatThrownBy(() -> MidiFileExporter.write(null, 120.0, tempDir.resolve("null.mid")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("notes");
    }

    @Test
    void shouldRejectEmptyNotes() {
        assertThatThrownBy(() -> MidiFileExporter.write(List.of(), 120.0, tempDir.resolve("empty.mid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notes");
    }

    @Test
    void shouldRejectNullOutputPath() {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 0)
        );

        assertThatThrownBy(() -> MidiFileExporter.write(notes, 120.0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outputPath");
    }

    @Test
    void shouldRejectNonPositiveTempo() {
        List<MidiFileExporter.NoteDescriptor> notes = List.of(
                new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 0)
        );

        assertThatThrownBy(() -> MidiFileExporter.write(notes, 0, tempDir.resolve("zero.mid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo");

        assertThatThrownBy(() -> MidiFileExporter.write(notes, -120, tempDir.resolve("neg.mid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo");
    }

    @Test
    void shouldRejectInvalidNoteDescriptor() {
        assertThatThrownBy(() -> new MidiFileExporter.NoteDescriptor(-1, 0, 4, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("midiNoteNumber");

        assertThatThrownBy(() -> new MidiFileExporter.NoteDescriptor(128, 0, 4, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("midiNoteNumber");

        assertThatThrownBy(() -> new MidiFileExporter.NoteDescriptor(60, -1, 4, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startColumn");

        assertThatThrownBy(() -> new MidiFileExporter.NoteDescriptor(60, 0, 0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationColumns");

        assertThatThrownBy(() -> new MidiFileExporter.NoteDescriptor(60, 0, 4, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocity");

        assertThatThrownBy(() -> new MidiFileExporter.NoteDescriptor(60, 0, 4, 100, 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }
}
