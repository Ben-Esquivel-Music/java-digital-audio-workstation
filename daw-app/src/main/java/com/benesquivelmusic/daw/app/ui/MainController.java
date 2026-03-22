package com.benesquivelmusic.daw.app.ui;

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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();
        undoManager = new UndoManager();

        var checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        projectManager = new ProjectManager(checkpointManager);

        audioTrackCounter = 0;
        midiTrackCounter = 0;

        applyIcons();
        applyTooltips();
        buildVisualizationTiles();
        setupTempoEditor();
        updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();
        updateUndoRedoState();

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
        var scene = playButton.getScene();
        if (scene == null) {
            return;
        }
        var accelerators = scene.getAccelerators();

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
        var parent = tempoLabel.getParent();
        if (!(parent instanceof HBox hbox)) {
            return;
        }
        int index = hbox.getChildren().indexOf(tempoLabel);
        if (index < 0) {
            return;
        }

        var editor = new TextField(String.format("%.1f", project.getTransport().getTempo()));
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
     * Each tile is a styled card containing a labeled display placeholder.
     */
    private void buildVisualizationTiles() {
        vizTileRow.setPrefHeight(120);
        vizTileRow.setMinHeight(100);

        vizTileRow.getChildren().addAll(
                createVizTile("WAVEFORM", DawIcon.WAVEFORM, "tile-header-accent-cyan"),
                createVizTile("SPECTRUM", DawIcon.SPECTRUM, "tile-header-accent-green"),
                createVizTile("LEVELS", DawIcon.VU_METER, "tile-header-accent-orange"),
                createVizTile("LOUDNESS", DawIcon.LOUDNESS_METER, "tile-header-accent-purple"),
                createVizTile("CORRELATION", DawIcon.CORRELATION, "tile-header-accent-red")
        );

        LOG.fine("Built visualization tile row with 5 display tiles");
    }

    /**
     * Creates a single visualization tile with a header label and display area.
     */
    private VBox createVizTile(String title, DawIcon icon, String accentClass) {
        var header = new Label(title);
        header.getStyleClass().addAll("viz-tile-label", accentClass);
        header.setGraphic(IconNode.of(icon, 12));

        var displayArea = new StackPane();
        displayArea.setStyle("-fx-background-color: #0a0a0a; -fx-background-radius: 6;");
        VBox.setVgrow(displayArea, Priority.ALWAYS);

        var tile = new VBox(4, header, displayArea);
        tile.getStyleClass().add("viz-tile");
        tile.setPadding(new Insets(8));
        HBox.setHgrow(tile, Priority.ALWAYS);

        return tile;
    }

    @FXML
    private void onPlay() {
        project.getTransport().play();
        updateStatus();
        statusBarLabel.setText("Playing...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
    }

    @FXML
    private void onStop() {
        project.getTransport().stop();
        updateStatus();
        timeDisplay.setText("00:00:00.0");
        statusBarLabel.setText("Stopped");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STOP, 12));
    }

    @FXML
    private void onPause() {
        project.getTransport().pause();
        updateStatus();
        statusBarLabel.setText("Paused");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PAUSE, 12));
    }

    @FXML
    private void onRecord() {
        project.getTransport().record();
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
            @Override public String description() { return "Add Audio Track: " + name; }
            @Override public void execute() {
                track = project.createAudioTrack(name);
                trackItem = addTrackToUI(track);
                updateArrangementPlaceholder();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
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
            @Override public String description() { return "Add MIDI Track: " + name; }
            @Override public void execute() {
                track = project.createMidiTrack(name);
                trackItem = addTrackToUI(track);
                updateArrangementPlaceholder();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
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
        var dialog = new PluginManagerDialog(pluginRegistry);
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
        var trackItem = new HBox(8);
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

        var nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("track-name");

        // Double-click to rename the track
        nameLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                startTrackRename(track, nameLabel, trackItem);
            }
        });
        nameLabel.setTooltip(new Tooltip("Double-click to rename"));

        // Volume slider
        var volumeSlider = new Slider(0.0, 1.0, track.getVolume());
        volumeSlider.getStyleClass().add("track-volume-slider");
        volumeSlider.setPrefWidth(80);
        volumeSlider.setTooltip(new Tooltip("Volume"));
        volumeSlider.valueProperty().addListener((_, _, newVal) -> {
            track.setVolume(newVal.doubleValue());
        });

        // Mute button with icon
        var muteBtn = new Button();
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, TRACK_CONTROL_ICON_SIZE));
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute"));
        muteBtn.setOnAction(_ -> {
            track.setMuted(!track.isMuted());
            muteBtn.setStyle(track.isMuted()
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
        });

        // Solo button with icon
        var soloBtn = new Button();
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, TRACK_CONTROL_ICON_SIZE));
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo"));
        soloBtn.setOnAction(_ -> {
            track.setSolo(!track.isSolo());
            soloBtn.setStyle(track.isSolo()
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
        });

        // Arm button with icon and toggle action
        var armBtn = new Button();
        armBtn.setGraphic(IconNode.of(DawIcon.ARM_TRACK, TRACK_CONTROL_ICON_SIZE));
        armBtn.getStyleClass().add("track-arm-button");
        armBtn.setTooltip(new Tooltip("Arm for Recording"));
        armBtn.setOnAction(_ -> {
            track.setArmed(!track.isArmed());
            armBtn.setStyle(track.isArmed()
                    ? "-fx-background-color: #ff1744; -fx-text-fill: #ffffff;" : "");
        });

        // Remove button (undoable)
        var removeBtn = new Button();
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
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        trackItem.getChildren().addAll(
                typeIcon, nameLabel, volumeSlider, spacer,
                muteBtn, soloBtn, armBtn, removeBtn);
        trackListPanel.getChildren().add(trackItem);
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

        var editor = new TextField(track.getName());
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
