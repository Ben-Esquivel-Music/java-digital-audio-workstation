package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.sdk.midi.MidiEvent;

import javax.sound.midi.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records MIDI input from a {@link javax.sound.midi.MidiDevice} and converts
 * incoming messages to {@link MidiNoteData} entries in a {@link MidiClip}.
 *
 * <p>The recorder listens to a MIDI input device via its {@link Transmitter}
 * and captures note-on/note-off pairs. During recording, listeners are
 * notified of each incoming note for real-time display (e.g., on the piano
 * roll).</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MidiRecorder recorder = new MidiRecorder(device, clip, tempo, channel);
 * recorder.addListener(note -> updatePianoRoll(note));
 * recorder.startRecording();
 * // ... user plays MIDI controller ...
 * recorder.stopRecording();
 * }</pre>
 */
public final class MidiRecorder {

    private static final Logger LOG = Logger.getLogger(MidiRecorder.class.getName());
    private static final ReentrantLock INPUT_LOCK = new ReentrantLock();
    private static final Map<MidiDevice, SharedInput> INPUTS = new IdentityHashMap<>();

    /**
     * Listener for real-time notification of recorded MIDI notes.
     */
    @FunctionalInterface
    public interface RecordingNoteListener {
        /**
         * Called when a complete note (note-on + note-off pair) has been
         * recorded.
         *
         * @param note the recorded note
         */
        void onNoteRecorded(MidiNoteData note);
    }

    /**
     * Listener for real-time notification of incoming MIDI events.
     */
    @FunctionalInterface
    public interface RecordingEventListener {
        /**
         * Called when a MIDI event is received from the input device.
         *
         * @param event the MIDI event
         */
        void onEventReceived(MidiEvent event);
    }

    /**
     * The number of beats each grid column represents (sixteenth note = 0.25).
     */
    public static final double BEATS_PER_COLUMN = 0.25;

    private final MidiDevice device;
    private final MidiClip clip;
    private final double tempo;
    private final int channel;
    private final List<RecordingNoteListener> noteListeners = new CopyOnWriteArrayList<>();
    private final List<RecordingEventListener> eventListeners = new CopyOnWriteArrayList<>();

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private Transmitter transmitter;
    private SharedInput input;
    private boolean recording;
    private long recordingStartTimeUs;
    private final LongSupplier monotonicNanos;
    private long monotonicOriginNanos;
    private long deviceOriginRelativeUs;
    private long lastRelativeUs;
    private boolean usedFallbackTime;

    // Active note tracking: index = MIDI note number, value = start column (-1 = inactive)
    private final int[] activeNoteStarts = new int[128];
    private final int[] activeNoteVelocities = new int[128];
    private int startColumnOffset;
    private long countInDurationUs;

    /** Notes recorded during the current session only (not pre-existing clip notes). */
    private final List<MidiNoteData> sessionNotes = new ArrayList<>();

    /**
     * Creates a new MIDI recorder.
     *
     * @param device  the MIDI input device to record from
     * @param clip    the clip to record notes into
     * @param tempo   the project tempo in BPM (used to convert timestamps to grid columns)
     * @param channel the MIDI channel to record (0–15)
     */
    public MidiRecorder(MidiDevice device, MidiClip clip, double tempo, int channel) {
        this(device, clip, tempo, channel, System::nanoTime);
    }

    MidiRecorder(MidiDevice device, MidiClip clip, double tempo, int channel,
                 LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        if (tempo <= 0) {
            throw new IllegalArgumentException("tempo must be positive: " + tempo);
        }
        if (channel < 0 || channel > 15) {
            throw new IllegalArgumentException("channel must be 0–15: " + channel);
        }
        this.tempo = tempo;
        this.channel = channel;
        for (int i = 0; i < 128; i++) {
            activeNoteStarts[i] = -1;
            activeNoteVelocities[i] = 0;
        }
    }

    /**
     * Adds a listener for completed note events.
     *
     * @param listener the listener
     */
    public void addListener(RecordingNoteListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        noteListeners.add(listener);
    }

    /**
     * Removes a note listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(RecordingNoteListener listener) {
        noteListeners.remove(listener);
    }

    /**
     * Adds a listener for incoming MIDI events.
     *
     * @param listener the listener
     */
    public void addEventListener(RecordingEventListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        eventListeners.add(listener);
    }

    /**
     * Removes an event listener.
     *
     * @param listener the listener to remove
     */
    public void removeEventListener(RecordingEventListener listener) {
        eventListeners.remove(listener);
    }

    /**
     * Sets the column offset applied to all recorded note start positions.
     *
     * <p>Use this to position recorded notes relative to the transport's
     * current beat when recording starts. For example, if recording starts
     * at beat 4.0 and each column is 0.25 beats, the offset is 16.</p>
     *
     * @param offset the column offset (must be &ge; 0)
     */
    public void setStartColumnOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("startColumnOffset must be >= 0: " + offset);
        }
        this.startColumnOffset = offset;
    }

    /**
     * Returns the column offset applied to recorded note start positions.
     *
     * @return the column offset
     */
    public int getStartColumnOffset() {
        return startColumnOffset;
    }

    /**
     * Sets the count-in duration during which incoming MIDI events are
     * discarded. Notes played during the count-in pre-roll are ignored so
     * they do not appear in the recorded clip.
     *
     * <p>The duration is specified in microseconds and measured relative to
     * the first MIDI message received after {@link #startRecording()} is
     * called. A value of {@code 0} disables count-in filtering.</p>
     *
     * @param durationUs the count-in duration in microseconds (must be &ge; 0)
     */
    public void setCountInDurationUs(long durationUs) {
        if (durationUs < 0) {
            throw new IllegalArgumentException("countInDurationUs must be >= 0: " + durationUs);
        }
        this.countInDurationUs = durationUs;
    }

    /**
     * Returns the count-in duration in microseconds.
     *
     * @return the count-in duration
     */
    public long getCountInDurationUs() {
        return countInDurationUs;
    }

    /**
     * Starts recording MIDI input from the device.
     *
     * @throws MidiUnavailableException if the device cannot be opened or no
     *                                  transmitter is available
     * @throws IllegalStateException    if already recording
     */
    public void startRecording() throws MidiUnavailableException {
        lifecycleLock.lock();
        try {
            synchronized (this) {
                if (recording) throw new IllegalStateException("Already recording");
                monotonicOriginNanos = monotonicNanos.getAsLong();
                recordingStartTimeUs = -1;
                lastRelativeUs = 0;
                deviceOriginRelativeUs = 0;
                usedFallbackTime = false;
                sessionNotes.clear();
                for (int i = 0; i < 128; i++) {
                    activeNoteStarts[i] = -1;
                    activeNoteVelocities[i] = 0;
                }
            }
            try {
                input = acquireInput(device);
                transmitter = device.getTransmitter();
                synchronized (this) { recording = true; }
                transmitter.setReceiver(new MidiInputReceiver());
            } catch (MidiUnavailableException | RuntimeException failure) {
                synchronized (this) { recording = false; }
                closeInput(failure);
                throw failure;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void closeInput(Throwable primary) {
        RuntimeException closeFailure = null;
        try {
            if (transmitter != null) transmitter.close();
        } catch (RuntimeException failure) {
            closeFailure = failure;
        } finally {
            transmitter = null;
            try {
                if (input != null) {
                    SharedInput acquired = input;
                    input = null;
                    releaseInput(acquired);
                }
            } catch (RuntimeException failure) {
                if (closeFailure == null) closeFailure = failure;
                else closeFailure.addSuppressed(failure);
            }
        }
        if (closeFailure != null) {
            if (primary != null) primary.addSuppressed(closeFailure);
            else throw closeFailure;
        }
    }

    private static SharedInput acquireInput(MidiDevice device) throws MidiUnavailableException {
        INPUT_LOCK.lock();
        try {
            SharedInput shared = INPUTS.get(device);
            if (shared != null) {
                shared.users++;
                return shared;
            }
            boolean owned = !device.isOpen();
            if (owned) {
                try {
                    device.open();
                } catch (MidiUnavailableException | RuntimeException failure) {
                    try { device.close(); }
                    catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                    throw failure;
                }
            }
            shared = new SharedInput(device, owned);
            INPUTS.put(device, shared);
            return shared;
        } finally {
            INPUT_LOCK.unlock();
        }
    }

    private static void releaseInput(SharedInput shared) {
        INPUT_LOCK.lock();
        try {
            if (--shared.users == 0) {
                INPUTS.remove(shared.device);
                if (shared.owned) shared.device.close();
            }
        } finally {
            INPUT_LOCK.unlock();
        }
    }

    /** Recorder-owned openings live until the last recorder leaves; external openings remain borrowed. */
    private static final class SharedInput {
        private final MidiDevice device;
        private final boolean owned;
        private int users = 1;

        private SharedInput(MidiDevice device, boolean owned) {
            this.device = device;
            this.owned = owned;
        }
    }

    /**
     * Stops recording and closes the transmitter.
     *
     * <p>Any notes that were still held (note-on without note-off) are
     * finalized at the stop time.</p>
     */
    public void stopRecording() {
        lifecycleLock.lock();
        try {
            synchronized (this) {
                if (!recording) return;
                recording = false;
            }
            RuntimeException failure = null;
            try {
                synchronized (this) { finalizeHeldNotes(); }
            } catch (RuntimeException finishingFailure) {
                failure = finishingFailure;
                throw finishingFailure;
            } finally {
                // A provider may wait for its receiver during close; the receiver monitor is free.
                closeInput(failure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns whether recording is currently active.
     *
     * @return {@code true} if recording
     */
    public synchronized boolean isRecording() {
        return recording;
    }

    /**
     * Returns the MIDI clip being recorded into.
     *
     * @return the clip
     */
    public MidiClip getClip() {
        return clip;
    }

    /**
     * Returns an unmodifiable view of the notes recorded during the
     * current (or most recent) session only — not pre-existing clip notes.
     *
     * @return the session-recorded note list
     */
    public synchronized List<MidiNoteData> getRecordedNotes() {
        return Collections.unmodifiableList(new ArrayList<>(sessionNotes));
    }

    /**
     * Converts a microsecond timestamp (relative to recording start) to a
     * grid column index based on the project tempo.
     */
    int timestampToColumn(long timestampUs) {
        if (timestampUs <= 0) {
            return 0;
        }
        double seconds = timestampUs / 1_000_000.0;
        double beats = seconds * (tempo / 60.0);
        double columns = beats / BEATS_PER_COLUMN;
        return Math.max(0, (int) Math.round(columns));
    }

    private void finalizeHeldNotes() {
        for (int noteNumber = 0; noteNumber < 128; noteNumber++) {
            if (activeNoteStarts[noteNumber] >= 0) {
                int startColumn = activeNoteStarts[noteNumber];
                long elapsedUs = Math.max(lastRelativeUs, elapsedMonotonicUs());
                int endColumn = timestampToColumn(Math.max(0, elapsedUs - countInDurationUs)) + startColumnOffset;
                int duration = Math.max(1, endColumn - startColumn);
                MidiNoteData note = new MidiNoteData(noteNumber, startColumn,
                        duration, activeNoteVelocities[noteNumber], channel);
                clip.addNote(note);
                sessionNotes.add(note);
                notifyNoteRecorded(note);
                activeNoteStarts[noteNumber] = -1;
            }
        }
    }

    private long elapsedMonotonicUs() {
        return Math.max(0, (monotonicNanos.getAsLong() - monotonicOriginNanos) / 1_000);
    }

    private void notifyNoteRecorded(MidiNoteData note) {
        for (RecordingNoteListener listener : noteListeners) {
            try {
                listener.onNoteRecorded(note);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Note listener threw exception", e);
            }
        }
    }

    private void notifyEventReceived(MidiEvent event) {
        for (RecordingEventListener listener : eventListeners) {
            try {
                listener.onEventReceived(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Event listener threw exception", e);
            }
        }
    }

    /**
     * Internal MIDI receiver that processes incoming MIDI messages from the
     * device transmitter.
     */
    private final class MidiInputReceiver implements Receiver {

        @Override
        public void send(MidiMessage message, long timeStamp) {
            synchronized (MidiRecorder.this) {
                receive(message, timeStamp);
            }
        }

        private void receive(MidiMessage message, long timeStamp) {
            if (!recording) {
                return;
            }
            if (!(message instanceof ShortMessage shortMsg)) {
                return;
            }

            long relativeUs;
            if (timeStamp < 0) {
                usedFallbackTime = true;
                relativeUs = elapsedMonotonicUs();
            } else {
                if (recordingStartTimeUs < 0) {
                    recordingStartTimeUs = timeStamp;
                    if (usedFallbackTime) {
                        deviceOriginRelativeUs = Math.max(lastRelativeUs, elapsedMonotonicUs());
                    } else {
                        // Device-timed sessions keep their first-event origin for fallback and Stop too.
                        monotonicOriginNanos = monotonicNanos.getAsLong();
                    }
                }
                relativeUs = Math.max(0, timeStamp - recordingStartTimeUs + deviceOriginRelativeUs);
            }
            lastRelativeUs = Math.max(lastRelativeUs, relativeUs);
            relativeUs = lastRelativeUs;
            int command = shortMsg.getCommand();
            int msgChannel = shortMsg.getChannel();
            int noteNumber = shortMsg.getData1();
            int velocity = shortMsg.getData2();

            // Ignore events on channels other than the configured recording channel
            if (msgChannel != channel) {
                return;
            }

            // Notify event listeners even during count-in (for activity indicators)
            if (command == ShortMessage.NOTE_ON && velocity > 0) {
                notifyEventReceived(MidiEvent.noteOn(msgChannel, noteNumber, velocity));
            } else if (command == ShortMessage.NOTE_OFF
                    || (command == ShortMessage.NOTE_ON && velocity == 0)) {
                notifyEventReceived(MidiEvent.noteOff(msgChannel, noteNumber));
            }

            // Discard note data during count-in pre-roll
            if (countInDurationUs > 0 && relativeUs < countInDurationUs) {
                return;
            }

            // Adjust relative time to exclude count-in period
            long adjustedUs = countInDurationUs > 0
                    ? relativeUs - countInDurationUs : relativeUs;

            if (command == ShortMessage.NOTE_ON && velocity > 0) {
                // Note On
                int column = timestampToColumn(adjustedUs) + startColumnOffset;
                activeNoteStarts[noteNumber] = column;
                activeNoteVelocities[noteNumber] = velocity;

            } else if (command == ShortMessage.NOTE_OFF
                    || (command == ShortMessage.NOTE_ON && velocity == 0)) {
                // Note Off
                if (activeNoteStarts[noteNumber] >= 0) {
                    int startColumn = activeNoteStarts[noteNumber];
                    int endColumn = timestampToColumn(adjustedUs) + startColumnOffset;
                    int duration = Math.max(1, endColumn - startColumn);
                    MidiNoteData note = new MidiNoteData(noteNumber, startColumn,
                            duration, activeNoteVelocities[noteNumber], channel);
                    clip.addNote(note);
                    sessionNotes.add(note);
                    notifyNoteRecorded(note);
                    activeNoteStarts[noteNumber] = -1;
                }
            }
        }

        @Override
        public void close() {
            // no resources to release
        }
    }
}
