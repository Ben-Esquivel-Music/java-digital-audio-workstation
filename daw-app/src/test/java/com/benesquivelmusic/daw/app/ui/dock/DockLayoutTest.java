package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DockEntry}, {@link DockZone}, and the immutable
 * {@link DockLayout} value type. These tests exercise the pure-logic
 * dock model and require no JavaFX screen.
 */
class DockLayoutTest {

    @Test
    void emptyLayoutHasNoEntries() {
        assertThat(DockLayout.empty().entries()).isEmpty();
    }

    @Test
    void dockedEntryRejectsFloatingZone() {
        assertThatThrownBy(() -> DockEntry.docked("p", DockZone.FLOATING, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void floatingEntryRequiresBounds() {
        assertThatThrownBy(() -> DockEntry.floating("p", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withEntryRenumbersTabIndices() {
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.docked("a", DockZone.TOP, 0, true))
                .withEntry(DockEntry.docked("b", DockZone.TOP, 99, true))
                .withEntry(DockEntry.docked("c", DockZone.TOP, 99, true));

        // tab indices must be 0..N-1 with no gaps
        assertThat(l.entriesInZone(DockZone.TOP))
                .extracting(DockEntry::panelId, DockEntry::tabIndex)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("a", 0),
                        org.assertj.core.api.Assertions.tuple("b", 1),
                        org.assertj.core.api.Assertions.tuple("c", 2));
    }

    @Test
    void moveToOtherZoneRenumbersBothZones() {
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.docked("a", DockZone.TOP, 0, true))
                .withEntry(DockEntry.docked("b", DockZone.TOP, 1, true))
                .withEntry(DockEntry.docked("c", DockZone.BOTTOM, 0, true));

        DockLayout moved = l.moveTo("a", DockZone.BOTTOM, 0);

        assertThat(moved.entry("a").get().zone()).isEqualTo(DockZone.BOTTOM);
        assertThat(moved.entry("a").get().tabIndex()).isEqualTo(0);
        // 'c' was bumped to index 1 by the renumbering
        assertThat(moved.entry("c").get().tabIndex()).isEqualTo(1);
        // 'b' is now alone in TOP, renumbered to 0
        assertThat(moved.entry("b").get().tabIndex()).isEqualTo(0);
    }

    @Test
    void floatAndBackProducesIdenticalBounds() {
        // The story explicitly requires this round-trip property.
        Rectangle2D bounds = new Rectangle2D(120, 80, 800, 480);
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.docked("mixer", DockZone.BOTTOM, 0, true));

        DockLayout floated = l.moveToFloating("mixer", bounds);
        assertThat(floated.entry("mixer").get().zone()).isEqualTo(DockZone.FLOATING);
        assertThat(floated.entry("mixer").get().floatingBounds()).isEqualTo(bounds);

        DockLayout reDocked = floated.moveTo("mixer", DockZone.BOTTOM, 0);
        assertThat(reDocked.entry("mixer").get().zone()).isEqualTo(DockZone.BOTTOM);
        assertThat(reDocked.entry("mixer").get().floatingBounds()).isNull();
    }

    @Test
    void moveToFloatingRequiresBounds() {
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.docked("a", DockZone.TOP, 0, true));
        assertThatThrownBy(() -> l.moveToFloating("a", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void moveToFloatingViaMoveToRejected() {
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.docked("a", DockZone.TOP, 0, true));
        assertThatThrownBy(() -> l.moveTo("a", DockZone.FLOATING, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setVisibleFlipsOnlyVisibility() {
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.docked("a", DockZone.TOP, 0, true));
        DockLayout hidden = l.setVisible("a", false);
        assertThat(hidden.entry("a").get().visible()).isFalse();
        assertThat(hidden.entry("a").get().zone()).isEqualTo(DockZone.TOP);
    }

    @Test
    void reDockAllFloatingFallsBackToPreferredZone() {
        DockLayout l = DockLayout.empty()
                .withEntry(DockEntry.floating("mixer", new Rectangle2D(0, 0, 100, 100)))
                .withEntry(DockEntry.docked("arr", DockZone.CENTER, 0, true));
        DockLayout reDocked = l.reDockAllFloating(id -> "mixer".equals(id) ? DockZone.BOTTOM : null);
        assertThat(reDocked.entry("mixer").get().zone()).isEqualTo(DockZone.BOTTOM);
        assertThat(reDocked.entry("mixer").get().floatingBounds()).isNull();
    }

    @Test
    void parseOrIsCaseInsensitiveAndTolerant() {
        assertThat(DockZone.parseOr("top", DockZone.CENTER)).isEqualTo(DockZone.TOP);
        assertThat(DockZone.parseOr("nope", DockZone.CENTER)).isEqualTo(DockZone.CENTER);
        assertThat(DockZone.parseOr(null, DockZone.LEFT)).isEqualTo(DockZone.LEFT);
    }
}
