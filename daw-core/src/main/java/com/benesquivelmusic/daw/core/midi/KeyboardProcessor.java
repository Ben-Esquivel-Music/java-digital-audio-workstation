package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keyboard Processor plugin that manages a virtual keyboard for
 * playing, recording, and playing back MIDI notes via a
 * {@link SoundFontRenderer} (Oracle MIDI / Java Sound).
 *
 * <p>The processor maintains:</p>
 * <ul>
 *   <li>A {@link KeyboardPreset} that controls the instrument sound,
 *       octave range, transposition, velocity, and velocity curve.</li>
 *   <li>A set of currently-pressed note numbers for live playing.</li>
 *   <li>A {@link MidiClip} for recording and playback of MIDI sequences.</li>
 *   <li>A {@link SoundFontRenderer} for real-time MIDI-to-audio synthesis.</li>
 * </ul>
 *
 * <h2>Recording</h2>
 * <p>When recording is active, incoming {@code noteOn}/{@code noteOff} calls
 * are captured as {@link MidiNoteData} entries in the clip. Timestamps are
 * converted to grid columns using the project tempo.</p>
 *
 * <h2>Playback</h2>
 * <p>When playback is active, the processor sends recorded MIDI events to
 * the renderer at the appropriate times based on the tempo.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>The processor is designed to be operated from the JavaFX application
 * thread (UI events) with rendering occurring on the audio thread.
 * Active note tracking uses a volatile snapshot pattern.</p>
 */
public final class KeyboardProcessor {

    private static final Logger LOG = Logger.getLogger(KeyboardProcessor.class.getName());

    /** Default MIDI channel used by the keyboard. */
    public static final int DEFAULT_CHANNEL = 0;

    /** Number of notes per octave. */
    public static final int NOTES_PER_OCTAVE = 12;

    /** Beats represented by each grid column (sixteenth note). */
    public static final double BEATS_PER_COLUMN = 0.25;

    /**
     * Listener for keyboard state changes (note on/off events).
     */
    @FunctionalInterface
    public interface KeyboardEventListener {
        /**
         * Called when a keyboard event occurs.
         *
         * @param event the MIDI event
         */
        void onKeyboardEvent(MidiEvent event);
    }

    private volatile KeyboardPreset preset;
    private final SoundFontRenderer renderer;
    private final int channel;
    private final MidiClip clip;
    private final List<KeyboardEventListener> listeners = new CopyOnWriteArrayList<>();

    // Active note tracking
    private final boolean[] activeNotes = new boolean[128];
    private volatile int activeNoteCount;

    // Recording state
    private volatile boolean recording;
    private long recordingStartTimeMs = -1;
    private double tempo = 120.0;
    private int startColumnOffset;
    private final int[] noteOnColumns = new int[128];
    private final int[] noteOnVelocities = new int[128];
    private final List<MidiNoteData> recordedNotes = new ArrayList<>();

    // Playback state
    private volatile boolean playing;
    private long playbackStartTimeMs = -1;
    private int playbackPositionColumn;

    /**
     * Creates a new keyboard processor.
     *
     * @param renderer the SoundFont renderer for MIDI synthesis
     * @param preset   the initial keyboard preset
     */
    public KeyboardProcessor(SoundFontRenderer renderer, KeyboardPreset preset) {
        this(renderer, preset, DEFAULT_CHANNEL);
    }

    /**
     * Creates a new keyboard processor on a specific MIDI channel.
     *
     * @param renderer the SoundFont renderer for MIDI synthesis
     * @param preset   the initial keyboard preset
     * @param channel  the MIDI channel (0–15)
     */
    public KeyboardProcessor(SoundFontRenderer renderer, KeyboardPreset preset, int channel) {
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.preset = Objects.requireNonNull(preset, "preset must not be null");
        if (channel < 0 || channel > 15) {
            throw new IllegalArgumentException("channel must be 0–15: " + channel);
        }
        this.channel = channel;
        this.clip = new MidiClip();
        for (int i = 0; i < 128; i++) {
            noteOnColumns[i] = -1;
        }
    }

    // ── Preset Management ──────────────────────────────────────────────

    /**
     * Returns the current keyboard preset.
     *
     * @return the active preset
     */
    public KeyboardPreset getPreset() {
        return preset;
    }

    /**
     * Sets the keyboard preset and sends the corresponding program change
     * to the renderer.
     *
     * @param newPreset the new preset to apply
     */
    public void setPreset(KeyboardPreset newPreset) {
        Objects.requireNonNull(newPreset, "preset must not be null");
        allNotesOff();
        this.preset = newPreset;
        applyPresetToRenderer();
    }

    /**
     * Applies the current preset's bank and program to the renderer.
     */
    private void applyPresetToRenderer() {
        KeyboardPreset p = preset;
        try {
            renderer.selectPreset(channel, p.bank(), p.program());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to apply preset bank/program", e);
        }
    }

    // ── Note Playing ───────────────────────────────────────────────────

    /**
     * Triggers a note-on event for the given MIDI note number.
     *
     * <p>The velocity is transformed through the preset's velocity curve.
     * If the note is already active, this call is ignored.</p>
     *
     * @param noteNumber the MIDI note number (0–127)
     * @param velocity   the raw velocity (1–127)
     */
    public void noteOn(int noteNumber, int velocity) {
        validateNoteNumber(noteNumber);
        if (velocity < 1 || velocity > 127) {
            throw new IllegalArgumentException("velocity must be 1–127: " + velocity);
        }

        KeyboardPreset p = preset;
        int transposed = noteNumber + p.transpose();
        if (transposed < 0 || transposed > 127) {
            return; // Out of MIDI range after transposition
        }

        int mappedVelocity = p.velocityCurve().apply(velocity);
        if (mappedVelocity < 1) {
            mappedVelocity = 1;
        }

        synchronized (activeNotes) {
            if (activeNotes[transposed]) {
                return; // Already sounding
            }
            activeNotes[transposed] = true;
            activeNoteCount++;
        }

        MidiEvent event = MidiEvent.noteOn(channel, transposed, mappedVelocity);
        try {
            renderer.sendEvent(event);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send note-on to renderer", e);
        }

        notifyListeners(event);

        // Recording
        if (recording) {
            long now = System.currentTimeMillis();
            if (recordingStartTimeMs < 0) {
                recordingStartTimeMs = now;
            }
            int column = timestampToColumn(now - recordingStartTimeMs) + startColumnOffset;
            noteOnColumns[transposed] = column;
            noteOnVelocities[transposed] = mappedVelocity;
        }
    }

    /**
     * Triggers a note-off event for the given MIDI note number.
     *
     * @param noteNumber the MIDI note number (0–127)
     */
    public void noteOff(int noteNumber) {
        validateNoteNumber(noteNumber);

        KeyboardPreset p = preset;
        int transposed = noteNumber + p.transpose();
        if (transposed < 0 || transposed > 127) {
            return;
        }

        synchronized (activeNotes) {
            if (!activeNotes[transposed]) {
                return; // Not active
            }
            activeNotes[transposed] = false;
            activeNoteCount--;
        }

        MidiEvent event = MidiEvent.noteOff(channel, transposed);
        try {
            renderer.sendEvent(event);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send note-off to renderer", e);
        }

        notifyListeners(event);

        // Recording
        if (recording && noteOnColumns[transposed] >= 0) {
            long now = System.currentTimeMillis();
            int column = timestampToColumn(now - recordingStartTimeMs) + startColumnOffset;
            int startCol = noteOnColumns[transposed];
            int duration = Math.max(1, column - startCol);
            MidiNoteData note = new MidiNoteData(transposed, startCol, duration,
                    noteOnVelocities[transposed], channel);
            clip.addNote(note);
            recordedNotes.add(note);
            noteOnColumns[transposed] = -1;
        }
    }

    /**
     * Sends all-notes-off on the keyboard's channel, clearing all active notes.
     */
    public void allNotesOff() {
        synchronized (activeNotes) {
            for (int i = 0; i < 128; i++) {
                if (activeNotes[i]) {
                    activeNotes[i] = false;
                    try {
                        renderer.sendEvent(MidiEvent.noteOff(channel, i));
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Failed to send all-notes-off", e);
                    }
                }
            }
            activeNoteCount = 0;
        }
    }

    /**
     * Returns whether the given MIDI note is currently active (sounding).
     *
     * @param noteNumber the MIDI note number (0–127)
     * @return {@code true} if the note is active
     */
    public boolean isNoteActive(int noteNumber) {
        validateNoteNumber(noteNumber);
        synchronized (activeNotes) {
            return activeNotes[noteNumber];
        }
    }

    /**
     * Returns the count of currently active (sounding) notes.
     *
     * @return the active note count
     */
    public int getActiveNoteCount() {
        return activeNoteCount;
    }

    // ── Recording ──────────────────────────────────────────────────────

    /**
     * Starts recording MIDI events into the clip.
     *
     * @param tempo          the project tempo in BPM
     * @param columnOffset   the column offset for recorded note start positions
     */
    public void startRecording(double tempo, int columnOffset) {
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (columnOffset < 0) {
            throw new IllegalArgumentException("columnOffset must be >= 0: " + columnOffset);
        }
        this.tempo = tempo;
        this.startColumnOffset = columnOffset;
        this.recordingStartTimeMs = -1;
        this.recordedNotes.clear();
        for (int i = 0; i < 128; i++) {
            noteOnColumns[i] = -1;
        }
        this.recording = true;
    }

    /**
     * Stops recording and finalizes any held notes.
     */
    public void stopRecording() {
        if (!recording) {
            return;
        }
        recording = false;
        finalizeHeldRecordingNotes();
    }

    /**
     * Returns whether recording is currently active.
     *
     * @return {@code true} if recording
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Returns an unmodifiable view of notes recorded during the current
     * or most recent recording session.
     *
     * @return the recorded notes
     */
    public List<MidiNoteData> getRecordedNotes() {
        return Collections.unmodifiableList(new ArrayList<>(recordedNotes));
    }

    private void finalizeHeldRecordingNotes() {
        for (int noteNumber = 0; noteNumber < 128; noteNumber++) {
            if (noteOnColumns[noteNumber] >= 0) {
                int startCol = noteOnColumns[noteNumber];
                int duration = 1;
                MidiNoteData note = new MidiNoteData(noteNumber, startCol, duration,
                        noteOnVelocities[noteNumber], channel);
                clip.addNote(note);
                recordedNotes.add(note);
                noteOnColumns[noteNumber] = -1;
            }
        }
    }

    // ── Playback ───────────────────────────────────────────────────────

    /**
     * Starts playback of the recorded MIDI clip.
     *
     * @param tempo the project tempo in BPM
     */
    public void startPlayback(double tempo) {
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (clip.isEmpty()) {
            return;
        }
        this.tempo = tempo;
        this.playbackStartTimeMs = System.currentTimeMillis();
        this.playbackPositionColumn = -1;
        this.playing = true;
    }

    /**
     * Stops playback and silences all notes.
     */
    public void stopPlayback() {
        if (!playing) {
            return;
        }
        playing = false;
        playbackStartTimeMs = -1;
        allNotesOff();
    }

    /**
     * Returns whether playback is currently active.
     *
     * @return {@code true} if playing back
     */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * Advances the playback position and sends any due MIDI events.
     *
     * <p>Call this method periodically (e.g., from a timeline or animation
     * timer) to drive playback. It computes the current column from the
     * elapsed time and sends note-on/off events for notes starting or
     * ending at the current position.</p>
     */
    public void advancePlayback() {
        if (!playing || playbackStartTimeMs < 0) {
            return;
        }

        long elapsed = System.currentTimeMillis() - playbackStartTimeMs;
        int currentColumn = timestampToColumn(elapsed);

        if (currentColumn <= playbackPositionColumn) {
            return;
        }

        List<MidiNoteData> notes = clip.getNotes();
        int maxEndColumn = 0;
        for (MidiNoteData note : notes) {
            if (note.endColumn() > maxEndColumn) {
                maxEndColumn = note.endColumn();
            }
        }

        for (int col = playbackPositionColumn + 1; col <= currentColumn; col++) {
            for (MidiNoteData note : notes) {
                if (note.startColumn() == col) {
                    MidiEvent event = MidiEvent.noteOn(note.channel(), note.noteNumber(),
                            note.velocity());
                    try {
                        renderer.sendEvent(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Playback note-on failed", e);
                    }
                    notifyListeners(event);
                }
                if (note.endColumn() == col) {
                    MidiEvent event = MidiEvent.noteOff(note.channel(), note.noteNumber());
                    try {
                        renderer.sendEvent(event);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Playback note-off failed", e);
                    }
                    notifyListeners(event);
                }
            }
        }

        playbackPositionColumn = currentColumn;

        // Stop at end of clip
        if (currentColumn >= maxEndColumn) {
            stopPlayback();
        }
    }

    // ── Clip Access ────────────────────────────────────────────────────

    /**
     * Returns the MIDI clip used by this processor.
     *
     * @return the clip
     */
    public MidiClip getClip() {
        return clip;
    }

    /**
     * Clears all recorded notes from the clip.
     */
    public void clearClip() {
        clip.clear();
        recordedNotes.clear();
    }

    // ── Listeners ──────────────────────────────────────────────────────

    /**
     * Adds a keyboard event listener.
     *
     * @param listener the listener
     */
    public void addListener(KeyboardEventListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a keyboard event listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(KeyboardEventListener listener) {
        listeners.remove(listener);
    }

    // ── Utility ────────────────────────────────────────────────────────

    /**
     * Returns the MIDI channel used by this processor.
     *
     * @return the MIDI channel (0–15)
     */
    public int getChannel() {
        return channel;
    }

    /**
     * Computes the MIDI note number for a given octave and note-within-octave index.
     *
     * <p>The note index is 0-based within the octave: C=0, C#=1, D=2, ... B=11.
     * The MIDI note number formula is {@code (octave + 1) * 12 + noteIndex}.</p>
     *
     * @param octave    the octave (−1 to 9)
     * @param noteIndex the note within the octave (0–11)
     * @return the MIDI note number (0–127)
     */
    public static int midiNoteNumber(int octave, int noteIndex) {
        if (noteIndex < 0 || noteIndex >= NOTES_PER_OCTAVE) {
            throw new IllegalArgumentException("noteIndex must be 0–11: " + noteIndex);
        }
        int note = (octave + 1) * NOTES_PER_OCTAVE + noteIndex;
        if (note < 0 || note > 127) {
            throw new IllegalArgumentException(
                    "Resulting MIDI note %d (octave=%d, index=%d) is out of range 0–127"
                            .formatted(note, octave, noteIndex));
        }
        return note;
    }

    /**
     * Returns the note name (e.g., "C4", "F#3") for a MIDI note number.
     *
     * @param noteNumber the MIDI note number (0–127)
     * @return the note name
     */
    public static String noteName(int noteNumber) {
        validateNoteNumber(noteNumber);
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int octave = (noteNumber / NOTES_PER_OCTAVE) - 1;
        int noteIndex = noteNumber % NOTES_PER_OCTAVE;
        return names[noteIndex] + octave;
    }

    /**
     * Returns whether a note index within an octave is a black key (sharp/flat).
     *
     * @param noteIndex the note index within an octave (0–11)
     * @return {@code true} if the note is a black key
     */
    public static boolean isBlackKey(int noteIndex) {
        return switch (noteIndex % NOTES_PER_OCTAVE) {
            case 1, 3, 6, 8, 10 -> true;
            default -> false;
        };
    }

    /**
     * Converts a millisecond timestamp (relative to recording/playback start)
     * to a grid column index based on the project tempo.
     */
    int timestampToColumn(long elapsedMs) {
        if (elapsedMs <= 0) {
            return 0;
        }
        double seconds = elapsedMs / 1_000.0;
        double beats = seconds * (tempo / 60.0);
        double columns = beats / BEATS_PER_COLUMN;
        return Math.max(0, (int) Math.round(columns));
    }

    private void notifyListeners(MidiEvent event) {
        for (KeyboardEventListener listener : listeners) {
            try {
                listener.onKeyboardEvent(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Keyboard event listener threw exception", e);
            }
        }
    }

    private static void validateNoteNumber(int noteNumber) {
        if (noteNumber < 0 || noteNumber > 127) {
            throw new IllegalArgumentException("noteNumber must be 0–127: " + noteNumber);
        }
    }
}
