package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.FaderSkin;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Press-drag-release applies the {@code :dragging} pseudo-class to the
 * fader and the cap centreline tints to the dragging overlay while the
 * pseudo-class is active.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderDragPseudoClassTest {

    private static MouseEvent mouseAt(javafx.event.EventType<MouseEvent> type,
            FaderSkin skin, double localY) {
        // Construct a synthetic MOUSE_PRESSED / DRAGGED / RELEASED with
        // PRIMARY button and a pick on the canvas.
        return new MouseEvent(type, /*x*/ skin.canvas().getWidth() / 2.0,
                /*y*/ localY, /*sx*/ 0, /*sy*/ localY,
                MouseButton.PRIMARY, /*click*/ 1,
                false, false, false, false,
                true, false, false, false, false, false,
                new PickResult(skin.canvas(), 0, localY));
    }

    @Test
    void pressAppliesDraggingPseudoClassReleaseRemovesIt() {
        boolean[] flags = new boolean[3]; // before, duringPress, afterRelease
        runOnFxThread(() -> {
            Fader f = Fader.create()
                    .min(-96).max(12).defaultValue(0)
                    .showMeter(false)
                    .build();
            StackPane root = new StackPane(f);
            new Scene(root, 40, 200);
            root.applyCss();
            f.resizeRelocate(0, 0, 20, 200);
            f.layout();
            FaderSkin skin = (FaderSkin) f.getSkin();
            flags[0] = f.getPseudoClassStates().stream()
                    .anyMatch(pc -> "dragging".equals(pc.getPseudoClassName()));
            // Press on the cap centre (not outside, so no snap surprise).
            double capY = skin.capCentreY();
            Event.fireEvent(skin.canvas(),
                    mouseAt(MouseEvent.MOUSE_PRESSED, skin, capY));
            flags[1] = f.getPseudoClassStates().stream()
                    .anyMatch(pc -> "dragging".equals(pc.getPseudoClassName()));
            Event.fireEvent(skin.canvas(),
                    mouseAt(MouseEvent.MOUSE_DRAGGED, skin, capY - 10));
            Event.fireEvent(skin.canvas(),
                    mouseAt(MouseEvent.MOUSE_RELEASED, skin, capY - 10));
            flags[2] = f.getPseudoClassStates().stream()
                    .anyMatch(pc -> "dragging".equals(pc.getPseudoClassName()));
            return null;
        });
        assertThat(flags[0]).as("not dragging before press").isFalse();
        assertThat(flags[1]).as("dragging applied during press").isTrue();
        assertThat(flags[2]).as("dragging removed on release").isFalse();
    }

    @Test
    void skinReportsDraggingStateBetweenPressAndRelease() {
        boolean[] flags = new boolean[3];
        runOnFxThread(() -> {
            Fader f = Fader.create()
                    .min(-96).max(12).defaultValue(0)
                    .showMeter(false)
                    .build();
            StackPane root = new StackPane(f);
            new Scene(root, 40, 200);
            root.applyCss();
            f.resizeRelocate(0, 0, 20, 200);
            f.layout();
            FaderSkin skin = (FaderSkin) f.getSkin();
            flags[0] = skin.isDragging();
            double capY = skin.capCentreY();
            Event.fireEvent(skin.canvas(),
                    mouseAt(MouseEvent.MOUSE_PRESSED, skin, capY));
            flags[1] = skin.isDragging();
            Event.fireEvent(skin.canvas(),
                    mouseAt(MouseEvent.MOUSE_RELEASED, skin, capY));
            flags[2] = skin.isDragging();
            return null;
        });
        assertThat(flags[0]).isFalse();
        assertThat(flags[1]).isTrue();
        assertThat(flags[2]).isFalse();
    }
}
