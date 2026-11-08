package com.benesquivelmusic.daw.app.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link CloseTimers} that fire only when a test says so (story 333): every
 * task armed is recorded with its delay, and {@link #fire(Duration)} runs,
 * on the calling thread — the FX thread, where a timer task runs — every
 * task of that delay still pending and not cancelled, once. No test sleeps
 * for a threshold.
 */
final class ManualCloseTimers implements CloseTimers {

    /** One armed task. */
    static final class Armed implements Cancellable {
        private final Duration delay;
        private final Runnable task;
        private volatile boolean cancelled;
        private volatile boolean fired;

        private Armed(Duration delay, Runnable task) {
            this.delay = delay;
            this.task = task;
        }

        /** The delay it was armed with. */
        Duration delay() {
            return delay;
        }

        /** Whether it was cancelled before it fired. */
        boolean cancelled() {
            return cancelled;
        }

        /** Whether it fired. */
        boolean fired() {
            return fired;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final List<Armed> armed = new CopyOnWriteArrayList<>();

    /** Run with each delay as it is armed, before the task is recorded: a test makes an arming throw. */
    volatile java.util.function.Consumer<Duration> onArm = _ -> { };

    @Override
    public Cancellable after(Duration delay, Runnable onFx) {
        onArm.accept(delay);
        Armed timer = new Armed(Objects.requireNonNull(delay, "delay must not be null"),
                Objects.requireNonNull(onFx, "onFx must not be null"));
        armed.add(timer);
        return timer;
    }

    /**
     * Runs every pending task armed with {@code delay}, on the calling thread
     * (call it on the FX thread), each once.
     *
     * @param delay the delay whose tasks are due
     * @return how many ran
     */
    int fire(Duration delay) {
        List<Armed> due = new ArrayList<>();
        for (Armed timer : armed) {
            if (timer.delay.equals(delay) && !timer.cancelled && !timer.fired) {
                due.add(timer);
            }
        }
        for (Armed timer : due) {
            timer.fired = true;
            timer.task.run();
        }
        return due.size();
    }

    /**
     * Runs {@code timer} alone, on the calling thread (call it on the FX
     * thread), when it is still pending: a test fires one of two tasks armed
     * with the same delay — the wait clock's note before the detach's.
     *
     * @param timer a task this clock armed
     * @return whether it ran
     */
    boolean fire(Armed timer) {
        if (!armed.contains(timer) || timer.cancelled || timer.fired) {
            return false;
        }
        timer.fired = true;
        timer.task.run();
        return true;
    }

    /**
     * Runs {@code timer}'s task whether or not it was cancelled or fired, on
     * the calling thread (call it on the FX thread): a timer whose cancel lost
     * a race with its firing — the case a one-shot wait must survive (story
     * 333, the feasibility critique's S8: a timer that fires after the wait
     * ended does nothing).
     *
     * @param timer a task this clock armed
     */
    void runAnyway(Armed timer) {
        if (!armed.contains(timer)) {
            throw new IllegalArgumentException("not armed by this clock");
        }
        timer.fired = true;
        timer.task.run();
    }

    /** Every task armed so far, oldest first. */
    List<Armed> armed() {
        return List.copyOf(armed);
    }

    /** The tasks armed with {@code delay} that neither fired nor were cancelled. */
    List<Armed> pending(Duration delay) {
        return armed.stream().filter(timer -> timer.delay.equals(delay) && !timer.cancelled && !timer.fired)
                .toList();
    }
}
