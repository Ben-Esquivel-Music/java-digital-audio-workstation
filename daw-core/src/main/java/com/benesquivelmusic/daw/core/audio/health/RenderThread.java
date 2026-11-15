package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Objects;

/**
 * A platform render thread that carries its {@link AudioHealthLane} (story
 * 337, ruling E20): the engine builds its render drive as one, and the
 * multi-core worker pool builds each worker as one.
 *
 * <p>Render code with no engine context — a DSP processor, the CLAP host, the
 * automation binder — reaches its lane through the current thread with one
 * type test ({@link #recordOnCurrentThread}), with no map, no
 * {@code ThreadLocal}, no static registry and no allocation. The same test
 * tells deep code that it is NOT on a render thread: an offline export,
 * bounce or freeze runs on an ordinary thread and records nothing here
 * (failure-surfacing book §2.7 — the lane is injected through the thread the
 * engine built, never defaulted).</p>
 *
 * <p>A daemon at {@link Thread#MAX_PRIORITY}, like every render thread this
 * engine runs. It installs no uncaught-exception handler of its own.</p>
 */
public final class RenderThread extends Thread {

    private final AudioHealthLane lane;

    /**
     * Creates an unstarted render thread.
     *
     * @param lane the lane this thread owns — its only writer; never {@code null}
     * @param body what the thread runs; never {@code null}
     * @param name the thread's name; never {@code null}
     */
    public RenderThread(AudioHealthLane lane, Runnable body, String name) {
        Objects.requireNonNull(lane, "lane must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(name, "name must not be null");
        this.lane = lane;
        super(body, name);
        setDaemon(true);
        setPriority(Thread.MAX_PRIORITY);
    }

    /**
     * Returns the lane this thread owns.
     *
     * @return the lane; never {@code null}
     */
    @RealTimeSafe
    public AudioHealthLane lane() {
        return lane;
    }

    /**
     * Records a failure into the current thread's lane when the current thread
     * is a render thread (story 337, ruling E20).
     *
     * @param category an {@link AudioFailureCodes} constant
     * @param failure  the thrown object, or {@code null}
     * @param subject  the site's subject, or {@code null}
     * @param detail   a site-specific number
     * @return {@code true} when recorded; {@code false} on a thread with no
     *         lane (offline export, bounce, freeze, tests), where nothing is
     *         recorded
     */
    @RealTimeSafe
    public static boolean recordOnCurrentThread(int category, Throwable failure,
                                                Object subject, int detail) {
        if (Thread.currentThread() instanceof RenderThread thread) {
            thread.lane.record(category, failure, subject, detail);
            return true;
        }
        return false;
    }
}
