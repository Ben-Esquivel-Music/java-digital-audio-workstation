package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EditMarkerActionTest {

    private MarkerManager manager;

    @BeforeEach
    void setUp() {
        manager = new MarkerManager();
    }

    @Test
    void shouldEditMarkerNameAndPositionOnExecute() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        EditMarkerAction action = new EditMarkerAction(manager, marker, "Chorus", 16.0);
        action.execute();

        assertThat(marker.getName()).isEqualTo("Chorus");
        assertThat(marker.getPositionInBeats()).isEqualTo(16.0);
    }

    @Test
    void shouldRestoreOldNameAndPositionOnUndo() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        EditMarkerAction action = new EditMarkerAction(manager, marker, "Chorus", 16.0);
        action.execute();
        action.undo();

        assertThat(marker.getName()).isEqualTo("Verse 1");
        assertThat(marker.getPositionInBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldResortMarkersOnExecute() {
        Marker a = new Marker("A", 0.0, MarkerType.SECTION);
        Marker b = new Marker("B", 16.0, MarkerType.SECTION);
        manager.addMarker(a);
        manager.addMarker(b);

        // Move A to after B
        EditMarkerAction action = new EditMarkerAction(manager, a, "A", 32.0);
        action.execute();

        assertThat(manager.getMarkers().get(0).getName()).isEqualTo("B");
        assertThat(manager.getMarkers().get(1).getName()).isEqualTo("A");
    }

    @Test
    void shouldResortMarkersOnUndo() {
        Marker a = new Marker("A", 0.0, MarkerType.SECTION);
        Marker b = new Marker("B", 16.0, MarkerType.SECTION);
        manager.addMarker(a);
        manager.addMarker(b);

        EditMarkerAction action = new EditMarkerAction(manager, a, "A", 32.0);
        action.execute();
        action.undo();

        assertThat(manager.getMarkers().get(0).getName()).isEqualTo("A");
        assertThat(manager.getMarkers().get(1).getName()).isEqualTo("B");
    }

    @Test
    void shouldSupportRedoAfterUndo() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        manager.addMarker(marker);

        EditMarkerAction action = new EditMarkerAction(manager, marker, "Bridge", 48.0);
        action.execute();
        action.undo();
        action.execute();

        assertThat(marker.getName()).isEqualTo("Bridge");
        assertThat(marker.getPositionInBeats()).isEqualTo(48.0);
    }

    @Test
    void shouldHaveDescription() {
        Marker marker = new Marker("Verse", 0.0, MarkerType.SECTION);
        EditMarkerAction action = new EditMarkerAction(manager, marker, "Chorus", 16.0);
        assertThat(action.description()).isEqualTo("Edit Marker");
    }
}
