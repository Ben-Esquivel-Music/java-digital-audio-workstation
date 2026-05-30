package com.benesquivelmusic.daw.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.benesquivelmusic.daw.app.ui.dock.DockZone;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link MainController#dockHomeZone(String, boolean, DockZone)}, the
 * story-288 review fix that coerces a drop-zone re-dock to each canonical
 * panel's docked home rather than the arbitrary zone the user released over.
 *
 * <p>Before the fix, dropping the browser grip on (say) the RIGHT zone wrote
 * {@code zone=RIGHT} into the layout model — which {@code reconcile()}
 * ignores (snapping the browser back to LEFT) and which the manifest bar
 * then renders, persisting a zone the panel never occupies. These cases lock
 * the mapping to {@code reconcile()}'s authority. Pure logic, so no JavaFX
 * toolkit is required.</p>
 */
class DockHomeZoneTest {

    @Test
    void browserAlwaysHomesLeftRegardlessOfDropZone() {
        // The headline regression: a browser dropped on RIGHT must not record RIGHT.
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_BROWSER, false, DockZone.RIGHT))
                .isEqualTo(DockZone.LEFT);
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_BROWSER, false, DockZone.BOTTOM))
                .isEqualTo(DockZone.LEFT);
    }

    @Test
    void centerViewsHomeCenterEvenWhenPreferredZoneDisagrees() {
        // MixerView.preferredZone() returns BOTTOM, but reconcile() homes the
        // mixer to CENTER — so preferredZone() is NOT the authority here.
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_MIXER, false, DockZone.BOTTOM))
                .isEqualTo(DockZone.CENTER);
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_ARRANGEMENT, false, DockZone.TOP))
                .isEqualTo(DockZone.CENTER);
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_EDITOR, false, DockZone.LEFT))
                .isEqualTo(DockZone.CENTER);
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_MASTERING, false, DockZone.RIGHT))
                .isEqualTo(DockZone.CENTER);
    }

    @Test
    void telemetryPanelsHomeRight() {
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_TELEMETRY, false, DockZone.LEFT))
                .isEqualTo(DockZone.RIGHT);
        assertThat(MainController.dockHomeZone(
                DefaultWorkspaces.PANEL_ROOM_3D, false, DockZone.CENTER))
                .isEqualTo(DockZone.RIGHT);
    }

    @Test
    void visualizationPanelsHomeBottom() {
        // Viz adapters are identified by the isVisualization flag, not a
        // fixed id constant; any such id homes to the BOTTOM analyzer strip.
        assertThat(MainController.dockHomeZone("viz-spectrum", true, DockZone.RIGHT))
                .isEqualTo(DockZone.BOTTOM);
    }

    @Test
    void unknownPanelFallsBackToTheDropZone() {
        // Forward-compatible: an unrecognised, non-viz id keeps the dropped zone.
        assertThat(MainController.dockHomeZone("future-panel", false, DockZone.TOP))
                .isEqualTo(DockZone.TOP);
    }
}
