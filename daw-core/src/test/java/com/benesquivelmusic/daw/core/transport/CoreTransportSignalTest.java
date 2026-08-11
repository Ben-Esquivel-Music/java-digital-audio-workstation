package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

        transport.setPositionInBeats(4.0);// POSITION (immediate — transport stopped)
        transport.play();                 // STATE
        transport.setTempo(150.0);        // TEMPO
        transport.setTimeSignature(3, 4); // TIME_SIGNATURE
        transport.setLoopEnabled(true);   // LOOP
        transport.setLoopRegion(0.0, 8.0);// LOOP
        transport.advancePosition(1.0);   // POSITION (engine-driven advance)

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
    void aListenerRemovingAnotherDuringNotificationDoesNotThrowAndKeepsSnapshotSemantics() {
        Transport transport = new Transport();
        List<ChangeKind> secondSaw = new ArrayList<>();

        // The first listener unregisters the second one *during* notification. Under the
        // old cached-size + get(i) loop this shrank the backing list, so the next get(i)
        // read a now-shorter array and threw IndexOutOfBoundsException. The snapshot array
        // captured once at notify start makes the in-flight notify immune to the removal.
        AtomicReference<Runnable> removeSecond = new AtomicReference<>();
        transport.addChangeListener(kind -> {
            Runnable token = removeSecond.getAndSet(null);
            if (token != null) {
                token.run(); // reentrant remove of the *second* listener
            }
        });
        removeSecond.set(transport.addChangeListener(secondSaw::add));

        transport.play(); // must not throw IndexOutOfBoundsException

        assertThat(secondSaw)
                .as("the snapshot captured at notify start is stable: a listener removed "
                        + "mid-notify still fires once this round")
                .containsExactly(ChangeKind.STATE);

        secondSaw.clear();
        transport.stop();

        assertThat(secondSaw)
                .as("after the reentrant removal, the second listener receives no further signals")
                .isEmpty();
    }

    @Test
    void registrationRejectsNullListener() {
        Transport transport = new Transport();
        assertThatNullPointerException().isThrownBy(() -> transport.addChangeListener(null));
        assertThatNullPointerException().isThrownBy(() -> transport.removeChangeListener(null));
    }

    // ── Story 315 — pre/post-roll transitions and stop semantics fire the
    //    same change signals as every other mutator ───────────────────────────

    @Test
    void playWithPreRollFiresPositionThenState() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.playWithPreRoll();

        assertThat(fired)
                .as("the pre-roll rewind moves the playhead and starts playback — "
                        + "both slices signal (previously silent)")
                .containsExactly(ChangeKind.POSITION, ChangeKind.STATE);
    }

    @Test
    void playWithPreRollWithoutARewindFiresOnlyState() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        // No pre-roll configured — equivalent to play(): no POSITION signal.
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.playWithPreRoll();

        assertThat(fired).containsExactly(ChangeKind.STATE);
    }

    @Test
    void requestStopEnteringThePostRollWindowFiresState() {
        Transport transport = new Transport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.record();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        boolean enteredPostRoll = transport.requestStop();

        assertThat(enteredPostRoll).isTrue();
        assertThat(fired)
                .as("entering the post-roll window (RECORDING → PLAYING + gating "
                        + "flags) signals STATE (previously silent)")
                .containsExactly(ChangeKind.STATE);
    }

    @Test
    void finishPostRollFiresStateAndPositionWhenThePlayheadMoved() {
        Transport transport = new Transport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.setPositionInBeats(32.0);
        transport.play();               // anchor = 32
        transport.advancePosition(2.0); // 34
        transport.requestStop();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.finishPostRoll();

        assertThat(fired)
                .as("the deferred stop signals STATE and, because the playhead "
                        + "rewound to the anchor, POSITION (previously silent)")
                .containsExactly(ChangeKind.STATE, ChangeKind.POSITION);
        assertThat(transport.getPositionInBeats()).isEqualTo(32.0);
    }

    @Test
    void finishPostRollFiresOnlyStateWhenThePlayheadDidNotMove() {
        Transport transport = new Transport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.setPositionInBeats(32.0);
        transport.play();   // anchor = 32; no advance — playhead already at anchor
        transport.requestStop();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.finishPostRoll();

        assertThat(fired).containsExactly(ChangeKind.STATE);
    }

    @Test
    void stopFiresStateAndPositionWhenThePlayheadRewindsToTheAnchor() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.play();
        transport.advancePosition(1.0);
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.stop();

        assertThat(fired).containsExactly(ChangeKind.STATE, ChangeKind.POSITION);
        assertThat(transport.getPositionInBeats()).isEqualTo(16.0);
    }

    @Test
    void stopWhileAlreadyStoppedFiresNothing() {
        Transport transport = new Transport();
        transport.setPositionInBeats(16.0);
        transport.play();
        transport.stop();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.stop(); // idempotent no-op

        assertThat(fired)
                .as("stop() from STOPPED changes nothing, so it must not fire a "
                        + "spurious signal")
                .isEmpty();
    }

    @Test
    void seekWhilePlayingFiresNothingUntilTheAdvanceAppliesIt() {
        Transport transport = new Transport();
        // Simulate the audio callback owning the clock — without a claim there
        // is no driver to drain the queue, so the seek applies inline instead
        // (story 315 review; see TransportRealTimeClockOwnershipTest).
        transport.setRealTimeClockActive(true);
        transport.play();
        List<ChangeKind> fired = new ArrayList<>();
        transport.addChangeListener(fired::add);

        transport.setPositionInBeats(4.0); // queued — RT thread will apply it

        assertThat(fired)
                .as("a seek issued during playback is queued; POSITION fires only "
                        + "when the RT advance applies it at the block boundary")
                .isEmpty();

        transport.advancePosition(0.5);

        assertThat(fired).containsExactly(ChangeKind.POSITION);
        assertThat(transport.getPositionInBeats()).isEqualTo(4.0);
    }
}
