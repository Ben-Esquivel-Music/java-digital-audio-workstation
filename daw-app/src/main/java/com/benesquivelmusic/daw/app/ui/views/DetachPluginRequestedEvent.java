package com.benesquivelmusic.daw.app.ui.views;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;

/**
 * Typed event fired when the user presses the {@code ◯ Detach} button in
 * the {@link WorkshopView}'s right-pane breadcrumb header (story 281).
 *
 * <p>Story 281 deliberately does <em>not</em> implement detached / floating
 * plugin windows — that is story 282's scope. This story stops at "the
 * button exists and the event fires". To keep that stub honest and
 * forward-compatible the detach trigger is a properly-typed {@link Event}
 * (skill §12) rather than an ad-hoc {@code Runnable} callback or
 * string-keyed bus:</p>
 *
 * <ul>
 *   <li>It carries a single {@link #DETACH_PLUGIN_REQUESTED}
 *       {@link EventType}.</li>
 *   <li>It is fired with {@link javafx.scene.Node#fireEvent(Event)} so it
 *       <strong>bubbles</strong> up the scene graph — story 282's consumer
 *       attaches an {@code addEventHandler(DETACH_PLUGIN_REQUESTED, …)} at
 *       the application root without the breadcrumb knowing who listens.</li>
 * </ul>
 *
 * <p>Mirrors the {@link CueLaunchRequestedEvent} convention established by
 * story 280.</p>
 */
public final class DetachPluginRequestedEvent extends Event {

    private static final long serialVersionUID = 20260522L;

    /** The single typed event-type for detach-plugin requests. */
    public static final EventType<DetachPluginRequestedEvent> DETACH_PLUGIN_REQUESTED =
            new EventType<>(Event.ANY, "DETACH_PLUGIN_REQUESTED");

    /**
     * Creates a detach-plugin-request event with no explicit source/target
     * (the dispatch chain fills them in when the event is fired via
     * {@link javafx.scene.Node#fireEvent(Event)}).
     */
    public DetachPluginRequestedEvent() {
        super(DETACH_PLUGIN_REQUESTED);
    }

    /**
     * Creates a detach-plugin-request event with an explicit source/target.
     *
     * @param source the event source (typically the Detach button)
     * @param target the event target
     */
    public DetachPluginRequestedEvent(Object source, EventTarget target) {
        super(source, target, DETACH_PLUGIN_REQUESTED);
    }
}
