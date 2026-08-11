package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 314 review — the stale-listener guard. {@code ChangeNotifier.fire}
 * reads its listener snapshot once into a local before iterating, so
 * detaching the binder's tracks listener cannot cancel an invocation the
 * snapshot already captured: without the binding-generation guard the
 * detached listener runs <em>after</em> the new (or cleared)
 * {@code setGraph} publish and folds the old project's tracks over it —
 * recreating the hybrid state the atomic publish exists to prevent. These
 * tests drive that exact interleaving deterministically: reentrantly (a
 * test listener registered ahead of the binder's in the captured snapshot
 * rebinds / unbinds mid-fire) and cross-thread (a latch parks the notifying
 * thread mid-iteration while the main thread rebinds). No sleeps, no
 * tolerances.
 */
class EngineBinderStaleListenerInvalidationTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 8);

    private AudioEngine engine;
    private EngineBinder binder;
    private DawProject projectA;
    private DawProject projectB;
    private Track lead;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        binder = new EngineBinder(engine);
        projectA = new DawProject("A", FORMAT);
        projectB = new DawProject("B", FORMAT);
        lead = projectB.createAudioTrack("Lead");
    }

    @Test
    void aReentrantRebindMidNotificationMakesTheStaleListenerANoOp() {
        // Registered BEFORE bind, so the notifier snapshot orders this test
        // listener AHEAD of the binder's — fire() rebinds first, then still
        // invokes the binder's just-detached projectA listener from the
        // captured snapshot.
        AtomicBoolean rebound = new AtomicBoolean();
        projectA.addChangeListener(kind -> {
            if (kind == DawProject.ChangeKind.TRACKS && rebound.compareAndSet(false, true)) {
                binder.bind(projectB);
            }
        });
        binder.bind(projectA);

        projectA.createAudioTrack("Stale");

        assertThat(engine.getTracks())
                .as("the stale projectA listener, invoked from the captured fire() "
                        + "snapshot after the rebind, must NOT fold projectA's tracks "
                        + "over projectB's freshly published graph")
                .containsExactly(lead);
        assertThat(engine.getTransport())
                .as("the engine holds projectB's live transport after the mid-fire rebind")
                .isSameAs(projectB.getTransport());
        assertThat(engine.getMixer())
                .as("the engine holds projectB's live mixer after the mid-fire rebind")
                .isSameAs(projectB.getMixer());

        Track pad = projectB.createAudioTrack("Pad");
        assertThat(engine.getTracks())
                .as("the NEW binding's listener is live — a projectB structural "
                        + "change still refreshes the engine snapshot")
                .containsExactly(lead, pad);
    }

    @Test
    void aReentrantUnbindMidNotificationLeavesTheClearedGraphCleared() {
        AtomicBoolean unbound = new AtomicBoolean();
        projectA.addChangeListener(kind -> {
            if (kind == DawProject.ChangeKind.TRACKS && unbound.compareAndSet(false, true)) {
                binder.unbind();
            }
        });
        binder.bind(projectA);

        projectA.createAudioTrack("Stale");

        assertThat(engine.getTracks())
                .as("the stale listener must not resurrect projectA's tracks onto "
                        + "the graph unbind just cleared")
                .isNull();
        assertThat(engine.getTransport())
                .as("transport stays null (playbackActive gate off) after the "
                        + "mid-fire unbind")
                .isNull();
        assertThat(engine.getMixer())
                .as("mixer stays cleared after the mid-fire unbind")
                .isNull();
    }

    @Test
    void aCrossThreadInFlightNotificationCannotFoldOldTracksOverARebind()
            throws InterruptedException {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean proceedObserved = new AtomicBoolean();
        // Registered BEFORE bind: the mutator thread parks here mid-fire with
        // the binder's projectA listener still ahead in the captured snapshot
        // — and holding NO binder lock, so the main-thread rebind below
        // cannot deadlock.
        projectA.addChangeListener(kind -> {
            if (kind == DawProject.ChangeKind.TRACKS) {
                inFlight.countDown();
                try {
                    proceedObserved.set(proceed.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        binder.bind(projectA);

        Thread mutator = new Thread(() -> projectA.createAudioTrack("Stale"), "stale-mutator");
        mutator.start();
        assertThat(inFlight.await(10, TimeUnit.SECONDS))
                .as("the mutator thread reached the mid-fire park point").isTrue();

        binder.bind(projectB); // fire() is mid-iteration on the old snapshot

        proceed.countDown();
        mutator.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(mutator.isAlive())
                .as("the mutator thread finished delivering the stale notification")
                .isFalse();
        assertThat(proceedObserved)
                .as("the parked listener resumed via the proceed latch, not a timeout")
                .isTrue();

        assertThat(engine.getTracks())
                .as("the binder's projectA listener — detached, but still in the "
                        + "captured fire() snapshot — must NOT fold projectA's tracks "
                        + "over projectB's published graph")
                .containsExactly(lead);
        assertThat(engine.getTransport())
                .as("the engine holds projectB's live transport after the rebind")
                .isSameAs(projectB.getTransport());
    }
}
