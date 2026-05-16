package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integrated {@link LevelMeter} on a {@link Fader}: consumers wire
 * the meter's {@code peakDb} property and the meter renders inside the
 * fader's footprint at a fixed 4 px gap to the right of the column.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FaderMeterIntegrationTest {

    @Test
    void getMeterReturnsTheSameInstanceLazily() {
        Fader f = runOnFxThread(Fader::new);
        assertThat(runOnFxThread(f::isMeterCreated)).isFalse();
        LevelMeter m1 = runOnFxThread(f::getMeter);
        LevelMeter m2 = runOnFxThread(f::getMeter);
        assertThat(m1).as("getMeter() is idempotent").isSameAs(m2);
        assertThat(runOnFxThread(f::isMeterCreated)).isTrue();
    }

    @Test
    void meterIsAttachedAsChildWhenShowMeterIsTrue() {
        boolean[] attached = new boolean[1];
        runOnFxThread(() -> {
            Fader f = Fader.create()
                    .showMeter(true).size("mixer").build();
            StackPane root = new StackPane(f);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            LevelMeter m = f.getMeter();
            attached[0] = m.getParent() != null;
            return null;
        });
        assertThat(attached[0]).as("meter should be a scene-graph child").isTrue();
    }

    @Test
    void showMeterFalseRemovesMeterFromSceneGraph() {
        boolean[] attached = new boolean[1];
        runOnFxThread(() -> {
            Fader f = Fader.create()
                    .showMeter(true).build();
            StackPane root = new StackPane(f);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            // Force meter creation, then disable.
            f.getMeter();
            f.setShowMeter(false);
            root.applyCss();
            root.layout();
            attached[0] = f.getMeter().getParent() != null;
            return null;
        });
        assertThat(attached[0]).as("meter detached when showMeter=false").isFalse();
    }

    @Test
    void peakDbBindingFromExternalSourceUpdatesMeter() {
        Fader f = runOnFxThread(() -> {
            Fader x = Fader.create().showMeter(true).build();
            StackPane root = new StackPane(x);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            return x;
        });
        // Drive the meter via direct setPeakDb (the relay would also work
        // but the property write is what consumers do via bind()).
        runOnFxThread(() -> {
            f.getMeter().setPeakDb(-6.0);
            return null;
        });
        assertThat(f.getMeter().getPeakDb()).isEqualTo(-6.0);
    }

    @Test
    void performanceSizeFaderPropagatesPerformanceSizeToMeter() {
        boolean[] hasClass = new boolean[1];
        runOnFxThread(() -> {
            Fader f = Fader.create().showMeter(true).size("performance").build();
            StackPane root = new StackPane(f);
            new Scene(root, 100, 400);
            root.applyCss();
            root.layout();
            hasClass[0] = f.getMeter().getStyleClass().contains("size-performance");
            return null;
        });
        assertThat(hasClass[0]).isTrue();
    }

    @Test
    void mixerSizeFaderPropagatesChannelSizeToMeter() {
        boolean[] hasClass = new boolean[1];
        runOnFxThread(() -> {
            Fader f = Fader.create().showMeter(true).size("mixer").build();
            StackPane root = new StackPane(f);
            new Scene(root, 60, 200);
            root.applyCss();
            root.layout();
            hasClass[0] = f.getMeter().getStyleClass().contains("size-channel");
            return null;
        });
        assertThat(hasClass[0]).isTrue();
    }
}
