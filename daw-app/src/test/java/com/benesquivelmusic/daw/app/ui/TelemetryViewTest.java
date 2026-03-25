package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.RoomTelemetryDisplay;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryViewTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized — will verify below
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        // Verify the FX Application Thread is actually processing events.
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
                // Platform.runLater failed — toolkit is not functional
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private TelemetryView createOnFxThread() throws Exception {
        AtomicReference<TelemetryView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new TelemetryView());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldHaveContentAreaStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view).isNotNull();
        assertThat(view.getStyleClass()).contains("content-area");
    }

    @Test
    void shouldShowSetupPanelInitially() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getChildren().get(0)).isInstanceOf(HBox.class);
        assertThat(view.getChildren().get(1)).isInstanceOf(TelemetrySetupPanel.class);
    }

    @Test
    void shouldContainHeaderBarAndSetupPanelInSetupState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // Setup state: headerBar, setupPanel, generateErrorLabel, generateButton
        assertThat(view.getChildren()).hasSize(4);
        assertThat(view.getChildren().get(0)).isInstanceOf(HBox.class);
        assertThat(view.getChildren().get(1)).isInstanceOf(TelemetrySetupPanel.class);
        assertThat(view.getChildren().get(2)).isInstanceOf(Label.class);
        assertThat(view.getChildren().get(3)).isInstanceOf(Button.class);
    }

    @Test
    void headerBarShouldContainLabelWithPanelHeaderStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        HBox headerBar = view.getHeaderBar();
        Label headerLabel = (Label) headerBar.getChildren().get(0);
        assertThat(headerLabel.getStyleClass()).contains("panel-header");
    }

    @Test
    void headerShouldContainTelemetryText() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        HBox headerBar = view.getHeaderBar();
        Label headerLabel = (Label) headerBar.getChildren().get(0);
        assertThat(headerLabel.getText()).isEqualTo("Sound Wave Telemetry");
    }

    @Test
    void headerShouldHaveGraphicIcon() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        HBox headerBar = view.getHeaderBar();
        Label headerLabel = (Label) headerBar.getChildren().get(0);
        assertThat(headerLabel.getGraphic()).isNotNull();
    }

    @Test
    void reconfigureButtonShouldBeHiddenInSetupState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view.getReconfigureButton().isVisible()).isFalse();
        assertThat(view.getReconfigureButton().isManaged()).isFalse();
    }

    @Test
    void setupPanelShouldFillAvailableSpace() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        Node setupPanel = view.getChildren().get(1);
        assertThat(VBox.getVgrow(setupPanel)).isEqualTo(Priority.ALWAYS);
    }

    @Test
    void shouldExposeRoomTelemetryDisplay() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view.getDisplay()).isNotNull();
        assertThat(view.getDisplay()).isInstanceOf(RoomTelemetryDisplay.class);
    }

    @Test
    void setTelemetryDataWithNonNullShouldSwitchToDisplayState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        RoomDimensions dimensions = new RoomDimensions(10.0, 8.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "Source1", "Mic1",
                List.of(new Position3D(1.0, 2.0, 1.5), new Position3D(5.0, 4.0, 1.5)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dimensions, List.of(path), 0.5, List.of());

        runOnFxThread(() -> view.setTelemetryData(data));

        assertThat(view.isDisplayingTelemetry()).isTrue();
        // Display state: headerBar, display
        assertThat(view.getChildren()).hasSize(2);
        assertThat(view.getChildren().get(1)).isInstanceOf(RoomTelemetryDisplay.class);
        assertThat(view.getReconfigureButton().isVisible()).isTrue();
    }

    @Test
    void setTelemetryDataWithNullShouldShowSetupState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // First switch to display state
        RoomDimensions dimensions = new RoomDimensions(10.0, 8.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "Source1", "Mic1",
                List.of(new Position3D(1.0, 2.0, 1.5), new Position3D(5.0, 4.0, 1.5)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dimensions, List.of(path), 0.5, List.of());
        runOnFxThread(() -> view.setTelemetryData(data));
        assertThat(view.isDisplayingTelemetry()).isTrue();

        // Now set null — should return to setup state
        runOnFxThread(() -> view.setTelemetryData(null));
        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getReconfigureButton().isVisible()).isFalse();
    }

    @Test
    void displayShouldFillAvailableSpaceInDisplayState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        RoomDimensions dimensions = new RoomDimensions(10.0, 8.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "Source1", "Mic1",
                List.of(new Position3D(1.0, 2.0, 1.5), new Position3D(5.0, 4.0, 1.5)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dimensions, List.of(path), 0.5, List.of());

        runOnFxThread(() -> view.setTelemetryData(data));

        Node display = view.getChildren().get(1);
        assertThat(VBox.getVgrow(display)).isEqualTo(Priority.ALWAYS);
    }

    @Test
    void startAndStopAnimationShouldNotThrow() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        runOnFxThread(view::startAnimation);
        runOnFxThread(view::stopAnimation);
    }

    @Test
    void shouldHaveZeroSpacing() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view.getSpacing()).isEqualTo(0);
    }

    @Test
    void generateWithNoSourcesShouldShowError() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // Add a mic but no sources
        runOnFxThread(() -> {
            TelemetrySetupPanel panel = view.getSetupPanel();
            panel.getMicNameField().setText("Mic1");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getAddMicButton().fire();
            view.getGenerateButton().fire();
        });

        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getGenerateErrorLabel().isVisible()).isTrue();
        assertThat(view.getGenerateErrorLabel().getText())
                .contains("At least one sound source is required");
    }

    @Test
    void generateWithNoMicsShouldShowError() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // Add a source but no mics
        runOnFxThread(() -> {
            TelemetrySetupPanel panel = view.getSetupPanel();
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("1.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            view.getGenerateButton().fire();
        });

        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getGenerateErrorLabel().isVisible()).isTrue();
        assertThat(view.getGenerateErrorLabel().getText())
                .contains("At least one microphone is required");
    }

    @Test
    void generateWithValidInputsShouldSwitchToDisplayState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        runOnFxThread(() -> {
            TelemetrySetupPanel panel = view.getSetupPanel();
            // Add a source
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("3.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            // Add a mic
            panel.getMicNameField().setText("Overhead");
            panel.getMicXField().setText("3.0");
            panel.getMicYField().setText("4.0");
            panel.getMicZField().setText("1.5");
            panel.getAddMicButton().fire();
            // Generate
            view.getGenerateButton().fire();
        });

        assertThat(view.isDisplayingTelemetry()).isTrue();
        assertThat(view.getChildren()).hasSize(2);
        assertThat(view.getChildren().get(1)).isInstanceOf(RoomTelemetryDisplay.class);
        assertThat(view.getReconfigureButton().isVisible()).isTrue();
    }

    @Test
    void reconfigureButtonShouldReturnToSetupState() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // Switch to display state first
        RoomDimensions dimensions = new RoomDimensions(10.0, 8.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "Source1", "Mic1",
                List.of(new Position3D(1.0, 2.0, 1.5), new Position3D(5.0, 4.0, 1.5)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dimensions, List.of(path), 0.5, List.of());

        runOnFxThread(() -> view.setTelemetryData(data));
        assertThat(view.isDisplayingTelemetry()).isTrue();

        // Click reconfigure
        runOnFxThread(() -> view.getReconfigureButton().fire());

        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getReconfigureButton().isVisible()).isFalse();
        assertThat(view.getChildren().get(1)).isInstanceOf(TelemetrySetupPanel.class);
    }

    @Test
    void generateWithInvalidDimensionsShouldShowError() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        runOnFxThread(() -> {
            TelemetrySetupPanel panel = view.getSetupPanel();
            // Set invalid dimensions
            panel.getWidthField().setText("abc");
            // Add source and mic
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("1.0");
            panel.getSourceYField().setText("1.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            panel.getMicNameField().setText("Mic1");
            panel.getMicXField().setText("1.0");
            panel.getMicYField().setText("1.0");
            panel.getMicZField().setText("1.0");
            panel.getAddMicButton().fire();
            view.getGenerateButton().fire();
        });

        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getGenerateErrorLabel().isVisible()).isTrue();
        assertThat(view.getGenerateErrorLabel().getText())
                .contains("Room dimensions are invalid");
    }

    @Test
    void startAnimationInSetupStateShouldNotThrow() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // Should not start animation in setup state
        assertThat(view.isDisplayingTelemetry()).isFalse();
        runOnFxThread(view::startAnimation);
        // No exception means success
    }
}
