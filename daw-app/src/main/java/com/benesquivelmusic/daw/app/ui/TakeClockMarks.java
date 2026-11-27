package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.recording.TakeManifest;

import java.util.Objects;
import java.util.function.Supplier;

/** The take the clock badge ({@link ClockStatusIndicator}) marks, as the record coordinator has it. FX thread. */
interface TakeClockMarks {

    /** What records now, as the clock notices word it (ruling E108). */
    enum TakeRecording {
        /**
         * No take records, or the audio take recording has had its capture sealed early and is about to be stopped
         * for it (ruling E280): nothing is marked, and no notice says that recording continues.
         */
        NONE,
        /** A take with an audio manifest records: a clock change is marked in it. */
        AUDIO,
        /** A take records with no audio manifest (MIDI only): nothing is marked. */
        WITHOUT_AUDIO
    }

    /** @return what records now */
    TakeRecording takeRecording();

    /**
     * A change of the clock lock on the source on show that the badge measured, replayed from what its status
     * carries, or inferred for a gap in it.
     *
     * @param locked        {@code false} for an unlock, {@code true} for a relock
     * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when unknown
     * @return for an unlock, whether an audio take records, its capture not sealed early, and is marked unlocked
     *         after the call, whether or not this call wrote the mark (rulings E223 and E280); {@code false} for a
     *         relock
     */
    boolean lockChanged(boolean locked, long evidenceNanos);

    /**
     * The audio take is marked unlocked from its start: an unlock applied before it was first seen is still on show
     * (E173), or a serial first measured during it has more changes than its status keeps (E218).
     *
     * @return whether an audio take records, its capture not sealed early, and is marked unlocked after the call,
     *         whether or not this call wrote the mark (rulings E223 and E280)
     */
    boolean takeStartsUnlocked();

    /**
     * The window's take marks: the current transport's record coordinator.
     *
     * @param transport the window's current transport controller, or a supplier of {@code null} while none
     * @return the marks
     */
    static TakeClockMarks of(Supplier<TransportController> transport) {
        Objects.requireNonNull(transport, "transport must not be null");
        return new TakeClockMarks() {
            @Override
            public TakeRecording takeRecording() {
                TransportController current = transport.get();
                if (current == null || current.recordCoordinator().getState() != RecordState.RECORDING
                        || current.recordCoordinator().isTakeCaptureSealed()) {
                    return TakeRecording.NONE;
                }
                return current.recordCoordinator().isAudioTakeRecording()
                        ? TakeRecording.AUDIO : TakeRecording.WITHOUT_AUDIO;
            }

            @Override
            public boolean lockChanged(boolean locked, long evidenceNanos) {
                TransportController current = transport.get();
                return current != null && current.recordCoordinator().noteClockEvent(
                        locked ? TakeManifest.ClockEvent.LOCKED : TakeManifest.ClockEvent.UNLOCKED, evidenceNanos);
            }

            @Override
            public boolean takeStartsUnlocked() {
                TransportController current = transport.get();
                return current != null && current.recordCoordinator().noteTakeStartsUnlocked();
            }
        };
    }
}
