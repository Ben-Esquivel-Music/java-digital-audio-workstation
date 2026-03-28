package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddMarkerActionTest {

    private MarkerManager manager;

    @BeforeEach
    void setUp() {
        manager = new MarkerManager();
    }

    @Test
    void shouldAddMarkerOnExecute() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        AddMarkerAction action = new AddMarkerAction(manager, marker);

        action.execute();

        assertThat(manager.getMarkerCount()).isEqualTo(1);
        assertThat(manager.getMarkers().get(0)).isEqualTo(marker);
    }

    @Test
    void shouldRemoveMarkerOnUndo() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        AddMarkerAction action = new AddMarkerAction(manager, marker);

        action.execute();
        action.undo();

        assertThat(manager.getMarkerCount()).isZero();
    }

    @Test
    void shouldSupportRedoAfterUndo() {
        Marker marker = new Marker("Verse 1", 0.0, MarkerType.SECTION);
        AddMarkerAction action = new AddMarkerAction(manager, marker);

        action.execute();
        action.undo();
        action.execute();

        assertThat(manager.getMarkerCount()).isEqualTo(1);
    }

    @Test
    void shouldHaveDescription() {
        AddMarkerAction action = new AddMarkerAction(manager,
                new Marker("Verse", 0.0, MarkerType.SECTION));
        assertThat(action.description()).isEqualTo("Add Marker");
    }
}
