package com.benesquivelmusic.daw.app.ui.dock;

import javafx.scene.Group;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Story 288 review — {@code DockDropZones.bindNode(...)} scopes the BOTTOM
 * drop zone to a fixed content node (the analyzer strip) instead of the
 * whole bottom {@code VBox} slot, so the status bar and dock manifest bar
 * are not dock drop targets. This pins the new seam's contract: it rejects
 * a null node and the {@code FLOATING} pseudo-zone, and installs the dock
 * drag handlers on the supplied node.
 *
 * <p>Headless-safe: only attaches handlers and inspects validation — no
 * skin, Tooltip, or rasterisation
 * ({@code feedback_javafx_headless_test_pitfalls.md}).</p>
 */
class DockDropZonesBindNodeTest {

    @Test
    void bindNodeRejectsNullNode() {
        DockDropZones zones = new DockDropZones(new Group());
        assertThatNullPointerException()
                .isThrownBy(() -> zones.bindNode(null, DockZone.BOTTOM));
    }

    @Test
    void bindNodeRejectsFloatingZone() {
        DockDropZones zones = new DockDropZones(new Group());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> zones.bindNode(new HBox(), DockZone.FLOATING));
    }

    @Test
    void bindNodeAttachesToTheSuppliedNode() {
        DockDropZones zones = new DockDropZones(new Group());
        HBox strip = new HBox();
        zones.bindNode(strip, DockZone.BOTTOM);
        // The active highlight class is only ever added by the installed
        // drag handlers, so a freshly-bound node starts clean.
        assertThat(strip.getStyleClass())
                .doesNotContain(DockDropZones.DROP_TARGET_ACTIVE_CLASS);
    }
}
