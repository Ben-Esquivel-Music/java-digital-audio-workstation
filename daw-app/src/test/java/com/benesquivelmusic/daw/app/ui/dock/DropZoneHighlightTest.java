package com.benesquivelmusic.daw.app.ui.dock;

import javafx.scene.Group;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 288 — {@code DropZoneHighlightTest}. A dock drag-over adds the
 * {@code dock-drop-target-active} style class to the hovered zone; a
 * drag-exit (or drop) removes it. Per UI Design Book §7.3 the active state
 * is a background swap via this style class (mapped to {@code -accent-soft}
 * in {@code styles.css}), never a border, and is instantaneous (no fade,
 * per the Reduce-Motion default of story 279).
 *
 * <p>The test drives the extracted {@link DockDropZones#applyHighlight(
 * Region, boolean)} core (the installed {@code DRAG_OVER}/{@code
 * DRAG_EXITED} handlers call it). It is pure style-class manipulation —
 * headless-safe, no rasterisation
 * ({@code feedback_javafx_headless_test_pitfalls.md}) — and needs no
 * started toolkit.</p>
 */
class DropZoneHighlightTest {

    @Test
    void applyHighlightTogglesActiveStyleClass() {
        DockDropZones zones = new DockDropZones(new Group());
        Region zone = new Region();

        assertThat(zone.getStyleClass())
                .doesNotContain(DockDropZones.DROP_TARGET_ACTIVE_CLASS);

        zones.applyHighlight(zone, true);
        assertThat(zone.getStyleClass())
                .as("highlight adds the active class")
                .contains(DockDropZones.DROP_TARGET_ACTIVE_CLASS);

        zones.applyHighlight(zone, false);
        assertThat(zone.getStyleClass())
                .as("clearing removes the active class")
                .doesNotContain(DockDropZones.DROP_TARGET_ACTIVE_CLASS);
    }

    @Test
    void applyHighlightIsIdempotent() {
        DockDropZones zones = new DockDropZones(new Group());
        Region zone = new Region();

        zones.applyHighlight(zone, true);
        zones.applyHighlight(zone, true); // second activate must not duplicate

        assertThat(zone.getStyleClass().stream()
                .filter(DockDropZones.DROP_TARGET_ACTIVE_CLASS::equals)
                .count())
                .as("the active class appears at most once")
                .isEqualTo(1L);

        zones.applyHighlight(zone, false);
        zones.applyHighlight(zone, false); // second clear is a harmless no-op
        assertThat(zone.getStyleClass())
                .doesNotContain(DockDropZones.DROP_TARGET_ACTIVE_CLASS);
    }
}
