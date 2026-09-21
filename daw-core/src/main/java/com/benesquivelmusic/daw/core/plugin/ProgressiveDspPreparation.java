package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Prepares replacements off-thread while allowing continuously automated controls to progress. */
final class ProgressiveDspPreparation<T> implements AutoCloseable {
    private final Supplier<T> prepare;
    private final Consumer<T> install;
    private volatile long requestedRevision;
    private long appliedRevision;
    private volatile Completion<T> completion;
    private volatile boolean closed;
    private final Thread worker;

    private record Completion<T>(long revision, T state, RuntimeException failure) { }

    ProgressiveDspPreparation(Supplier<T> prepare, Consumer<T> install) {
        this.prepare = prepare;
        this.install = install;
        var reference = new WeakReference<>(this);
        // Virtual threads (JEP 444, final in Java 21) keep DSP preparation off the render thread.
        worker = Thread.ofVirtual().name("plugin-dsp-preparation").start(() -> watch(reference));
    }

    @RealTimeSafe
    void request() {
        requestedRevision++;
    }

    @RealTimeSafe
    void apply() {
        if (closed) return;
        Completion<T> ready = completion;
        if (ready != null && ready.revision() > appliedRevision) {
            if (ready.failure() != null) {
                if (ready.revision() == requestedRevision) throw ready.failure();
                return;
            }
            install.accept(ready.state());
            appliedRevision = ready.revision();
        }
    }

    void await() {
        long target = requestedRevision;
        while (!closed && appliedRevision < target) {
            apply();
            if (appliedRevision >= target) return;
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while preparing plugin parameters", interrupted);
            }
        }
    }

    private static <T> void watch(WeakReference<ProgressiveDspPreparation<T>> reference) {
        long completed = 0;
        while (!Thread.currentThread().isInterrupted()) {
            ProgressiveDspPreparation<T> preparation = reference.get();
            if (preparation == null || preparation.closed) return;
            long revision = preparation.requestedRevision;
            if (revision != completed) {
                Completion<T> ready;
                try {
                    ready = new Completion<>(revision, preparation.prepare.get(), null);
                } catch (RuntimeException failure) {
                    ready = new Completion<>(revision, null, failure);
                }
                // The sole worker publishes in revision order. Accepting a completed
                // revision even when a newer one is requested prevents ramp starvation.
                if (!preparation.closed) {
                    preparation.completion = ready;
                    if (preparation.closed) preparation.completion = null;
                }
                completed = revision;
            }
            preparation = null;
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        completion = null;
        worker.interrupt();
    }
}
