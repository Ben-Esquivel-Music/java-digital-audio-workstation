package com.benesquivelmusic.daw.core.persistence;

/**
 * Receives what a {@link RehydrationSession} reports (Persistence Integrity
 * book §4.3, §6.1). Free of any UI toolkit: the application marshals what it
 * receives to its owner thread and applies it there.
 *
 * <p><strong>Threading.</strong> Every method is called on a decode thread,
 * never on the owner thread — possibly before {@link ClipRehydrator#start}
 * has returned the session — and calls for different sources may arrive
 * concurrently. An implementation must not block and must not touch the
 * project: it queues what it receives and returns. An exception it throws
 * is logged and contained.</p>
 *
 * <p><strong>Order.</strong> {@link #onOutcome} is called exactly once per
 * source; {@link #onFinished} exactly once, after every
 * {@code onOutcome} call has returned. After
 * {@link RehydrationSession#cancel()} nothing more is called — best-effort:
 * a call already under way completes, so the owner thread's own fence
 * decides whether to apply what arrives.</p>
 */
public interface RehydrationListener {

    /**
     * One source's outcome.
     *
     * @param outcome the outcome; apply it on the owner thread with
     *                {@link RehydrationSession#apply(RehydrationOutcome)}
     */
    void onOutcome(RehydrationOutcome outcome);

    /**
     * The run is over: every source's outcome was reported.
     *
     * @param summary how the run went
     */
    void onFinished(Summary summary);

    /**
     * How a run went.
     *
     * @param sources the number of sources the run decoded
     * @param decoded how many decoded
     * @param failed  how many failed
     */
    record Summary(int sources, int decoded, int failed) {
    }
}
