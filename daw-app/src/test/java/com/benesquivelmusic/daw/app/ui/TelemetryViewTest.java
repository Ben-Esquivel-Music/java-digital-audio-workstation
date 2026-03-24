package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.RoomTelemetryDisplay;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
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
    void shouldContainHeaderAndDisplay() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view.getChildren()).hasSize(2);
        assertThat(view.getChildren().get(0)).isInstanceOf(Label.class);
        assertThat(view.getChildren().get(1)).isInstanceOf(RoomTelemetryDisplay.class);
    }

    @Test
    void headerShouldHavePanelHeaderStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        Label header = (Label) view.getChildren().get(0);
        assertThat(header.getStyleClass()).contains("panel-header");
    }

    @Test
    void headerShouldContainTelemetryText() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        Label header = (Label) view.getChildren().get(0);
        assertThat(header.getText()).isEqualTo("Sound Wave Telemetry");
    }

    @Test
    void headerShouldHaveGraphicIcon() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        Label header = (Label) view.getChildren().get(0);
        assertThat(header.getGraphic()).isNotNull();
    }

    @Test
    void displayShouldFillAvailableSpace() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        Node display = view.getChildren().get(1);
        assertThat(VBox.getVgrow(display)).isEqualTo(Priority.ALWAYS);
    }

    @Test
    void shouldExposeRoomTelemetryDisplay() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        assertThat(view.getDisplay()).isNotNull();
        assertThat(view.getDisplay()).isInstanceOf(RoomTelemetryDisplay.class);
        assertThat(view.getDisplay()).isSameAs(view.getChildren().get(1));
    }

    @Test
    void setTelemetryDataShouldDelegateToDisplay() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        RoomDimensions dimensions = new RoomDimensions(10.0, 8.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "Source1", "Mic1",
                List.of(new Position3D(1.0, 2.0, 1.5), new Position3D(5.0, 4.0, 1.5)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dimensions, List.of(path), 0.5, List.of());

        // Should not throw when passing data through
        runOnFxThread(() -> view.setTelemetryData(data));
    }

    @Test
    void setTelemetryDataShouldAcceptNull() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        TelemetryView view = createOnFxThread();

        // Should not throw when passing null
        runOnFxThread(() -> view.setTelemetryData(null));
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
}
