package com.benesquivelmusic.daw.core.persistence;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rehydration session's admission rules (decision D10), driven without threads where possible. */
class DecodeGateTest {

    @Test
    void ticketsAreAdmittedStrictlyInOrderAndASkippedTicketLetsTheNextOneThrough() {
        DecodeGate gate = new DecodeGate(3, 4, 1_000);

        assertThat(gate.tryAdmit(1, 10)).as("ticket 1 waits for ticket 0").isEmpty();
        gate.skip(0);
        assertThat(gate.tryAdmit(2, 10)).as("ticket 2 waits for ticket 1").isEmpty();
        assertThat(gate.tryAdmit(1, 10)).isPresent();
        assertThat(gate.tryAdmit(2, 10)).isPresent();
    }

    @Test
    void atMostThePermittedNumberOfDecodesRunAtOnce() {
        DecodeGate gate = new DecodeGate(3, 2, 1_000);
        DecodeGate.Lease first = gate.tryAdmit(0, 10).orElseThrow();
        gate.tryAdmit(1, 10).orElseThrow();

        assertThat(gate.tryAdmit(2, 10)).isEmpty();
        gate.release(first);
        assertThat(gate.tryAdmit(2, 10)).isPresent();
    }

    @Test
    void theRunningDecodesStayWithinTheBudget() {
        DecodeGate gate = new DecodeGate(2, 4, 250);
        DecodeGate.Lease first = gate.tryAdmit(0, 200).orElseThrow();

        assertThat(gate.tryAdmit(1, 100)).as("200 + 100 exceeds 250").isEmpty();
        gate.release(first);
        assertThat(gate.tryAdmit(1, 100)).isPresent();
    }

    @Test
    void aDecodeAboveTheBudgetWaitsForAnEmptyGateThenRunsAlone() {
        DecodeGate gate = new DecodeGate(3, 4, 250);
        DecodeGate.Lease small = gate.tryAdmit(0, 10).orElseThrow();

        assertThat(gate.tryAdmit(1, 300)).as("something is running").isEmpty();
        gate.release(small);
        DecodeGate.Lease oversize = gate.tryAdmit(1, 300).orElseThrow();
        assertThat(oversize).isEqualTo(new DecodeGate.Lease(250, true));
        assertThat(gate.tryAdmit(2, 0)).as("nothing runs beside it, not even a decode that costs nothing")
                .isEmpty();
        gate.release(oversize);
        assertThat(gate.tryAdmit(2, 0)).isPresent();
    }

    @Test
    void cancellingWakesAWaitingDecodeWhichGivesUp() throws Exception {
        DecodeGate gate = new DecodeGate(2, 1, 1_000);
        gate.tryAdmit(0, 10).orElseThrow();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                gate.admit(1, 10);
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                done.countDown();
            }
        });

        gate.cancel();

        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        waiter.join();
        assertThat(outcome.get()).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> gate.tryAdmit(1, 10)).isInstanceOf(CancellationException.class);
    }
}
