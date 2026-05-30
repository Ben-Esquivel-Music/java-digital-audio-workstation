package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

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
 * <p>The event carries the {@code panelId} of the panel being detached and
 * an optional drop-point {@link Rectangle2D bounds} (story 288 — the grip
 * gesture supplies the screen position and panel size of the release point
 * so the floating {@code Stage} opens where the user let go). Consumers
 * (typically {@code MainController}) listen for it at the scene-root level
 * and route the request to
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockManager#float_(String,
 * com.benesquivelmusic.daw.sdk.ui.Rectangle2D)}; a {@code null} bounds lets
 * the {@code DockManager} fall back to remembered / default placement. The
 * dock layer owns the actual {@code Stage} creation so the event itself is
 * UI-toolkit-light (it only mentions JavaFX through {@code Event}).</p>
 */
public final class PanelDetachRequestedEvent extends Event {

    private static final long serialVersionUID = 20260523L;

    /** The single typed event-type for panel-detach requests. */
    public static final EventType<PanelDetachRequestedEvent> PANEL_DETACH_REQUESTED =
            new EventType<>(Event.ANY, "PANEL_DETACH_REQUESTED");

    private final String panelId;
    private final Rectangle2D bounds;

    /** Creates a detach-request event for the given panel id (no drop bounds). */
    public PanelDetachRequestedEvent(String panelId) {
        this(panelId, (Rectangle2D) null);
    }

    /**
     * Creates a detach-request event for the given panel id carrying the
     * drop-point {@code bounds} the floating window should open at.
     *
     * @param panelId stable {@code Dockable#dockId()} (non-null, non-blank)
     * @param bounds  drop-point floating bounds, or {@code null} to let the
     *                {@code DockManager} choose remembered / default bounds
     */
    public PanelDetachRequestedEvent(String panelId, Rectangle2D bounds) {
        super(PANEL_DETACH_REQUESTED);
        this.panelId = checkPanelId(panelId);
        this.bounds = bounds;
    }

    /** Creates a detach-request event with an explicit source/target (no drop bounds). */
    public PanelDetachRequestedEvent(String panelId, Object source, EventTarget target) {
        super(source, target, PANEL_DETACH_REQUESTED);
        this.panelId = checkPanelId(panelId);
        this.bounds = null;
    }

    /** Stable {@code Dockable#dockId()} of the panel being detached. */
    public String getPanelId() {
        return panelId;
    }

    /**
     * Drop-point bounds the floating window should open at, or {@code null}
     * when no explicit placement was supplied (the {@code DockManager} then
     * falls back to remembered / default bounds).
     */
    public Rectangle2D getBounds() {
        return bounds;
    }

    private static String checkPanelId(String id) {
        Objects.requireNonNull(id, "panelId must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("panelId must not be blank");
        }
        return id;
    }
}
