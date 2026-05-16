package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link KnobSkin#dispose()} removes every {@code ChangeListener}
 * the skin registered in its constructor — mutating a control property
 * after disposal must not provoke a skin-side reaction.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class KnobDisposeTest {

    @Test
    void disposeRemovesAllRegisteredListeners() {
        int[] counts = new int[2];
        runOnFxThread(() -> {
            Knob k = new Knob();
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            counts[0] = skin.registeredListenerCount();
            // Swap the skin — equivalent to dispose() on the original.
            k.setSkin(null);
            counts[1] = skin.registeredListenerCount();
            assertThat(skin.isDisposed()).isTrue();
            return null;
        });
        assertThat(counts[0])
                .as("skin registers listeners on construction")
                .isGreaterThan(0);
        assertThat(counts[1])
                .as("dispose() removes every listener")
                .isEqualTo(0);
    }

    @Test
    void valueChangesAfterDisposeDoNotRepaintOldCanvas() {
        boolean[] disposed = new boolean[1];
        double[] preWidth = new double[1];
        double[] postWidth = new double[1];
        runOnFxThread(() -> {
            Knob k = new Knob();
            StackPane root = new StackPane(k);
            new Scene(root, 100, 100);
            root.applyCss();
            root.layout();
            KnobSkin skin = (KnobSkin) k.getSkin();
            Canvas oldCanvas = skin.canvas();
            preWidth[0] = oldCanvas.getWidth();
            k.setSkin(null);
            disposed[0] = skin.isDisposed();
            // Mutate a property the disposed skin used to react to.
            k.setValue(0.5);
            k.setBipolar(true);
            // The disposed skin's canvas should NOT have been resized or
            // re-rendered as a side-effect of the property change.
            postWidth[0] = oldCanvas.getWidth();
            return null;
        });
        assertThat(disposed[0]).isTrue();
        assertThat(postWidth[0]).isEqualTo(preWidth[0]);
    }
}
