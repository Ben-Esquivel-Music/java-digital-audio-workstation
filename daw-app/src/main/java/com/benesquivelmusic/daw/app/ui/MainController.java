package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.CorrelationDisplay;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.collections.ObservableMap;

/**
 * Controller for the main DAW window.
 *
 * <p>Manages the project lifecycle, transport controls, track list,
 * and coordinates with the {@link ProjectManager} for auto-save
 * during long-running recording sessions. Uses the {@link DawIcon}
 * icon pack throughout the UI via {@link IconNode}.</p>
 */
public final class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    /** Icon size for transport-bar buttons (play, stop, record). */
    private static final double TRANSPORT_ICON_SIZE = 18;
    /** Icon size for toolbar buttons (add track, save, plugins). */
    private static final double TOOLBAR_ICON_SIZE = 16;
    /** Icon size for track-strip controls (mute, solo, arm). */
    private static final double TRACK_CONTROL_ICON_SIZE = 14;
    /** Icon size for track-type indicators. */
    private static final double TRACK_TYPE_ICON_SIZE = 18;
    /** Icon size for panel-header labels. */
    private static final double PANEL_ICON_SIZE = 16;

    @FXML private Button playButton;
    @FXML private Button pauseButton;
    @FXML private Button stopButton;
    @FXML private Button recordButton;
    @FXML private Button addAudioTrackButton;
    @FXML private Button addMidiTrackButton;
    @FXML private Button undoButton;
    @FXML private Button redoButton;
    @FXML private Button saveButton;
    @FXML private Button pluginsButton;
    @FXML private Label statusLabel;
    @FXML private Label tempoLabel;
    @FXML private Label timeDisplay;
    @FXML private Label projectInfoLabel;
    @FXML private Label checkpointLabel;
    @FXML private Label statusBarLabel;
    @FXML private Label arrangementPlaceholder;
    @FXML private Label tracksPanelHeader;
    @FXML private VBox trackListPanel;
    @FXML private HBox vizTileRow;

    private DawProject project;
    private PluginRegistry pluginRegistry;
    private ProjectManager projectManager;
    private UndoManager undoManager;
    private int audioTrackCounter;
    private int midiTrackCounter;

    // ── Animation state ──────────────────────────────────────────────────────
    /** Drives all continuous frame-by-frame animations at ~60 fps. */
    private AnimationTimer mainAnimTimer;
    /** Accumulated phase (seconds) for the idle visualization waveform simulation. */
    private double idleAnimPhase;
    /** Accumulated phase (seconds) for the transport-state glow animations. */
    private double glowAnimPhase;
    /** Nanosecond timestamp when playback/recording started; used for time display. */
    private long timeTickerStartNanos;
    /** Whether the time ticker is actively counting up. */
    private boolean timeTickerRunning;
    /** Cached elapsed nanoseconds before the last pause (for correct resume). */
    private long timeTickerPausedElapsedNanos;

    /** Reference kept for the idle demo animation. */
    private SpectrumDisplay spectrumDisplay;
    /** Reference kept for the idle demo animation. */
    private LevelMeterDisplay levelMeterDisplay;
    /** Reusable bin buffer for the idle spectrum animation — avoids per-frame heap allocation. */
    private static final int IDLE_FFT_SIZE = 1024;
    private final float[] idleSpectrumBins = new float[IDLE_FFT_SIZE / 2];

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();
        undoManager = new UndoManager();

        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        projectManager = new ProjectManager(checkpointManager);

        audioTrackCounter = 0;
        midiTrackCounter = 0;

        applyIcons();
        applyTooltips();
        applyButtonPressAnimations();
        buildVisualizationTiles();
        setupTempoEditor();
        updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();
        updateUndoRedoState();
        startMainAnimTimer();

        // Register keyboard shortcuts after the scene is available
        playButton.sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                registerKeyboardShortcuts();
            }
        });

        LOG.info("DAW initialized with studio quality format");
    }

    /**
     * Applies SVG icons from the DAW icon pack to all UI controls.
     */
    private void applyIcons() {
        // Transport controls
        playButton.setGraphic(IconNode.of(DawIcon.PLAY, TRANSPORT_ICON_SIZE));
        pauseButton.setGraphic(IconNode.of(DawIcon.PAUSE, TRANSPORT_ICON_SIZE));
        stopButton.setGraphic(IconNode.of(DawIcon.STOP, TRANSPORT_ICON_SIZE));
        recordButton.setGraphic(IconNode.of(DawIcon.RECORD, TRANSPORT_ICON_SIZE));

        // Toolbar buttons
        addAudioTrackButton.setGraphic(IconNode.of(DawIcon.MICROPHONE, TOOLBAR_ICON_SIZE));
        addMidiTrackButton.setGraphic(IconNode.of(DawIcon.MIDI, TOOLBAR_ICON_SIZE));
        undoButton.setGraphic(IconNode.of(DawIcon.UNDO, TOOLBAR_ICON_SIZE));
        redoButton.setGraphic(IconNode.of(DawIcon.REDO, TOOLBAR_ICON_SIZE));
        saveButton.setGraphic(IconNode.of(DawIcon.UPLOAD, TOOLBAR_ICON_SIZE));
        pluginsButton.setGraphic(IconNode.of(DawIcon.SETTINGS, TOOLBAR_ICON_SIZE));

        // Time display — clock icon prefix
        timeDisplay.setGraphic(IconNode.of(DawIcon.CLOCK, PANEL_ICON_SIZE));

        // Panel headers
        tracksPanelHeader.setGraphic(IconNode.of(DawIcon.MIXER, PANEL_ICON_SIZE));

        // Arrangement placeholder
        arrangementPlaceholder.setGraphic(IconNode.of(DawIcon.WAVEFORM, 24));

        // Status bar icons
        projectInfoLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
        checkpointLabel.setGraphic(IconNode.of(DawIcon.SYNC, 12));

        LOG.fine("Applied SVG icons from DAW icon pack");
    }

    /**
     * Applies descriptive tooltips with keyboard shortcut hints to all UI controls.
     */
    private void applyTooltips() {
        playButton.setTooltip(new Tooltip("Play (Space)"));
        pauseButton.setTooltip(new Tooltip("Pause (P)"));
        stopButton.setTooltip(new Tooltip("Stop (Escape)"));
        recordButton.setTooltip(new Tooltip("Record (R)"));
        addAudioTrackButton.setTooltip(new Tooltip("Add Audio Track (Ctrl+Shift+A)"));
        addMidiTrackButton.setTooltip(new Tooltip("Add MIDI Track (Ctrl+Shift+M)"));
        undoButton.setTooltip(new Tooltip("Undo (Ctrl+Z)"));
        redoButton.setTooltip(new Tooltip("Redo (Ctrl+Shift+Z)"));
        saveButton.setTooltip(new Tooltip("Save Project (Ctrl+S)"));
        pluginsButton.setTooltip(new Tooltip("Manage Plugins"));
    }

    /**
     * Registers global keyboard shortcuts for transport and project actions.
     */
    private void registerKeyboardShortcuts() {
        Scene scene = playButton.getScene();
        if (scene == null) {
            return;
        }
        ObservableMap<KeyCombination, Runnable> accelerators = scene.getAccelerators();

        // Space — toggle play/stop
        accelerators.put(
                new KeyCodeCombination(KeyCode.SPACE),
                () -> {
                    if (project.getTransport().getState() == TransportState.PLAYING) {
                        onStop();
                    } else {
                        onPlay();
                    }
                });

        // Escape — stop
        accelerators.put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                this::onStop);

        // P — pause
        accelerators.put(
                new KeyCodeCombination(KeyCode.P),
                this::onPause);

        // R — record
        accelerators.put(
                new KeyCodeCombination(KeyCode.R),
                this::onRecord);

        // Ctrl+S — save
        accelerators.put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                this::onSaveProject);

        // Ctrl+Shift+A — add audio track
        accelerators.put(
                new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onAddAudioTrack);

        // Ctrl+Shift+M — add MIDI track
        accelerators.put(
                new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onAddMidiTrack);

        // Ctrl+Z — undo
        accelerators.put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                this::onUndo);

        // Ctrl+Shift+Z — redo
        accelerators.put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onRedo);

        LOG.fine("Registered keyboard shortcuts");
    }

    /**
     * Configures the tempo label to become editable on double-click,
     * allowing users to type a new BPM value.
     */
    private void setupTempoEditor() {
        tempoLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                startTempoEdit();
            }
        });
        tempoLabel.setTooltip(new Tooltip("Double-click to edit tempo"));
    }

    private void startTempoEdit() {
        Parent parent = tempoLabel.getParent();
        if (!(parent instanceof HBox hbox)) {
            return;
        }
        int index = hbox.getChildren().indexOf(tempoLabel);
        if (index < 0) {
            return;
        }

        TextField editor = new TextField(String.format("%.1f", project.getTransport().getTempo()));
        editor.getStyleClass().add("tempo-editor");
        editor.setPrefWidth(80);

        // Commit on Enter
        editor.setOnAction(_ -> commitTempoEdit(editor, hbox, index));

        // Commit on focus loss
        editor.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                commitTempoEdit(editor, hbox, index);
            }
        });

        hbox.getChildren().set(index, editor);
        editor.requestFocus();
        editor.selectAll();
    }

    private void commitTempoEdit(TextField editor, HBox hbox, int index) {
        try {
            double newTempo = Double.parseDouble(editor.getText().strip());
            double oldTempo = project.getTransport().getTempo();
            if (Double.compare(newTempo, oldTempo) != 0) {
                undoManager.execute(new UndoableAction() {
                    @Override public String description() {
                        return String.format("Set Tempo to %.1f BPM", newTempo);
                    }
                    @Override public void execute() {
                        project.getTransport().setTempo(newTempo);
                    }
                    @Override public void undo() {
                        project.getTransport().setTempo(oldTempo);
                    }
                });
                updateUndoRedoState();
            }
            statusBarLabel.setText(String.format("Tempo set to %.1f BPM", newTempo));
            statusBarLabel.setGraphic(IconNode.of(DawIcon.METRONOME, 12));
        } catch (IllegalArgumentException e) {
            statusBarLabel.setText("Invalid tempo — must be 20–999 BPM");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
        }
        updateTempoDisplay();
        hbox.getChildren().set(index, tempoLabel);
    }

    /**
     * Builds the visualization tile row at the bottom of the main content area.
     * Each tile is a styled card containing a live display component.
     */
    private void buildVisualizationTiles() {
        vizTileRow.setPrefHeight(120);
        vizTileRow.setMinHeight(100);

        spectrumDisplay    = new SpectrumDisplay();
        levelMeterDisplay  = new LevelMeterDisplay();
        WaveformDisplay waveformDisplay   = new WaveformDisplay();
        LoudnessDisplay loudnessDisplay   = new LoudnessDisplay();
        CorrelationDisplay correlationDisplay = new CorrelationDisplay();

        vizTileRow.getChildren().addAll(
                createVizTile("WAVEFORM",    DawIcon.WAVEFORM,       "tile-header-accent-cyan",   waveformDisplay),
                createVizTile("SPECTRUM",    DawIcon.SPECTRUM,        "tile-header-accent-green",  spectrumDisplay),
                createVizTile("LEVELS",      DawIcon.VU_METER,        "tile-header-accent-orange", levelMeterDisplay),
                createVizTile("LOUDNESS",    DawIcon.LOUDNESS_METER,  "tile-header-accent-purple", loudnessDisplay),
                createVizTile("CORRELATION", DawIcon.CORRELATION,     "tile-header-accent-red",    correlationDisplay)
        );

        LOG.fine("Built visualization tile row with 5 display tiles");
    }

    /**
     * Creates a single visualization tile with a header label and a live display component.
     */
    private VBox createVizTile(String title, DawIcon icon, String accentClass, Region displayComponent) {
        Label header = new Label(title);
        header.getStyleClass().addAll("viz-tile-label", accentClass);
        header.setGraphic(IconNode.of(icon, 12));

        displayComponent.setMinHeight(0);
        VBox.setVgrow(displayComponent, Priority.ALWAYS);

        VBox tile = new VBox(4, header, displayComponent);
        tile.getStyleClass().add("viz-tile");
        tile.setPadding(new Insets(8));
        HBox.setHgrow(tile, Priority.ALWAYS);

        return tile;
    }

    @FXML
    private void onPlay() {
        project.getTransport().play();
        startTimeTicker();
        updateStatus();
        statusBarLabel.setText("Playing...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
    }

    @FXML
    private void onStop() {
        project.getTransport().stop();
        stopTimeTicker();
        updateStatus();
        timeDisplay.setText("00:00:00.0");
        statusBarLabel.setText("Stopped");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STOP, 12));
        // Restore button appearance in case the record blink was active
        recordButton.setOpacity(1.0);
        recordButton.setStyle("");
    }

    @FXML
    private void onPause() {
        project.getTransport().pause();
        pauseTimeTicker();
        updateStatus();
        statusBarLabel.setText("Paused");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PAUSE, 12));
    }

    @FXML
    private void onRecord() {
        project.getTransport().record();
        startTimeTicker();
        updateStatus();
        statusBarLabel.setText("Recording — auto-save active");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.RECORD, 12));
    }

    @FXML
    private void onAddAudioTrack() {
        audioTrackCounter++;
        String name = "Audio " + audioTrackCounter;
        undoManager.execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add Audio Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = project.createAudioTrack(name);
                    trackItem = addTrackToUI(track);
                    initialExecute = false;
                } else {
                    project.addTrack(track);
                    trackListPanel.getChildren().add(trackItem);
                }
                updateArrangementPlaceholder();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
                audioTrackCounter--;
                updateArrangementPlaceholder();
            }
        });
        updateUndoRedoState();
        statusBarLabel.setText("Added audio track: " + name);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.MICROPHONE, 12));
        LOG.fine(() -> "Added audio track: " + name);
    }

    @FXML
    private void onAddMidiTrack() {
        midiTrackCounter++;
        String name = "MIDI " + midiTrackCounter;
        undoManager.execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add MIDI Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = project.createMidiTrack(name);
                    trackItem = addTrackToUI(track);
                    initialExecute = false;
                } else {
                    project.addTrack(track);
                    trackListPanel.getChildren().add(trackItem);
                }
                updateArrangementPlaceholder();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
                midiTrackCounter--;
                updateArrangementPlaceholder();
            }
        });
        updateUndoRedoState();
        statusBarLabel.setText("Added MIDI track: " + name);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.MIDI, 12));
        LOG.fine(() -> "Added MIDI track: " + name);
    }

    @FXML
    private void onSaveProject() {
        try {
            if (projectManager.getCurrentProject() == null) {
                Path tempDir = Files.createTempDirectory("daw-project-");
                projectManager.createProject(project.getName(), tempDir.getParent());
            }
            projectManager.saveProject();
            int count = projectManager.getCheckpointManager().getCheckpointCount();
            checkpointLabel.setText("Saved (checkpoint #" + count + ")");
            checkpointLabel.setGraphic(IconNode.of(DawIcon.SUCCESS, 12));
            statusBarLabel.setText("Project saved");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UPLOAD, 12));
            LOG.info("Project saved successfully");
        } catch (IOException e) {
            statusBarLabel.setText("Save failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            LOG.log(Level.WARNING, "Failed to save project", e);
        }
    }

    @FXML
    private void onManagePlugins() {
        PluginManagerDialog dialog = new PluginManagerDialog(pluginRegistry);
        dialog.showAndWait();
    }

    @FXML
    private void onUndo() {
        if (undoManager.undo()) {
            statusBarLabel.setText("Undo: " + undoManager.redoDescription());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UNDO, 12));
            updateTempoDisplay();
        } else {
            statusBarLabel.setText("Nothing to undo");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO, 12));
        }
        updateUndoRedoState();
    }

    @FXML
    private void onRedo() {
        if (undoManager.redo()) {
            statusBarLabel.setText("Redo: " + undoManager.undoDescription());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.REDO, 12));
            updateTempoDisplay();
        } else {
            statusBarLabel.setText("Nothing to redo");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO, 12));
        }
        updateUndoRedoState();
    }

    private HBox addTrackToUI(Track track) {
        HBox trackItem = new HBox(8);
        trackItem.getStyleClass().add("track-item");
        trackItem.setPadding(new Insets(6, 8, 6, 8));
        trackItem.setAlignment(Pos.CENTER_LEFT);

        // Track type icon
        Node typeIcon = switch (track.getType()) {
            case AUDIO        -> IconNode.of(DawIcon.MICROPHONE, TRACK_TYPE_ICON_SIZE);
            case MIDI         -> IconNode.of(DawIcon.KEYBOARD, TRACK_TYPE_ICON_SIZE);
            case AUX          -> IconNode.of(DawIcon.MIXER, TRACK_TYPE_ICON_SIZE);
            case MASTER       -> IconNode.of(DawIcon.SPEAKER, TRACK_TYPE_ICON_SIZE);
            case BED_CHANNEL  -> IconNode.of(DawIcon.SURROUND, TRACK_TYPE_ICON_SIZE);
            case AUDIO_OBJECT -> IconNode.of(DawIcon.PAN, TRACK_TYPE_ICON_SIZE);
        };

        Label nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("track-name");

        // Double-click to rename the track
        nameLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                startTrackRename(track, nameLabel, trackItem);
            }
        });
        nameLabel.setTooltip(new Tooltip("Double-click to rename"));

        // Volume slider
        Slider volumeSlider = new Slider(0.0, 1.0, track.getVolume());
        volumeSlider.getStyleClass().add("track-volume-slider");
        volumeSlider.setPrefWidth(80);
        volumeSlider.setTooltip(new Tooltip("Volume"));
        volumeSlider.valueProperty().addListener((_, _, newVal) -> {
            track.setVolume(newVal.doubleValue());
        });

        // Mute button with icon
        Button muteBtn = new Button();
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, TRACK_CONTROL_ICON_SIZE));
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute"));
        muteBtn.setOnAction(_ -> {
            track.setMuted(!track.isMuted());
            muteBtn.setStyle(track.isMuted()
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });

        // Solo button with icon
        Button soloBtn = new Button();
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, TRACK_CONTROL_ICON_SIZE));
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo"));
        soloBtn.setOnAction(_ -> {
            track.setSolo(!track.isSolo());
            soloBtn.setStyle(track.isSolo()
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
        });

        // Arm button with icon and toggle action
        Button armBtn = new Button();
        armBtn.setGraphic(IconNode.of(DawIcon.ARM_TRACK, TRACK_CONTROL_ICON_SIZE));
        armBtn.getStyleClass().add("track-arm-button");
        armBtn.setTooltip(new Tooltip("Arm for Recording"));
        armBtn.setOnAction(_ -> {
            track.setArmed(!track.isArmed());
            armBtn.setStyle(track.isArmed()
                    ? "-fx-background-color: #ff1744; -fx-text-fill: #ffffff;" : "");
        });

        // Remove button (undoable)
        Button removeBtn = new Button();
        removeBtn.setGraphic(IconNode.of(DawIcon.DELETE, TRACK_CONTROL_ICON_SIZE));
        removeBtn.getStyleClass().add("track-remove-button");
        removeBtn.setTooltip(new Tooltip("Remove Track"));
        removeBtn.setOnAction(_ -> {
            int uiIndex = trackListPanel.getChildren().indexOf(trackItem);
            undoManager.execute(new UndoableAction() {
                @Override public String description() { return "Remove Track: " + track.getName(); }
                @Override public void execute() {
                    project.removeTrack(track);
                    trackListPanel.getChildren().remove(trackItem);
                    updateArrangementPlaceholder();
                }
                @Override public void undo() {
                    project.addTrack(track);
                    if (uiIndex >= 0 && uiIndex < trackListPanel.getChildren().size()) {
                        trackListPanel.getChildren().add(uiIndex, trackItem);
                    } else {
                        trackListPanel.getChildren().add(trackItem);
                    }
                    updateArrangementPlaceholder();
                }
            });
            updateUndoRedoState();
            statusBarLabel.setText("Removed track: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.DELETE, 12));
            LOG.fine(() -> "Removed track: " + track.getName());
        });

        // Spacer pushes controls to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        trackItem.getChildren().addAll(
                typeIcon, nameLabel, volumeSlider, spacer,
                muteBtn, soloBtn, armBtn, removeBtn);
        trackListPanel.getChildren().add(trackItem);

        // Slide-fade entry animation: item slides in from the left and fades in
        trackItem.setTranslateX(-24);
        trackItem.setOpacity(0.0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(200), trackItem);
        slide.setToX(0.0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition fade = new FadeTransition(Duration.millis(200), trackItem);
        fade.setToValue(1.0);
        new ParallelTransition(slide, fade).play();

        return trackItem;
    }

    /**
     * Replaces the track name label with a text field for inline renaming.
     */
    private void startTrackRename(Track track, Label nameLabel, HBox trackItem) {
        int labelIndex = trackItem.getChildren().indexOf(nameLabel);
        if (labelIndex < 0) {
            return;
        }

        TextField editor = new TextField(track.getName());
        editor.getStyleClass().add("tempo-editor");
        editor.setPrefWidth(120);

        Runnable commit = () -> {
            String newName = editor.getText().strip();
            if (!newName.isEmpty() && !newName.equals(track.getName())) {
                String oldName = track.getName();
                undoManager.execute(new UndoableAction() {
                    @Override public String description() {
                        return "Rename Track: " + oldName + " → " + newName;
                    }
                    @Override public void execute() {
                        track.setName(newName);
                        nameLabel.setText(newName);
                    }
                    @Override public void undo() {
                        track.setName(oldName);
                        nameLabel.setText(oldName);
                    }
                });
                updateUndoRedoState();
                statusBarLabel.setText("Renamed track: " + oldName + " → " + newName);
                statusBarLabel.setGraphic(IconNode.of(DawIcon.TAG, 12));
            }
            trackItem.getChildren().set(labelIndex, nameLabel);
        };

        editor.setOnAction(_ -> commit.run());
        editor.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                commit.run();
            }
        });

        trackItem.getChildren().set(labelIndex, editor);
        editor.requestFocus();
        editor.selectAll();
    }

    // ── Animation helpers ────────────────────────────────────────────────────

    /**
     * Starts the single {@link AnimationTimer} that drives all continuous
     * frame-by-frame animations: idle visualization demo, transport glow, and
     * the time-display ticker.
     */
    private void startMainAnimTimer() {
        mainAnimTimer = new AnimationTimer() {
            private long lastNanos = 0;

            @Override
            public void handle(long now) {
                if (lastNanos == 0) {
                    lastNanos = now;
                    return;
                }
                double delta = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;

                // Advance animation phases
                idleAnimPhase += delta;
                glowAnimPhase += delta;

                TransportState state = project.getTransport().getState();

                // Time ticker: update time display while playing or recording
                if (timeTickerRunning) {
                    long elapsedNanos = timeTickerPausedElapsedNanos + (now - timeTickerStartNanos);
                    refreshTimeDisplay(elapsedNanos);
                }

                // Transport glow on play and record buttons
                applyTransportGlow(state);

                // Idle visualization (always runs to keep displays alive)
                tickIdleVisualization(delta);
            }
        };
        mainAnimTimer.start();
    }

    /**
     * Applies a pulsing glow to the play button while playing and a blink
     * to the record button while recording.
     */
    private void applyTransportGlow(TransportState state) {
        if (state == TransportState.PLAYING) {
            double pulse = 0.5 + 0.5 * Math.sin(glowAnimPhase * Math.PI * 1.4);
            double radius = 8 + pulse * 14;
            double spread = 0.05 + pulse * 0.25;
            playButton.setStyle(String.format(
                    "-fx-effect: dropshadow(gaussian, #00e676, %.1f, %.2f, 0, 0);",
                    radius, spread));
            recordButton.setStyle("");
        } else if (state == TransportState.RECORDING) {
            // Blink record button: full opacity <-> dim, at ~2 Hz
            double blink = 0.5 + 0.5 * Math.sin(glowAnimPhase * Math.PI * 4.0);
            double opacity = 0.4 + blink * 0.6;
            recordButton.setOpacity(opacity);
            double glowRadius = 8 + blink * 16;
            double glowSpread = 0.1 + blink * 0.3;
            recordButton.setStyle(String.format(
                    "-fx-effect: dropshadow(gaussian, #ff1744, %.1f, %.2f, 0, 0);",
                    glowRadius, glowSpread));
            playButton.setStyle("");
        } else {
            playButton.setStyle("");
            recordButton.setOpacity(1.0);
            recordButton.setStyle("");
        }
    }

    /**
     * Generates synthetic spectrum and level data for the idle demo animation so
     * the visualization displays stay visually alive when no audio is being processed.
     */
    private void tickIdleVisualization(double deltaSeconds) {
        if (spectrumDisplay == null || levelMeterDisplay == null) {
            return;
        }

        // ── Spectrum: pink-noise shape with gentle wobble ──────────────────
        int binCount = idleSpectrumBins.length;
        for (int i = 1; i < binCount; i++) {
            // Logarithmic position: 0.0 (low) → 1.0 (high)
            double t = Math.log((double) i / binCount + 1.0) / Math.log(2.0);
            // Pink-noise baseline: gentle downward slope
            double base = -28.0 - t * 30.0;
            // Slow wobble across the frequency range
            double wobble = 7.0 * Math.sin(idleAnimPhase * 0.9 + t * 5.5);
            // Low-mid bump that breathes
            double bump = 5.0 * Math.exp(-Math.pow((t - 0.25), 2) / 0.01)
                    * (0.5 + 0.5 * Math.sin(idleAnimPhase * 0.6));
            idleSpectrumBins[i] = (float) Math.max(-90.0, base + wobble + bump);
        }
        idleSpectrumBins[0] = idleSpectrumBins[1];
        spectrumDisplay.updateSpectrum(new SpectrumData(idleSpectrumBins, IDLE_FFT_SIZE, 44100.0));

        // ── Level meter: gentle breathing RMS with occasional peaks ──────
        double rmsLinear = 0.18 + 0.12 * Math.abs(Math.sin(idleAnimPhase * 0.75));
        double peakBoost = 1.0 + 0.25 * Math.abs(Math.sin(idleAnimPhase * 1.8));
        double peakLinear = Math.min(rmsLinear * peakBoost * 1.3, 0.85);
        double dbRms = 20.0 * Math.log10(Math.max(rmsLinear, 1e-9));
        double dbPeak = 20.0 * Math.log10(Math.max(peakLinear, 1e-9));
        levelMeterDisplay.update(
                new LevelData(peakLinear, rmsLinear, dbPeak, dbRms, false),
                (long) (deltaSeconds * 1_000_000_000L));
    }

    /** Updates the time display label from the given elapsed nanosecond count. */
    private void refreshTimeDisplay(long elapsedNanos) {
        long elapsedMs = elapsedNanos / 1_000_000L;
        long tenths = (elapsedMs % 1000) / 100;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long hours = minutes / 60;
        timeDisplay.setText(String.format("%02d:%02d:%02d.%d",
                hours, minutes % 60, totalSeconds % 60, tenths));
    }

    /** Starts the time ticker from zero (or resumes from a paused position). */
    private void startTimeTicker() {
        timeTickerStartNanos = System.nanoTime();
        timeTickerPausedElapsedNanos = 0;
        timeTickerRunning = true;
    }

    /** Pauses the time ticker, preserving elapsed time for clean resume. */
    private void pauseTimeTicker() {
        if (timeTickerRunning) {
            timeTickerPausedElapsedNanos += System.nanoTime() - timeTickerStartNanos;
            timeTickerRunning = false;
        }
    }

    /** Stops and resets the time ticker. */
    private void stopTimeTicker() {
        timeTickerRunning = false;
        timeTickerPausedElapsedNanos = 0;
    }

    /**
     * Adds a scale-bounce press/release animation to every transport button so
     * clicks feel tactile and immediate.
     */
    private void applyButtonPressAnimations() {
        for (Button btn : new Button[]{
                playButton, pauseButton, stopButton, recordButton,
                addAudioTrackButton, addMidiTrackButton,
                undoButton, redoButton, saveButton, pluginsButton}) {
            applyPressAnimation(btn);
        }
    }

    /**
     * Attaches a subtle scale-down-then-spring-back animation to a single button.
     */
    private void applyPressAnimation(Button btn) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(70), btn);
        pressDown.setToX(0.90);
        pressDown.setToY(0.90);
        pressDown.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition springBack = new ScaleTransition(Duration.millis(130), btn);
        springBack.setToX(1.0);
        springBack.setToY(1.0);
        springBack.setInterpolator(Interpolator.EASE_OUT);

        btn.setOnMousePressed(_ -> {
            springBack.stop();
            pressDown.playFromStart();
        });
        btn.setOnMouseReleased(_ -> {
            pressDown.stop();
            springBack.playFromStart();
        });
    }

    // ── Status update ────────────────────────────────────────────────────────

    private void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();

        statusLabel.setText(state.name());
        statusLabel.getStyleClass().removeAll(
                "status-recording", "status-playing", "status-stopped", "status-paused");
        switch (state) {
            case RECORDING -> {
                statusLabel.getStyleClass().add("status-recording");
                statusLabel.setGraphic(IconNode.of(DawIcon.RECORD, 12));
            }
            case PLAYING -> {
                statusLabel.getStyleClass().add("status-playing");
                statusLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
            }
            case PAUSED -> {
                statusLabel.getStyleClass().add("status-paused");
                statusLabel.setGraphic(IconNode.of(DawIcon.PAUSE, 12));
            }
            default -> {
                statusLabel.getStyleClass().add("status-stopped");
                statusLabel.setGraphic(IconNode.of(DawIcon.STOP, 12));
            }
        }

        // Smooth fade-in so the status label change feels polished
        statusLabel.setOpacity(0.0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), statusLabel);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        playButton.setDisable(state == TransportState.PLAYING);
        pauseButton.setDisable(state == TransportState.STOPPED || state == TransportState.PAUSED);
        recordButton.setDisable(state == TransportState.RECORDING);
        stopButton.setDisable(state == TransportState.STOPPED);
    }

    private void updateTempoDisplay() {
        tempoLabel.setText(String.format("%.1f BPM", project.getTransport().getTempo()));
        tempoLabel.setGraphic(IconNode.of(DawIcon.METRONOME, PANEL_ICON_SIZE));
    }

    private void updateProjectInfo() {
        AudioFormat fmt = project.getFormat();
        projectInfoLabel.setText(String.format("%s  ·  %.0f kHz / %d-bit / %dch",
                project.getName(),
                fmt.sampleRate() / 1000.0,
                fmt.bitDepth(),
                fmt.channels()));
    }

    private void updateCheckpointStatus() {
        checkpointLabel.setText("Auto-save: ON");
    }

    private void updateArrangementPlaceholder() {
        arrangementPlaceholder.setVisible(project.getTracks().isEmpty());
    }

    private void updateUndoRedoState() {
        undoButton.setDisable(!undoManager.canUndo());
        redoButton.setDisable(!undoManager.canRedo());

        String undoTip = undoManager.canUndo()
                ? "Undo: " + undoManager.undoDescription() + " (Ctrl+Z)"
                : "Nothing to undo";
        String redoTip = undoManager.canRedo()
                ? "Redo: " + undoManager.redoDescription() + " (Ctrl+Shift+Z)"
                : "Nothing to redo";
        undoButton.setTooltip(new Tooltip(undoTip));
        redoButton.setTooltip(new Tooltip(redoTip));
    }
}
