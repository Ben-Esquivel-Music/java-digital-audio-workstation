package com.benesquivelmusic.daw.sdk.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    @Test
    void rejectsNullOrBlankName() {
        assertThatThrownBy(() -> new Workspace(null, Map.of(), List.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Workspace("  ", Map.of(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullCollections() {
        assertThatThrownBy(() -> new Workspace("X", null, List.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Workspace("X", Map.of(), null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Workspace("X", Map.of(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        var states = new java.util.HashMap<String, PanelState>();
        states.put("mixer", PanelState.DEFAULT);
        var dialogs = new java.util.ArrayList<>(List.of("audio-settings"));
        var bounds = new java.util.HashMap<String, Rectangle2D>();
        bounds.put("mixer", new Rectangle2D(0, 0, 100, 50));

        Workspace ws = new Workspace("X", states, dialogs, bounds);

        states.put("editor", PanelState.HIDDEN);
        dialogs.add("preferences");
        bounds.put("editor", new Rectangle2D(0, 0, 1, 1));

        assertThat(ws.panelStates()).containsOnlyKeys("mixer");
        assertThat(ws.openDialogs()).containsExactly("audio-settings");
        assertThat(ws.panelBounds()).containsOnlyKeys("mixer");
    }

    @Test
    void withNameReturnsRenamedCopy() {
        Workspace ws = new Workspace("Mixing", Map.of(), List.of(), Map.of());
        assertThat(ws.withName("My Mix").name()).isEqualTo("My Mix");
        assertThat(ws.name()).isEqualTo("Mixing");
    }

    @Test
    void panelStateRejectsNonPositiveZoom() {
        assertThatThrownBy(() -> new PanelState(true, 0.0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanelState(true, -1.0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void panelStateRejectsInfiniteValues() {
        assertThatThrownBy(() -> new PanelState(true, Double.POSITIVE_INFINITY, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanelState(true, 1.0, Double.POSITIVE_INFINITY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanelState(true, 1.0, 0, Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rectangle2DRejectsNegativeDimensions() {
        assertThatThrownBy(() -> new Rectangle2D(0, 0, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rectangle2D(0, 0, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rectangle2DRejectsInfiniteValues() {
        assertThatThrownBy(() -> new Rectangle2D(Double.POSITIVE_INFINITY, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rectangle2D(0, 0, Double.POSITIVE_INFINITY, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fourArgConstructorDefaultsDockLayoutToEmpty() {
        // Backwards-compatible 4-arg constructor used by every caller
        // written before dockable panels existed.
        Workspace ws = new Workspace("Mixing", Map.of(), List.of(), Map.of());
        assertThat(ws.dockLayoutJson()).isEqualTo("");
    }

    @Test
    void fiveArgConstructorRejectsNullDockLayout() {
        assertThatThrownBy(() -> new Workspace("X", Map.of(), List.of(), Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withDockLayoutJsonReturnsCopyWithLayout() {
        Workspace ws = new Workspace("Mixing", Map.of(), List.of(), Map.of());
        Workspace docked = ws.withDockLayoutJson("{\"entries\":[]}");
        assertThat(ws.dockLayoutJson()).isEqualTo("");
        assertThat(docked.dockLayoutJson()).isEqualTo("{\"entries\":[]}");
        assertThat(docked.name()).isEqualTo("Mixing");
    }

    @Test
    void withNamePreservesDockLayoutJson() {
        Workspace ws = new Workspace("Mixing", Map.of(), List.of(), Map.of(), "{\"x\":1}");
        assertThat(ws.withName("Mix2").dockLayoutJson()).isEqualTo("{\"x\":1}");
    }
}
