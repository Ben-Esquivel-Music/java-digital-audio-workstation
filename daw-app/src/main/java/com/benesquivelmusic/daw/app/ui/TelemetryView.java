package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.RoomTelemetryDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.SoundWaveTelemetryEngine;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Full-screen Sound Wave Telemetry view with two states.
 *
 * <p><b>Setup state</b> (initial): shows a header bar and a
 * {@link TelemetrySetupPanel} where the user configures the room,
 * sound sources, and microphones, plus a "Generate Telemetry" button.</p>
 *
 * <p><b>Display state</b>: shows a header bar with a "Reconfigure" button
 * and the {@link RoomTelemetryDisplay} canvas with animated visualization.</p>
 *
 * <p>The "Generate Telemetry" button builds a {@link RoomConfiguration} from
 * the panel's current inputs and calls
 * {@link SoundWaveTelemetryEngine#compute(RoomConfiguration)} to produce
 * telemetry data. The resulting data is passed to the display and the view
 * transitions to display state.</p>
 *
 * <p>An internal {@link AnimationTimer} drives continuous rendering of
 * particle animations, sonar ripples, and RT60 glow effects. The timer
 * runs only while the view is in display state.</p>
 *
 * <p>Uses existing CSS classes: {@code .content-area}, {@code .panel-header},
 * {@code .placeholder-label}.</p>
 */
public final class TelemetryView extends VBox {

    private static final String BUTTON_STYLE =
            "-fx-background-color: #3a3a6a; -fx-text-fill: #e0e0e0; "
                    + "-fx-border-color: #5a5a8a; -fx-border-radius: 3; "
                    + "-fx-background-radius: 3; -fx-cursor: hand;";

    private static final String GENERATE_BUTTON_STYLE =
            "-fx-background-color: #7c4dff; -fx-text-fill: #ffffff; "
                    + "-fx-font-size: 14px; -fx-font-weight: bold; "
                    + "-fx-border-radius: 4; -fx-background-radius: 4; "
                    + "-fx-cursor: hand; -fx-padding: 10 20 10 20;";

    private static final String ERROR_STYLE =
            "-fx-text-fill: #ff5252; -fx-font-size: 12px;";

    private final TelemetrySetupPanel setupPanel;
    private final RoomTelemetryDisplay display;
    private final AnimationTimer animationTimer;
    private final HBox headerBar;
    private final Button reconfigureButton;
    private final Button generateButton;
    private final Label generateErrorLabel;
    private long lastNanos;
    private boolean displayingTelemetry;
    private RoomConfiguration lastConfig;
    private final PauseTransition dragDebounce;

    /**
     * Creates a new telemetry view panel.
     * The view starts in setup state showing the room configuration panel.
     */
    public TelemetryView() {
        getStyleClass().add("content-area");
        setSpacing(0);

        // Header label
        Label headerLabel = new Label("Sound Wave Telemetry");
        headerLabel.getStyleClass().add("panel-header");
        headerLabel.setGraphic(IconNode.of(DawIcon.SURROUND, 16));
        headerLabel.setPadding(new Insets(6, 10, 6, 10));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Reconfigure button — visible only in display state
        reconfigureButton = new Button("Reconfigure");
        reconfigureButton.setStyle(BUTTON_STYLE);
        reconfigureButton.setOnAction(event -> showSetupState());
        reconfigureButton.setVisible(false);
        reconfigureButton.setManaged(false);
        reconfigureButton.setPadding(new Insets(4, 10, 4, 10));

        headerBar = new HBox(headerLabel, spacer, reconfigureButton);
        headerBar.setAlignment(Pos.CENTER_LEFT);

        // Setup panel — room configuration form
        setupPanel = new TelemetrySetupPanel();
        VBox.setVgrow(setupPanel, Priority.ALWAYS);

        // Generate error label — shown when validation fails
        generateErrorLabel = new Label();
        generateErrorLabel.setStyle(ERROR_STYLE);
        generateErrorLabel.setWrapText(true);
        generateErrorLabel.setVisible(false);
        generateErrorLabel.setManaged(false);
        generateErrorLabel.setPadding(new Insets(4, 10, 0, 10));

        // Generate button — triggers telemetry computation
        generateButton = new Button("Generate Telemetry");
        generateButton.setStyle(GENERATE_BUTTON_STYLE);
        generateButton.setOnAction(event -> generateTelemetry());
        generateButton.setMaxWidth(Double.MAX_VALUE);
        generateButton.setPadding(new Insets(10, 20, 10, 20));

        // Display canvas — used in display state
        display = new RoomTelemetryDisplay();
        VBox.setVgrow(display, Priority.ALWAYS);

        // Wire drag-and-drop callbacks for interactive repositioning
        display.setOnSourceDragged(this::handleSourceDragged);
        display.setOnMicDragged(this::handleMicDragged);

        // Debounce timer coalesces rapid drag events to avoid recomputing
        // telemetry on every mouse-drag pixel, which would stall the FX thread.
        dragDebounce = new PauseTransition(Duration.millis(50));
        dragDebounce.setOnFinished(event -> {
            // Only recompute if the display is currently visible (display state).
            // This avoids unnecessary recomputes if the view has returned to setup state.
            if (display.isVisible()) {
                recomputeTelemetry();
            }
        });

        // Animation timer drives particle/ripple/pulse animations
        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNanos == 0) {
                    lastNanos = now;
                    return;
                }
                double deltaSecs = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                display.updateAnimation(deltaSecs);
            }
        };

        // Start in setup state
        showSetupState();
    }

    // ── State transitions ────────────────────────────────────────────

    /**
     * Transitions to setup state, showing the configuration panel.
     * Stops the animation timer if it was running.
     */
    private void showSetupState() {
        displayingTelemetry = false;
        animationTimer.stop();
        reconfigureButton.setVisible(false);
        reconfigureButton.setManaged(false);
        getChildren().setAll(headerBar, setupPanel, generateErrorLabel, generateButton);
    }

    /**
     * Transitions to display state, showing the telemetry visualization.
     * Starts the animation timer.
     */
    private void showDisplayState() {
        displayingTelemetry = true;
        reconfigureButton.setVisible(true);
        reconfigureButton.setManaged(true);
        generateErrorLabel.setVisible(false);
        generateErrorLabel.setManaged(false);
        getChildren().setAll(headerBar, display);
        lastNanos = 0;
        animationTimer.start();
    }

    // ── Generate telemetry ───────────────────────────────────────────

    /**
     * Validates setup panel inputs, builds a {@link RoomConfiguration},
     * computes telemetry data, and transitions to display state.
     */
    private void generateTelemetry() {
        RoomDimensions dimensions = setupPanel.getRoomDimensions();
        WallMaterial material = setupPanel.getSelectedWallMaterial();

        StringBuilder errors = new StringBuilder();
        if (dimensions == null) {
            errors.append("Room dimensions are invalid. ");
        }
        if (material == null) {
            errors.append("Please select a wall material. ");
        }
        if (setupPanel.getSoundSources().isEmpty()) {
            errors.append("At least one sound source is required. ");
        }
        if (setupPanel.getMicrophones().isEmpty()) {
            errors.append("At least one microphone is required. ");
        }

        if (!errors.isEmpty()) {
            generateErrorLabel.setText(errors.toString().trim());
            generateErrorLabel.setVisible(true);
            generateErrorLabel.setManaged(true);
            return;
        }

        generateErrorLabel.setVisible(false);
        generateErrorLabel.setManaged(false);

        RoomConfiguration config = new RoomConfiguration(dimensions, material);
        for (SoundSource source : setupPanel.getSoundSources()) {
            config.addSoundSource(source);
        }
        for (MicrophonePlacement mic : setupPanel.getMicrophones()) {
            config.addMicrophone(mic);
        }

        lastConfig = config;
        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);
        display.setTelemetryData(data);
        showDisplayState();
    }

    // ── Drag-and-drop handlers ───────────────────────────────────────

    /**
     * Handles a sound source being dragged to a new position on the canvas.
     * Rebuilds the room configuration with the updated source position and
     * schedules debounced telemetry recomputation.
     */
    private void handleSourceDragged(String sourceName, Position3D newPosition) {
        if (lastConfig == null) return;

        RoomConfiguration updated = new RoomConfiguration(
                lastConfig.getDimensions(), lastConfig.getWallMaterial());
        for (SoundSource src : lastConfig.getSoundSources()) {
            if (src.name().equals(sourceName)) {
                updated.addSoundSource(new SoundSource(src.name(), newPosition, src.powerDb()));
            } else {
                updated.addSoundSource(src);
            }
        }
        for (MicrophonePlacement mic : lastConfig.getMicrophones()) {
            updated.addMicrophone(mic);
        }

        lastConfig = updated;
        dragDebounce.playFromStart();
    }

    /**
     * Handles a microphone being dragged to a new position on the canvas.
     * Rebuilds the room configuration with the updated mic position and
     * schedules debounced telemetry recomputation.
     */
    private void handleMicDragged(String micName, Position3D newPosition) {
        if (lastConfig == null) return;

        RoomConfiguration updated = new RoomConfiguration(
                lastConfig.getDimensions(), lastConfig.getWallMaterial());
        for (SoundSource src : lastConfig.getSoundSources()) {
            updated.addSoundSource(src);
        }
        for (MicrophonePlacement mic : lastConfig.getMicrophones()) {
            if (mic.name().equals(micName)) {
                updated.addMicrophone(new MicrophonePlacement(
                        mic.name(), newPosition, mic.azimuth(), mic.elevation()));
            } else {
                updated.addMicrophone(mic);
            }
        }

        lastConfig = updated;
        dragDebounce.playFromStart();
    }

    /**
     * Recomputes telemetry data from the current {@link #lastConfig} and
     * updates the display. Called by the debounce timer after drag events
     * settle.
     */
    private void recomputeTelemetry() {
        if (lastConfig == null) return;
        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(lastConfig);
        display.setTelemetryData(data);
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Updates the telemetry data displayed in the view.
     *
     * <p>If {@code data} is non-null the view transitions to display state
     * and shows the animated visualization. If {@code data} is {@code null}
     * the view shows (or remains in) setup state.</p>
     *
     * @param data the latest telemetry snapshot (may be {@code null} to show setup)
     */
    public void setTelemetryData(RoomTelemetryData data) {
        display.setTelemetryData(data);
        if (data != null) {
            showDisplayState();
        } else {
            showSetupState();
        }
    }

    /**
     * Returns the underlying {@link RoomTelemetryDisplay}.
     *
     * @return the telemetry display canvas
     */
    public RoomTelemetryDisplay getDisplay() {
        return display;
    }

    /**
     * Returns the setup panel for room configuration.
     *
     * @return the telemetry setup panel
     */
    public TelemetrySetupPanel getSetupPanel() {
        return setupPanel;
    }

    /**
     * Returns whether the view is currently displaying telemetry data.
     *
     * @return {@code true} if in display state, {@code false} if in setup state
     */
    public boolean isDisplayingTelemetry() {
        return displayingTelemetry;
    }

    /**
     * Returns the "Reconfigure" button shown in display state.
     *
     * @return the reconfigure button
     */
    public Button getReconfigureButton() {
        return reconfigureButton;
    }

    /**
     * Returns the "Generate Telemetry" button shown in setup state.
     *
     * @return the generate button
     */
    public Button getGenerateButton() {
        return generateButton;
    }

    /**
     * Returns the error label shown when generation validation fails.
     *
     * @return the generate error label
     */
    public Label getGenerateErrorLabel() {
        return generateErrorLabel;
    }

    /**
     * Returns the header bar containing the title label and reconfigure button.
     *
     * @return the header bar
     */
    public HBox getHeaderBar() {
        return headerBar;
    }

    /**
     * Returns the last generated room configuration, or {@code null} if
     * telemetry has not yet been generated.
     *
     * @return the last room configuration
     */
    public RoomConfiguration getLastConfig() {
        return lastConfig;
    }

    /**
     * Starts the animation timer for continuous rendering.
     * Call this when the telemetry view becomes the active view.
     * The timer only runs when the view is in display state.
     */
    public void startAnimation() {
        if (displayingTelemetry) {
            lastNanos = 0;
            animationTimer.start();
        }
    }

    /**
     * Stops the animation timer to conserve resources.
     * Call this when the user switches away from the telemetry view.
     */
    public void stopAnimation() {
        animationTimer.stop();
    }
}
