package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.export.MidiFileExporter;
import com.benesquivelmusic.daw.core.export.TrackBouncer;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Button;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds and manages individual track strips in the arrangement track list.
 *
 * <p>Extracted from {@link MainController} to isolate track-strip construction,
 * context-menu building, and inline-rename logic into a dedicated, independently
 * testable class. All dependencies are received via constructor injection.</p>
 */
final class TrackStripController {

    private static final Logger LOG = Logger.getLogger(TrackStripController.class.getName());

    /** Icon size for track-strip controls (mute, solo, arm). */
    static final double TRACK_CONTROL_ICON_SIZE = 14;
    /** Icon size for track-type indicators. */
    static final double TRACK_TYPE_ICON_SIZE = 18;
    /** Custom data format for track-ID drag-and-drop payloads. */
    private static final DataFormat TRACK_ID_FORMAT =
            new DataFormat("application/x-daw-track-id");

    /**
     * Callback interface implemented by the host controller to provide
     * state access and coordination methods that remain in the top-level
     * controller.
     */
    interface Host {
        void updateArrangementPlaceholder();
        void updateUndoRedoState();
        void undoLastAction();
        void zoomIn();
        void zoomOut();
        void toggleSnap();
        void skipToStart();
        void markProjectDirty();
        boolean isSnapEnabled();
        ZoomLevel currentZoomLevel();
        EditorView editorView();
    }

    private final DawProject project;
    private final UndoManager undoManager;
    private final AudioEngine audioEngine;
    private final MixerView mixerView;
    private final NotificationBar notificationBar;
    private final Label statusBarLabel;
    private final VBox trackListPanel;
    private final BorderPane rootPane;
    private final ClipboardManager clipboardManager;
    private final SelectionModel selectionModel;
    private final Host host;
    private ArrangementCanvas arrangementCanvas;

    TrackStripController(DawProject project,
                         UndoManager undoManager,
                         AudioEngine audioEngine,
                         MixerView mixerView,
                         NotificationBar notificationBar,
                         Label statusBarLabel,
                         VBox trackListPanel,
                         BorderPane rootPane,
                         ClipboardManager clipboardManager,
                         SelectionModel selectionModel,
                         Host host) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.undoManager = Objects.requireNonNull(undoManager, "undoManager must not be null");
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.mixerView = Objects.requireNonNull(mixerView, "mixerView must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.trackListPanel = Objects.requireNonNull(trackListPanel, "trackListPanel must not be null");
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.clipboardManager = Objects.requireNonNull(clipboardManager, "clipboardManager must not be null");
        this.selectionModel = Objects.requireNonNull(selectionModel, "selectionModel must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    /**
     * Sets the arrangement canvas reference for automation lane toggles.
     * Called after the canvas is created.
     */
    void setArrangementCanvas(ArrangementCanvas canvas) {
        this.arrangementCanvas = canvas;
    }

    HBox addTrackToUI(Track track) {
        return addTrackToUI(track, -1);
    }

    /**
     * Builds a track strip for the given track and inserts it into the
     * track-list panel at the specified UI index.  If {@code uiIndex} is
     * negative or greater than or equal to the panel's current child count,
     * the strip is appended to the end.
     */
    HBox addTrackToUI(Track track, int uiIndex) {
        HBox trackItem = new HBox(8);
        trackItem.getStyleClass().add("track-item");
        trackItem.setPadding(new Insets(6, 8, 6, 8));
        trackItem.setAlignment(Pos.CENTER_LEFT);

        // Track type icon — pulls from Media, Instruments, DAW, and Volume categories
        Node typeIcon = switch (track.getType()) {
            case AUDIO        -> IconNode.of(DawIcon.MICROPHONE, TRACK_TYPE_ICON_SIZE);
            case MIDI         -> IconNode.of(DawIcon.PIANO, TRACK_TYPE_ICON_SIZE);
            case AUX          -> IconNode.of(DawIcon.MIXER, TRACK_TYPE_ICON_SIZE);
            case MASTER       -> IconNode.of(DawIcon.SPEAKER, TRACK_TYPE_ICON_SIZE);
            case FOLDER       -> IconNode.of(DawIcon.FOLDER, TRACK_TYPE_ICON_SIZE);
            case BED_CHANNEL  -> IconNode.of(DawIcon.SURROUND, TRACK_TYPE_ICON_SIZE);
            case AUDIO_OBJECT -> IconNode.of(DawIcon.PAN, TRACK_TYPE_ICON_SIZE);
            case REFERENCE    -> IconNode.of(DawIcon.HEADPHONES, TRACK_TYPE_ICON_SIZE);
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

        // ── I/O routing indicator (Connectivity category) ───────────────────
        DawIcon ioIcon = switch (track.getType()) {
            case AUDIO        -> DawIcon.XLR;
            case MIDI         -> DawIcon.MIDI_CABLE;
            case AUX, MASTER  -> DawIcon.LINK;
            case FOLDER       -> DawIcon.FOLDER;
            case BED_CHANNEL  -> DawIcon.SPDIF;
            case AUDIO_OBJECT -> DawIcon.HDMI;
            case REFERENCE    -> DawIcon.LINK;
        };
        Label ioLabel = new Label();
        ioLabel.setGraphic(IconNode.of(ioIcon, 10));
        ioLabel.setTooltip(new Tooltip("I/O: " + ioIcon.name().replace('_', ' ')
                + " — Double-click to change input"));
        ioLabel.getStyleClass().add("status-bar-label");

        // Double-click to re-open input port selection dialog
        if (track.getType() == TrackType.AUDIO) {
            ioLabel.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    List<AudioDeviceInfo> devices = List.of();
                    audioEngine.ensureBackendInitialized();
                    NativeAudioBackend backend = audioEngine.getAudioBackend();
                    if (backend != null) {
                        try {
                            devices = backend.getAvailableDevices();
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "Failed to enumerate audio devices", e);
                        }
                    }
                    InputPortSelectionDialog dialog = new InputPortSelectionDialog(devices, track.getInputDeviceIndex());
                    dialog.showAndWait().ifPresent(device -> {
                        track.setInputDeviceIndex(device.index());
                        ioLabel.setTooltip(new Tooltip("Input: " + device.name()));
                        statusBarLabel.setText("Input changed: " + track.getName()
                                + " ← " + device.name());
                        statusBarLabel.setGraphic(IconNode.of(DawIcon.INPUT, 12));
                        host.markProjectDirty();
                    });
                }
            });
        } else if (track.getType() == TrackType.MIDI) {
            ioLabel.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    MidiInputPortSelectionDialog dialog = new MidiInputPortSelectionDialog(null);
                    dialog.showAndWait().ifPresent(midiInfo -> {
                        ioLabel.setTooltip(new Tooltip("MIDI Input: " + midiInfo.getName()));
                        statusBarLabel.setText("MIDI input changed: " + track.getName()
                                + " ← " + midiInfo.getName());
                        statusBarLabel.setGraphic(IconNode.of(DawIcon.MIDI, 12));
                        host.markProjectDirty();
                    });
                }
            });
        }

        // ── Volume slider with icon decorations (Volume category) ───────────
        Slider volumeSlider = new Slider(0.0, 1.0, track.getVolume());
        volumeSlider.getStyleClass().add("track-volume-slider");
        volumeSlider.setPrefWidth(80);
        volumeSlider.setTooltip(new Tooltip("Volume"));
        volumeSlider.valueProperty().addListener((_, _, newVal) -> {
            track.setVolume(newVal.doubleValue());
        });
        HBox volRow = new HBox(4,
                IconNode.of(DawIcon.VOLUME_DOWN, TRACK_CONTROL_ICON_SIZE),
                volumeSlider,
                IconNode.of(DawIcon.VOLUME_UP, TRACK_CONTROL_ICON_SIZE));
        volRow.setAlignment(Pos.CENTER_LEFT);

        // ── Pan slider with audio-balance icon (Volume category) ────────────
        Slider panSlider = new Slider(-1.0, 1.0, track.getPan());
        panSlider.getStyleClass().add("track-volume-slider");
        panSlider.setPrefWidth(60);
        panSlider.setTooltip(new Tooltip("Pan (L/R)"));
        panSlider.valueProperty().addListener((_, _, newVal) -> {
            track.setPan(newVal.doubleValue());
        });
        HBox panRow = new HBox(4,
                IconNode.of(DawIcon.AUDIO_BALANCE, TRACK_CONTROL_ICON_SIZE),
                panSlider);
        panRow.setAlignment(Pos.CENTER_LEFT);

        // ── DSP insert chain indicators (DAW category) ──────────────────────
        // Shows placeholder inserts that represent the default signal chain
        HBox insertChain = new HBox(2);
        insertChain.setAlignment(Pos.CENTER_LEFT);
        if (track.getType() == TrackType.AUDIO || track.getType() == TrackType.MASTER) {
            Node gainIcon = IconNode.of(DawIcon.GAIN, 10);
            Tooltip.install(gainIcon, new Tooltip("Gain"));
            Node gateIcon = IconNode.of(DawIcon.NOISE_GATE, 10);
            Tooltip.install(gateIcon, new Tooltip("Gate"));
            Node compIcon = IconNode.of(DawIcon.COMPRESSOR, 10);
            Tooltip.install(compIcon, new Tooltip("Compressor"));
            Node eqIcon = IconNode.of(DawIcon.HIGH_PASS, 10);
            Tooltip.install(eqIcon, new Tooltip("High-Pass Filter"));
            Node limiterIcon = IconNode.of(DawIcon.LIMITER, 10);
            Tooltip.install(limiterIcon, new Tooltip("Limiter"));
            insertChain.getChildren().addAll(gainIcon, gateIcon, compIcon, eqIcon, limiterIcon);
        } else if (track.getType() == TrackType.MIDI) {
            // MIDI tracks get instrument-category hint icons
            DawIcon instrIcon = midiInstrumentIcon(track.getName());
            Node instrNode = IconNode.of(instrIcon, 10);
            Tooltip.install(instrNode, new Tooltip("Instrument: " + instrIcon.name().replace('_', ' ')));
            Node velocityIcon = IconNode.of(DawIcon.NORMALIZE, 10);
            Tooltip.install(velocityIcon, new Tooltip("Velocity / Normalize"));
            insertChain.getChildren().addAll(instrNode, velocityIcon);
        } else {
            Node routeIcon = IconNode.of(DawIcon.CROSSFADE, 10);
            Tooltip.install(routeIcon, new Tooltip("Crossfade routing"));
            insertChain.getChildren().add(routeIcon);
        }

        // ── Output assignment indicator (Recording category) ────────────────
        Label outputLabel = new Label();
        outputLabel.setGraphic(IconNode.of(DawIcon.OUTPUT, 10));
        outputLabel.setTooltip(new Tooltip("Output: Master"));
        outputLabel.getStyleClass().add("status-bar-label");

        // ── Mute button with icon (Recording category) ──────────────────────
        Button muteBtn = new Button();
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, TRACK_CONTROL_ICON_SIZE));
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute"));
        muteBtn.setOnAction(_ -> {
            track.setMuted(!track.isMuted());
            muteBtn.setStyle(track.isMuted()
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
            // Volume-category feedback: use VOLUME_MUTE or VOLUME_OFF
            statusBarLabel.setText(track.isMuted()
                    ? "Muted: " + track.getName()
                    : "Unmuted: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(
                    track.isMuted() ? DawIcon.VOLUME_MUTE : DawIcon.VOLUME_SLIDER, 12));
        });

        // ── Solo button with icon (Recording category) ──────────────────────
        Button soloBtn = new Button();
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, TRACK_CONTROL_ICON_SIZE));
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo"));
        soloBtn.setOnAction(_ -> {
            track.setSolo(!track.isSolo());
            soloBtn.setStyle(track.isSolo()
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
            statusBarLabel.setText(track.isSolo()
                    ? "Solo: " + track.getName()
                    : "Unsolo: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SOLO, 12));
        });

        // ── Arm button with icon and toggle action (Recording category) ─────
        Button armBtn = new Button();
        armBtn.setGraphic(IconNode.of(DawIcon.ARM_TRACK, TRACK_CONTROL_ICON_SIZE));
        armBtn.getStyleClass().add("track-arm-button");
        armBtn.setTooltip(new Tooltip("Arm for Recording"));
        armBtn.setOnAction(_ -> {
            track.setArmed(!track.isArmed());
            armBtn.setStyle(track.isArmed()
                    ? "-fx-background-color: #ff1744; -fx-text-fill: #ffffff;" : "");
            statusBarLabel.setText(track.isArmed()
                    ? "Armed: " + track.getName()
                    : "Disarmed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(
                    track.isArmed() ? DawIcon.BELL_RING : DawIcon.ARM_TRACK, 12));
        });

        // ── Phase invert toggle (Recording category) ────────────────────────
        Button phaseBtn = new Button();
        phaseBtn.setGraphic(IconNode.of(DawIcon.PHASE, TRACK_CONTROL_ICON_SIZE));
        phaseBtn.getStyleClass().add("track-mute-button");
        phaseBtn.setTooltip(new Tooltip("Phase Invert (Ø)"));
        phaseBtn.setOnAction(_ -> {
            track.setPhaseInverted(!track.isPhaseInverted());
            phaseBtn.setStyle(track.isPhaseInverted()
                    ? "-fx-background-color: #448aff; -fx-text-fill: #ffffff;" : "");
            statusBarLabel.setText(track.isPhaseInverted()
                    ? "Phase inverted: " + track.getName()
                    : "Phase normal: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHASE, 12));
        });

        // ── Remove button (undoable) ────────────────────────────────────────
        Button removeBtn = new Button();
        removeBtn.setGraphic(IconNode.of(DawIcon.DELETE, TRACK_CONTROL_ICON_SIZE));
        removeBtn.getStyleClass().add("track-remove-button");
        removeBtn.setTooltip(new Tooltip("Remove Track"));
        removeBtn.setOnAction(_ -> {
            int removeUiIndex = trackListPanel.getChildren().indexOf(trackItem);
            int projectIndex = project.getTracks().indexOf(track);
            undoManager.execute(new UndoableAction() {
                @Override public String description() { return "Remove Track: " + track.getName(); }
                @Override public void execute() {
                    project.removeTrack(track);
                    trackListPanel.getChildren().remove(trackItem);
                    host.updateArrangementPlaceholder();
                    mixerView.refresh();
                }
                @Override public void undo() {
                    project.addTrack(track);
                    // Restore original position in the project track list
                    int currentIndex = project.getTracks().size() - 1;
                    if (projectIndex >= 0 && projectIndex < project.getTracks().size()
                            && currentIndex != projectIndex) {
                        project.moveTrack(currentIndex, projectIndex);
                    }
                    if (removeUiIndex >= 0 && removeUiIndex < trackListPanel.getChildren().size()) {
                        trackListPanel.getChildren().add(removeUiIndex, trackItem);
                    } else {
                        trackListPanel.getChildren().add(trackItem);
                    }
                    host.updateArrangementPlaceholder();
                    mixerView.refresh();
                }
            });
            host.updateUndoRedoState();
            host.markProjectDirty();
            statusBarLabel.setText("Removed track: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.CUT, 12));
            notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                    "Removed track: " + track.getName(), host::undoLastAction);
            LOG.fine(() -> "Removed track: " + track.getName());
        });

        // ── Automation lane toggle (DAW category) ───────────────────────────
        Button autoBtn = new Button();
        autoBtn.setGraphic(IconNode.of(DawIcon.AUTOMATION, TRACK_CONTROL_ICON_SIZE));
        autoBtn.getStyleClass().add("track-mute-button");
        autoBtn.setTooltip(new Tooltip("Toggle Automation Lane"));
        autoBtn.setOnAction(_ -> {
            if (arrangementCanvas != null) {
                arrangementCanvas.toggleAutomationLane(track);
                autoBtn.setStyle(arrangementCanvas.isAutomationLaneVisible(track)
                        ? "-fx-background-color: #00E5FF; -fx-text-fill: #0d0d0d;" : "");
                statusBarLabel.setText(arrangementCanvas.isAutomationLaneVisible(track)
                        ? "Automation: " + track.getName()
                        : "Hide automation: " + track.getName());
                statusBarLabel.setGraphic(IconNode.of(DawIcon.AUTOMATION, 12));
            }
        });

        // ── Automation parameter selector ───────────────────────────────────
        ComboBox<AutomationParameter> paramSelector = new ComboBox<>();
        paramSelector.getItems().addAll(AutomationParameter.values());
        paramSelector.setValue(AutomationParameter.VOLUME);
        paramSelector.setTooltip(new Tooltip("Automation Parameter"));
        paramSelector.setPrefWidth(90);
        paramSelector.getStyleClass().add("status-bar-label");
        paramSelector.setOnAction(_ -> {
            if (arrangementCanvas != null && arrangementCanvas.isAutomationLaneVisible(track)) {
                arrangementCanvas.setAutomationParameter(track, paramSelector.getValue());
            }
        });

        // Spacer pushes controls to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ── Drag-and-drop reordering ────────────────────────────────────────
        attachDragHandlers(track, trackItem, typeIcon, nameLabel);

        // ── Right-click context menu with editing actions (Editing category) ─
        ContextMenu contextMenu = buildTrackContextMenu(track, nameLabel, trackItem);
        trackItem.setOnContextMenuRequested(e ->
                contextMenu.show(trackItem, e.getScreenX(), e.getScreenY()));

        trackItem.getChildren().addAll(
                typeIcon, ioLabel, nameLabel, insertChain, volRow, panRow,
                autoBtn, paramSelector, spacer,
                outputLabel, phaseBtn, muteBtn, soloBtn, armBtn, removeBtn);
        if (uiIndex >= 0 && uiIndex < trackListPanel.getChildren().size()) {
            trackListPanel.getChildren().add(uiIndex, trackItem);
        } else {
            trackListPanel.getChildren().add(trackItem);
        }

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
     * Selects an instrument-category icon based on the MIDI track name.
     *
     * <p>Scans the track name for common instrument keywords and returns
     * the matching {@link DawIcon} from the <em>Instruments</em> category.
     * Falls back to {@link DawIcon#PIANO} for unrecognized names.</p>
     */
    static DawIcon midiInstrumentIcon(String trackName) {
        String lower = trackName.toLowerCase(Locale.ROOT);
        if (lower.contains("drum") || lower.contains("perc")) return DawIcon.DRUMS;
        if (lower.contains("bass guitar"))     return DawIcon.BASS_GUITAR;
        if (lower.contains("electric guitar")) return DawIcon.ELECTRIC_GUITAR;
        if (lower.contains("acoustic guitar")) return DawIcon.ACOUSTIC_GUITAR;
        if (lower.contains("guitar"))    return DawIcon.GUITAR;
        if (lower.contains("bass"))      return DawIcon.BASS_GUITAR;
        if (lower.contains("violin") || lower.contains("string")) return DawIcon.VIOLIN;
        if (lower.contains("cello"))     return DawIcon.CELLO;
        if (lower.contains("sax"))       return DawIcon.SAXOPHONE;
        if (lower.contains("trumpet"))   return DawIcon.TRUMPET;
        if (lower.contains("trombone"))  return DawIcon.TROMBONE;
        if (lower.contains("tuba"))      return DawIcon.TUBA;
        if (lower.contains("flute"))     return DawIcon.FLUTE;
        if (lower.contains("clarinet"))  return DawIcon.CLARINET;
        if (lower.contains("harp"))      return DawIcon.HARP;
        if (lower.contains("harmonica")) return DawIcon.HARMONICA;
        if (lower.contains("banjo"))     return DawIcon.BANJO;
        if (lower.contains("mandolin"))  return DawIcon.MANDOLIN;
        if (lower.contains("ukulele") || lower.contains("uke")) return DawIcon.UKULELE;
        if (lower.contains("accordion")) return DawIcon.ACCORDION;
        if (lower.contains("xylo") || lower.contains("marimba")) return DawIcon.XYLOPHONE;
        if (lower.contains("bongo"))     return DawIcon.BONGOS;
        if (lower.contains("djembe"))    return DawIcon.DJEMBE;
        if (lower.contains("maraca"))    return DawIcon.MARACAS;
        if (lower.contains("tambourine")) return DawIcon.TAMBOURINE;
        if (lower.contains("electric"))  return DawIcon.ELECTRIC_GUITAR;
        if (lower.contains("acoustic"))  return DawIcon.ACOUSTIC_GUITAR;
        if (lower.contains("organ") || lower.contains("key")) return DawIcon.KEYBOARD;
        if (lower.contains("synth"))     return DawIcon.EQUALIZER;
        if (lower.contains("pad"))       return DawIcon.PAD;
        return DawIcon.PIANO;
    }

    /**
     * Builds a right-click context menu for a track strip, providing editing
     * operations from the <em>Editing</em>, <em>Navigation</em>, <em>Social</em>,
     * <em>General</em>, and <em>File Types</em> icon categories.
     */
    ContextMenu buildTrackContextMenu(Track track, Label nameLabel, HBox trackItem) {
        ContextMenu menu = new ContextMenu();

        // ── Editing operations ──────────────────────────────────────────────
        MenuItem copyItem = new MenuItem("Copy Track");
        copyItem.setGraphic(IconNode.of(DawIcon.COPY, 14));
        copyItem.setOnAction(_ -> {
            undoManager.execute(new UndoableAction() {
                private Track copy;
                private HBox copyTrackItem;
                @Override public String description() { return "Copy Track: " + track.getName(); }
                @Override public void execute() {
                    copy = project.duplicateTrack(track);
                    // duplicateTrack inserts at index+1 in the model; place UI strip to match
                    int modelIndex = project.getTracks().indexOf(copy);
                    // trackListPanel child 0 is the "TRACKS" header, so offset by 1
                    copyTrackItem = addTrackToUI(copy, modelIndex + 1);
                    host.updateArrangementPlaceholder();
                    mixerView.refresh();
                }
                @Override public void undo() {
                    project.removeTrack(copy);
                    trackListPanel.getChildren().remove(copyTrackItem);
                    host.updateArrangementPlaceholder();
                    mixerView.refresh();
                }
            });
            host.updateUndoRedoState();
            statusBarLabel.setText("Copied: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.COPY, 12));
            notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                    "Copied: " + track.getName(), host::undoLastAction);
            host.markProjectDirty();
        });

        MenuItem pasteItem = new MenuItem("Paste Over");
        pasteItem.setGraphic(IconNode.of(DawIcon.PASTE, 14));
        boolean clipboardEmpty = !clipboardManager.hasContent();
        pasteItem.setDisable(clipboardEmpty);
        if (clipboardEmpty) {
            pasteItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(pasteItem.getGraphic(), new Tooltip("Nothing copied to clipboard"));
        }
        pasteItem.setOnAction(_ -> {
            notificationBar.show(NotificationLevel.WARNING,
                    "Paste Over — not yet implemented");
        });

        MenuItem splitItem = new MenuItem("Split at Playhead");
        splitItem.setGraphic(IconNode.of(DawIcon.SPLIT, 14));
        boolean noClipsForSplit = track.getClips().isEmpty();
        if (noClipsForSplit) {
            splitItem.setDisable(true);
            splitItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(splitItem.getGraphic(), new Tooltip("No audio clip on track"));
        }
        splitItem.setOnAction(_ -> {
            double playhead = project.getTransport().getPositionInBeats();
            List<AudioClip> clipsToSplit = new ArrayList<>();
            for (AudioClip clip : track.getClips()) {
                if (playhead > clip.getStartBeat() && playhead < clip.getEndBeat()) {
                    clipsToSplit.add(clip);
                }
            }
            if (clipsToSplit.isEmpty()) {
                statusBarLabel.setText("No clips at playhead to split on: " + track.getName());
                statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, 12));
                return;
            }
            undoManager.execute(new UndoableAction() {
                private final List<AudioClip> originals = new ArrayList<>(clipsToSplit);
                private final List<double[]> savedState = new ArrayList<>();
                private final List<AudioClip> newClips = new ArrayList<>();
                {
                    for (AudioClip clip : originals) {
                        savedState.add(new double[]{
                                clip.getDurationBeats(), clip.getFadeOutBeats()});
                    }
                }
                @Override public String description() { return "Split at Playhead: " + track.getName(); }
                @Override public void execute() {
                    newClips.clear();
                    for (AudioClip clip : originals) {
                        AudioClip second = clip.splitAt(playhead);
                        track.addClip(second);
                        newClips.add(second);
                    }
                }
                @Override public void undo() {
                    for (int i = 0; i < originals.size(); i++) {
                        AudioClip clip = originals.get(i);
                        double[] saved = savedState.get(i);
                        clip.setDurationBeats(saved[0]);
                        clip.setFadeOutBeats(saved[1]);
                        track.removeClip(newClips.get(i));
                    }
                    newClips.clear();
                }
            });
            host.updateUndoRedoState();
            statusBarLabel.setText("Split: " + track.getName() + " at beat " + String.format("%.1f", playhead));
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SPLIT, 12));
            notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                    "Split: " + track.getName() + " at beat " + String.format("%.1f", playhead),
                    host::undoLastAction);
            host.markProjectDirty();
        });

        MenuItem trimItem = new MenuItem("Trim to Selection");
        trimItem.setGraphic(IconNode.of(DawIcon.TRIM, 14));
        boolean noSelectionForTrim = !selectionModel.hasSelection();
        trimItem.setDisable(noSelectionForTrim);
        if (noSelectionForTrim) {
            trimItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(trimItem.getGraphic(), new Tooltip("No active time selection"));
        }
        trimItem.setOnAction(_ -> {
            notificationBar.show(NotificationLevel.WARNING,
                    "Trim to Selection — not yet implemented");
        });

        MenuItem cropItem = new MenuItem("Crop");
        cropItem.setGraphic(IconNode.of(DawIcon.CROP, 14));
        boolean noSelectionForCrop = !selectionModel.hasSelection();
        cropItem.setDisable(noSelectionForCrop);
        if (noSelectionForCrop) {
            cropItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(cropItem.getGraphic(), new Tooltip("No active time selection"));
        }
        cropItem.setOnAction(_ -> {
            notificationBar.show(NotificationLevel.WARNING,
                    "Crop — not yet implemented");
        });

        MenuItem moveItem = new MenuItem("Move");
        moveItem.setGraphic(IconNode.of(DawIcon.MOVE, 14));
        moveItem.setDisable(true);
        moveItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(moveItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem reverseItem = new MenuItem("Reverse");
        reverseItem.setGraphic(IconNode.of(DawIcon.REVERSE, 14));
        boolean isMidiTrack = track.getType() == TrackType.MIDI;
        boolean noAudioClipsForReverse = isMidiTrack || track.getClips().isEmpty();
        if (noAudioClipsForReverse) {
            reverseItem.setDisable(true);
            reverseItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(reverseItem.getGraphic(), new Tooltip(
                    isMidiTrack ? "Reverse is not available for MIDI tracks" : "No audio clip on track"));
        }
        reverseItem.setOnAction(_ -> {
            List<AudioClip> clips = track.getClips();
            if (clips.isEmpty()) {
                statusBarLabel.setText("No clips to reverse on: " + track.getName());
                statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, 12));
                return;
            }
            undoManager.execute(new UndoableAction() {
                @Override public String description() { return "Reverse: " + track.getName(); }
                @Override public void execute() {
                    for (AudioClip clip : clips) {
                        clip.setReversed(!clip.isReversed());
                    }
                }
                @Override public void undo() {
                    for (AudioClip clip : clips) {
                        clip.setReversed(!clip.isReversed());
                    }
                }
            });
            host.updateUndoRedoState();
            statusBarLabel.setText("Reversed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.REVERSE, 12));
            notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                    "Reversed: " + track.getName(), host::undoLastAction);
            host.markProjectDirty();
        });

        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setGraphic(IconNode.of(DawIcon.SELECT_ALL, 14));
        selectAllItem.setDisable(true);
        selectAllItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(selectAllItem.getGraphic(), new Tooltip("Coming soon"));

        // ── Fade operations (Editing category) ──────────────────────────────
        MenuItem fadeInItem = new MenuItem("Fade In");
        fadeInItem.setGraphic(IconNode.of(DawIcon.FADE_IN, 14));
        boolean noAudioClipsForFadeIn = isMidiTrack || track.getClips().isEmpty();
        if (noAudioClipsForFadeIn) {
            fadeInItem.setDisable(true);
            fadeInItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(fadeInItem.getGraphic(), new Tooltip(
                    isMidiTrack ? "Fade In is not available for MIDI tracks" : "No audio clip on track"));
        }
        fadeInItem.setOnAction(_ -> {
            List<AudioClip> clips = track.getClips();
            if (clips.isEmpty()) {
                statusBarLabel.setText("No clips for fade in on: " + track.getName());
                statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, 12));
                return;
            }
            undoManager.execute(new UndoableAction() {
                private final double defaultFadeBeats = 2.0;
                private final List<double[]> savedFades = new ArrayList<>();
                {
                    for (AudioClip clip : clips) {
                        savedFades.add(new double[]{clip.getFadeInBeats()});
                    }
                }
                @Override public String description() { return "Fade In: " + track.getName(); }
                @Override public void execute() {
                    for (AudioClip clip : clips) {
                        clip.setFadeInBeats(defaultFadeBeats);
                    }
                }
                @Override public void undo() {
                    for (int i = 0; i < clips.size(); i++) {
                        clips.get(i).setFadeInBeats(savedFades.get(i)[0]);
                    }
                }
            });
            host.updateUndoRedoState();
            statusBarLabel.setText("Fade in applied: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FADE_IN, 12));
            notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                    "Fade in applied: " + track.getName(), host::undoLastAction);
            host.markProjectDirty();
        });

        MenuItem fadeOutItem = new MenuItem("Fade Out");
        fadeOutItem.setGraphic(IconNode.of(DawIcon.FADE_OUT, 14));
        boolean noAudioClipsForFadeOut = isMidiTrack || track.getClips().isEmpty();
        if (noAudioClipsForFadeOut) {
            fadeOutItem.setDisable(true);
            fadeOutItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(fadeOutItem.getGraphic(), new Tooltip(
                    isMidiTrack ? "Fade Out is not available for MIDI tracks" : "No audio clip on track"));
        }
        fadeOutItem.setOnAction(_ -> {
            List<AudioClip> clips = track.getClips();
            if (clips.isEmpty()) {
                statusBarLabel.setText("No clips for fade out on: " + track.getName());
                statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, 12));
                return;
            }
            undoManager.execute(new UndoableAction() {
                private final double defaultFadeBeats = 2.0;
                private final List<double[]> savedFades = new ArrayList<>();
                {
                    for (AudioClip clip : clips) {
                        savedFades.add(new double[]{clip.getFadeOutBeats()});
                    }
                }
                @Override public String description() { return "Fade Out: " + track.getName(); }
                @Override public void execute() {
                    for (AudioClip clip : clips) {
                        clip.setFadeOutBeats(defaultFadeBeats);
                    }
                }
                @Override public void undo() {
                    for (int i = 0; i < clips.size(); i++) {
                        clips.get(i).setFadeOutBeats(savedFades.get(i)[0]);
                    }
                }
            });
            host.updateUndoRedoState();
            statusBarLabel.setText("Fade out applied: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FADE_OUT, 12));
            notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                    "Fade out applied: " + track.getName(), host::undoLastAction);
            host.markProjectDirty();
        });

        // ── Zoom controls (Editing category) ────────────────────────────────
        ZoomLevel currentZoom = host.currentZoomLevel();

        MenuItem zoomInItem = new MenuItem("Zoom In");
        zoomInItem.setGraphic(IconNode.of(DawIcon.ZOOM_IN, 14));
        if (!currentZoom.canZoomIn()) {
            zoomInItem.setDisable(true);
            zoomInItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(zoomInItem.getGraphic(), new Tooltip("Already at maximum zoom level"));
        }
        zoomInItem.setOnAction(_ -> {
            host.zoomIn();
            statusBarLabel.setText("Zoom in: " + host.currentZoomLevel().toPercentageString());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ZOOM_IN, 12));
        });

        MenuItem zoomOutItem = new MenuItem("Zoom Out");
        zoomOutItem.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, 14));
        if (!currentZoom.canZoomOut()) {
            zoomOutItem.setDisable(true);
            zoomOutItem.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(zoomOutItem.getGraphic(), new Tooltip("Already at minimum zoom level"));
        }
        zoomOutItem.setOnAction(_ -> {
            host.zoomOut();
            statusBarLabel.setText("Zoom out: " + host.currentZoomLevel().toPercentageString());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, 12));
        });

        // ── Snap toggle (Editing category) ──────────────────────────────────
        MenuItem snapItem = new MenuItem(host.isSnapEnabled() ? "Snap: ON" : "Snap: OFF");
        snapItem.setGraphic(IconNode.of(DawIcon.SNAP, 14));
        snapItem.setOnAction(_ -> {
            host.toggleSnap();
            snapItem.setText(host.isSnapEnabled() ? "Snap: ON" : "Snap: OFF");
            statusBarLabel.setText(host.isSnapEnabled() ? "Snap to grid enabled" : "Snap to grid disabled");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SNAP, 12));
        });

        // ── Alignment (Editing category) ────────────────────────────────────
        MenuItem alignItem = new MenuItem("Align to Grid");
        alignItem.setGraphic(IconNode.of(DawIcon.ALIGN_CENTER, 14));
        alignItem.setDisable(true);
        alignItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(alignItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem alignLeftItem = new MenuItem("Align Left");
        alignLeftItem.setGraphic(IconNode.of(DawIcon.ALIGN_LEFT, 14));
        alignLeftItem.setDisable(true);
        alignLeftItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(alignLeftItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem alignRightItem = new MenuItem("Align Right");
        alignRightItem.setGraphic(IconNode.of(DawIcon.ALIGN_RIGHT, 14));
        alignRightItem.setDisable(true);
        alignRightItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(alignRightItem.getGraphic(), new Tooltip("Coming soon"));

        // ── View controls (Navigation category) ─────────────────────────────
        MenuItem expandItem = new MenuItem("Expand Track");
        expandItem.setGraphic(IconNode.of(DawIcon.EXPAND, 14));
        expandItem.setOnAction(_ -> {
            trackItem.setPrefHeight(120);
            trackItem.setMinHeight(120);
            statusBarLabel.setText("Expanded: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.EXPAND, 12));
        });

        MenuItem collapseItem = new MenuItem("Collapse Track");
        collapseItem.setGraphic(IconNode.of(DawIcon.COLLAPSE, 14));
        collapseItem.setOnAction(_ -> {
            trackItem.setPrefHeight(Region.USE_COMPUTED_SIZE);
            trackItem.setMinHeight(Region.USE_COMPUTED_SIZE);
            statusBarLabel.setText("Collapsed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.COLLAPSE, 12));
        });

        MenuItem fullscreenItem = new MenuItem("Fullscreen Editor");
        fullscreenItem.setGraphic(IconNode.of(DawIcon.FULLSCREEN, 14));
        fullscreenItem.setDisable(true);
        fullscreenItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(fullscreenItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem minimizeItem = new MenuItem("Minimize");
        minimizeItem.setGraphic(IconNode.of(DawIcon.MINIMIZE, 14));
        minimizeItem.setDisable(true);
        minimizeItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(minimizeItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem homeItem = new MenuItem("Go to Start");
        homeItem.setGraphic(IconNode.of(DawIcon.HOME, 14));
        homeItem.setOnAction(_ -> { host.skipToStart();
            statusBarLabel.setGraphic(IconNode.of(DawIcon.HOME, 12)); });

        MenuItem pipItem = new MenuItem("Picture-in-Picture");
        pipItem.setGraphic(IconNode.of(DawIcon.PIP, 14));
        pipItem.setDisable(true);
        pipItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(pipItem.getGraphic(), new Tooltip("Coming soon"));

        // ── Social/sharing (Social category) — disabled, future epic ────────
        MenuItem shareItem = new MenuItem("Share Track");
        shareItem.setGraphic(IconNode.of(DawIcon.SHARE, 14));
        shareItem.setDisable(true);
        shareItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(shareItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        MenuItem broadcastItem = new MenuItem("Broadcast");
        broadcastItem.setGraphic(IconNode.of(DawIcon.BROADCAST, 14));
        broadcastItem.setDisable(true);
        broadcastItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(broadcastItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        MenuItem streamItem = new MenuItem("Stream");
        streamItem.setGraphic(IconNode.of(DawIcon.STREAM, 14));
        streamItem.setDisable(true);
        streamItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(streamItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        MenuItem rateItem = new MenuItem("Rate Track");
        rateItem.setGraphic(IconNode.of(DawIcon.RATE, 14));
        rateItem.setDisable(true);
        rateItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(rateItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        MenuItem dislikeItem = new MenuItem("Dislike Track");
        dislikeItem.setGraphic(IconNode.of(DawIcon.DISLIKE, 14));
        dislikeItem.setDisable(true);
        dislikeItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(dislikeItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        MenuItem commentItem = new MenuItem("Add Comment");
        commentItem.setGraphic(IconNode.of(DawIcon.COMMENT, 14));
        commentItem.setDisable(true);
        commentItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(commentItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        MenuItem followItem = new MenuItem("Follow Track");
        followItem.setGraphic(IconNode.of(DawIcon.FOLLOW, 14));
        followItem.setDisable(true);
        followItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(followItem.getGraphic(), new Tooltip("Coming soon — social features planned for future release"));

        // ── Export sub-options (File Types category) ─────────────────────────
        boolean noAudioData = track.getClips().isEmpty();
        boolean isNotMidiTrack = track.getType() != TrackType.MIDI;

        MenuItem exportWav = new MenuItem("Export as WAV");
        exportWav.setGraphic(IconNode.of(DawIcon.WAV, 14));
        if (noAudioData) {
            exportWav.setDisable(true);
            exportWav.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(exportWav.getGraphic(), new Tooltip("No audio data to export"));
        }
        exportWav.setOnAction(_ -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export Track as WAV");
            fileChooser.setInitialFileName(track.getName() + ".wav");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("WAV Audio", "*.wav"));
            Stage stage = (Stage) rootPane.getScene().getWindow();
            java.io.File file = fileChooser.showSaveDialog(stage);
            if (file != null) {
                int sampleRate = (int) project.getFormat().sampleRate();
                int bitDepth = project.getFormat().bitDepth();
                int channels = project.getFormat().channels();
                double tempo = project.getTransport().getTempo();
                float[][] audioData = TrackBouncer.bounce(track, sampleRate, tempo, channels);
                if (audioData == null) {
                    notificationBar.show(NotificationLevel.WARNING,
                            "No audio data to export for track: " + track.getName());
                    return;
                }
                statusBarLabel.setText("Exporting WAV: " + track.getName() + "…");
                statusBarLabel.setGraphic(IconNode.of(DawIcon.WAV, 12));
                try {
                    WavExporter.write(audioData, sampleRate, bitDepth,
                            com.benesquivelmusic.daw.sdk.export.DitherType.TPDF,
                            com.benesquivelmusic.daw.sdk.export.AudioMetadata.EMPTY,
                            file.toPath());
                    statusBarLabel.setText("Exported WAV: " + track.getName() + " → " + file.getName());
                    notificationBar.show(NotificationLevel.SUCCESS,
                            "Exported WAV: " + track.getName() + " → " + file.getAbsolutePath());
                    LOG.info(() -> "Exported track " + track.getName() + " as WAV to " + file.getAbsolutePath());
                } catch (IOException ex) {
                    statusBarLabel.setText("WAV export failed: " + ex.getMessage());
                    statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
                    notificationBar.show(NotificationLevel.ERROR,
                            "WAV export failed: " + ex.getMessage());
                    LOG.log(Level.WARNING, "WAV export failed for track " + track.getName(), ex);
                }
            }
        });

        MenuItem exportMp3 = new MenuItem("Export as MP3");
        exportMp3.setGraphic(IconNode.of(DawIcon.MP3, 14));
        exportMp3.setDisable(true);
        exportMp3.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(exportMp3.getGraphic(), new Tooltip(
                noAudioData ? "No audio data to export" : "Format not yet supported — export as WAV and convert externally"));

        MenuItem exportAac = new MenuItem("Export as AAC");
        exportAac.setGraphic(IconNode.of(DawIcon.AAC, 14));
        exportAac.setDisable(true);
        exportAac.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(exportAac.getGraphic(), new Tooltip(
                noAudioData ? "No audio data to export" : "Format not yet supported — export as WAV and convert externally"));

        MenuItem exportMidi = new MenuItem("Export as MIDI");
        exportMidi.setGraphic(IconNode.of(DawIcon.MIDI_FILE, 14));
        if (isNotMidiTrack) {
            exportMidi.setDisable(true);
            exportMidi.setStyle("-fx-opacity: 0.5;");
            Tooltip.install(exportMidi.getGraphic(), new Tooltip("Track is not a MIDI track"));
        }
        exportMidi.setOnAction(_ -> {
            EditorView ev = host.editorView();
            List<MidiNote> notes = ev != null ? ev.getNotes() : List.of();
            if (notes.isEmpty()) {
                notificationBar.show(NotificationLevel.WARNING,
                        "No MIDI notes to export for track: " + track.getName());
                return;
            }
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export Track as MIDI");
            fileChooser.setInitialFileName(track.getName() + ".mid");
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("MIDI File", "*.mid"));
            Stage stage = (Stage) rootPane.getScene().getWindow();
            java.io.File file = fileChooser.showSaveDialog(stage);
            if (file != null) {
                double tempo = project.getTransport().getTempo();
                List<MidiFileExporter.NoteDescriptor> descriptors = new java.util.ArrayList<>();
                for (MidiNote note : notes) {
                    int midiNoteNumber = MidiNote.MAX_NOTE - note.note();
                    descriptors.add(new MidiFileExporter.NoteDescriptor(
                            midiNoteNumber, note.startColumn(),
                            note.durationColumns(), note.velocity(), 0));
                }
                statusBarLabel.setText("Exporting MIDI: " + track.getName() + "…");
                statusBarLabel.setGraphic(IconNode.of(DawIcon.MIDI_FILE, 12));
                try {
                    MidiFileExporter.write(descriptors, tempo, file.toPath());
                    statusBarLabel.setText("Exported MIDI: " + track.getName() + " → " + file.getName());
                    notificationBar.show(NotificationLevel.SUCCESS,
                            "Exported MIDI: " + track.getName() + " → " + file.getAbsolutePath());
                    LOG.info(() -> "Exported track " + track.getName() + " as MIDI to " + file.getAbsolutePath());
                } catch (IOException ex) {
                    statusBarLabel.setText("MIDI export failed: " + ex.getMessage());
                    statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
                    notificationBar.show(NotificationLevel.ERROR,
                            "MIDI export failed: " + ex.getMessage());
                    LOG.log(Level.WARNING, "MIDI export failed for track " + track.getName(), ex);
                }
            }
        });

        MenuItem exportWma = new MenuItem("Export as WMA");
        exportWma.setGraphic(IconNode.of(DawIcon.WMA, 14));
        exportWma.setDisable(true);
        exportWma.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(exportWma.getGraphic(), new Tooltip(
                noAudioData ? "No audio data to export" : "Format not yet supported — export as WAV and convert externally"));

        // ── General category items ──────────────────────────────────────────
        MenuItem favoriteItem = new MenuItem("Add to Favorites");
        favoriteItem.setGraphic(IconNode.of(DawIcon.FAVORITE, 14));
        favoriteItem.setDisable(true);
        favoriteItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(favoriteItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem playlistItem = new MenuItem("Add to Playlist");
        playlistItem.setGraphic(IconNode.of(DawIcon.PLAYLIST, 14));
        playlistItem.setDisable(true);
        playlistItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(playlistItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem filmScoreItem = new MenuItem("Film Score Mode");
        filmScoreItem.setGraphic(IconNode.of(DawIcon.FILM, 14));
        filmScoreItem.setDisable(true);
        filmScoreItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(filmScoreItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem notifyItem = new MenuItem("Set Alert");
        notifyItem.setGraphic(IconNode.of(DawIcon.BELL, 14));
        notifyItem.setDisable(true);
        notifyItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(notifyItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem repeatOneItem = new MenuItem("Repeat Once");
        repeatOneItem.setGraphic(IconNode.of(DawIcon.REPEAT_ONE, 14));
        repeatOneItem.setDisable(true);
        repeatOneItem.setStyle("-fx-opacity: 0.5;");
        Tooltip.install(repeatOneItem.getGraphic(), new Tooltip("Coming soon"));

        MenuItem renameItem = new MenuItem("Rename");
        renameItem.setGraphic(IconNode.of(DawIcon.BOOKMARK, 14));
        renameItem.setOnAction(_ -> startTrackRename(track, nameLabel, trackItem));

        menu.getItems().addAll(
                copyItem, pasteItem, new SeparatorMenuItem(),
                splitItem, trimItem, cropItem, moveItem, reverseItem, new SeparatorMenuItem(),
                fadeInItem, fadeOutItem, new SeparatorMenuItem(),
                selectAllItem, alignItem, alignLeftItem, alignRightItem, snapItem, new SeparatorMenuItem(),
                zoomInItem, zoomOutItem, new SeparatorMenuItem(),
                expandItem, collapseItem, fullscreenItem, minimizeItem, pipItem, homeItem, new SeparatorMenuItem(),
                exportWav, exportMp3, exportAac, exportMidi, exportWma, new SeparatorMenuItem(),
                shareItem, broadcastItem, streamItem, rateItem, dislikeItem, commentItem, followItem, new SeparatorMenuItem(),
                favoriteItem, playlistItem, filmScoreItem, notifyItem, repeatOneItem, renameItem);

        // Recalculate dynamic enabled/disabled states each time the menu is shown
        // so items reflect the current clipboard, selection, clip, and zoom state.
        menu.setOnShowing(_ -> {
            boolean cbEmpty = !clipboardManager.hasContent();
            pasteItem.setDisable(cbEmpty);
            pasteItem.setStyle(cbEmpty ? "-fx-opacity: 0.5;" : "");

            boolean noClips = track.getClips().isEmpty();
            boolean midi = track.getType() == TrackType.MIDI;
            boolean noAudio = midi || noClips;

            splitItem.setDisable(noClips);
            splitItem.setStyle(noClips ? "-fx-opacity: 0.5;" : "");

            boolean noSel = !selectionModel.hasSelection();
            trimItem.setDisable(noSel);
            trimItem.setStyle(noSel ? "-fx-opacity: 0.5;" : "");
            cropItem.setDisable(noSel);
            cropItem.setStyle(noSel ? "-fx-opacity: 0.5;" : "");

            reverseItem.setDisable(noAudio);
            reverseItem.setStyle(noAudio ? "-fx-opacity: 0.5;" : "");
            fadeInItem.setDisable(noAudio);
            fadeInItem.setStyle(noAudio ? "-fx-opacity: 0.5;" : "");
            fadeOutItem.setDisable(noAudio);
            fadeOutItem.setStyle(noAudio ? "-fx-opacity: 0.5;" : "");

            ZoomLevel zoom = host.currentZoomLevel();
            zoomInItem.setDisable(!zoom.canZoomIn());
            zoomInItem.setStyle(!zoom.canZoomIn() ? "-fx-opacity: 0.5;" : "");
            zoomOutItem.setDisable(!zoom.canZoomOut());
            zoomOutItem.setStyle(!zoom.canZoomOut() ? "-fx-opacity: 0.5;" : "");

            snapItem.setText(host.isSnapEnabled() ? "Snap: ON" : "Snap: OFF");

            exportWav.setDisable(noClips);
            exportWav.setStyle(noClips ? "-fx-opacity: 0.5;" : "");
        });

        return menu;
    }

    // ── Drag-and-drop reordering ────────────────────────────────────────────

    /**
     * Computes the model-level target index for a track move operation given
     * the source index, the drop-target track's model index, and whether the
     * cursor landed in the top half of the target strip (insert above) or the
     * bottom half (insert below).
     *
     * <p>The result accounts for the remove-then-insert semantics of
     * {@link DawProject#moveTrack(int, int)}: when the source precedes the
     * target, removing it shifts the target index down by one.</p>
     *
     * @param fromIndex        the current model index of the dragged track
     * @param targetModelIndex the model index of the track the cursor is over
     * @param dropAbove        {@code true} if the cursor is in the top half
     * @return the target index to pass to {@code moveTrack}, or {@code -1}
     *         if no move is needed (same position)
     */
    static int computeDropTargetIndex(int fromIndex, int targetModelIndex, boolean dropAbove) {
        int insertPos = dropAbove ? targetModelIndex : targetModelIndex + 1;
        int toIndex = fromIndex < insertPos ? insertPos - 1 : insertPos;
        return fromIndex == toIndex ? -1 : toIndex;
    }

    /**
     * Attaches JavaFX drag-and-drop event handlers to the given track strip.
     *
     * <p>The drag gesture is initiated only from the header region (type icon
     * and name label) so that interactive controls (Sliders, Buttons) are not
     * affected. Drop-over, drop, and drag-done handlers are attached to the
     * entire strip so it acts as a valid drop target.</p>
     */
    private void attachDragHandlers(Track track, HBox trackItem,
                                    Node typeIcon, Label nameLabel) {
        // Initiate drag only from header elements to avoid conflicts with
        // interactive controls (volume/pan Sliders, buttons, etc.)
        Runnable initiateDrag = () -> {
            Dragboard db = trackItem.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(TRACK_ID_FORMAT, track.getId());
            db.setContent(content);
            db.setDragView(trackItem.snapshot(null, null));
            trackItem.setOpacity(0.4);
        };
        typeIcon.setOnDragDetected(event -> {
            initiateDrag.run();
            event.consume();
        });
        nameLabel.setOnDragDetected(event -> {
            initiateDrag.run();
            event.consume();
        });

        trackItem.setOnDragOver(event -> {
            if (event.getGestureSource() != trackItem
                    && event.getDragboard().hasContent(TRACK_ID_FORMAT)) {
                event.acceptTransferModes(TransferMode.MOVE);
                boolean topHalf = event.getY() < trackItem.getHeight() / 2;
                trackItem.getStyleClass().removeAll("track-drop-above", "track-drop-below");
                trackItem.getStyleClass().add(topHalf ? "track-drop-above" : "track-drop-below");
            }
            event.consume();
        });

        trackItem.setOnDragExited(event -> {
            trackItem.getStyleClass().removeAll("track-drop-above", "track-drop-below");
            event.consume();
        });

        trackItem.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasContent(TRACK_ID_FORMAT)) {
                String sourceTrackId = (String) db.getContent(TRACK_ID_FORMAT);
                Track sourceTrack = findTrackById(sourceTrackId);
                if (sourceTrack != null && sourceTrack != track) {
                    int fromIndex = project.getTracks().indexOf(sourceTrack);
                    int targetModelIndex = project.getTracks().indexOf(track);
                    boolean topHalf = event.getY() < trackItem.getHeight() / 2;
                    int toIndex = computeDropTargetIndex(fromIndex, targetModelIndex, topHalf);

                    if (toIndex >= 0 && fromIndex >= 0
                            && toIndex < project.getTracks().size()) {
                        int finalFrom = fromIndex;
                        int finalTo = toIndex;
                        undoManager.execute(new UndoableAction() {
                            @Override public String description() { return "Move Track"; }
                            @Override public void execute() {
                                project.moveTrack(finalFrom, finalTo);
                                reorderTrackStrip(finalFrom, finalTo);
                                mixerView.refresh();
                            }
                            @Override public void undo() {
                                project.moveTrack(finalTo, finalFrom);
                                reorderTrackStrip(finalTo, finalFrom);
                                mixerView.refresh();
                            }
                        });
                        animateDrop(trackListPanel.getChildren().get(finalTo + 1));
                        host.updateUndoRedoState();
                        host.markProjectDirty();
                        statusBarLabel.setText("Moved track: " + sourceTrack.getName());
                        statusBarLabel.setGraphic(IconNode.of(DawIcon.MOVE, 12));
                        success = true;
                    }
                }
            }
            trackItem.getStyleClass().removeAll("track-drop-above", "track-drop-below");
            event.setDropCompleted(success);
            event.consume();
        });

        trackItem.setOnDragDone(event -> {
            // Only reset opacity when the gesture was cancelled/unsuccessful;
            // successful drops let animateDrop() control the fade-in.
            if (event.getTransferMode() == null) {
                trackItem.setOpacity(1.0);
            }
            for (Node child : trackListPanel.getChildren()) {
                child.getStyleClass().removeAll("track-drop-above", "track-drop-below");
            }
            event.consume();
        });
    }

    /**
     * Reorders the track strip nodes in the {@code trackListPanel} to match a
     * model-level move. Child index 0 is the "TRACKS" header label, so the
     * model-to-UI offset is 1.
     */
    private void reorderTrackStrip(int fromModelIndex, int toModelIndex) {
        int fromUI = fromModelIndex + 1;
        int toUI = toModelIndex + 1;
        Node node = trackListPanel.getChildren().remove(fromUI);
        trackListPanel.getChildren().add(toUI, node);
    }

    private Track findTrackById(String trackId) {
        for (Track t : project.getTracks()) {
            if (t.getId().equals(trackId)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Plays a brief translate/fade animation on a dropped track strip,
     * consistent with the existing track-strip entry animation style.
     */
    private void animateDrop(Node trackItem) {
        trackItem.setTranslateY(-8);
        trackItem.setOpacity(0.6);
        TranslateTransition slide = new TranslateTransition(Duration.millis(200), trackItem);
        slide.setToY(0.0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition fade = new FadeTransition(Duration.millis(200), trackItem);
        fade.setToValue(1.0);
        new ParallelTransition(slide, fade).play();
    }

    /**
     * Replaces the track name label with a text field for inline renaming.
     */
    void startTrackRename(Track track, Label nameLabel, HBox trackItem) {
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
                host.updateUndoRedoState();
                host.markProjectDirty();
                statusBarLabel.setText("Renamed track: " + oldName + " → " + newName);
                statusBarLabel.setGraphic(IconNode.of(DawIcon.BOOKMARK, 12));
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
}
