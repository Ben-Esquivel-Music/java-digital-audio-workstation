package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.inspector.sections.InsertsSection;
import com.benesquivelmusic.daw.app.ui.inspector.sections.TrackSection;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.6 selection routing — publishing a typed
 * {@link InspectorSelection} on the drawer's
 * {@link InspectorSelectionModel} updates the matching section.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InspectorSelectionRoutingTest {

    @Test
    void trackSelectionUpdatesTrackSectionFields() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
        String[] nameSeen = new String[1];
        UUID[] idSeen = new UUID[1];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            // Pre-populate the track section so the routing has
            // something to display when the selection arrives.
            TrackSection ts = drawer.getTrackSection();
            ts.showTrack(id, "Drums", null, "Audio", "In 1", "Master");
            Pane root = new Pane(drawer);
            new Scene(root, 600, 400);
            root.applyCss();
            root.layout();

            drawer.getSelectionModel().setSelection(
                    new InspectorSelection.TrackSelection(id));
            nameSeen[0] = ts.getNameField().getText();
            idSeen[0] = ts.trackIdProperty().get();
            return null;
        });
        assertThat(nameSeen[0]).isEqualTo("Drums");
        assertThat(idSeen[0]).isEqualTo(id);
    }

    @Test
    void insertSelectionUpdatesInsertsSectionSelectedIndex() {
        int[] seen = new int[1];
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            InsertsSection inserts = drawer.getInsertsSection();
            inserts.setInserts(List.of(
                    new InsertsSection.Row("EQ", true),
                    new InsertsSection.Row("Compressor", false)));
            Pane root = new Pane(drawer);
            new Scene(root, 600, 400);
            root.applyCss();
            root.layout();

            drawer.getSelectionModel().setSelection(
                    new InspectorSelection.InsertSelection(null, 1));
            seen[0] = inserts.selectedIndexProperty().get();
            return null;
        });
        assertThat(seen[0]).isEqualTo(1);
    }
}
