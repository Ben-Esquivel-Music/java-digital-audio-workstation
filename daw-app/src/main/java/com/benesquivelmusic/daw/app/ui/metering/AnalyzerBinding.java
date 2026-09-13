package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.analysis.AnalyzerProcessor;
import com.benesquivelmusic.daw.core.analysis.AnalyzerSnapshot;
import com.benesquivelmusic.daw.core.metering.AnalysisSubscription;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;

import javafx.scene.Node;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One surface, tap, coalescing key and generation. All lifecycle work is FX-owned. */
public final class AnalyzerBinding implements AutoCloseable {
    private final MeteringTapBus bus;
    private final FxDispatcher dispatcher;
    private final Supplier<MeterTapPoint> point;
    private final BooleanSupplier visible;
    private final Function<Consumer<AnalyzerSnapshot>, AnalyzerProcessor> factory;
    private final Consumer<AnalyzerSnapshot> sink;
    private final LongSupplier clock;
    private final Object key = new Object();
    private final Runnable removePulse;
    private AnalysisSubscription subscription;
    private MeterTapPoint attachedPoint;
    private volatile long generation;
    private java.util.concurrent.atomic.AtomicLong lastFrame = new java.util.concurrent.atomic.AtomicLong();
    private LongSupplier configurationRevision = () -> 0;
    private long attachedRevision;
    private boolean idle = true;
    private volatile boolean closed;
    private long dropped;

    public AnalyzerBinding(MeteringTapBus bus, FxDispatcher dispatcher,
            Supplier<MeterTapPoint> point, BooleanSupplier visible,
            Function<Consumer<AnalyzerSnapshot>, AnalyzerProcessor> factory,
            Consumer<AnalyzerSnapshot> sink) {
        this(bus, dispatcher, point, visible, factory, sink, System::nanoTime);
    }

    public AnalyzerBinding(MeteringTapBus bus, FxDispatcher dispatcher,
            Supplier<MeterTapPoint> point, BooleanSupplier visible,
            Function<Consumer<AnalyzerSnapshot>, AnalyzerProcessor> factory,
            Consumer<AnalyzerSnapshot> sink, LongSupplier clock) {
        this.bus = Objects.requireNonNull(bus);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.point = Objects.requireNonNull(point);
        this.visible = Objects.requireNonNull(visible);
        this.factory = Objects.requireNonNull(factory);
        this.sink = Objects.requireNonNull(sink);
        this.clock = Objects.requireNonNull(clock);
        removePulse = dispatcher.addPulseParticipant(this::pulse);
        sink.accept(null);
    }

    public AnalyzerBinding withConfigurationRevision(LongSupplier revision) {
        configurationRevision = Objects.requireNonNull(revision);
        return this;
    }

    /** Visibility includes every ancestor and the floating window's showing state. */
    public static boolean isShowing(Node node) {
        if (node.getScene() == null || node.getScene().getWindow() == null
                || !node.getScene().getWindow().isShowing()) return false;
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
            if (!ancestor.isVisible()) return false;
        }
        return true;
    }

    private void pulse() {
        if (closed) return;
        MeterTapPoint desired = visible.getAsBoolean() && bus.isBound() && !bus.isClosed()
                ? point.get() : null;
        if (subscription != null && (subscription.isDisposed()
                || !Objects.equals(attachedPoint, desired)
                || attachedRevision != configurationRevision.getAsLong())) detach();
        if (subscription == null && desired != null) {
            long epoch = ++generation;
            attachedPoint = desired;
            attachedRevision = configurationRevision.getAsLong();
            var heartbeat = new java.util.concurrent.atomic.AtomicLong(clock.getAsLong());
            lastFrame = heartbeat;
            var processor = factory.apply(snapshot -> {
                if (closed || generation != epoch) return;
                long arrival = clock.getAsLong();
                heartbeat.set(arrival);
                dispatcher.onFx(key, () -> {
                    if (!closed && generation == epoch && subscription != null
                            && !subscription.isDisposed() && visible.getAsBoolean()
                            && clock.getAsLong() - arrival < AnalyzerProcessor.IDLE_NANOS) {
                        idle = false;
                        sink.accept(snapshot);
                    }
                });
            });
            subscription = bus.attachAnalysis(desired, 16, processor);
            subscription.onDisposed(processor::close);
        }
        if (subscription != null) {
            dropped = subscription.droppedBlocks();
            if (!idle && clock.getAsLong() - lastFrame.get() >= AnalyzerProcessor.IDLE_NANOS) {
                idle = true;
                sink.accept(null);
            }
        }
    }

    private void detach() {
        generation++;
        dispatcher.cancelKey(key);
        if (subscription != null) subscription.dispose();
        subscription = null;
        attachedPoint = null;
        idle = true;
        sink.accept(null);
    }

    public long droppedBlocks() { return dropped; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        removePulse.run();
        detach();
    }
}
