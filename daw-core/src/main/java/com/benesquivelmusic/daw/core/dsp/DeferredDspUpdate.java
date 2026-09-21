package com.benesquivelmusic.daw.core.dsp;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

/** Coalesces control changes into a single state replacement prepared off the audio thread. */
final class DeferredDspUpdate implements AutoCloseable {
    private final Supplier<Runnable> preparation;
    private volatile long requestedRevision;
    private volatile long cancelledRevision = -1;
    private volatile long appliedRevision;
    private volatile Completion completion;
    private volatile boolean closed;
    private final Thread worker;

    private record Completion(long revision, Runnable update, RuntimeException failure) { }

    DeferredDspUpdate(Supplier<Runnable> preparation) {
        this.preparation = preparation;
        var reference = new WeakReference<>(this);
        // Virtual threads (JEP 444, final in Java 21) keep idle preparation watchers inexpensive.
        worker = Thread.ofVirtual().name("dsp-parameter-preparation").start(() -> watch(reference));
    }

    void request() {
        requestedRevision++;
    }

    void cancel() {
        cancelledRevision = ++requestedRevision;
        completion = null;
    }

    void apply() {
        if (closed) {
            return;
        }
        Completion prepared = completion;
        if (prepared != null && prepared.revision() == requestedRevision
                && prepared.revision() != cancelledRevision) {
            if (prepared.failure() != null) {
                throw prepared.failure();
            }
            completion = null;
            prepared.update().run();
            appliedRevision = prepared.revision();
        }
    }

    void await() {
        while (!closed && requestedRevision != appliedRevision
                && requestedRevision != cancelledRevision) {
            apply();
            if (requestedRevision == appliedRevision) {
                return;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while preparing DSP parameters", interrupted);
            }
        }
    }

    private static void watch(WeakReference<DeferredDspUpdate> reference) {
        long completedRevision = 0;
        while (!Thread.currentThread().isInterrupted()) {
            DeferredDspUpdate update = reference.get();
            if (update == null || update.closed) {
                return;
            }
            long revision = update.requestedRevision;
            if (revision != completedRevision && revision != update.cancelledRevision) {
                try {
                    Runnable prepared = update.preparation.get();
                    if (!update.closed && revision == update.requestedRevision
                            && revision != update.cancelledRevision) {
                        update.completion = new Completion(revision, prepared, null);
                        if (update.closed) {
                            update.completion = null;
                        }
                    }
                } catch (RuntimeException failure) {
                    if (!update.closed && revision == update.requestedRevision
                            && revision != update.cancelledRevision) {
                        update.completion = new Completion(revision, null, failure);
                        if (update.closed) {
                            update.completion = null;
                        }
                    }
                }
                completedRevision = revision;
            }
            update = null;
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
