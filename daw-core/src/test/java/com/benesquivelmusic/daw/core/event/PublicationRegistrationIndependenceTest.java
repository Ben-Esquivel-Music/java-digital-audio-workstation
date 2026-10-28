package com.benesquivelmusic.daw.core.event;

import com.benesquivelmusic.daw.sdk.event.BusEvent;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.ProjectEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each {@link EventBus#onPublication} call is its own registration: closing its handle removes exactly
 * that registration, a repeated close removes nothing, and observers run inside {@code publish} in
 * registration order with a throwing observer isolated.
 */
class PublicationRegistrationIndependenceTest {

    private static final Instant AT = Instant.parse("2026-10-27T12:00:00Z");
    private static final int CLOSING_THREADS = 16;

    private final List<String> deliveries = new CopyOnWriteArrayList<>();
    private final Consumer<DawEvent> recorder = this::record;
    private DefaultEventBus bus;

    @AfterEach
    void closeBus() {
        if (bus != null) bus.close();
    }

    private void record(DawEvent event) {
        deliveries.add("r:" + event.getClass().getSimpleName());
    }

    private List<String> deliveriesOf(BusEvent event) {
        deliveries.clear();
        bus.publish(event);
        return List.copyOf(deliveries);
    }

    private static ClipEvent.Added clipAdded() {
        return new ClipEvent.Added(UUID.randomUUID(), UUID.randomUUID(), AT);
    }

    private static ClipEvent.Removed clipRemoved() {
        return new ClipEvent.Removed(UUID.randomUUID(), UUID.randomUUID(), AT);
    }

    private static ProjectEvent.Created projectCreated() {
        return new ProjectEvent.Created(UUID.randomUUID(), AT);
    }

    private static MixerEvent.GainChanged gainChanged() {
        return new MixerEvent.GainChanged(UUID.randomUUID(), AT);
    }

    @Test
    void aSuperTypeAndALeafRegistrationOfOneSharedMethodReferenceCloseIndependently() {
        bus = new DefaultEventBus();
        EventBus.Subscription superType = bus.onPublication(ClipEvent.class, recorder);
        EventBus.Subscription leaf = bus.onPublication(ClipEvent.Added.class, recorder);

        assertThat(deliveriesOf(clipAdded())).containsExactly("r:Added", "r:Added");
        assertThat(deliveriesOf(clipRemoved())).containsExactly("r:Removed");

        superType.close();
        superType.close();
        assertThat(deliveriesOf(clipAdded())).containsExactly("r:Added");
        assertThat(deliveriesOf(clipRemoved())).isEmpty();

        leaf.close();
        assertThat(deliveriesOf(clipAdded())).isEmpty();
    }

    @Test
    void equalRegistrationsClosedInAScrambledOrderWithRepeatedClosesRemoveOnlyThemselves() {
        bus = new DefaultEventBus();
        EventBus.Subscription first = bus.onPublication(ProjectEvent.class, recorder);
        bus.onPublication(ProjectEvent.class, event -> deliveries.add("m1"));
        EventBus.Subscription second = bus.onPublication(ProjectEvent.class, recorder);
        bus.onPublication(ProjectEvent.class, event -> deliveries.add("m2"));
        EventBus.Subscription third = bus.onPublication(ProjectEvent.class, recorder);
        bus.onPublication(ProjectEvent.class, event -> deliveries.add("m3"));
        String r = "r:Created";

        assertThat(deliveriesOf(projectCreated())).containsExactly(r, "m1", r, "m2", r, "m3");

        second.close();
        second.close();
        assertThat(deliveriesOf(projectCreated())).containsExactly(r, "m1", "m2", r, "m3");

        third.close();
        assertThat(deliveriesOf(projectCreated())).containsExactly(r, "m1", "m2", "m3");

        EventBus.Subscription fourth = bus.onPublication(ProjectEvent.class, recorder);
        third.close();
        second.close();
        third.close();
        assertThat(deliveriesOf(projectCreated()))
                .as("repeated closes of already-closed handles must not detach first or fourth")
                .containsExactly(r, "m1", "m2", "m3", r);

        first.close();
        first.close();
        assertThat(deliveriesOf(projectCreated())).containsExactly("m1", "m2", "m3", r);

        fourth.close();
        assertThat(deliveriesOf(projectCreated())).containsExactly("m1", "m2", "m3");
    }

    @Test
    void anObserverClosingAnEqualLaterSiblingStillReachesItInThatPublishButNotTheNext() {
        bus = new DefaultEventBus();
        AtomicReference<EventBus.Subscription> sibling = new AtomicReference<>();
        Consumer<MixerEvent> closer = event -> {
            deliveries.add("closer");
            sibling.get().close();
        };
        EventBus.Subscription first = bus.onPublication(MixerEvent.class, closer);
        bus.onPublication(MixerEvent.class, event -> deliveries.add("m"));
        sibling.set(bus.onPublication(MixerEvent.class, closer));

        // publish iterates the copy-on-write snapshot taken when it started, so the sibling closed
        // during this delivery still receives this event; its own repeated close is a no-op.
        assertThat(deliveriesOf(gainChanged())).containsExactly("closer", "m", "closer");

        // The next publish takes a fresh snapshot without the sibling; first is still registered.
        assertThat(deliveriesOf(gainChanged())).containsExactly("closer", "m");
        assertThat(deliveriesOf(gainChanged())).containsExactly("closer", "m");

        first.close();
        assertThat(deliveriesOf(gainChanged())).containsExactly("m");
    }

    @Test
    void manyThreadsClosingTheSameHandleRemoveExactlyOneRegistration() throws Exception {
        bus = new DefaultEventBus();
        bus.onPublication(ClipEvent.class, recorder);
        bus.onPublication(ClipEvent.class, event -> deliveries.add("m1"));
        EventBus.Subscription contested = bus.onPublication(ClipEvent.class, recorder);
        bus.onPublication(ClipEvent.class, event -> deliveries.add("m2"));
        bus.onPublication(ClipEvent.class, recorder);

        CountDownLatch ready = new CountDownLatch(CLOSING_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> closes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CLOSING_THREADS)) {
            for (int i = 0; i < CLOSING_THREADS; i++) {
                closes.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    contested.close();
                    return null;
                }));
            }
            try {
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
        }
        for (Future<?> close : closes) {
            close.get();
        }

        assertThat(deliveriesOf(clipAdded())).containsExactly("r:Added", "m1", "m2", "r:Added");
    }

    @Test
    void closingHandlesAfterTheBusClosesReturnsNormally() {
        bus = new DefaultEventBus();
        EventBus.Subscription open = bus.onPublication(ProjectEvent.Created.class, recorder);
        EventBus.Subscription closedEarly = bus.onPublication(ProjectEvent.Created.class, recorder);
        closedEarly.close();
        bus.close();

        assertThatCode(() -> {
            open.close();
            open.close();
            closedEarly.close();
        }).doesNotThrowAnyException();
        assertThat(deliveriesOf(projectCreated())).isEmpty();
        assertThatThrownBy(() -> bus.onPublication(ProjectEvent.Created.class, recorder))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void observersRunOnThePublishingThreadInRegistrationOrderPastALoggedFailure() {
        bus = new DefaultEventBus();
        Logger busLogger = Logger.getLogger(DefaultEventBus.class.getName());
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord logRecord) {
                if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(logRecord);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        List<Thread> observerThreads = new CopyOnWriteArrayList<>();
        bus.onPublication(DawEvent.class, event -> {
            deliveries.add("super");
            observerThreads.add(Thread.currentThread());
        });
        bus.onPublication(MixerEvent.GainChanged.class, event -> {
            throw new IllegalStateException("probe observer failure");
        });
        bus.onPublication(MixerEvent.GainChanged.class, event -> {
            deliveries.add("leaf");
            observerThreads.add(Thread.currentThread());
        });
        bus.onPublication(BusEvent.class, event -> deliveries.add("root"));

        busLogger.addHandler(capture);
        try {
            assertThat(deliveriesOf(gainChanged())).containsExactly("super", "leaf", "root");
        } finally {
            busLogger.removeHandler(capture);
        }

        assertThat(observerThreads).containsOnly(Thread.currentThread()).hasSize(2);
        assertThat(warnings).singleElement()
                .satisfies(warning -> assertThat(warning.getThrown())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("probe observer failure"));
    }
}
