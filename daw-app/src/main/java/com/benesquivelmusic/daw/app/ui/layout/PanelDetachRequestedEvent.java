package com.benesquivelmusic.daw.app.ui.layout;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;

import java.util.Objects;

/**
 * Typed event fired when the user grabs a panel's {@code ⋮⋮} grip
 * handle (UI Design Book §4 Concept D mockup) and drags it out of its
 * dock zone, signalling intent to detach the panel into a floating
 * window.
 *
 * <p>Per story 282's "Detach behaviour" acceptance criteria and Skill
 * §12 ("typed {@link Event} subclasses … so consumers integrate via the
 * standard event dispatch chain"). Mirrors the {@code
 * DetachPluginRequestedEvent} convention from story 281.</p>
 *
 * <p>The event carries the {@code panelId} of the panel being detached.
 * Consumers (typically {@code MainController}) listen for it at the
 * scene-root level and route the request to
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockManager#float_(String,
 * com.benesquivelmusic.daw.sdk.ui.Rectangle2D)}; the dock layer owns the
 * actual {@code Stage} creation so the event itself is UI-toolkit-light
 * (it only mentions JavaFX through {@code Event}).</p>
 */
public final class PanelDetachRequestedEvent extends Event {

    private static final long serialVersionUID = 20260523L;

    /** The single typed event-type for panel-detach requests. */
    public static final EventType<PanelDetachRequestedEvent> PANEL_DETACH_REQUESTED =
            new EventType<>(Event.ANY, "PANEL_DETACH_REQUESTED");

    private final String panelId;

    /** Creates a detach-request event for the given panel id. */
    public PanelDetachRequestedEvent(String panelId) {
        super(PANEL_DETACH_REQUESTED);
        this.panelId = Objects.requireNonNull(panelId, "panelId must not be null");
        if (panelId.isBlank()) {
            throw new IllegalArgumentException("panelId must not be blank");
        }
    }

    /** Creates a detach-request event with an explicit source/target. */
    public PanelDetachRequestedEvent(String panelId, Object source, EventTarget target) {
        super(source, target, PANEL_DETACH_REQUESTED);
        this.panelId = Objects.requireNonNull(panelId, "panelId must not be null");
        if (panelId.isBlank()) {
            throw new IllegalArgumentException("panelId must not be blank");
        }
    }

    /** Stable {@code Dockable#dockId()} of the panel being detached. */
    public String getPanelId() {
        return panelId;
    }
}
