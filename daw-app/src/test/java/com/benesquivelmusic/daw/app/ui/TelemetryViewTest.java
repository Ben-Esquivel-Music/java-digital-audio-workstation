package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.RoomTelemetryDisplay;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class TelemetryViewTest {

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
        TelemetryView view = createOnFxThread();

        assertThat(view).isNotNull();
        assertThat(view.getStyleClass()).contains("content-area");
    }

    @Test
    void shouldShowSetupPanelInitially() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.isDisplayingTelemetry()).isFalse();
        assertThat(view.getChildren().get(0)).isInstanceOf(HBox.class);
        assertThat(view.getChildren().get(1)).isInstanceOf(TelemetrySetupPanel.class);
    }

    @Test
    void shouldContainHeaderBarAndSetupPanelInSetupState() throws Exception {
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
        TelemetryView view = createOnFxThread();

        HBox headerBar = view.getHeaderBar();
        Label headerLabel = (Label) headerBar.getChildren().get(0);
        assertThat(headerLabel.getStyleClass()).contains("panel-header");
    }

    @Test
    void headerShouldContainTelemetryText() throws Exception {
        TelemetryView view = createOnFxThread();

        HBox headerBar = view.getHeaderBar();
        Label headerLabel = (Label) headerBar.getChildren().get(0);
        assertThat(headerLabel.getText()).isEqualTo("Sound Wave Telemetry");
    }

    @Test
    void headerShouldHaveGraphicIcon() throws Exception {
        TelemetryView view = createOnFxThread();

        HBox headerBar = view.getHeaderBar();
        Label headerLabel = (Label) headerBar.getChildren().get(0);
        assertThat(headerLabel.getGraphic()).isNotNull();
    }

    @Test
    void reconfigureButtonShouldBeHiddenInSetupState() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getReconfigureButton().isVisible()).isFalse();
        assertThat(view.getReconfigureButton().isManaged()).isFalse();
    }

    @Test
    void setupPanelShouldFillAvailableSpace() throws Exception {
        TelemetryView view = createOnFxThread();

        Node setupPanel = view.getChildren().get(1);
        assertThat(VBox.getVgrow(setupPanel)).isEqualTo(Priority.ALWAYS);
    }

    @Test
    void shouldExposeRoomTelemetryDisplay() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getDisplay()).isNotNull();
        assertThat(view.getDisplay()).isInstanceOf(RoomTelemetryDisplay.class);
    }

    @Test
    void shouldExposeSetupPanel() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getSetupPanel()).isNotNull();
        assertThat(view.getSetupPanel()).isInstanceOf(TelemetrySetupPanel.class);
    }

    @Test
    void setTelemetryDataWithNonNullShouldSwitchToDisplayState() throws Exception {
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
        TelemetryView view = createOnFxThread();

        runOnFxThread(view::startAnimation);
        runOnFxThread(view::stopAnimation);
    }

    @Test
    void shouldHaveZeroSpacing() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getSpacing()).isEqualTo(0);
    }

    @Test
    void generateWithNoSourcesShouldShowError() throws Exception {
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
        TelemetryView view = createOnFxThread();

        // Should not start animation in setup state
        assertThat(view.isDisplayingTelemetry()).isFalse();
        runOnFxThread(view::startAnimation);
        // No exception means success
    }

    // ── Drag-and-drop integration ────────────────────────────────────

    @Test
    void displayShouldHaveDragCallbacksWired() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getDisplay().getOnSourceDragged()).isNotNull();
        assertThat(view.getDisplay().getOnMicDragged()).isNotNull();
    }

    @Test
    void generateShouldSaveLastConfig() throws Exception {
        TelemetryView view = createOnFxThread();

        runOnFxThread(() -> {
            TelemetrySetupPanel panel = view.getSetupPanel();
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("3.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            panel.getMicNameField().setText("Overhead");
            panel.getMicXField().setText("3.0");
            panel.getMicYField().setText("4.0");
            panel.getMicZField().setText("1.5");
            panel.getAddMicButton().fire();
            view.getGenerateButton().fire();
        });

        assertThat(view.getLastConfig()).isNotNull();
        assertThat(view.getLastConfig().getSoundSources()).hasSize(1);
        assertThat(view.getLastConfig().getMicrophones()).hasSize(1);
    }

    @Test
    void lastConfigShouldBeNullBeforeGeneration() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getLastConfig()).isNull();
    }

    // ── Project integration ──────────────────────────────────────────

    @Test
    void shouldHaveNullProjectByDefault() throws Exception {
        TelemetryView view = createOnFxThread();

        assertThat(view.getProject()).isNull();
    }

    @Test
    void shouldAcceptProjectReference() throws Exception {
        TelemetryView view = createOnFxThread();
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);

        runOnFxThread(() -> view.setProject(project));

        assertThat(view.getProject()).isSameAs(project);
    }

    @Test
    void setProjectShouldPopulateSetupPanelFromRoomConfig() throws Exception {
        TelemetryView view = createOnFxThread();

        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(15, 12, 5), WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("Piano", new Position3D(7, 6, 1), 80));
        config.addMicrophone(new MicrophonePlacement("Overhead", new Position3D(7, 5, 2.5), 0, 0));
        project.setRoomConfiguration(config);

        runOnFxThread(() -> view.setProject(project));

        TelemetrySetupPanel panel = view.getSetupPanel();
        assertThat(panel.getWidthField().getText()).isEqualTo("15.0");
        assertThat(panel.getLengthField().getText()).isEqualTo("12.0");
        assertThat(panel.getHeightField().getText()).isEqualTo("5.0");
        assertThat(panel.getWallMaterialCombo().getValue()).isEqualTo(WallMaterial.CONCRETE);
        assertThat(panel.getSoundSources()).hasSize(1);
        assertThat(panel.getSoundSources().get(0).name()).isEqualTo("Piano");
        assertThat(panel.getMicrophones()).hasSize(1);
        assertThat(panel.getMicrophones().get(0).name()).isEqualTo("Overhead");
    }

    @Test
    void setProjectWithNoRoomConfigShouldNotChangeSetupPanel() throws Exception {
        TelemetryView view = createOnFxThread();

        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        runOnFxThread(() -> view.setProject(project));

        TelemetrySetupPanel panel = view.getSetupPanel();
        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getMicrophones()).isEmpty();
    }

    @Test
    void generateShouldSaveConfigToProject() throws Exception {
        TelemetryView view = createOnFxThread();
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        AtomicBoolean dirtyCallbackFired = new AtomicBoolean(false);

        runOnFxThread(() -> {
            view.setProject(project);
            view.setOnDirtyChanged(() -> dirtyCallbackFired.set(true));
            TelemetrySetupPanel panel = view.getSetupPanel();
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("3.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            panel.getMicNameField().setText("Mic1");
            panel.getMicXField().setText("3.0");
            panel.getMicYField().setText("4.0");
            panel.getMicZField().setText("1.5");
            panel.getAddMicButton().fire();
            view.getGenerateButton().fire();
        });

        assertThat(project.getRoomConfiguration()).isNotNull();
        assertThat(project.getRoomConfiguration().getSoundSources()).hasSize(1);
        assertThat(project.getRoomConfiguration().getMicrophones()).hasSize(1);
        assertThat(project.isDirty()).isTrue();
        assertThat(dirtyCallbackFired.get()).isTrue();
    }

    @Test
    void generateWithoutProjectShouldNotThrow() throws Exception {
        TelemetryView view = createOnFxThread();

        runOnFxThread(() -> {
            TelemetrySetupPanel panel = view.getSetupPanel();
            panel.getSourceNameField().setText("Guitar");
            panel.getSourceXField().setText("2.0");
            panel.getSourceYField().setText("3.0");
            panel.getSourceZField().setText("1.0");
            panel.getAddSourceButton().fire();
            panel.getMicNameField().setText("Mic1");
            panel.getMicXField().setText("3.0");
            panel.getMicYField().setText("4.0");
            panel.getMicZField().setText("1.5");
            panel.getAddMicButton().fire();
            view.getGenerateButton().fire();
        });

        assertThat(view.isDisplayingTelemetry()).isTrue();
    }

    @Test
    void generateShouldPreserveAudienceMembers() throws Exception {
        TelemetryView view = createOnFxThread();
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(5, 4, 1.5), 0, 0));
        config.addAudienceMember(new AudienceMember("Seat A", new Position3D(2, 7, 0)));
        config.addAudienceMember(new AudienceMember("Seat B", new Position3D(4, 7, 0)));
        project.setRoomConfiguration(config);

        runOnFxThread(() -> {
            view.setProject(project);
            view.getGenerateButton().fire();
        });

        RoomConfiguration saved = project.getRoomConfiguration();
        assertThat(saved).isNotNull();
        assertThat(saved.getAudienceMembers()).hasSize(2);
        assertThat(saved.getAudienceMembers().get(0).name()).isEqualTo("Seat A");
        assertThat(saved.getAudienceMembers().get(1).name()).isEqualTo("Seat B");
    }

    @Test
    void setProjectWithNoConfigShouldResetPanelToDefaults() throws Exception {
        TelemetryView view = createOnFxThread();

        // First, set a project with a saved room config
        DawProject project1 = new DawProject("Project1", AudioFormat.CD_QUALITY);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(20, 15, 6), WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("Drums", new Position3D(10, 7, 1), 100));
        project1.setRoomConfiguration(config);

        runOnFxThread(() -> view.setProject(project1));

        // Verify it loaded
        TelemetrySetupPanel panel = view.getSetupPanel();
        assertThat(panel.getWidthField().getText()).isEqualTo("20.0");
        assertThat(panel.getSoundSources()).hasSize(1);

        // Now switch to a project with no room config
        DawProject project2 = new DawProject("Project2", AudioFormat.CD_QUALITY);
        runOnFxThread(() -> view.setProject(project2));

        // Panel should be reset to defaults (STUDIO preset)
        RoomDimensions studioDefaults = RoomPreset.STUDIO.dimensions();
        assertThat(panel.getWidthField().getText()).isEqualTo(String.valueOf(studioDefaults.width()));
        assertThat(panel.getLengthField().getText()).isEqualTo(String.valueOf(studioDefaults.length()));
        assertThat(panel.getHeightField().getText()).isEqualTo(String.valueOf(studioDefaults.height()));
        assertThat(panel.getWallMaterialCombo().getValue()).isEqualTo(RoomPreset.STUDIO.wallMaterial());
        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getMicrophones()).isEmpty();
    }

    @Test
    void setNullProjectShouldResetPanelToDefaults() throws Exception {
        TelemetryView view = createOnFxThread();

        // Set a project with config
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(20, 15, 6), WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("Drums", new Position3D(10, 7, 1), 100));
        project.setRoomConfiguration(config);
        runOnFxThread(() -> view.setProject(project));

        // Now set null project
        runOnFxThread(() -> view.setProject(null));

        TelemetrySetupPanel panel = view.getSetupPanel();
        assertThat(panel.getSoundSources()).isEmpty();
        assertThat(panel.getMicrophones()).isEmpty();
        assertThat(view.getProject()).isNull();
        assertThat(view.getLastConfig()).isNull();
    }
}
