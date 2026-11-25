package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.recording.TakeManifest;

import java.util.Objects;
import java.util.function.Supplier;

/** The take the clock badge ({@link ClockStatusIndicator}) marks, as the record coordinator has it. FX thread. */
interface TakeClockMarks {

    /** What records now, as the clock notices word it (ruling E108). */
    enum TakeRecording {
        /** No take records. */
        NONE,
        /** A take with an audio manifest records: a clock change is marked in it. */
        AUDIO,
        /** A take records with no audio manifest (MIDI only): nothing is marked. */
        WITHOUT_AUDIO
    }

    /** @return what records now */
    TakeRecording takeRecording();

    /**
     * The clock was declared unlocked or locked again on the source on show.
     *
     * @param locked        {@code false} for an unlock, {@code true} for a relock
     * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when unknown
     */
    void lockChanged(boolean locked, long evidenceNanos);

    /** The audio take first seen recording started while an unlock applied before it was on show (E173). */
    void takeStartsUnlocked();

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
                if (current == null || current.recordCoordinator().getState() != RecordState.RECORDING) {
                    return TakeRecording.NONE;
                }
                return current.recordCoordinator().isAudioTakeRecording()
                        ? TakeRecording.AUDIO : TakeRecording.WITHOUT_AUDIO;
            }

            @Override
            public void lockChanged(boolean locked, long evidenceNanos) {
                TransportController current = transport.get();
                if (current != null) {
                    current.recordCoordinator().noteClockEvent(
                            locked ? TakeManifest.ClockEvent.LOCKED : TakeManifest.ClockEvent.UNLOCKED, evidenceNanos);
                }
            }

            @Override
            public void takeStartsUnlocked() {
                TransportController current = transport.get();
                if (current != null) {
                    current.recordCoordinator().noteTakeStartsUnlocked();
                }
            }
        };
    }
}
