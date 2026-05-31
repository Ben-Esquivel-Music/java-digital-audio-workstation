package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;

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
        TransportVM vm = new TransportVM(transport, new FxDispatcher());
        try {
            transport.play();
            transport.setTempo(140.0);
            transport.setTimeSignature(3, 4);
            transport.setLoopRegion(2.0, 6.0);
            flushFx();

            assertThat(vm.getState()).isEqualTo(TransportState.PLAYING);
            assertThat(vm.getTempo()).isEqualTo(140.0);
            assertThat(vm.getTimeSignature()).isEqualTo(new TimeSignature(3, 4));
            assertThat(vm.getLoopRegion()).isEqualTo(new LoopRegion(false, 2.0, 6.0));

            transport.stop();
            flushFx();
            assertThat(vm.getState()).isEqualTo(TransportState.STOPPED);
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

            transport.advancePosition(2.0); // POSITION from the render-path method
            runOnFx(dispatcher::pulse);
            assertThat(vm.getPlayhead()).isEqualTo(52.0);
        } finally {
            vm.dispose();
        }
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
