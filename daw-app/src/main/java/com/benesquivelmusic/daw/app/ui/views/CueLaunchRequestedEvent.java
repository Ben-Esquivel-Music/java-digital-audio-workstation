package com.benesquivelmusic.daw.app.ui.views;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;

/**
 * Typed event fired when the user presses a track tile's <strong>CUE</strong>
 * button on the {@link PerformanceStageView} (story 280).
 *
 * <p>Story 280 deliberately does <em>not</em> implement clip launch
 * ("session view", Ableton-Session-style) — that is a separate, not-yet-filed
 * audio-engine story. This story stops at "the UI exists and the button
 * fires the event". To keep that stub honest and forward-compatible, the
 * cue trigger is a properly-typed {@link Event} (skill §12) rather than an
 * ad-hoc {@code Runnable} callback or string-keyed bus:</p>
 *
 * <ul>
 *   <li>It carries a single {@link #CUE_LAUNCH_REQUESTED} {@link EventType}.</li>
 *   <li>It is fired with {@link javafx.scene.Node#fireEvent(Event)} so it
 *       <strong>bubbles</strong> up the scene graph — a future audio-engine
 *       consumer attaches an {@code addEventHandler(CUE_LAUNCH_REQUESTED, …)}
 *       at any ancestor (the stage view, the scene root) without the tile
 *       knowing who listens.</li>
 *   <li>It carries the 1-based {@link #getTrackIndex() track index} so the
 *       consumer knows which tile fired.</li>
 * </ul>
 *
 * <p>Mirrors the {@code TrackStrip.TrackSelectionEvent} convention already
 * established in the {@code controls} package.</p>
 */
public final class CueLaunchRequestedEvent extends Event {

    private static final long serialVersionUID = 20260521L;

    /** The single typed event-type for cue-launch requests. */
    public static final EventType<CueLaunchRequestedEvent> CUE_LAUNCH_REQUESTED =
            new EventType<>(Event.ANY, "CUE_LAUNCH_REQUESTED");

    private final int trackIndex;

    /**
     * Creates a cue-launch-request event with no explicit source/target
     * (the dispatch chain fills them in when the event is fired via
     * {@link javafx.scene.Node#fireEvent(Event)}).
     *
     * @param trackIndex the 1-based index of the track whose CUE button
     *                   was pressed
     */
    public CueLaunchRequestedEvent(int trackIndex) {
        super(CUE_LAUNCH_REQUESTED);
        this.trackIndex = trackIndex;
    }

    /**
     * Creates a cue-launch-request event with an explicit source/target.
     *
     * @param source     the event source (typically the CUE button)
     * @param target     the event target
     * @param trackIndex the 1-based index of the track whose CUE button
     *                   was pressed
     */
    public CueLaunchRequestedEvent(Object source, EventTarget target, int trackIndex) {
        super(source, target, CUE_LAUNCH_REQUESTED);
        this.trackIndex = trackIndex;
    }

    /** @return the 1-based index of the track whose CUE button was pressed. */
    public int getTrackIndex() {
        return trackIndex;
    }
}
