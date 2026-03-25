package com.benesquivelmusic.daw.core.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Exports MIDI note data to a Standard MIDI File (.mid).
 *
 * <p>Writes a Type 0 (single-track) MIDI file using {@link MidiSystem}.
 * Notes are placed on the timeline using the specified tempo and
 * grid resolution to convert grid-column positions to MIDI ticks.</p>
 */
public final class MidiFileExporter {

    /** MIDI ticks per quarter note (PPQ). */
    private static final int TICKS_PER_QUARTER = 480;

    private MidiFileExporter() {
        // utility class
    }

    /**
     * Describes a single MIDI note to be exported.
     *
     * @param midiNoteNumber  the MIDI note number (0–127)
     * @param startColumn     the grid column where the note starts (≥ 0)
     * @param durationColumns the length of the note in grid columns (≥ 1)
     * @param velocity        the MIDI velocity (1–127)
     * @param channel         the MIDI channel (0–15)
     */
    public record NoteDescriptor(
            int midiNoteNumber,
            int startColumn,
            int durationColumns,
            int velocity,
            int channel
    ) {
        public NoteDescriptor {
            if (midiNoteNumber < 0 || midiNoteNumber > 127) {
                throw new IllegalArgumentException(
                        "midiNoteNumber must be 0–127: " + midiNoteNumber);
            }
            if (startColumn < 0) {
                throw new IllegalArgumentException(
                        "startColumn must be >= 0: " + startColumn);
            }
            if (durationColumns < 1) {
                throw new IllegalArgumentException(
                        "durationColumns must be >= 1: " + durationColumns);
            }
            if (velocity < 0 || velocity > 127) {
                throw new IllegalArgumentException(
                        "velocity must be 0–127: " + velocity);
            }
            if (channel < 0 || channel > 15) {
                throw new IllegalArgumentException(
                        "channel must be 0–15: " + channel);
            }
        }
    }

    /**
     * Writes a list of MIDI notes to a Standard MIDI File.
     *
     * <p>Each grid column corresponds to one sixteenth note. The tempo
     * is embedded as a MIDI tempo meta-event at the start of the file.</p>
     *
     * @param notes          the notes to export
     * @param tempo          the tempo in BPM
     * @param outputPath     the output file path
     * @throws IOException if an I/O error occurs while writing
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if tempo is not positive or notes is empty
     */
    public static void write(List<NoteDescriptor> notes, double tempo,
                             Path outputPath) throws IOException {
        Objects.requireNonNull(notes, "notes must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("notes must not be empty");
        }

        try {
            Sequence sequence = new Sequence(Sequence.PPQ, TICKS_PER_QUARTER);
            Track track = sequence.createTrack();

            // Add tempo meta-event at tick 0
            addTempoEvent(track, tempo);

            // Each grid column = 1 sixteenth note = TICKS_PER_QUARTER / 4 ticks
            int ticksPerColumn = TICKS_PER_QUARTER / 4;

            for (NoteDescriptor note : notes) {
                long startTick = (long) note.startColumn() * ticksPerColumn;
                long endTick = startTick + (long) note.durationColumns() * ticksPerColumn;

                ShortMessage noteOn = new ShortMessage(
                        ShortMessage.NOTE_ON, note.channel(),
                        note.midiNoteNumber(), note.velocity());
                track.add(new MidiEvent(noteOn, startTick));

                ShortMessage noteOff = new ShortMessage(
                        ShortMessage.NOTE_OFF, note.channel(),
                        note.midiNoteNumber(), 0);
                track.add(new MidiEvent(noteOff, endTick));
            }

            MidiSystem.write(sequence, 0, outputPath.toFile());

        } catch (InvalidMidiDataException e) {
            throw new IOException("Failed to create MIDI data: " + e.getMessage(), e);
        }
    }

    /**
     * Adds a MIDI tempo meta-event to the given track at tick 0.
     *
     * @param track the MIDI track
     * @param tempo the tempo in BPM
     */
    private static void addTempoEvent(Track track, double tempo)
            throws InvalidMidiDataException {
        // Tempo in microseconds per quarter note
        int microsecondsPerBeat = (int) Math.round(60_000_000.0 / tempo);

        // MIDI meta-event 0x51 (set tempo) with 3 data bytes
        byte[] tempoData = new byte[]{
                (byte) ((microsecondsPerBeat >> 16) & 0xFF),
                (byte) ((microsecondsPerBeat >> 8) & 0xFF),
                (byte) (microsecondsPerBeat & 0xFF)
        };

        javax.sound.midi.MetaMessage tempoMessage = new javax.sound.midi.MetaMessage(
                0x51, tempoData, tempoData.length);
        track.add(new MidiEvent(tempoMessage, 0));
    }

    /**
     * Returns the PPQ (ticks per quarter note) resolution used for export.
     *
     * @return the PPQ resolution
     */
    public static int getTicksPerQuarter() {
        return TICKS_PER_QUARTER;
    }
}
