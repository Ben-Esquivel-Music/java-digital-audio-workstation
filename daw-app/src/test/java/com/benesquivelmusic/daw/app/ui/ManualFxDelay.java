package com.benesquivelmusic.daw.app.ui;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link TransportController.FxDelay} a test fires by hand: it records each
 * action the controller schedules, and the delay it asked for, and runs none
 * of them until {@link #fireAll()}. So the still-writing warning a Stop
 * schedules ({@code TransportController.TAKE_STILL_WRITING_DELAY} after the
 * Stop) appears exactly when the test says, never because a slow disk made a
 * take outlast a wall-clock delay. Scheduled and fired on the FX thread; its
 * record of delays may be read from any thread.
 */
final class ManualFxDelay implements TransportController.FxDelay {

    private final List<Duration> delays = new CopyOnWriteArrayList<>();
    private final List<Runnable> pending = new CopyOnWriteArrayList<>();

    @Override
    public void after(Duration delay, Runnable action) {
        delays.add(delay);
        pending.add(action);
    }

    /** Every delay asked for so far, in order. */
    List<Duration> delays() {
        return List.copyOf(delays);
    }

    /** Runs every action scheduled and not yet run, in order. FX thread. */
    void fireAll() {
        List<Runnable> due = List.copyOf(pending);
        pending.clear();
        due.forEach(Runnable::run);
    }
}
