package com.benesquivelmusic.daw.sdk.event;

/**
 * Listener for recording session lifecycle events.
 *
 * <p>Implementations are notified when the DAW starts, pauses, resumes,
 * or stops recording, as well as when a new recording segment is created
 * during a long-running session.</p>
 */
public interface RecordingListener {

    /** Called when a recording session starts. */
    void onRecordingStarted();

    /** Called when a recording session is paused. */
    void onRecordingPaused();

    /** Called when a recording session resumes after a pause. */
    void onRecordingResumed();

    /** Called when a recording session stops. */
    void onRecordingStopped();

    /**
     * Called when a new recording segment is created during a long-running session.
     *
     * @param segmentIndex the zero-based index of the new segment
     */
    void onNewSegmentCreated(int segmentIndex);
}
