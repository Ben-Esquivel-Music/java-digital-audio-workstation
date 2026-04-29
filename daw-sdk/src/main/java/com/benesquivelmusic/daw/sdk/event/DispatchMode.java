package com.benesquivelmusic.daw.sdk.event;

/**
 * Per-subscription dispatch policy controlling on which thread an
 * {@link EventBus} subscriber's {@code onNext} callback fires.
 *
 * <p>Selecting the right mode is critical for audio applications:
 * UI updates must never run on the audio thread, and audio callbacks
 * must never block on UI work.</p>
 *
 * <ul>
 *   <li>{@link #ON_CALLER_THREAD} — the subscriber runs synchronously
 *       on the thread that dispatches the event, with no thread
 *       handoff or executor re-dispatch. Lowest latency, but handlers
 *       must be glitch-free for audio-originated events.</li>
 *   <li>{@link #ON_UI_THREAD} — every event is re-dispatched through the
 *       UI executor (typically {@code Platform::runLater}) so the
 *       subscriber always runs on the JavaFX Application Thread.</li>
 *   <li>{@link #ON_VIRTUAL_THREAD} — every event is re-dispatched onto a
 *       freshly-spawned virtual thread (JEP&nbsp;444). Use for work-
 *       generating subscribers that may block on I/O.</li>
 * </ul>
 */
public enum DispatchMode {
    /** Run subscriber inline on the publisher's delivery thread. */
    ON_CALLER_THREAD,
    /** Run subscriber on the UI thread via the bus's UI executor. */
    ON_UI_THREAD,
    /** Run subscriber on a fresh virtual thread per event. */
    ON_VIRTUAL_THREAD
}
