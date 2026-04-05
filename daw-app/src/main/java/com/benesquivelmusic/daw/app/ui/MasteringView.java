package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mastering.MasteringChainPresets;
import com.benesquivelmusic.daw.core.mastering.MasteringProcessorFactory;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageConfig;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
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
    private static final double DEFAULT_SAMPLE_RATE = 44100.0;
    private static final int DEFAULT_CHANNELS = 2;
    private static final long METER_UPDATE_INTERVAL_NS = 33_333_333L; // ~30 Hz

    private static final String ACTIVE_BYPASS_STYLE =
            "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;";

    private final MasteringChain masteringChain;
    private final HBox stageContainer;
    private final ComboBox<String> presetSelector;
    private final ToggleButton abToggle;
    private final LoudnessDisplay loudnessDisplay;
    private final Label statusLabel;
    private final List<MasteringChainPreset> availablePresets;
    private final double sampleRate;
    private final int channels;

    // Per-stage meter references for real-time updates
    private final List<Label> grLabels = new ArrayList<>();
    private final List<LevelMeterDisplay> levelMeters = new ArrayList<>();
    private AnimationTimer meterTimer;

    /**
     * Creates a new mastering view with an empty mastering chain.
     *
     * <p><strong>Note:</strong> Uses default sample rate (44100 Hz) and channels (2).
     * Prefer {@link #MasteringView(MasteringChain, double, int)} with the engine's
     * actual audio format for correct processor configuration.</p>
     */
    public MasteringView() {
        this(new MasteringChain(), DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS);
    }

    /**
     * Creates a new mastering view bound to the given mastering chain.
     *
     * <p><strong>Note:</strong> Uses default sample rate (44100 Hz) and channels (2).
     * Prefer {@link #MasteringView(MasteringChain, double, int)} with the engine's
     * actual audio format for correct processor configuration.</p>
     *
     * @param masteringChain the mastering chain to visualize and control
     */
    public MasteringView(MasteringChain masteringChain) {
        this(masteringChain, DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS);
    }

    /**
     * Creates a new mastering view bound to the given mastering chain
     * with the specified audio parameters for processor creation.
     *
     * @param masteringChain the mastering chain to visualize and control
     * @param sampleRate     the sample rate in Hz for processor instantiation
     * @param channels       the number of audio channels
     */
    public MasteringView(MasteringChain masteringChain, double sampleRate, int channels) {
        this.masteringChain = Objects.requireNonNull(masteringChain, "masteringChain must not be null");
        this.sampleRate = sampleRate;
        this.channels = channels;
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
        presetSelector.getSelectionModel().selectedIndexProperty().addListener(
                (obs, oldIdx, newIdx) -> onPresetSelected());

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

        // Keep the meter timer lifecycle symmetric with scene attachment
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                stopMeterTimer();
            } else if (!masteringChain.getStages().isEmpty()) {
                startMeterTimer();
            }
        });
    }

    /**
     * Rebuilds the stage cards from the current mastering chain state.
     *
     * <p>Call this method after modifying the chain to keep the view
     * synchronized with the model.</p>
     */
    public void refresh() {
        stageContainer.getChildren().clear();
        grLabels.clear();
        levelMeters.clear();
        List<MasteringChain.Stage> stages = masteringChain.getStages();
        for (int i = 0; i < stages.size(); i++) {
            MasteringChain.Stage stage = stages.get(i);
            stageContainer.getChildren().add(buildStageCard(stage, i, stages.size()));
            if (i < stages.size() - 1) {
                stageContainer.getChildren().add(buildChainArrow());
            }
        }
        startMeterTimer();
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
     * configurations. Each stage is populated with the corresponding real
     * DSP processor from {@link MasteringProcessorFactory}.</p>
     *
     * @param preset the preset to load
     */
    private void loadPreset(MasteringChainPreset preset) {
        // Clear existing stages
        while (!masteringChain.isEmpty()) {
            masteringChain.removeStage(0);
        }
        // Add stages from preset with real DSP processors
        for (MasteringStageConfig config : preset.stages()) {
            AudioProcessor processor = MasteringProcessorFactory.createProcessor(
                    config, channels, sampleRate);
            masteringChain.addStage(config.stageType(), config.name(), processor);
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

        // Gain reduction label (updated in real time by meter timer)
        Label grLabel = new Label("GR: 0.0 dB");
        grLabel.setStyle("-fx-text-fill: #00e676; -fx-font-size: 10px;");
        grLabels.add(grLabel);
        levelMeters.add(levelMeter);

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
     * Starts the meter polling timer that updates GR labels and level meters
     * at approximately 30 Hz. Stops any previously running timer.
     */
    private void startMeterTimer() {
        stopMeterTimer();
        if (masteringChain.isEmpty()) {
            return;
        }
        meterTimer = new AnimationTimer() {
            private long lastUpdate;

            @Override
            public void handle(long now) {
                if (lastUpdate == 0L) {
                    lastUpdate = now;
                    return;
                }
                long elapsed = now - lastUpdate;
                if (elapsed < METER_UPDATE_INTERVAL_NS) {
                    return;
                }
                lastUpdate = now;
                updateMeters(elapsed);
            }
        };
        meterTimer.start();
    }

    /**
     * Stops the meter polling timer.
     */
    private void stopMeterTimer() {
        if (meterTimer != null) {
            meterTimer.stop();
            meterTimer = null;
        }
    }

    /**
     * Reads metering data from the mastering chain and updates UI labels
     * and meters. Called from the JavaFX application thread by the
     * animation timer.
     *
     * @param deltaNanos the actual elapsed time in nanoseconds since last update
     */
    private void updateMeters(long deltaNanos) {
        int stageCount = masteringChain.size();
        for (int i = 0; i < Math.min(stageCount, grLabels.size()); i++) {
            double gr = masteringChain.getStageGainReductionDb(i);
            grLabels.get(i).setText(String.format("GR: %.1f dB", gr));

            double outputDb = masteringChain.getStageOutputPeakDb(i);
            double linear = (outputDb > -60.0)
                    ? Math.pow(10.0, outputDb / 20.0)
                    : 0.0;
            LevelData levelData = new LevelData(linear, linear, outputDb, outputDb, outputDb > 0.0);
            levelMeters.get(i).update(levelData, deltaNanos);
        }
    }
}
