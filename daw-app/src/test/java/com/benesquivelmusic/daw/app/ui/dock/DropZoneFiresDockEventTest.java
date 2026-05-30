package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.app.ui.layout.PanelDockRequestedEvent;

import javafx.scene.Group;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 288 — {@code DropZoneFiresDockEventTest}. Dropping a dock-carrying
 * drag on a zone must fire a {@link PanelDockRequestedEvent} on the
 * configured event target with the dragged panel id and the zone.
 *
 * <p>A real {@code DragEvent}/{@code Dragboard} cannot be built headless,
 * so the test drives the extracted {@link DockDropZones#fireDock(DockZone,
 * String)} core — the same call the installed {@code DRAG_DROPPED} handler
 * makes after pulling the panel id off the dragboard. The event-target is a
 * parent node whose {@code addEventFilter} captures the payload (never
 * {@code Event.getSource()} identity, which JavaFX rewrites on bubble —
 * {@code feedback_javafx_bubbling_event_test_pitfall.md}).</p>
 *
 * <p>Lives in {@code …ui.dock} (same package as the package-private seam)
 * and needs no started toolkit: it constructs only a bare {@link Group}
 * and fires an event — no skin, Tooltip, or rasterisation
 * ({@code feedback_javafx_headless_test_pitfalls.md}).</p>
 */
class DropZoneFiresDockEventTest {

    @Test
    void fireDockDispatchesDockEventWithPanelIdAndZone() {
        AtomicReference<PanelDockRequestedEvent> captured = new AtomicReference<>();

        // Event target = a parent that filters on the event payload.
        Group eventTarget = new Group();
        eventTarget.addEventFilter(
                PanelDockRequestedEvent.PANEL_DOCK_REQUESTED, captured::set);

        DockDropZones zones = new DockDropZones(eventTarget);
        zones.fireDock(DockZone.LEFT, "mixer");

        PanelDockRequestedEvent event = captured.get();
        assertThat(event).as("dock event was fired on the target").isNotNull();
        assertThat(event.getPanelId()).isEqualTo("mixer");
        assertThat(event.getTargetZone()).isEqualTo(DockZone.LEFT);
    }

    @Test
    void fireDockHonoursEachZone() {
        for (DockZone zone : new DockZone[] {
                DockZone.TOP, DockZone.BOTTOM, DockZone.LEFT, DockZone.RIGHT, DockZone.CENTER}) {
            AtomicReference<PanelDockRequestedEvent> captured = new AtomicReference<>();
            Group eventTarget = new Group();
            eventTarget.addEventFilter(
                    PanelDockRequestedEvent.PANEL_DOCK_REQUESTED, captured::set);

            new DockDropZones(eventTarget).fireDock(zone, "browser");

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().getTargetZone()).isEqualTo(zone);
            assertThat(captured.get().getPanelId()).isEqualTo("browser");
        }
    }
}
