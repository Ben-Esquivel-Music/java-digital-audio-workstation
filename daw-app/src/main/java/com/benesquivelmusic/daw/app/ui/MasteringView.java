package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mastering.MasteringChainPresets;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A dedicated mastering view that displays the mastering signal chain as a
 * horizontal chain of processing stages with preset management and A/B comparison.
 *
 * <p>The view provides:
 * <ul>
 *   <li>A horizontal chain of processing stage cards</li>
 *   <li>Preset selector to load genre-specific mastering chain presets</li>
 *   <li>Per-stage bypass toggles for A/B comparison of individual stages</li>
 *   <li>A global A/B toggle to compare processed vs. dry master bus audio</li>
 *   <li>Per-stage gain reduction and level metering placeholders</li>
 *   <li>Drag-to-reorder stage support via move-up/move-down buttons</li>
 *   <li>Integrated loudness metering display</li>
 * </ul>
 *
 * <p>Uses existing CSS classes: {@code .content-area}, {@code .panel-header},
 * {@code .mixer-channel}.</p>
 */
public final class MasteringView extends VBox {

    private static final double STAGE_CARD_WIDTH = 140;
    private static final double METER_WIDTH = 10;
    private static final double METER_HEIGHT = 80;
    private static final double CONTROL_ICON_SIZE = 14;

    private static final String ACTIVE_BYPASS_STYLE =
            "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;";

    private final MasteringChain masteringChain;
    private final HBox stageContainer;
    private final ComboBox<String> presetSelector;
    private final ToggleButton abToggle;
    private final LoudnessDisplay loudnessDisplay;
    private final Label statusLabel;
    private final List<MasteringChainPreset> availablePresets;

    /**
     * Creates a new mastering view with an empty mastering chain.
     */
    public MasteringView() {
        this(new MasteringChain());
    }

    /**
     * Creates a new mastering view bound to the given mastering chain.
     *
     * @param masteringChain the mastering chain to visualize and control
     */
    public MasteringView(MasteringChain masteringChain) {
        this.masteringChain = Objects.requireNonNull(masteringChain, "masteringChain must not be null");
        getStyleClass().add("content-area");
        setSpacing(0);

        availablePresets = new ArrayList<>(MasteringChainPresets.allDefaults());

        // ── Header bar ──────────────────────────────────────────────────────
        Label headerLabel = new Label("Mastering");
        headerLabel.getStyleClass().add("panel-header");
        headerLabel.setGraphic(IconNode.of(DawIcon.LIMITER, 16));
        headerLabel.setPadding(new Insets(6, 10, 6, 10));

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        // ── Status bar (initialized early — referenced by event handlers) ───
        statusLabel = new Label("Load a preset to begin mastering");
        statusLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        statusLabel.setPadding(new Insets(4, 10, 6, 10));

        // Preset selector
        List<String> presetNames = new ArrayList<>();
        presetNames.add("-- Select Preset --");
        for (MasteringChainPreset preset : availablePresets) {
            presetNames.add(preset.name());
        }
        presetSelector = new ComboBox<>(FXCollections.observableArrayList(presetNames));
        presetSelector.getSelectionModel().selectFirst();
        presetSelector.setTooltip(new Tooltip("Load a mastering chain preset"));
        presetSelector.setOnAction(event -> onPresetSelected());

        // A/B toggle button
        abToggle = new ToggleButton("A/B");
        abToggle.setTooltip(new Tooltip("Toggle A/B comparison — bypass entire chain"));
        abToggle.setOnAction(event -> {
            boolean bypassed = abToggle.isSelected();
            masteringChain.setChainBypassed(bypassed);
            abToggle.setStyle(bypassed ? ACTIVE_BYPASS_STYLE : "");
            statusLabel.setText(bypassed ? "Chain bypassed (B) — dry signal" : "Chain active (A) — processed signal");
        });

        HBox headerBar = new HBox(8, headerLabel, headerSpacer, presetSelector, abToggle);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(4, 10, 4, 0));

        // ── Stage chain area ────────────────────────────────────────────────
        stageContainer = new HBox(8);
        stageContainer.setAlignment(Pos.TOP_LEFT);
        stageContainer.setPadding(new Insets(10));

        ScrollPane stageScroll = new ScrollPane(stageContainer);
        stageScroll.setFitToHeight(true);
        stageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        stageScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        stageScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(stageScroll, Priority.ALWAYS);

        // ── Loudness meter section ──────────────────────────────────────────
        Label meterHeader = new Label("Loudness");
        meterHeader.getStyleClass().add("panel-header");
        meterHeader.setGraphic(IconNode.of(DawIcon.LOUDNESS_METER, 14));
        meterHeader.setPadding(new Insets(6, 10, 4, 10));

        loudnessDisplay = new LoudnessDisplay();
        loudnessDisplay.setPrefHeight(100);
        loudnessDisplay.setMinHeight(80);

        VBox meterSection = new VBox(2, meterHeader, loudnessDisplay);
        meterSection.setPadding(new Insets(0, 10, 8, 10));

        getChildren().addAll(headerBar, new Separator(), stageScroll, meterSection, statusLabel);
    }

    /**
     * Rebuilds the stage cards from the current mastering chain state.
     *
     * <p>Call this method after modifying the chain to keep the view
     * synchronized with the model.</p>
     */
    public void refresh() {
        stageContainer.getChildren().clear();
        List<MasteringChain.Stage> stages = masteringChain.getStages();
        for (int i = 0; i < stages.size(); i++) {
            MasteringChain.Stage stage = stages.get(i);
            stageContainer.getChildren().add(buildStageCard(stage, i, stages.size()));
            if (i < stages.size() - 1) {
                stageContainer.getChildren().add(buildChainArrow());
            }
        }
    }

    /**
     * Returns the mastering chain model backing this view.
     *
     * @return the mastering chain
     */
    public MasteringChain getMasteringChain() {
        return masteringChain;
    }

    /**
     * Returns the container holding the stage cards.
     * Visible for testing.
     *
     * @return the stage card container
     */
    HBox getStageContainer() {
        return stageContainer;
    }

    /**
     * Returns the preset selector combo box.
     * Visible for testing.
     *
     * @return the preset selector
     */
    ComboBox<String> getPresetSelector() {
        return presetSelector;
    }

    /**
     * Returns the A/B toggle button.
     * Visible for testing.
     *
     * @return the A/B toggle button
     */
    ToggleButton getAbToggle() {
        return abToggle;
    }

    /**
     * Returns the loudness display.
     * Visible for testing.
     *
     * @return the loudness display
     */
    LoudnessDisplay getLoudnessDisplay() {
        return loudnessDisplay;
    }

    /**
     * Returns the status label.
     * Visible for testing.
     *
     * @return the status label
     */
    Label getStatusLabel() {
        return statusLabel;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void onPresetSelected() {
        int selectedIndex = presetSelector.getSelectionModel().getSelectedIndex();
        if (selectedIndex <= 0) {
            return;
        }
        MasteringChainPreset preset = availablePresets.get(selectedIndex - 1);
        loadPreset(preset);
    }

    /**
     * Loads a mastering chain preset into the chain and refreshes the view.
     *
     * <p>Clears the existing chain and rebuilds it from the preset's stage
     * configurations. Since the view layer does not instantiate real audio
     * processors, each stage is populated with a stub no-op processor.</p>
     *
     * @param preset the preset to load
     */
    private void loadPreset(MasteringChainPreset preset) {
        // Clear existing stages
        while (!masteringChain.isEmpty()) {
            masteringChain.removeStage(0);
        }
        // Add stages from preset
        for (MasteringStageConfig config : preset.stages()) {
            masteringChain.addStage(config.stageType(), config.name(), new NoOpProcessor());
        }
        refresh();
        statusLabel.setText("Loaded preset: " + preset.name() + " (" + preset.genre() + ")");
    }

    private VBox buildStageCard(MasteringChain.Stage stage, int index, int totalStages) {
        VBox card = new VBox(4);
        card.getStyleClass().add("mixer-channel");
        card.setAlignment(Pos.TOP_CENTER);
        card.setPrefWidth(STAGE_CARD_WIDTH);
        card.setMinWidth(STAGE_CARD_WIDTH);

        // Stage type label
        Label typeLabel = new Label(stage.getType().name().replace("_", " "));
        typeLabel.setStyle("-fx-text-fill: #b0b0b0; -fx-font-size: 9px;");
        typeLabel.setMaxWidth(STAGE_CARD_WIDTH - 12);

        // Stage name label
        Label nameLabel = new Label(stage.getName());
        nameLabel.getStyleClass().add("mixer-channel-name");
        nameLabel.setMaxWidth(STAGE_CARD_WIDTH - 12);
        nameLabel.setStyle("-fx-font-weight: bold;");

        // Stage icon
        Node stageIcon = stageTypeIcon(stage.getType());

        // Level meter
        LevelMeterDisplay levelMeter = new LevelMeterDisplay(true);
        levelMeter.setPrefWidth(METER_WIDTH);
        levelMeter.setMinWidth(METER_WIDTH);
        levelMeter.setMaxWidth(METER_WIDTH);
        levelMeter.setPrefHeight(METER_HEIGHT);
        levelMeter.setMinHeight(METER_HEIGHT);

        // Gain reduction label (placeholder for real-time metering)
        Label grLabel = new Label("GR: 0.0 dB");
        grLabel.setStyle("-fx-text-fill: #00e676; -fx-font-size: 10px;");

        // Bypass button
        Button bypassBtn = new Button("Bypass");
        bypassBtn.getStyleClass().add("track-mute-button");
        bypassBtn.setTooltip(new Tooltip("Bypass " + stage.getName()));
        bypassBtn.setOnAction(event -> {
            boolean bypassed = !stage.isBypassed();
            stage.setBypassed(bypassed);
            bypassBtn.setStyle(bypassed ? ACTIVE_BYPASS_STYLE : "");
            statusLabel.setText(stage.getName() + (bypassed ? " bypassed" : " active"));
        });
        if (stage.isBypassed()) {
            bypassBtn.setStyle(ACTIVE_BYPASS_STYLE);
        }

        // Move buttons for reordering
        HBox moveRow = new HBox(2);
        moveRow.setAlignment(Pos.CENTER);
        if (index > 0) {
            Button moveLeft = new Button("\u25C0");
            moveLeft.setTooltip(new Tooltip("Move left"));
            moveLeft.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4;");
            moveLeft.setOnAction(event -> moveStage(index, index - 1));
            moveRow.getChildren().add(moveLeft);
        }
        if (index < totalStages - 1) {
            Button moveRight = new Button("\u25B6");
            moveRight.setTooltip(new Tooltip("Move right"));
            moveRight.setStyle("-fx-font-size: 9px; -fx-padding: 2 4 2 4;");
            moveRight.setOnAction(event -> moveStage(index, index + 1));
            moveRow.getChildren().add(moveRight);
        }

        card.getChildren().addAll(typeLabel, nameLabel, stageIcon, levelMeter, grLabel, bypassBtn, moveRow);
        return card;
    }

    private void moveStage(int fromIndex, int toIndex) {
        MasteringChain.Stage stage = masteringChain.removeStage(fromIndex);
        masteringChain.insertStage(toIndex, stage.getType(), stage.getName(), stage.getProcessor());
        // Preserve bypass state
        masteringChain.getStages().get(toIndex).setBypassed(stage.isBypassed());
        refresh();
        statusLabel.setText("Moved " + stage.getName() + " to position " + (toIndex + 1));
    }

    private static Node buildChainArrow() {
        Label arrow = new Label("\u279C");
        arrow.setStyle("-fx-text-fill: #7c4dff; -fx-font-size: 20px;");
        arrow.setPadding(new Insets(40, 2, 0, 2));
        return arrow;
    }

    private static Node stageTypeIcon(MasteringStageType type) {
        return switch (type) {
            case GAIN_STAGING    -> IconNode.of(DawIcon.GAIN, CONTROL_ICON_SIZE);
            case EQ_CORRECTIVE   -> IconNode.of(DawIcon.EQ, CONTROL_ICON_SIZE);
            case COMPRESSION     -> IconNode.of(DawIcon.COMPRESSOR, CONTROL_ICON_SIZE);
            case EQ_TONAL        -> IconNode.of(DawIcon.EQUALIZER, CONTROL_ICON_SIZE);
            case STEREO_IMAGING  -> IconNode.of(DawIcon.STEREO, CONTROL_ICON_SIZE);
            case LIMITING        -> IconNode.of(DawIcon.LIMITER, CONTROL_ICON_SIZE);
            case DITHERING       -> IconNode.of(DawIcon.NOISE_GATE, CONTROL_ICON_SIZE);
        };
    }

    /**
     * No-op audio processor used as a placeholder in the mastering view.
     *
     * <p>The view layer does not process audio — it provides a visual
     * representation of the mastering chain. Real processors are attached
     * by the audio engine at playback time.</p>
     */
    private static final class NoOpProcessor implements com.benesquivelmusic.daw.sdk.audio.AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
            // no state to reset
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }
}
