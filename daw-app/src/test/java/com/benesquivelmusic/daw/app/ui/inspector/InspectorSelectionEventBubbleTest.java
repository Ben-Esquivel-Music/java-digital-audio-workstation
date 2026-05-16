package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.6 — the drawer fires
 * {@link InspectorSelectionEvent#SELECTION_CHANGED} on the standard
 * dispatch chain so a parent / sibling event filter sees it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class InspectorSelectionEventBubbleTest {

    @Test
    void parentFilterReceivesSelectionChangedEvent() {
        UUID id = UUID.randomUUID();
        AtomicReference<InspectorSelection> capturedByParentFilter = new AtomicReference<>();
        runOnFxThread(() -> {
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            Pane parent = new Pane(drawer);
            new Scene(parent, 400, 400);
            parent.applyCss();
            parent.layout();

            // Filter on the parent — fires before any drawer handler.
            parent.addEventFilter(
                    InspectorSelectionEvent.SELECTION_CHANGED,
                    e -> capturedByParentFilter.set(e.getSelection()));

            drawer.getSelectionModel().setSelection(
                    new InspectorSelection.TrackSelection(id));
            return null;
        });
        assertThat(capturedByParentFilter.get())
                .as("parent event filter must observe the selection-changed event")
                .isInstanceOf(InspectorSelection.TrackSelection.class);
        assertThat(((InspectorSelection.TrackSelection) capturedByParentFilter.get()).trackId())
                .isEqualTo(id);
    }
}
