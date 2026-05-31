package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Story 290 — verifies the transport command cascade end-to-end: a
 * {@link SetTempoCommand} runs the mutation through {@link CoreTransportIntentHandler}
 * (the MUTATE phase) and the existing {@link TransportEvent.TempoChanged} reaches a
 * subscriber via the bus (the ANNOUNCE phase) — Control Synchronization Design
 * Book §5.1, §5.2.
 *
 * <p>The typed event is asserted via its <em>payload</em> captured by a bus
 * subscriber (the design-book analogue of "a parent {@code addEventFilter} on the
 * payload"), never via source identity and never via rendered output — so this
 * test needs no JavaFX toolkit.</p>
 */
class TransportCommandEventTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final double SAMPLE_RATE = 48_000.0;

    private DefaultEventBus bus;

    @BeforeEach
    void installBus() {
        bus = new DefaultEventBus();
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void clearBus() {
        EventBusPublisher.setDefault(null);
        bus.close();
    }

    @Test
    void setTempoCommandMutatesTransportAndAnnouncesTempoChangedOnTheBus() throws InterruptedException {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TransportEvent.TempoChanged> captured = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.TempoChanged.class,
                DispatchMode.ON_CALLER_THREAD, event -> {
                    captured.set(event);
                    delivered.countDown();
                })) {

            // A control raises the intent; the handler runs MUTATE then ANNOUNCE.
            new SetTempoCommand(140.0).execute(handler);

            assertThat(transport.getTempo())
                    .as("MUTATE: the command ran the tempo mutation").isEqualTo(140.0);
            assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("ANNOUNCE: TempoChanged reaches the subscriber via the bus").isTrue();
            assertThat(captured.get().previousBpm()).isEqualTo(120.0);
            assertThat(captured.get().newBpm()).isEqualTo(140.0);
        }
    }

    @Test
    void startCommandMutatesAndAnnouncesStarted() throws InterruptedException {
        Transport transport = new Transport();
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        CountDownLatch delivered = new CountDownLatch(1);
        try (EventBus.Subscription sub = bus.on(TransportEvent.Started.class,
                DispatchMode.ON_CALLER_THREAD, event -> delivered.countDown())) {

            new StartTransportCommand().execute(handler);

            assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
            assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void outOfRangeTempoIsRejectedInValidateAndNeverMutatesOrAnnounces() {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        AtomicReference<TransportEvent> any = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.class,
                DispatchMode.ON_CALLER_THREAD, any::set)) {

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SetTempoCommand(5.0).execute(handler));

            assertThat(transport.getTempo())
                    .as("VALIDATE rejected the intent before MUTATE").isEqualTo(120.0);
            assertThat(any.get())
                    .as("no event is announced when validation fails").isNull();
        }
    }
}
