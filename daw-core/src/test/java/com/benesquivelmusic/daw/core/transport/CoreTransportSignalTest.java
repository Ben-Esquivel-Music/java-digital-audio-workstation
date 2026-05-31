package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Story 290 — verifies the {@link Transport} toolkit-neutral change-notification
 * seam (Control Synchronization Design Book §1.3, §2.5, §3.2). The seam fixes the
 * "silent model" problem: the transport now emits a neutral
 * {@code Consumer<ChangeKind>} signal so the view-model can mirror it.
 *
 * <p>This test lives in {@code daw-core}, which has <strong>no JavaFX on the
 * classpath</strong> — the fact that it compiles and runs is itself the proof
 * that the seam is toolkit-neutral (no {@code javafx.beans.*} leaked into the
 * core, §9 rejection list). It asserts on {@link ChangeKind} callbacks only,
 * never on rendered output.</p>
 */
class CoreTransportSignalTest {

    @Test
    void signalFiresAfterTheFieldChangesSoTheObserverReadsThePostMutationValue() {
        Transport transport = new Transport();
        List<TransportState> observedStates = new ArrayList<>();
        transport.addChangeListener(kind -> {
            if (kind == ChangeKind.STATE) {
                // Re-reading inside the callback must yield the POST-mutation value.
                observedStates.add(transport.getState());
            }
        });

        transport.play();
        transport.record();
        transport.pause();
        transport.stop();

        assertThat(observedStates)
                .as("each STATE signal fires after the field flips, so the observer reads the new state")
                .containsExactly(
                        TransportState.PLAYING,
                        TransportState.RECORDING,
                        TransportState.PAUSED,
                        TransportState.STOPPED);
    }

    @Test
    void tempoSignalCarriesThePostMutationTempo() {
        Transport transport = new Transport();
        List<Double> observed = new ArrayList<>();
        transport.addChangeListener(kind -> {
            if (kind == ChangeKind.TEMPO) {
                observed.add(transport.getTempo());
            }
        });

        transport.setTempo(140.0);

        assertThat(observed).containsExactly(140.0);
    }

    @Test
    void eachMutationFiresExactlyOnceWithItsMatchingKind() {
        Transport transport = new Transport();
        Map<ChangeKind, AtomicInteger> counts = new EnumMap<>(ChangeKind.class);
        for (ChangeKind kind : ChangeKind.values()) {
            counts.put(kind, new AtomicInteger());
        }
        transport.addChangeListener(kind -> counts.get(kind).incrementAndGet());

        transport.play();                 // STATE
        transport.setTempo(150.0);        // TEMPO
        transport.setTimeSignature(3, 4); // TIME_SIGNATURE
        transport.setLoopEnabled(true);   // LOOP
        transport.setLoopRegion(0.0, 8.0);// LOOP
        transport.setPositionInBeats(4.0);// POSITION
        transport.advancePosition(1.0);   // POSITION

        assertThat(counts.get(ChangeKind.STATE).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.TEMPO).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.TIME_SIGNATURE).get()).isEqualTo(1);
        assertThat(counts.get(ChangeKind.LOOP).get()).isEqualTo(2);
        assertThat(counts.get(ChangeKind.POSITION).get()).isEqualTo(2);
    }

    @Test
    void pauseFromStoppedDoesNotFireBecauseNoFieldChanged() {
        Transport transport = new Transport();
        AtomicInteger fires = new AtomicInteger();
        transport.addChangeListener(kind -> fires.incrementAndGet());

        transport.pause(); // STOPPED → no transition → no signal

        assertThat(fires.get())
                .as("pause() is a no-op from STOPPED, so it must not fire a spurious signal")
                .isZero();
    }

    @Test
    void removedListenerStopsReceivingSignals() {
        Transport transport = new Transport();
        AtomicInteger fires = new AtomicInteger();
        Runnable token = transport.addChangeListener(kind -> fires.incrementAndGet());

        transport.play();
        token.run(); // unregister
        transport.stop();

        assertThat(fires.get())
                .as("after the removal token runs, the listener receives no further signals")
                .isEqualTo(1);
    }

    @Test
    void removeChangeListenerAlsoUnregisters() {
        Transport transport = new Transport();
        AtomicInteger fires = new AtomicInteger();
        var listener = (java.util.function.Consumer<ChangeKind>) kind -> fires.incrementAndGet();
        transport.addChangeListener(listener);

        transport.play();
        transport.removeChangeListener(listener);
        transport.stop();

        assertThat(fires.get()).isEqualTo(1);
    }

    @Test
    void registrationRejectsNullListener() {
        Transport transport = new Transport();
        assertThatNullPointerException().isThrownBy(() -> transport.addChangeListener(null));
        assertThatNullPointerException().isThrownBy(() -> transport.removeChangeListener(null));
    }
}
