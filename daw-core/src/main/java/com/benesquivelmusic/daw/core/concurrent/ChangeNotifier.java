package com.benesquivelmusic.daw.core.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A small, allocation-free-on-notify observer list for the toolkit-neutral
 * {@code Consumer<ChangeKind>} change signal that the engine models expose
 * ({@code Track} / {@code MixerChannel} / {@code Transport} / {@code DawProject},
 * Control Synchronization Design Book §3.2, §3.4). It is the single home for the
 * register / unregister / notify mechanism each of those models used to copy
 * verbatim.
 *
 * <h2>Why a fresh-snapshot array</h2>
 *
 * <p>Registration mutates a canonical {@link #listeners} list under
 * {@link #lock}; each mutation rebuilds an immutable {@link #snapshot} array and
 * publishes it through a {@code volatile} field. {@link #fire(Object)} reads that
 * reference <em>once</em> into a local and iterates the array with a
 * for-each — so the hot notify path takes no lock, allocates nothing (an array
 * for-each compiles to an indexed loop, with no {@code Iterator}), and is immune
 * to a listener being added or removed mid-notification (reentrantly, or from
 * another thread): the captured array reference stays stable while an add/remove
 * swaps in a fresh array. This makes {@code fire} safe to call from a
 * {@code @RealTimeSafe} notify path (it is intentionally <em>not</em> annotated
 * {@code @RealTimeSafe} itself, matching the un-annotated model
 * {@code notifyChange} methods that delegate to it).</p>
 *
 * <p>{@code fire} invokes each listener on the calling thread; a listener must do
 * only lock-free, non-blocking work (the sanctioned sink is the view-model's
 * lock-free single-reader buffer, §4.1, §4.6). Exceptions from a listener
 * propagate to the caller — listeners are trusted, in-process UI adapters.</p>
 *
 * @param <K> the change-tag type (each model's {@code ChangeKind} enum)
 */
public final class ChangeNotifier<K> {

    /** Guards mutations to {@link #listeners}; never held on the {@link #fire} path. */
    private final Object lock = new Object();

    /**
     * The canonical observer list, mutated only under {@link #lock}.
     * {@link #snapshot} is rebuilt from it on each add/remove so the hot notify
     * path never touches this list.
     */
    private final List<Consumer<K>> listeners = new ArrayList<>();

    /**
     * An immutable snapshot of {@link #listeners}, replaced wholesale (never
     * mutated in place) on each add/remove and published through this
     * {@code volatile} reference so {@link #fire} can read it lock-free.
     */
    @SuppressWarnings("unchecked")
    private volatile Consumer<K>[] snapshot = (Consumer<K>[]) new Consumer<?>[0];

    /**
     * Registers a listener notified by every later {@link #fire(Object)}.
     *
     * @param listener the listener to add; must not be {@code null}
     * @return a removal token; running it (equivalently, calling
     *         {@link #remove(Consumer)}) unregisters the listener
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public Runnable add(Consumer<K> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        synchronized (lock) {
            listeners.add(listener);
            snapshot = snapshotOf(listeners);
        }
        return () -> remove(listener);
    }

    /**
     * Unregisters a previously {@linkplain #add(Consumer) added} listener. A
     * listener that was never added (or was already removed) is silently ignored.
     *
     * @param listener the listener to remove; must not be {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public void remove(Consumer<K> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        synchronized (lock) {
            if (listeners.remove(listener)) {
                snapshot = snapshotOf(listeners);
            }
        }
    }

    /**
     * Notifies every registered listener of {@code change}. Reads the
     * {@code volatile} snapshot once into a stable local and iterates it, so the
     * call takes no lock, allocates nothing, and tolerates a concurrent/reentrant
     * add or remove.
     *
     * @param change the change tag to deliver
     */
    public void fire(K change) {
        Consumer<K>[] current = snapshot; // read volatile once → stable local
        for (Consumer<K> listener : current) { // array for-each allocates no iterator
            listener.accept(change);
        }
    }

    /** Builds a fresh immutable snapshot array from the canonical listener list. */
    @SuppressWarnings("unchecked")
    private static <K> Consumer<K>[] snapshotOf(List<Consumer<K>> listeners) {
        return listeners.toArray((Consumer<K>[]) new Consumer<?>[listeners.size()]);
    }
}
