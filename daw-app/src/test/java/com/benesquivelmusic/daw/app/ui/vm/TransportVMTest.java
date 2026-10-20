package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import javafx.application.Platform;
import javafx.beans.property.Property;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 290 — verifies {@link TransportVM}: each read-only property mirrors the
 * core after a {@link Transport.ChangeKind} signal; the VM is the single writer
 * (a control cannot mutate a property); and the continuous {@code playhead} is
 * deterministic because the test drives the {@link FxDispatcher} drain manually
 * (Control Synchronization Design Book §3.2, §4.3, §4.5, §5.2).
 *
 * <p>Assertions are on property values only — never on rasterised output.</p>
 */
class TransportVMTest {

    private static final long TIMEOUT_SECONDS = 5;

    @BeforeAll
    static void initToolkit() throws InterruptedException {
        FxTestSupport.startToolkit();
    }

    /** Posts a barrier onto the FX thread and blocks until it (and every earlier {@code onFx}) has run. */
    private static void flushFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("FX queue must flush within %ds", TIMEOUT_SECONDS).isTrue();
    }

    /** Runs {@code work} on the FX thread and blocks until it completes. */
    private static void runOnFx(Runnable work) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof RuntimeException re) {
            throw re;
        }
        if (thrown.get() instanceof Error e) {
            throw e;
        }
    }

    @Test
    void propertiesSeedFromCurrentTransportStateAtConstruction() {
        Transport transport = new Transport();
        transport.setTempo(123.0);
        transport.setTimeSignature(7, 8);
        transport.setLoopEnabled(true);

        TransportVM vm = new TransportVM(transport, new FxDispatcher());
        try {
            assertThat(vm.getState()).isEqualTo(TransportState.STOPPED);
            assertThat(vm.getTempo()).isEqualTo(123.0);
            assertThat(vm.getTimeSignature()).isEqualTo(new TimeSignature(7, 8));
            assertThat(vm.getLoopRegion().enabled()).isTrue();
            assertThat(vm.getPlayhead()).isEqualTo(0.0);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void stateAndTempoAndLoopPropertiesFollowCoreSignals() throws InterruptedException {
        Transport transport = new Transport();
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);
        try {
            // This test thread is not the FX thread: a STATE fired here goes
            // through the lock-free channel the pulse drains (story 328
            // review); the other discrete facts are posted with runLater.
            transport.play();
            transport.setTempo(140.0);
            transport.setTimeSignature(3, 4);
            transport.setLoopRegion(2.0, 6.0);
            flushFx();
            runOnFx(dispatcher::pulse);

            assertThat(vm.getState()).isEqualTo(TransportState.PLAYING);
            assertThat(vm.getTempo()).isEqualTo(140.0);
            assertThat(vm.getTimeSignature()).isEqualTo(new TimeSignature(3, 4));
            assertThat(vm.getLoopRegion()).isEqualTo(new LoopRegion(false, 2.0, 6.0));

            transport.stop();
            runOnFx(dispatcher::pulse);
            assertThat(vm.getState()).isEqualTo(TransportState.STOPPED);

            // From the FX thread a STATE change is posted with runLater, as before.
            runOnFx(transport::play);
            flushFx();
            assertThat(vm.getState()).isEqualTo(TransportState.PLAYING);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void aStateFiredOffTheFxThreadReachesTheVmThroughThePulseNotThroughRunLater() throws InterruptedException {
        // Story 328 review: the count-in's record start fires STATE on the
        // RENDER thread. Platform.runLater takes the toolkit's queue lock and
        // allocates; the VM must therefore route an off-FX STATE through the
        // dispatcher's wait-free channel — observable here as "a runLater
        // drain alone does not deliver it; the pulse does" — and re-read the
        // live state on the drain, so a later FX-thread change is never
        // overwritten by the earlier value.
        Transport transport = new Transport();
        FxDispatcher dispatcher = new FxDispatcher();
        runOnFx(dispatcher::pulse); // records the FX thread: isFxThread() is one volatile read from now on
        TransportVM vm = new TransportVM(transport, dispatcher);
        try {
            Thread renderThread = new Thread(transport::record, "render-thread-stand-in");
            renderThread.start();
            renderThread.join();
            flushFx();
            assertThat(vm.getState()).as("no runLater was posted off the FX thread").isEqualTo(TransportState.STOPPED);
            runOnFx(dispatcher::pulse);
            assertThat(vm.getState()).as("the pulse drains the lock-free channel").isEqualTo(TransportState.RECORDING);

            // The fire followed at once by a Stop on the FX thread: the drain
            // re-reads the live state, so the VM ends STOPPED, never RECORDING.
            Thread again = new Thread(transport::record, "render-thread-stand-in");
            again.start();
            again.join();
            runOnFx(transport::stop);
            flushFx();
            runOnFx(dispatcher::pulse);
            assertThat(vm.getState()).isEqualTo(TransportState.STOPPED);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void loopRecordPropertyFollowsTheLoopSignal() throws InterruptedException {
        // Story 328 — the loop-record mirror's producer is the transport's
        // LOOP signal, fired by Transport.setLoopRecordEnabled (the command
        // cascade behind the Loop Rec toggle and Shift+L).
        Transport transport = new Transport();
        transport.setLoopRecordEnabled(true);
        TransportVM vm = new TransportVM(transport, new FxDispatcher());
        try {
            assertThat(vm.isLoopRecord()).as("seeded from the transport").isTrue();
            assertThat(vm.loopRecordProperty()).isNotInstanceOf(Property.class);

            transport.setLoopRecordEnabled(false);
            flushFx();
            assertThat(vm.isLoopRecord()).isFalse();

            transport.setLoopRecordEnabled(true);
            flushFx();
            assertThat(vm.isLoopRecord()).isTrue();
            assertThat(vm.getLoopRegion().enabled()).as("loop-record is independent of loop playback").isFalse();
        } finally {
            vm.dispose();
        }
    }

    @Test
    void punchRegionPropertyFollowsThePunchSignalAndIsEmptyWhenCleared() throws InterruptedException {
        // Story 328 — the punch mirror's producer is Transport.setPunchRegion /
        // clearPunchRegion (fired by the command cascade and the ruler gestures).
        Transport transport = new Transport();
        TransportVM vm = new TransportVM(transport, new FxDispatcher());
        try {
            assertThat(vm.getPunchRegion()).as("seeded empty with no region installed").isEmpty();
            assertThat(vm.punchRegionProperty()).isNotInstanceOf(Property.class);

            transport.setPunchRegion(PunchRegion.enabled(96_000L, 288_000L));
            flushFx();
            assertThat(vm.getPunchRegion()).contains(PunchRegion.enabled(96_000L, 288_000L));

            transport.clearPunchRegion();
            flushFx();
            assertThat(vm.getPunchRegion()).isEmpty();
        } finally {
            vm.dispose();
        }
    }

    @Test
    void vmIsTheSingleWriter_exposedPropertiesAreReadOnlyAndCannotBeMutatedByAControl() {
        Transport transport = new Transport();
        TransportVM vm = new TransportVM(transport, new FxDispatcher());
        try {
            // A read-only property is NOT a writable Property, so a control that
            // binds to it has no setter/bindBidirectional path back into the VM.
            assertThat(vm.stateProperty()).isNotInstanceOf(Property.class);
            assertThat(vm.tempoProperty()).isNotInstanceOf(Property.class);
            assertThat(vm.timeSignatureProperty()).isNotInstanceOf(Property.class);
            assertThat(vm.loopRegionProperty()).isNotInstanceOf(Property.class);
            assertThat(vm.playheadProperty()).isNotInstanceOf(Property.class);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void continuousPlayheadIsDrainedDeterministicallyAndCoalesced() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        Transport transport = new Transport();
        TransportVM vm = new TransportVM(transport, dispatcher);
        try {
            // A burst of position changes publishes into the lock-free buffer;
            // no value reaches the property until the drain runs.
            for (int beat = 1; beat <= 50; beat++) {
                transport.setPositionInBeats(beat);
            }
            assertThat(vm.getPlayhead())
                    .as("playhead must not update before the drain pulse")
                    .isEqualTo(0.0);

            runOnFx(dispatcher::pulse); // single manual drain on the FX thread
            assertThat(vm.getPlayhead())
                    .as("a coalesced burst drains to the latest position")
                    .isEqualTo(50.0);

            // POSITION from the render-path method. Story 315: advancePosition
            // only advances a ROLLING transport (the stop/pause back-off), so
            // the transport must be playing for the block advance to land.
            transport.play();
            transport.advancePosition(2.0);
            runOnFx(dispatcher::pulse);
            assertThat(vm.getPlayhead()).isEqualTo(52.0);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void stopDeliversTheRewoundAnchorPositionToTheVm() throws InterruptedException {
        // Story 315 AC — "TransportVM observes the rewound/transitioned position
        // immediately": stop() rewinds the position to the play-start anchor and
        // fires STATE, whose handler republishes the playhead; after the drain
        // the VM must show the anchor, not the position playback had reached.
        FxDispatcher dispatcher = new FxDispatcher();
        Transport transport = new Transport();
        TransportVM vm = new TransportVM(transport, dispatcher);
        try {
            transport.setPositionInBeats(8.0); // STOPPED → applied inline
            runOnFx(dispatcher::pulse);
            assertThat(vm.getPlayhead()).isEqualTo(8.0);

            transport.play();                  // anchors play-start at 8.0
            transport.advancePosition(4.0);    // rolls to 12.0
            runOnFx(dispatcher::pulse);
            assertThat(vm.getPlayhead()).isEqualTo(12.0);

            transport.stop();                  // rewinds to the anchor
            runOnFx(dispatcher::pulse);
            assertThat(vm.getState()).isEqualTo(TransportState.STOPPED);
            assertThat(vm.getPlayhead())
                    .as("the STATE-signal republish delivers the rewound anchor")
                    .isEqualTo(8.0);
        } finally {
            vm.dispose();
        }
    }

    @Test
    void disposeClosesTheContinuousChannelSoItDoesNotLeakInTheDispatcher() {
        FxDispatcher dispatcher = new FxDispatcher();
        Transport transport = new Transport();
        assertThat(dispatcher.openChannelCount())
                .as("no continuous channel before the VM is created").isZero();

        TransportVM vm = new TransportVM(transport, dispatcher);
        assertThat(dispatcher.openChannelCount())
                .as("the VM opens exactly two continuous channels: the playhead and the off-FX STATE hop")
                .isEqualTo(2);

        vm.dispose();
        // The leak this guards is observationally silent within a single lifecycle
        // (nothing publishes to the channel once the listener is gone), so assert
        // on the dispatcher's channel set directly: dispose() must CLOSE the
        // channel, not merely unregister the upstream listener (story 290 AC —
        // "no leaked listeners"). A leak would accumulate across create/dispose.
        assertThat(dispatcher.openChannelCount())
                .as("dispose() must close both channels, not leak them in the "
                        + "long-lived dispatcher")
                .isZero();

        vm.dispose(); // idempotent
        assertThat(dispatcher.openChannelCount()).isZero();
    }

    @Test
    void disposeUnregistersSoNoFurtherSignalsAreObserved() throws InterruptedException {
        Transport transport = new Transport();
        FxDispatcher dispatcher = new FxDispatcher();
        TransportVM vm = new TransportVM(transport, dispatcher);

        vm.dispose();

        transport.setTempo(200.0);
        transport.setPositionInBeats(9.0);
        runOnFx(dispatcher::pulse);
        flushFx();

        assertThat(vm.getTempo())
                .as("tempo retains its last value because the listener was unregistered")
                .isNotEqualTo(200.0);
        assertThat(vm.getPlayhead()).isEqualTo(0.0);
    }
}
