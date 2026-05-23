package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.app.ui.dock.DockZone;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;

import java.util.Objects;

/**
 * Typed event fired when the user drags a floating panel back over a
 * dock target (UI Design Book §4 Concept D, §7.3 — dock targets light
 * up with the {@code -accent-soft} background-swap overlay), signalling
 * intent to re-dock the panel into a specific {@link DockZone}.
 *
 * <p>Per story 282's "Float-to-dock" acceptance criterion and Skill
 * §12. Companion to {@link PanelDetachRequestedEvent}.</p>
 *
 * <p>The event carries the {@code panelId} of the panel being re-docked
 * and the target {@link DockZone}; consumers (typically
 * {@code MainController}) listen at the scene-root level and route the
 * request to
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockManager#moveToEnd(String, DockZone)}
 * (or {@code move(...)} with a specific tab index).</p>
 */
public final class PanelDockRequestedEvent extends Event {

    private static final long serialVersionUID = 20260523L;

    /** The single typed event-type for panel-dock requests. */
    public static final EventType<PanelDockRequestedEvent> PANEL_DOCK_REQUESTED =
            new EventType<>(Event.ANY, "PANEL_DOCK_REQUESTED");

    private final String panelId;
    private final DockZone targetZone;

    /** Creates a dock-request event for the given panel id and target zone. */
    public PanelDockRequestedEvent(String panelId, DockZone targetZone) {
        super(PANEL_DOCK_REQUESTED);
        this.panelId = checkPanelId(panelId);
        this.targetZone = checkZone(targetZone);
    }

    /** Creates a dock-request event with an explicit source/target. */
    public PanelDockRequestedEvent(String panelId, DockZone targetZone,
                                   Object source, EventTarget target) {
        super(source, target, PANEL_DOCK_REQUESTED);
        this.panelId = checkPanelId(panelId);
        this.targetZone = checkZone(targetZone);
    }

    /** Stable {@code Dockable#dockId()} of the panel being re-docked. */
    public String getPanelId() {
        return panelId;
    }

    /** Dock zone the user dropped the panel onto. */
    public DockZone getTargetZone() {
        return targetZone;
    }

    private static String checkPanelId(String id) {
        Objects.requireNonNull(id, "panelId must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("panelId must not be blank");
        }
        return id;
    }

    private static DockZone checkZone(DockZone z) {
        Objects.requireNonNull(z, "targetZone must not be null");
        if (z == DockZone.FLOATING) {
            throw new IllegalArgumentException(
                    "targetZone must not be FLOATING — use PanelDetachRequestedEvent");
        }
        return z;
    }
}
