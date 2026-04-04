package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;
import com.benesquivelmusic.daw.core.midi.VelocityCurve;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.Objects;

/**
 * JavaFX virtual keyboard UI for the {@link KeyboardProcessor} plugin.
 *
 * <p>Renders a piano keyboard on a {@link Canvas} with interactive white and
 * black keys. Users can click (or click-and-drag) on keys to play MIDI notes
 * in real time. The view also provides controls for:</p>
 * <ul>
 *   <li>Preset selection via a {@link ComboBox}</li>
 *   <li>Octave offset and transposition via {@link Spinner} controls</li>
 *   <li>Velocity control via a {@link Slider}</li>
 *   <li>Velocity curve selection</li>
 *   <li>Record / Stop / Play / Clear buttons for MIDI recording and playback</li>
 * </ul>
 *
 * <p>The keyboard visually highlights active (pressed) keys and shows note
 * names on white keys.</p>
 */
public final class KeyboardProcessorView extends VBox {

    // ── Layout Constants ───────────────────────────────────────────────

    /** Width of a white key in pixels. */
    static final double WHITE_KEY_WIDTH = 28.0;

    /** Height of a white key in pixels. */
    static final double WHITE_KEY_HEIGHT = 120.0;

    /** Width of a black key in pixels. */
    static final double BLACK_KEY_WIDTH = 18.0;

    /** Height of a black key in pixels. */
    static final double BLACK_KEY_HEIGHT = 75.0;

    /** Number of notes per octave. */
    static final int NOTES_PER_OCTAVE = 12;

    private static final Color WHITE_KEY_COLOR = Color.WHITE;
    private static final Color WHITE_KEY_PRESSED_COLOR = Color.rgb(120, 180, 255);
    private static final Color BLACK_KEY_COLOR = Color.rgb(30, 30, 30);
    private static final Color BLACK_KEY_PRESSED_COLOR = Color.rgb(80, 140, 220);
    private static final Color KEY_BORDER_COLOR = Color.rgb(100, 100, 100);
    private static final Color NOTE_LABEL_COLOR = Color.rgb(100, 100, 100);
    private static final double HEADER_ICON_SIZE = 18;

    private final KeyboardProcessor processor;
    private final Canvas keyboardCanvas;
    private final KeyboardProcessor.KeyboardEventListener keyboardListener;
    private final AnimationTimer playbackTimer;
    private int lastPressedNote = -1;
    private boolean updatingControls;

    // Controls
    private final ComboBox<KeyboardPreset> presetCombo;
    private final Slider velocitySlider;
    private final ComboBox<VelocityCurve> curveCombo;
    private final Spinner<Integer> transposeSpinner;
    private final ToggleButton recordButton;
    private final Button playButton;
    private final Button stopButton;
    private final Button clearButton;

    /**
     * Creates a new keyboard processor view.
     *
     * @param processor the keyboard processor to control
     */
    public KeyboardProcessorView(KeyboardProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(8);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-radius: 4;");

        // ── Title ──────────────────────────────────────────────────────
        Label title = new Label("Virtual Keyboard");
        title.setGraphic(IconNode.of(DawIcon.KEYBOARD, HEADER_ICON_SIZE));
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Preset selector ────────────────────────────────────────────
        presetCombo = new ComboBox<>(FXCollections.observableArrayList(KeyboardPreset.factoryPresets()));
        presetCombo.setCellFactory(_ -> new PresetCell());
        presetCombo.setButtonCell(new PresetCell());
        KeyboardPreset currentPreset = processor.getPreset();
        presetCombo.getSelectionModel().select(currentPreset);
        if (presetCombo.getSelectionModel().getSelectedIndex() < 0) {
            presetCombo.getSelectionModel().selectFirst();
        }
        presetCombo.setTooltip(new Tooltip("Select instrument preset"));
        presetCombo.setOnAction(_ -> onPresetChanged());

        Label presetLabel = new Label("Preset:");
        presetLabel.setStyle("-fx-text-fill: #ccc;");

        // ── Velocity slider ────────────────────────────────────────────
        velocitySlider = new Slider(1, 127, processor.getPreset().defaultVelocity());
        velocitySlider.setShowTickLabels(true);
        velocitySlider.setShowTickMarks(true);
        velocitySlider.setMajorTickUnit(32);
        velocitySlider.setPrefWidth(150);
        velocitySlider.setTooltip(new Tooltip("Note velocity"));
        velocitySlider.valueProperty().addListener((_, _, newValue) -> {
            if (updatingControls) {
                return;
            }
            processor.setPreset(processor.getPreset().withDefaultVelocity(newValue.intValue()));
        });

        Label velLabel = new Label("Velocity:");
        velLabel.setStyle("-fx-text-fill: #ccc;");

        // ── Velocity curve ─────────────────────────────────────────────
        curveCombo = new ComboBox<>(FXCollections.observableArrayList(VelocityCurve.values()));
        curveCombo.getSelectionModel().select(processor.getPreset().velocityCurve());
        curveCombo.setTooltip(new Tooltip("Velocity response curve"));
        curveCombo.setOnAction(_ -> onCurveChanged());

        Label curveLabel = new Label("Curve:");
        curveLabel.setStyle("-fx-text-fill: #ccc;");

        // ── Transpose spinner ──────────────────────────────────────────
        transposeSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                -KeyboardPreset.MAX_TRANSPOSE, KeyboardPreset.MAX_TRANSPOSE, 0));
        transposeSpinner.setPrefWidth(70);
        transposeSpinner.setEditable(true);
        transposeSpinner.setTooltip(new Tooltip("Transpose (semitones)"));
        transposeSpinner.valueProperty().addListener((_, _, newVal) -> onTransposeChanged(newVal));

        Label transposeLabel = new Label("Transpose:");
        transposeLabel.setStyle("-fx-text-fill: #ccc;");

        // ── Record / Play / Stop / Clear buttons ───────────────────────
        recordButton = new ToggleButton("⏺ Record");
        recordButton.setStyle("-fx-text-fill: #ff4444;");
        recordButton.setTooltip(new Tooltip("Start/stop MIDI recording"));
        recordButton.setOnAction(_ -> onRecordToggle());

        playButton = new Button("▶ Play");
        playButton.setTooltip(new Tooltip("Play back recorded MIDI clip"));
        playButton.setOnAction(_ -> onPlay());

        stopButton = new Button("⏹ Stop");
        stopButton.setTooltip(new Tooltip("Stop playback/recording"));
        stopButton.setOnAction(_ -> onStop());

        clearButton = new Button("🗑 Clear");
        clearButton.setTooltip(new Tooltip("Clear recorded MIDI clip"));
        clearButton.setOnAction(_ -> onClear());

        // ── Control bar rows ───────────────────────────────────────────
        HBox presetRow = new HBox(6, presetLabel, presetCombo, transposeLabel, transposeSpinner);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        HBox velocityRow = new HBox(6, velLabel, velocitySlider, curveLabel, curveCombo);
        velocityRow.setAlignment(Pos.CENTER_LEFT);

        HBox transportRow = new HBox(6, recordButton, playButton, stopButton, clearButton);
        transportRow.setAlignment(Pos.CENTER_LEFT);

        // ── Keyboard canvas ────────────────────────────────────────────
        KeyboardPreset p = processor.getPreset();
        int whiteKeyCount = countWhiteKeys(p.lowestOctave(), p.highestOctave());
        double canvasWidth = whiteKeyCount * WHITE_KEY_WIDTH + 1;

        keyboardCanvas = new Canvas(canvasWidth, WHITE_KEY_HEIGHT + 2);
        keyboardCanvas.setOnMousePressed(this::onMousePressed);
        keyboardCanvas.setOnMouseDragged(this::onMouseDragged);
        keyboardCanvas.setOnMouseReleased(this::onMouseReleased);

        // Register listener for visual feedback (stored for cleanup)
        keyboardListener = event -> {
            if (Platform.isFxApplicationThread()) {
                paintKeyboard();
            } else {
                Platform.runLater(this::paintKeyboard);
            }
        };
        processor.addListener(keyboardListener);

        // Animation timer to drive playback advancement
        playbackTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (processor.isPlaying()) {
                    processor.advancePlayback();
                } else {
                    stop();
                }
            }
        };

        // Clean up listener and timer when removed from scene graph
        sceneProperty().addListener((_, _, newScene) -> {
            if (newScene == null) {
                dispose();
            }
        });

        getChildren().addAll(title, presetRow, velocityRow, transportRow, keyboardCanvas);
        HBox.setHgrow(presetCombo, Priority.ALWAYS);

        paintKeyboard();
    }

    // ── Event Handlers ─────────────────────────────────────────────────

    private void onPresetChanged() {
        KeyboardPreset selected = presetCombo.getValue();
        if (selected != null) {
            processor.setPreset(selected);
            updatingControls = true;
            try {
                velocitySlider.setValue(selected.defaultVelocity());
                curveCombo.getSelectionModel().select(selected.velocityCurve());
                transposeSpinner.getValueFactory().setValue(selected.transpose());
            } finally {
                updatingControls = false;
            }
            resizeCanvas();
            paintKeyboard();
        }
    }

    private void onCurveChanged() {
        if (updatingControls) {
            return;
        }
        VelocityCurve curve = curveCombo.getValue();
        if (curve != null) {
            processor.setPreset(processor.getPreset().withVelocityCurve(curve));
        }
    }

    private void onTransposeChanged(int newValue) {
        if (updatingControls) {
            return;
        }
        processor.setPreset(processor.getPreset().withTranspose(newValue));
    }

    private void onRecordToggle() {
        if (recordButton.isSelected()) {
            int velocity = (int) velocitySlider.getValue();
            processor.setPreset(processor.getPreset().withDefaultVelocity(Math.max(1, velocity)));
            processor.startRecording(120.0, 0);
        } else {
            processor.stopRecording();
        }
    }

    private void onPlay() {
        if (!processor.isPlaying()) {
            processor.startPlayback(120.0);
            if (processor.isPlaying()) {
                playbackTimer.start();
            }
        }
    }

    private void onStop() {
        if (processor.isRecording()) {
            processor.stopRecording();
            recordButton.setSelected(false);
        }
        if (processor.isPlaying()) {
            processor.stopPlayback();
        }
        playbackTimer.stop();
    }

    private void onClear() {
        processor.clearClip();
    }

    // ── Mouse Interaction ──────────────────────────────────────────────

    private void onMousePressed(MouseEvent e) {
        int note = hitTest(e.getX(), e.getY());
        if (note >= 0) {
            lastPressedNote = note;
            processor.noteOn(note, (int) velocitySlider.getValue());
            paintKeyboard();
        }
    }

    private void onMouseDragged(MouseEvent e) {
        int note = hitTest(e.getX(), e.getY());
        if (note != lastPressedNote) {
            if (lastPressedNote >= 0) {
                processor.noteOff(lastPressedNote);
            }
            if (note >= 0) {
                processor.noteOn(note, (int) velocitySlider.getValue());
            }
            lastPressedNote = note;
            paintKeyboard();
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (lastPressedNote >= 0) {
            processor.noteOff(lastPressedNote);
            lastPressedNote = -1;
            paintKeyboard();
        }
    }

    // ── Hit Testing ────────────────────────────────────────────────────

    /**
     * Determines which MIDI note number is at the given canvas coordinates.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @return the MIDI note number, or {@code -1} if no key is at that position
     */
    int hitTest(double x, double y) {
        if (y < 0 || y > WHITE_KEY_HEIGHT) {
            return -1;
        }

        KeyboardPreset p = processor.getPreset();
        int lowestNote = (p.lowestOctave() + 1) * NOTES_PER_OCTAVE;
        int highestNote = Math.min(127, (p.highestOctave() + 2) * NOTES_PER_OCTAVE - 1);

        // Check black keys first (they overlap white keys)
        if (y < BLACK_KEY_HEIGHT) {
            int whiteIndex = 0;
            for (int note = lowestNote; note <= highestNote; note++) {
                int noteIndex = note % NOTES_PER_OCTAVE;
                if (KeyboardProcessor.isBlackKey(noteIndex)) {
                    double bx = whiteIndex * WHITE_KEY_WIDTH - BLACK_KEY_WIDTH / 2.0;
                    if (x >= bx && x < bx + BLACK_KEY_WIDTH) {
                        return note;
                    }
                } else {
                    whiteIndex++;
                }
            }
        }

        // Check white keys
        int whiteIndex = 0;
        for (int note = lowestNote; note <= highestNote; note++) {
            int noteIndex = note % NOTES_PER_OCTAVE;
            if (!KeyboardProcessor.isBlackKey(noteIndex)) {
                double wx = whiteIndex * WHITE_KEY_WIDTH;
                if (x >= wx && x < wx + WHITE_KEY_WIDTH) {
                    return note;
                }
                whiteIndex++;
            }
        }

        return -1;
    }

    // ── Painting ───────────────────────────────────────────────────────

    /**
     * Repaints the keyboard canvas reflecting current active note state.
     */
    void paintKeyboard() {
        GraphicsContext gc = keyboardCanvas.getGraphicsContext2D();
        double width = keyboardCanvas.getWidth();
        double height = keyboardCanvas.getHeight();
        gc.clearRect(0, 0, width, height);

        KeyboardPreset p = processor.getPreset();
        int lowestNote = (p.lowestOctave() + 1) * NOTES_PER_OCTAVE;
        int highestNote = Math.min(127, (p.highestOctave() + 2) * NOTES_PER_OCTAVE - 1);

        // Draw white keys
        int whiteIndex = 0;
        for (int note = lowestNote; note <= highestNote; note++) {
            int noteIndex = note % NOTES_PER_OCTAVE;
            if (!KeyboardProcessor.isBlackKey(noteIndex)) {
                double x = whiteIndex * WHITE_KEY_WIDTH;
                boolean active = processor.isNoteActive(note);
                gc.setFill(active ? WHITE_KEY_PRESSED_COLOR : WHITE_KEY_COLOR);
                gc.fillRect(x, 0, WHITE_KEY_WIDTH - 1, WHITE_KEY_HEIGHT);
                gc.setStroke(KEY_BORDER_COLOR);
                gc.setLineWidth(1);
                gc.strokeRect(x, 0, WHITE_KEY_WIDTH - 1, WHITE_KEY_HEIGHT);

                // Note name label on C keys
                if (noteIndex == 0) {
                    gc.setFill(NOTE_LABEL_COLOR);
                    gc.setFont(Font.font(9));
                    gc.setTextAlign(TextAlignment.CENTER);
                    String name = KeyboardProcessor.noteName(note);
                    gc.fillText(name, x + WHITE_KEY_WIDTH / 2.0 - 0.5, WHITE_KEY_HEIGHT - 5);
                }
                whiteIndex++;
            }
        }

        // Draw black keys (on top)
        whiteIndex = 0;
        for (int note = lowestNote; note <= highestNote; note++) {
            int noteIndex = note % NOTES_PER_OCTAVE;
            if (KeyboardProcessor.isBlackKey(noteIndex)) {
                double bx = whiteIndex * WHITE_KEY_WIDTH - BLACK_KEY_WIDTH / 2.0;
                boolean active = processor.isNoteActive(note);
                gc.setFill(active ? BLACK_KEY_PRESSED_COLOR : BLACK_KEY_COLOR);
                gc.fillRect(bx, 0, BLACK_KEY_WIDTH, BLACK_KEY_HEIGHT);
                gc.setStroke(KEY_BORDER_COLOR);
                gc.setLineWidth(0.5);
                gc.strokeRect(bx, 0, BLACK_KEY_WIDTH, BLACK_KEY_HEIGHT);
            } else {
                whiteIndex++;
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void resizeCanvas() {
        KeyboardPreset p = processor.getPreset();
        int whiteKeyCount = countWhiteKeys(p.lowestOctave(), p.highestOctave());
        keyboardCanvas.setWidth(whiteKeyCount * WHITE_KEY_WIDTH + 1);
    }

    /**
     * Counts the number of white keys across the given octave range.
     */
    static int countWhiteKeys(int lowestOctave, int highestOctave) {
        int lowestNote = (lowestOctave + 1) * NOTES_PER_OCTAVE;
        int highestNote = Math.min(127, (highestOctave + 2) * NOTES_PER_OCTAVE - 1);
        int count = 0;
        for (int note = lowestNote; note <= highestNote; note++) {
            if (!KeyboardProcessor.isBlackKey(note % NOTES_PER_OCTAVE)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the keyboard canvas (for testing).
     *
     * @return the canvas
     */
    Canvas getKeyboardCanvas() {
        return keyboardCanvas;
    }

    /**
     * Returns the preset combo box (for testing).
     *
     * @return the preset combo
     */
    ComboBox<KeyboardPreset> getPresetCombo() {
        return presetCombo;
    }

    /**
     * Returns the velocity slider (for testing).
     *
     * @return the velocity slider
     */
    Slider getVelocitySlider() {
        return velocitySlider;
    }

    /**
     * Returns the record toggle button (for testing).
     *
     * @return the record button
     */
    ToggleButton getRecordButton() {
        return recordButton;
    }

    // ── Disposal ─────────────────────────────────────────────────────────

    /**
     * Removes the keyboard event listener and stops the playback timer.
     * Called automatically when the view is removed from the scene graph,
     * or may be called manually when the view is no longer needed.
     */
    public void dispose() {
        playbackTimer.stop();
        processor.removeListener(keyboardListener);
    }

    // ── Inner Classes ──────────────────────────────────────────────────

    private static final class PresetCell extends ListCell<KeyboardPreset> {
        @Override
        protected void updateItem(KeyboardPreset item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.name());
        }
    }
}
