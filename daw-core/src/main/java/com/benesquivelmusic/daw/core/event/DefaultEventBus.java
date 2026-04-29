package com.benesquivelmusic.daw.core.event;

import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.EventBusMetrics;
import com.benesquivelmusic.daw.sdk.event.OverflowStrategy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default in-process implementation of {@link EventBus}.
 *
 * <p>Each subscription owns its own bounded buffer (a
 * {@link java.util.ArrayDeque}) and a serial worker that drains the
 * buffer to the downstream subscriber. {@link #publish} fans the event
 * out to every subscription whose declared type is assignable from the
 * event's runtime type.</p>
 *
 * <p>{@link OverflowStrategy} is applied per-subscription at enqueue
 * time:</p>
 * <ul>
 *   <li>{@link OverflowStrategy#DROP_OLDEST} discards the head of the
 *       deque to make room for the new event (the publisher never
 *       blocks).</li>
 *   <li>{@link OverflowStrategy#BLOCK} blocks the publishing thread on
 *       a {@code Condition} until buffer space is available.</li>
 * </ul>
 *
 * <p>{@link DispatchMode} is applied per-subscription at delivery time:
 * the worker either delivers inline (caller thread relative to the
 * dispatcher), via the configured UI executor (typically
 * {@code Platform::runLater}), or on a fresh virtual thread.</p>
 *
 * <p>This class is thread-safe; {@link #publish} may be called from any
 * thread including the audio thread.</p>
 */
public final class DefaultEventBus implements EventBus {

    private static final Logger LOG = Logger.getLogger(DefaultEventBus.class.getName());

    private final int bufferCapacity;
    private final Executor uiExecutor;
    private final Map<Class<? extends DawEvent>, OverflowStrategy> strategyByType;
    private final OverflowStrategy defaultStrategy;
    private final ExecutorService dispatcher;
    private final List<Sub<?>> subs = new CopyOnWriteArrayList<>();
    private final Metrics metrics = new Metrics();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Builder for {@link DefaultEventBus}. */
    public static final class Builder {
        private int bufferCapacity = DEFAULT_BUFFER_CAPACITY;
        private Executor uiExecutor;
        private OverflowStrategy defaultStrategy = OverflowStrategy.DROP_OLDEST;
        private final Map<Class<? extends DawEvent>, OverflowStrategy> strategies = new HashMap<>();

        /** Sets the per-subscription bounded-buffer capacity (default {@value #DEFAULT_BUFFER_CAPACITY}). */
        public Builder bufferCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("bufferCapacity must be > 0");
            }
            this.bufferCapacity = capacity;
            return this;
        }

        /**
         * Sets the executor used by {@link DispatchMode#ON_UI_THREAD} subscriptions.
         * Typical wiring in JavaFX code is {@code uiExecutor(Platform::runLater)}.
         */
        public Builder uiExecutor(Executor executor) {
            this.uiExecutor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** Sets the default overflow strategy used when no per-type override is registered. */
        public Builder defaultOverflowStrategy(OverflowStrategy strategy) {
            this.defaultStrategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        /** Registers a per-event-type overflow strategy override. */
        public Builder overflowStrategy(Class<? extends DawEvent> type, OverflowStrategy strategy) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(strategy, "strategy");
            strategies.put(type, strategy);
            return this;
        }

        public DefaultEventBus build() {
            return new DefaultEventBus(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private DefaultEventBus(Builder b) {
        this.bufferCapacity = b.bufferCapacity;
        this.uiExecutor = b.uiExecutor;
        this.defaultStrategy = b.defaultStrategy;
        this.strategyByType = Map.copyOf(b.strategies);
        // One thread-per-subscription worker; daemon so it does not block JVM shutdown.
        this.dispatcher = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "daw-event-bus-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Convenience constructor — default buffer, DROP_OLDEST default, no UI executor. */
    public DefaultEventBus() {
        this(builder());
    }

    @Override
    public void publish(DawEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            return;
        }
        metrics.recordPublished(event.getClass());
        OverflowStrategy strategy = strategyFor(event.getClass());
        for (Sub<?> sub : subs) {
            sub.tryEnqueue(event, strategy);
        }
    }

    private OverflowStrategy strategyFor(Class<?> type) {
        Class<?> c = type;
        while (c != null) {
            OverflowStrategy s = strategyByType.get(c);
            if (s != null) {
                return s;
            }
            for (Class<?> i : c.getInterfaces()) {
                OverflowStrategy si = strategyByType.get(i);
                if (si != null) {
                    return si;
                }
            }
            c = c.getSuperclass();
        }
        return defaultStrategy;
    }

    @Override
    public <E extends DawEvent> Flow.Publisher<E> subscribe(Class<E> type) {
        Objects.requireNonNull(type, "type");
        return downstream -> attachFlowSubscriber(type, downstream);
    }

    private <E extends DawEvent> void attachFlowSubscriber(Class<E> type,
                                                           Flow.Subscriber<? super E> downstream) {
        Objects.requireNonNull(downstream, "subscriber");
        Consumer<E> bridge = ev -> {
            try {
                downstream.onNext(ev);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Subscriber threw from onNext", t);
            }
        };
        Sub<E> sub = new Sub<>(type, bufferCapacity, DispatchMode.ON_CALLER_THREAD,
                uiExecutor, dispatcher, metrics, bridge);
        subs.add(sub);
        downstream.onSubscribe(new Flow.Subscription() {
            @Override public void request(long n) { /* unbounded; bus enforces back-pressure */ }
            @Override public void cancel() { sub.cancel(); subs.remove(sub); }
        });
        sub.start();
    }

    @Override
    public <E extends DawEvent> Subscription on(Class<E> type,
                                                DispatchMode mode,
                                                Consumer<? super E> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(handler, "handler");
        if (mode == DispatchMode.ON_UI_THREAD && uiExecutor == null) {
            throw new IllegalStateException(
                    "DispatchMode.ON_UI_THREAD requires uiExecutor on the EventBus builder");
        }
        Sub<E> sub = new Sub<>(type, bufferCapacity, mode, uiExecutor, dispatcher, metrics, handler);
        subs.add(sub);
        sub.start();
        return () -> {
            sub.cancel();
            subs.remove(sub);
        };
    }

    @Override
    public EventBusMetrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Sub<?> s : subs) {
                s.cancel();
            }
            subs.clear();
            dispatcher.shutdownNow();
        }
    }

    // Visible for tests.
    int subscriptionCount() {
        return subs.size();
    }

    // -----------------------------------------------------------------
    // Per-subscription worker
    // -----------------------------------------------------------------

    private static final class Sub<E extends DawEvent> {
        private final Class<E> type;
        private final int capacity;
        private final DispatchMode mode;
        private final Executor uiExecutor;
        private final ExecutorService dispatcher;
        private final Metrics metrics;
        @SuppressWarnings("rawtypes")
        private final Consumer handler;
        private final Deque<DawEvent> buffer = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final java.util.concurrent.locks.Condition notFull = lock.newCondition();
        private final java.util.concurrent.locks.Condition notEmpty = lock.newCondition();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final String id;

        @SuppressWarnings({"rawtypes", "unchecked"})
        Sub(Class<E> type, int capacity, DispatchMode mode,
            Executor uiExecutor, ExecutorService dispatcher, Metrics metrics,
            Consumer handler) {
            this.type = type;
            this.capacity = capacity;
            this.mode = mode;
            this.uiExecutor = uiExecutor;
            this.dispatcher = dispatcher;
            this.metrics = metrics;
            this.handler = handler;
            this.id = type.getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " [" + mode + "]";
            metrics.registerSubscription(id);
        }

        void start() {
            dispatcher.execute(this::runLoop);
        }

        void cancel() {
            cancelled.set(true);
            lock.lock();
            try {
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void tryEnqueue(DawEvent event, OverflowStrategy strategy) {
            if (cancelled.get() || !type.isInstance(event)) {
                return;
            }
            lock.lock();
            try {
                if (buffer.size() >= capacity) {
                    if (strategy == OverflowStrategy.DROP_OLDEST) {
                        buffer.pollFirst();
                        metrics.recordDropped(event.getClass());
                    } else {
                        // BLOCK
                        while (buffer.size() >= capacity && !cancelled.get()) {
                            notFull.awaitUninterruptibly();
                        }
                        if (cancelled.get()) return;
                    }
                }
                buffer.addLast(event);
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        private void runLoop() {
            while (!cancelled.get()) {
                DawEvent next;
                lock.lock();
                try {
                    while (buffer.isEmpty() && !cancelled.get()) {
                        notEmpty.awaitUninterruptibly();
                    }
                    if (cancelled.get()) return;
                    next = buffer.pollFirst();
                    notFull.signal();
                } finally {
                    lock.unlock();
                }
                final E typed = (E) next;
                Runnable deliver = () -> {
                    long start = System.nanoTime();
                    try {
                        handler.accept(typed);
                    } catch (Throwable t) {
                        LOG.log(Level.WARNING, "Subscriber " + id + " threw", t);
                    } finally {
                        metrics.recordDispatch(id, System.nanoTime() - start);
                    }
                };
                switch (mode) {
                    case ON_CALLER_THREAD -> deliver.run();
                    case ON_UI_THREAD -> uiExecutor.execute(deliver);
                    case ON_VIRTUAL_THREAD -> Thread.ofVirtual().start(deliver);
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------

    static final class Metrics implements EventBusMetrics {
        private final ConcurrentHashMap<String, AtomicLong> published = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> dropped = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, DispatchStats> dispatch = new ConcurrentHashMap<>();

        void registerSubscription(String id) {
            dispatch.putIfAbsent(id, new DispatchStats());
        }

        void recordPublished(Class<?> type) {
            published.computeIfAbsent(typeKey(type), k -> new AtomicLong()).incrementAndGet();
        }

        void recordDropped(Class<?> type) {
            dropped.computeIfAbsent(typeKey(type), k -> new AtomicLong()).incrementAndGet();
        }

        void recordDispatch(String id, long elapsedNanos) {
            DispatchStats s = dispatch.get(id);
            if (s != null) {
                s.add(elapsedNanos);
            }
        }

        private static String typeKey(Class<?> type) {
            Class<?> enclosing = type.getEnclosingClass();
            if (enclosing != null) {
                return enclosing.getSimpleName() + "." + type.getSimpleName();
            }
            return type.getSimpleName();
        }

        @Override
        public Map<String, Long> publishedByType() {
            return snapshot(published);
        }

        @Override
        public Map<String, Long> droppedByType() {
            return snapshot(dropped);
        }

        @Override
        public Iterable<String> slowSubscribers() {
            List<String> result = new ArrayList<>();
            dispatch.forEach((id, stats) -> {
                long count = stats.count.get();
                if (count == 0) return;
                long avg = stats.totalNanos.get() / count;
                if (avg > SLOW_SUBSCRIBER_THRESHOLD_NANOS) {
                    result.add(id + " avg=" + (avg / 1_000) + "us count=" + count);
                }
            });
            return Collections.unmodifiableList(result);
        }

        private static Map<String, Long> snapshot(Map<String, AtomicLong> source) {
            Map<String, Long> out = new LinkedHashMap<>();
            source.forEach((k, v) -> out.put(k, v.get()));
            return Collections.unmodifiableMap(out);
        }

        private static final class DispatchStats {
            final AtomicLong count = new AtomicLong();
            final AtomicLong totalNanos = new AtomicLong();
            void add(long nanos) {
                count.incrementAndGet();
                totalNanos.addAndGet(nanos);
            }
        }
    }
}
