package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveMarkerActionTest {

    private MarkerManager manager;

    @BeforeEach
    void setUp() {
        manager = new MarkerManager();
    }

    @Test
    void shouldRemoveMarkerOnExecute() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        RemoveMarkerAction action = new RemoveMarkerAction(manager, marker);
        action.execute();

        assertThat(manager.getMarkerCount()).isZero();
    }

    @Test
    void shouldReAddMarkerOnUndo() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        RemoveMarkerAction action = new RemoveMarkerAction(manager, marker);
        action.execute();
        action.undo();

        assertThat(manager.getMarkerCount()).isEqualTo(1);
        assertThat(manager.getMarkers().get(0)).isEqualTo(marker);
    }

    @Test
    void shouldSupportRedoAfterUndo() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        RemoveMarkerAction action = new RemoveMarkerAction(manager, marker);
        action.execute();
        action.undo();
        action.execute();

        assertThat(manager.getMarkerCount()).isZero();
    }

    @Test
    void shouldHaveDescription() {
        RemoveMarkerAction action = new RemoveMarkerAction(manager,
                new Marker("Verse", 0.0, MarkerType.SECTION));
        assertThat(action.description()).isEqualTo("Remove Marker");
    }
}
