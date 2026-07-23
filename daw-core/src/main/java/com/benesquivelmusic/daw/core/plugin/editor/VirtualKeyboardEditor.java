package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;
import com.benesquivelmusic.daw.core.midi.VelocityCurve;
import com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin;
import com.benesquivelmusic.daw.sdk.editor.ChromePolicy;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.Theme;

/**
 * {@link PluginEditorFactory.Panel} editor for the built-in
 * {@link VirtualKeyboardPlugin} — the story 302 (§8.3) replacement for the
 * retired {@code daw-app} class {@code KeyboardProcessorView}.
 *
 * <p>Renders an interactive piano keyboard on a
 * {@link javafx.scene.canvas.Canvas Canvas} plus controls
 * for preset selection, default velocity, velocity curve, transposition, and
 * MIDI record / play / stop / clear. Clicking (or click-drag glissando) on the
 * keyboard plays notes in real time; external note events repaint the keys via
 * the processor's {@link KeyboardProcessor.KeyboardEventListener}.</p>
 *
 * <p>This class is a reference example of the {@code Panel} contract with
 * {@link ChromePolicy#MINIMAL MINIMAL} chrome: the plugin hands the host a
 * {@link Region} and owns only its contents; the host owns frame, sizing and
 * clipping. Keyboard-canvas painting reads the resolved {@link Theme} tokens
 * from {@link EditorContext#theme()} and repaints when
 * {@link EditorContext#themeProperty()} changes. The playback-advancing
 * {@link AnimationTimer} and the processor listener are gated by
 * {@link ShowingWindowGate} — attached while the panel sits in a scene whose
 * window is showing, released when it leaves the scene or the window hides,
 * re-attachable both ways — because a {@code Panel} has no dispose hook.</p>
 *
 * <p>The {@code VirtualKeyboardPlugin} exposes no
 * {@code PluginParameter}-backed state, so every control talks to the
 * plugin's own {@link KeyboardProcessor} accessors. MIDI note events
 * ({@code noteOn}/{@code noteOff}) also go straight to the processor — the
 * plugin editing its own state is within the contract (§2.6); only the
 * {@code AudioProcessor} is off-limits to direct writes.</p>
 */
public final class VirtualKeyboardEditor implements PluginEditorFactory.Panel {

    // ── Layout Constants (package-private for VirtualKeyboardEditorLayoutTest) ──

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

    private final VirtualKeyboardPlugin plugin;

    /**
     * Creates the editor factory for the given plugin instance.
     *
     * @param plugin the virtual keyboard plugin to edit; must not be {@code null}
     */
    public VirtualKeyboardEditor(VirtualKeyboardPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the plugin has not been initialized (no
     *         {@link KeyboardProcessor} yet) — the host's fault harness turns
     *         this into a fault-banner editor rather than a crash
     */
    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        KeyboardProcessor processor = plugin.getProcessor();
        if (processor == null) {
            throw new IllegalStateException(
                    "Virtual Keyboard plugin has not been initialized — no KeyboardProcessor available");
        }
        return new KeyboardPane(processor, context);
    }

    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(720, 300).withChrome(ChromePolicy.MINIMAL);
    }

    // ── Pure layout math (package-private statics, headlessly testable) ───

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
     * Determines which MIDI note number is at the given canvas coordinates for
     * a keyboard spanning the given octave range.
     *
     * @param x             the x coordinate
     * @param y             the y coordinate
     * @param lowestOctave  the keyboard's lowest displayed octave
     * @param highestOctave the keyboard's highest displayed octave
     * @return the MIDI note number, or {@code -1} if no key is at that position
     */
    static int hitTest(double x, double y, int lowestOctave, int highestOctave) {
        if (y < 0 || y > WHITE_KEY_HEIGHT) {
            return -1;
        }

        int lowestNote = (lowestOctave + 1) * NOTES_PER_OCTAVE;
        int highestNote = Math.min(127, (highestOctave + 2) * NOTES_PER_OCTAVE - 1);

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

    // ── The panel ──────────────────────────────────────────────────────────

    /** The editor's root region — one instance per {@code createPanel} call. */
    private static final class KeyboardPane extends VBox {

        private final KeyboardProcessor processor;
        private final EditorContext context;
        // Fully qualified: the inherited member type PluginEditorFactory.Canvas
        // shadows a javafx.scene.canvas.Canvas import inside this class body.
        private final javafx.scene.canvas.Canvas keyboardCanvas;
        private final KeyboardProcessor.KeyboardEventListener keyboardListener;
        private final AnimationTimer playbackTimer;
        private int lastPressedNote = -1;
        private boolean processorListenerAttached;

        // Controls
        private final ComboBox<KeyboardPreset> presetCombo;
        private final Slider velocitySlider;
        private final ComboBox<VelocityCurve> curveCombo;
        private final Spinner<Integer> transposeSpinner;
        private final ToggleButton recordButton;

        KeyboardPane(KeyboardProcessor processor, EditorContext context) {
            this.processor = processor;
            this.context = context;

            setSpacing(8);
            setPadding(new Insets(10));
            setAlignment(Pos.TOP_CENTER);

            // ── Title ──────────────────────────────────────────────────────
            Label title = new Label("Virtual Keyboard");
            title.setFont(Font.font(null, FontWeight.BOLD, 14));

            // ── Preset selector ────────────────────────────────────────────
            presetCombo = new ComboBox<>(
                    FXCollections.observableArrayList(KeyboardPreset.factoryPresets()));
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

            // ── Velocity slider ────────────────────────────────────────────
            velocitySlider = new Slider(1, 127, processor.getPreset().defaultVelocity());
            velocitySlider.setShowTickLabels(true);
            velocitySlider.setShowTickMarks(true);
            velocitySlider.setMajorTickUnit(32);
            velocitySlider.setPrefWidth(150);
            velocitySlider.setTooltip(new Tooltip("Note velocity"));
            velocitySlider.valueProperty().addListener((_, _, newValue) -> {
                // Stateless echo-guard (story 293): a programmatic push
                // (onPresetChanged) writes the value the processor's preset
                // already carries, so the echo short-circuits; only a genuine
                // user drag to a new velocity mutates the preset. Round (not
                // truncate) and snap the thumb onto whole velocities; the snap
                // re-enters once and terminates.
                int candidate = (int) Math.round(newValue.doubleValue());
                if (newValue.doubleValue() != candidate) {
                    velocitySlider.setValue(candidate);
                    return;
                }
                if (candidate == processor.getPreset().defaultVelocity()) {
                    return;
                }
                processor.setPreset(processor.getPreset().withDefaultVelocity(candidate));
            });

            Label velLabel = new Label("Velocity:");

            // ── Velocity curve ─────────────────────────────────────────────
            curveCombo = new ComboBox<>(FXCollections.observableArrayList(VelocityCurve.values()));
            curveCombo.getSelectionModel().select(processor.getPreset().velocityCurve());
            curveCombo.setTooltip(new Tooltip("Velocity response curve"));
            curveCombo.setOnAction(_ -> onCurveChanged());

            Label curveLabel = new Label("Curve:");

            // ── Transpose spinner ──────────────────────────────────────────
            transposeSpinner = new Spinner<>(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    -KeyboardPreset.MAX_TRANSPOSE, KeyboardPreset.MAX_TRANSPOSE,
                    processor.getPreset().transpose()));
            transposeSpinner.setPrefWidth(70);
            transposeSpinner.setEditable(true);
            transposeSpinner.setTooltip(new Tooltip("Transpose (semitones)"));
            transposeSpinner.valueProperty().addListener((_, _, newVal) -> {
                if (newVal != null) {
                    onTransposeChanged(newVal);
                }
            });

            Label transposeLabel = new Label("Transpose:");

            // ── Record / Play / Stop / Clear buttons ───────────────────────
            recordButton = new ToggleButton("⏺ Record");
            recordButton.setTooltip(new Tooltip("Start/stop MIDI recording"));
            recordButton.setOnAction(_ -> onRecordToggle());

            Button playButton = new Button("▶ Play");
            playButton.setTooltip(new Tooltip("Play back recorded MIDI clip"));
            playButton.setOnAction(_ -> onPlay());

            Button stopButton = new Button("⏹ Stop");
            stopButton.setTooltip(new Tooltip("Stop playback/recording"));
            stopButton.setOnAction(_ -> onStop());

            Button clearButton = new Button("🗑 Clear");
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

            keyboardCanvas = new javafx.scene.canvas.Canvas(canvasWidth, WHITE_KEY_HEIGHT + 2);
            keyboardCanvas.setOnMousePressed(this::onMousePressed);
            keyboardCanvas.setOnMouseDragged(this::onMouseDragged);
            keyboardCanvas.setOnMouseReleased(this::onMouseReleased);

            // Listener for external-note visual feedback; marshals off-thread
            // events back to the FX thread (§4.7).
            keyboardListener = _ -> {
                if (Platform.isFxApplicationThread()) {
                    paintKeyboard();
                } else {
                    Platform.runLater(this::paintKeyboard);
                }
            };

            // Animation timer to drive playback advancement; self-stops once
            // playback ends.
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

            // Showing-window-gated lifecycle (a Panel has no dispose hook):
            // attach the processor listener and resume any running playback
            // advancement when the panel becomes visibly on screen; release
            // both when it leaves the scene or its window hides.
            ShowingWindowGate.install(this,
                    this::attachToProcessor, this::detachFromProcessor);

            // Repaint the canvas with the new palette when the theme changes.
            context.themeProperty().addListener((_, _, _) -> paintKeyboard());

            getChildren().addAll(title, presetRow, velocityRow, transportRow, keyboardCanvas);
            HBox.setHgrow(presetCombo, Priority.ALWAYS);

            paintKeyboard();
        }

        // ── Showing-window-gated lifecycle ─────────────────────────────────

        private void attachToProcessor() {
            if (!processorListenerAttached) {
                processor.addListener(keyboardListener);
                processorListenerAttached = true;
            }
            if (processor.isPlaying()) {
                playbackTimer.start();
            }
            paintKeyboard();
        }

        private void detachFromProcessor() {
            playbackTimer.stop();
            if (processorListenerAttached) {
                processor.removeListener(keyboardListener);
                processorListenerAttached = false;
            }
        }

        // ── Event Handlers ─────────────────────────────────────────────────

        private void onPresetChanged() {
            KeyboardPreset selected = presetCombo.getValue();
            if (selected != null) {
                // Push the new preset to the processor first, so the three
                // control writes below land as echoes of the processor's own
                // current state — each listener's stateless echo-guard then
                // short-circuits instead of re-pushing the preset.
                processor.setPreset(selected);
                velocitySlider.setValue(selected.defaultVelocity());
                curveCombo.getSelectionModel().select(selected.velocityCurve());
                transposeSpinner.getValueFactory().setValue(selected.transpose());
                resizeCanvas();
                paintKeyboard();
            }
        }

        private void onCurveChanged() {
            // Stateless echo-guard: a programmatic re-selection (onPresetChanged)
            // picks the curve the processor already holds, so it no-ops here.
            VelocityCurve curve = curveCombo.getValue();
            if (curve != null && curve != processor.getPreset().velocityCurve()) {
                processor.setPreset(processor.getPreset().withVelocityCurve(curve));
            }
        }

        private void onTransposeChanged(int newValue) {
            // Stateless echo-guard: a programmatic spinner write (onPresetChanged)
            // sets the transpose the processor already holds, so it no-ops here.
            if (newValue != processor.getPreset().transpose()) {
                processor.setPreset(processor.getPreset().withTranspose(newValue));
            }
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

        // ── Mouse Interaction (mouse → MIDI, straight to the processor) ────

        private void onMousePressed(MouseEvent e) {
            int note = hitNote(e);
            if (note >= 0) {
                lastPressedNote = note;
                processor.noteOn(note, (int) velocitySlider.getValue());
                paintKeyboard();
            }
        }

        private void onMouseDragged(MouseEvent e) {
            int note = hitNote(e);
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

        private int hitNote(MouseEvent e) {
            KeyboardPreset p = processor.getPreset();
            return hitTest(e.getX(), e.getY(), p.lowestOctave(), p.highestOctave());
        }

        // ── Painting (colours from the resolved host Theme tokens, §2.5) ───

        private void paintKeyboard() {
            Theme theme = context.theme();
            Color whiteKey = theme.foreground();
            Color whiteKeyPressed = theme.accent();
            Color blackKey = theme.background();
            Color blackKeyPressed = theme.accent().darker();
            Color keyBorder = theme.background().interpolate(theme.foreground(), 0.5);
            Color noteLabel = keyBorder;

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
                    gc.setFill(active ? whiteKeyPressed : whiteKey);
                    gc.fillRect(x, 0, WHITE_KEY_WIDTH - 1, WHITE_KEY_HEIGHT);
                    gc.setStroke(keyBorder);
                    gc.setLineWidth(1);
                    gc.strokeRect(x, 0, WHITE_KEY_WIDTH - 1, WHITE_KEY_HEIGHT);

                    // Note name label on C keys
                    if (noteIndex == 0) {
                        gc.setFill(noteLabel);
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
                    gc.setFill(active ? blackKeyPressed : blackKey);
                    gc.fillRect(bx, 0, BLACK_KEY_WIDTH, BLACK_KEY_HEIGHT);
                    gc.setStroke(keyBorder);
                    gc.setLineWidth(0.5);
                    gc.strokeRect(bx, 0, BLACK_KEY_WIDTH, BLACK_KEY_HEIGHT);
                } else {
                    whiteIndex++;
                }
            }
        }

        private void resizeCanvas() {
            KeyboardPreset p = processor.getPreset();
            int whiteKeyCount = countWhiteKeys(p.lowestOctave(), p.highestOctave());
            keyboardCanvas.setWidth(whiteKeyCount * WHITE_KEY_WIDTH + 1);
        }
    }

    private static final class PresetCell extends ListCell<KeyboardPreset> {
        @Override
        protected void updateItem(KeyboardPreset item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.name());
        }
    }
}
