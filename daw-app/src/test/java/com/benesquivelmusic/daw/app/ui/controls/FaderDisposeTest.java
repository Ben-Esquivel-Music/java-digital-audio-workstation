package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.skin.FaderSkin;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FaderSkin#dispose()} removes every listener registered in the
 * skin's constructor — mirrors {@link KnobDisposeTest}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderDisposeTest {

    @Test
    void disposeRemovesAllRegisteredListeners() {
        int[] counts = new int[2];
        runOnFxThread(() -> {
            Fader f = new Fader();
            StackPane root = new StackPane(f);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            FaderSkin skin = (FaderSkin) f.getSkin();
            counts[0] = skin.registeredListenerCount();
            f.setSkin(null);
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
            Fader f = new Fader();
            StackPane root = new StackPane(f);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            FaderSkin skin = (FaderSkin) f.getSkin();
            Canvas oldCanvas = skin.canvas();
            preWidth[0] = oldCanvas.getWidth();
            f.setSkin(null);
            disposed[0] = skin.isDisposed();
            f.setValue(0.5);
            f.setCurve(Fader.TravelCurve.LINEAR);
            postWidth[0] = oldCanvas.getWidth();
            return null;
        });
        assertThat(disposed[0]).isTrue();
        assertThat(postWidth[0]).isEqualTo(preWidth[0]);
    }
}
