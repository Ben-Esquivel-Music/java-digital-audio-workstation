package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 271 — clicking an insert slot row fires a typed
 * {@link MixerChannelStrip.InsertSelectedEvent} that bubbles
 * <strong>through the scene graph</strong>: a filter installed on a
 * <em>parent</em> node receives it (proving it travels the standard
 * dispatch chain, which is how story 272's Inspector consumes it).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripInsertActivationTest {

    @Test
    void clickingInsertRowFiresEventThatBubblesToParent() {
        AtomicInteger received = new AtomicInteger(-1);

        runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            strip.setChannelName("Drums");
            strip.insertsProperty().add(new InsertSlotModel("EQ", true, false));
            strip.insertsProperty().add(new InsertSlotModel("Comp", false, true));

            StackPane parent = new StackPane(strip);
            parent.getStyleClass().add("root-pane");
            Scene scene = new Scene(parent, 200, 600);
            ThemeManager.getDefault().applyTo(scene);
            parent.applyCss();
            parent.layout();

            // Filter on the PARENT (not the strip) — proves the event
            // bubbles up the scene graph.
            // The filter is on the PARENT — if it fires at all the event
            // travelled the standard dispatch chain up from the strip.
            parent.addEventFilter(MixerChannelStrip.INSERT_SELECTED,
                    ev -> received.set(ev.getInsertIndex()));

            MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();
            Node row = skin.insertRowNode(1);
            assertThat(row).as("insert row 1 must exist").isNotNull();
            row.getOnMouseClicked().handle(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                            0, 0, 0, 0,
                            javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false,
                            true, false, false, false, false, false, null));
            return null;
        });

        assertThat(received.get())
                .as("parent filter received the InsertSelectedEvent (proving "
                        + "it bubbled through the scene graph) with the clicked "
                        + "slot index")
                .isEqualTo(1);
    }
}
