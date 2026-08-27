package com.benesquivelmusic.daw.core.metering;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A consumer's attachment token on the {@link MeteringTapBus} (book
 * &sect;3.5, &sect;6.2). Attachment is an off-RT act; the token records the
 * binding epoch it was created under and is disposed either explicitly
 * ({@link #dispose()} — editor close, dock hide) or by the bus when a project
 * rebind moves to a newer epoch, when the engine unbinds, or when the bus
 * closes.
 *
 * <p>{@link #onDisposed(Runnable)} callbacks run on the thread that disposed
 * the token, <strong>after</strong> the bus has released its registry lock —
 * never while it is held — so a callback may freely call back into the bus,
 * the binder or the application. A callback registered on an already-disposed
 * token runs immediately.</p>
 */
public sealed abstract class TapSubscription
        permits LevelSubscription, InsertIoSubscription, AnalysisSubscription {

    private final MeteringTapBus bus;
    private final MeterTapPoint point;
    private final long epoch;
    private final Object callbackLock = new Object();
    private final List<Runnable> disposedCallbacks = new ArrayList<>(2);
    private volatile boolean disposed;

    TapSubscription(MeteringTapBus bus, MeterTapPoint point, long epoch) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.point = Objects.requireNonNull(point, "point must not be null");
        this.epoch = epoch;
    }

    /** The tap point this token is attached to. */
    public final MeterTapPoint point() {
        return point;
    }

    /** The bus binding epoch this token was created under. */
    public final long epoch() {
        return epoch;
    }

    /** {@code true} once disposed, explicitly or by the bus. */
    public final boolean isDisposed() {
        return disposed;
    }

    /**
     * Registers a callback to run once when this token is disposed (runs
     * immediately if it already is). Callbacks run after the bus's registry
     * lock is released.
     */
    public final void onDisposed(Runnable callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        boolean runNow;
        synchronized (callbackLock) {
            runNow = disposed;
            if (!runNow) {
                disposedCallbacks.add(callback);
            }
        }
        if (runNow) {
            callback.run();
        }
    }

    /** Detaches this token from the bus. Idempotent. */
    public final void dispose() {
        bus.detach(this);
    }

    final MeteringTapBus bus() {
        return bus;
    }

    /**
     * Marks the token disposed and hands back the callbacks to fire — the bus
     * calls this under its registry lock and runs the callbacks after
     * releasing it. Returns an empty list on a repeat call.
     */
    final List<Runnable> markDisposed() {
        synchronized (callbackLock) {
            if (disposed) {
                return List.of();
            }
            disposed = true;
            List<Runnable> pending = List.copyOf(disposedCallbacks);
            disposedCallbacks.clear();
            return pending;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + point + ", epoch=" + epoch
                + (disposed ? ", disposed" : "") + "]";
    }
}
