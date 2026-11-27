package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Closes the window's replaced CPU budget enforcers only once the render
 * thread can no longer be using them (story 338, ruling E203), so every
 * marking an old enforcer makes, its last block's included, is offered to its
 * xrun log listener, which stays subscribed until that close.
 *
 * <p>{@link #retire} detaches the engine's enforcer, then reads the engine's
 * blocks begun ({@link AudioEngine#blocksBegun()}) and then its blocks ended
 * (the {@linkplain MeteringTapBus#blockIndex() metering block index}). A
 * block counts itself begun before it reads the enforcer and ended after its
 * last use of it, so equal counts mean no block can still be using the old
 * enforcer: it is closed at once. Otherwise it waits, with the two counts, and
 * the first pulse that finds either count changed closes it: the block in
 * flight has ended, and every later block read the engine's enforcer after
 * the detach. The engine builds a render drive only after the previous
 * drive's thread has exited, so the two counts are one render thread's. A close first
 * asks the listener to hand over a count that is due
 * ({@link ShedLogSubscriber#handOverAtTheRead()}), and closes the enforcer
 * even when that hand-over throws; the close then delivers what the listener
 * had queued, and its completion hands over the rest. While enforcers wait,
 * the window's read of the log asks their listeners too
 * ({@link #handOverAtTheRead()}), so a count does not wait for a close while
 * no stream runs. A pulse reads the engine's two counts only while an
 * enforcer waits, and never waits for the block in flight to end: a close
 * takes the listener's monitor and the publisher's lock, each briefly, the
 * latter held by the render thread only while it offers a marking.</p>
 *
 * <p>Accepted: the window's disposal closes every waiting enforcer at once
 * ({@link #closeAll()}), and a replacement after it closes at once, while the
 * engine may still render a block with one, and nobody reads the log after
 * it; a block that never ends keeps its enforcer open until that
 * disposal.</p>
 *
 * <p>With no window pulse, in a pure-unit context, {@link #retire} closes at
 * once. FX thread.</p>
 */
final class CpuBudgetEnforcerRetirer {

    private final Runnable detach;
    private final LongSupplier blocksBegun;
    private final LongSupplier blocksEnded;
    private final boolean pulsed;
    /** Set by the window's disposal ({@link #closeAll()}), after which no pulse runs. */
    private boolean disposed;
    /** The enforcers waiting for the block in flight at their retirement, oldest first. */
    private final Deque<Retiring> waiting = new ArrayDeque<>();

    /** A retired enforcer, its log listener or {@code null}, and the engine's counts read at its retirement. */
    private record Retiring(TrackCpuBudgetEnforcer enforcer, ShedLogSubscriber listener, long begun, long ended) {
    }

    /**
     * A retirer over the engine's detach and counts.
     *
     * @param detach      removes the engine's CPU budget enforcer
     * @param blocksBegun the engine's blocks begun
     * @param blocksEnded the engine's blocks ended, counted after each block's last use of the enforcer
     * @param pulsed      whether {@link #pulse()} is registered on the window's dispatcher; without it a
     *                    replaced enforcer is closed at once
     */
    CpuBudgetEnforcerRetirer(Runnable detach, LongSupplier blocksBegun, LongSupplier blocksEnded, boolean pulsed) {
        this.detach = Objects.requireNonNull(detach, "detach must not be null");
        this.blocksBegun = Objects.requireNonNull(blocksBegun, "blocksBegun must not be null");
        this.blocksEnded = Objects.requireNonNull(blocksEnded, "blocksEnded must not be null");
        this.pulsed = pulsed;
    }

    /**
     * The window's retirer: it detaches {@code engine}'s enforcer and reads its
     * blocks begun and its metering block index.
     *
     * @param engine the window's engine
     * @param pulsed whether the caller registers {@link #pulse()} on the window's dispatcher
     * @return the retirer
     */
    static CpuBudgetEnforcerRetirer forEngine(AudioEngine engine, boolean pulsed) {
        Objects.requireNonNull(engine, "engine must not be null");
        MeteringTapBus bus = engine.meteringTapBus();
        return new CpuBudgetEnforcerRetirer(() -> engine.setCpuBudgetEnforcer(null), engine::blocksBegun,
                bus::blockIndex, pulsed);
    }

    /**
     * Retires the window's replaced enforcer: detaches the engine's enforcer,
     * then closes {@code enforcer} at once when no block is in flight or no
     * pulse runs, the window's disposal included, and otherwise once the
     * block in flight has ended.
     *
     * @param enforcer the replaced enforcer, the engine's until now
     * @param listener its xrun log listener, or {@code null}
     */
    void retire(TrackCpuBudgetEnforcer enforcer, ShedLogSubscriber listener) {
        Objects.requireNonNull(enforcer, "enforcer must not be null");
        detach.run();
        long begun = blocksBegun.getAsLong();
        long ended = blocksEnded.getAsLong();
        if (!pulsed || disposed || begun == ended) {
            close(enforcer, listener);
        } else {
            waiting.addLast(new Retiring(enforcer, listener, begun, ended));
        }
    }

    /**
     * One window pulse: closes, oldest first, each waiting enforcer whose
     * block in flight has ended. A later retirement read counts no lower, so
     * the first one still waiting stops the walk. A listener's hand-over that
     * throws leaves this pulse once its enforcer is closed; the others whose
     * block has ended are closed at the next pulse.
     */
    void pulse() {
        if (waiting.isEmpty()) {
            return;
        }
        long begun = blocksBegun.getAsLong();
        long ended = blocksEnded.getAsLong();
        for (Retiring oldest = waiting.peekFirst();
             oldest != null && (begun != oldest.begun() || ended != oldest.ended());
             oldest = waiting.peekFirst()) {
            waiting.removeFirst();
            close(oldest.enforcer(), oldest.listener());
        }
    }

    /** The window's read of the log: each waiting enforcer's listener hands over a count that is due. */
    void handOverAtTheRead() {
        for (Retiring retiring : waiting) {
            if (retiring.listener() != null) {
                retiring.listener().handOverAtTheRead();
            }
        }
    }

    /**
     * The window's disposal: closes every waiting enforcer at once, without asking its listener for a
     * hand-over first, since nobody reads the log after the disposal and the close's completion hands the
     * listener's count over all the same; no pulse runs after it, so a later {@link #retire} closes at once.
     */
    void closeAll() {
        disposed = true;
        for (Retiring retiring = waiting.pollFirst(); retiring != null; retiring = waiting.pollFirst()) {
            retiring.enforcer().close();
        }
    }

    private static void close(TrackCpuBudgetEnforcer enforcer, ShedLogSubscriber listener) {
        try {
            if (listener != null) {
                listener.handOverAtTheRead();
            }
        } finally {
            enforcer.close();
        }
    }
}
