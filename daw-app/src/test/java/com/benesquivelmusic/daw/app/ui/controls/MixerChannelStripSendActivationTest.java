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
 * Story 271 — clicking a send slot row fires a typed
 * {@link MixerChannelStrip.SendSelectedEvent} that bubbles
 * <strong>through the scene graph</strong>: a filter installed on a
 * <em>parent</em> node receives it (proving it travels the standard
 * dispatch chain, symmetric with {@link MixerChannelStripInsertActivationTest}
 * — story 272's Inspector consumes both the same way).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripSendActivationTest {

    @Test
    void clickingSendRowFiresEventThatBubblesToParent() {
        AtomicInteger received = new AtomicInteger(-1);

        runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            strip.setChannelName("Drums");
            strip.sendsProperty().add(new SendSlotModel("Reverb", 0.5, false));
            strip.sendsProperty().add(new SendSlotModel("Delay", 0.25, true));

            StackPane parent = new StackPane(strip);
            parent.getStyleClass().add("root-pane");
            Scene scene = new Scene(parent, 200, 600);
            ThemeManager.getDefault().applyTo(scene);
            parent.applyCss();
            parent.layout();

            // Filter on the PARENT (not the strip) — if it fires at all the
            // event travelled the standard dispatch chain up from the strip.
            parent.addEventFilter(MixerChannelStrip.SEND_SELECTED,
                    ev -> received.set(ev.getSendIndex()));

            MixerChannelStripSkin skin = (MixerChannelStripSkin) strip.getSkin();
            Node row = skin.sendRowNode(1);
            assertThat(row).as("send row 1 must exist").isNotNull();
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
                .as("parent filter received the SendSelectedEvent (proving "
                        + "it bubbled through the scene graph) with the clicked "
                        + "slot index")
                .isEqualTo(1);
    }
}
