package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Editor view for detailed MIDI piano-roll editing and audio waveform editing.
 *
 * <p>This view is accessible via the toolbar view switcher when a track or clip
 * is selected. It supports two modes:</p>
 * <ul>
 *   <li><b>MIDI Editor</b> — Piano roll grid with note display (pitch on Y-axis,
 *       time on X-axis), note velocity display, and tool selection integration
 *       (pointer, pencil, eraser).</li>
 *   <li><b>Audio Editor</b> — Waveform display using the existing
 *       {@link WaveformDisplay} component, with selection, trim, and fade handles,
 *       and zoom controls for time and amplitude.</li>
 * </ul>
 *
 * <p>Displays a placeholder message when no track or clip is selected.</p>
 *
 * <p>Uses existing CSS classes: {@code .editor-panel}, {@code .content-area},
 * {@code .panel-header}, {@code .placeholder-label}.</p>
 */
public final class EditorView extends VBox {

    /** The mode the editor is currently operating in. */
    public enum Mode {
        /** No content is selected — placeholder displayed. */
        EMPTY,
        /** MIDI piano-roll editor for MIDI tracks. */
        MIDI,
        /** Audio waveform editor for audio tracks. */
        AUDIO
    }

    private static final double TOOLBAR_ICON_SIZE = 14;
    private static final int PIANO_ROLL_OCTAVES = 8;
    private static final int NOTES_PER_OCTAVE = 12;
    private static final int TOTAL_KEYS = PIANO_ROLL_OCTAVES * NOTES_PER_OCTAVE;
    private static final double KEY_HEIGHT = 12;
    private static final double GRID_COLUMNS = 32;
    private static final double PIANO_KEY_WIDTH = 48;
    private static final double BASE_COL_WIDTH = 20;
    private static final double VELOCITY_BAR_HEIGHT = 40;

    private static final Color GRID_BG = Color.web("#1a1a2e");
    private static final Color GRID_LINE = Color.web("#ffffff", 0.08);
    private static final Color OCTAVE_LINE = Color.web("#7c4dff", 0.25);
    private static final Color WHITE_KEY_COLOR = Color.web("#2a2a4a");
    private static final Color BLACK_KEY_COLOR = Color.web("#1a1a2e");
    private static final Color KEY_LABEL_COLOR = Color.web("#888888");
    private static final Color VELOCITY_BG = Color.web("#0d0d1a");
    private static final Color VELOCITY_BAR_COLOR = Color.web("#00e5ff", 0.5);
    private static final Color NOTE_COLOR = Color.web("#00e5ff", 0.8);
    private static final Color NOTE_SELECTED_COLOR = Color.web("#ffab40", 0.9);

    private final Label header;
    private final StackPane contentArea;
    private final Label placeholderLabel;
    private final VBox midiEditorPane;
    private final VBox audioEditorPane;
    private final WaveformDisplay waveformDisplay;
    private final Canvas pianoRollCanvas;
    private final Canvas velocityCanvas;
    private final HBox toolBar;

    private Mode currentMode = Mode.EMPTY;
    private Track selectedTrack;

    // ── Tool state ───────────────────────────────────────────────────────────
    private EditTool activeEditTool = EditTool.POINTER;
    private Button pointerBtn;
    private Button pencilBtn;
    private Button eraserBtn;
    private Consumer<EditTool> onToolChanged;

    // ── Audio handle state ──────────────────────────────────────────────────
    private Button trimBtn;
    private Button fadeInBtn;
    private Button fadeOutBtn;
    private Runnable onTrimAction;
    private Runnable onFadeInAction;
    private Runnable onFadeOutAction;

    // ── Zoom state ───────────────────────────────────────────────────────────
    private final ZoomLevel zoomLevel = new ZoomLevel();

    // ── Note state ───────────────────────────────────────────────────────────
    private final List<MidiNote> notes = new ArrayList<>();
    private int selectedNoteIndex = -1;

    /**
     * Creates a new editor view.
     */
    public EditorView() {
        getStyleClass().add("editor-panel");

        header = new Label("EDITOR");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.WAVEFORM, 16));
        header.setPadding(new Insets(0, 0, 6, 0));

        // Tool bar with pointer, pencil, eraser, zoom controls
        toolBar = buildToolBar();

        // Placeholder for empty state
        placeholderLabel = new Label("No track or clip selected");
        placeholderLabel.getStyleClass().add("placeholder-label");
        placeholderLabel.setGraphic(IconNode.of(DawIcon.WAVEFORM, 24));

        // MIDI editor: piano roll grid
        pianoRollCanvas = new Canvas();
        velocityCanvas = new Canvas();
        midiEditorPane = buildMidiEditor();

        // Audio editor: waveform display
        waveformDisplay = new WaveformDisplay();
        audioEditorPane = buildAudioEditor();

        // Content area wraps placeholder / midi / audio
        contentArea = new StackPane(placeholderLabel);
        contentArea.getStyleClass().add("content-area");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        getChildren().addAll(header, toolBar, contentArea);
        setPadding(new Insets(8));
    }

    /**
     * Sets the track to edit. If the track is {@code null}, the editor
     * switches to the empty/placeholder state. Otherwise, it switches
     * to the appropriate mode based on the track type.
     *
     * @param track the track to edit, or {@code null} to clear
     */
    public void setTrack(Track track) {
        this.selectedTrack = track;
        if (track == null) {
            switchMode(Mode.EMPTY);
        } else if (track.getType() == TrackType.MIDI) {
            switchMode(Mode.MIDI);
        } else {
            switchMode(Mode.AUDIO);
        }
        updateAudioHandleButtons();
    }

    /**
     * Returns the currently selected track, or {@code null} if none.
     *
     * @return the selected track
     */
    public Track getSelectedTrack() {
        return selectedTrack;
    }

    /**
     * Returns the current editor mode.
     *
     * @return the active mode
     */
    public Mode getMode() {
        return currentMode;
    }

    /**
     * Returns the content area node. Visible for testing.
     *
     * @return the center content stack pane
     */
    StackPane getContentArea() {
        return contentArea;
    }

    /**
     * Returns the tool bar. Visible for testing.
     *
     * @return the tool bar HBox
     */
    HBox getToolBar() {
        return toolBar;
    }

    /**
     * Returns the waveform display used in audio editor mode.
     * Visible for testing.
     *
     * @return the waveform display component
     */
    WaveformDisplay getWaveformDisplay() {
        return waveformDisplay;
    }

    /**
     * Returns the piano roll canvas used in MIDI editor mode.
     * Visible for testing.
     *
     * @return the piano roll canvas
     */
    Canvas getPianoRollCanvas() {
        return pianoRollCanvas;
    }

    /**
     * Returns the velocity canvas used in MIDI editor mode.
     * Visible for testing.
     *
     * @return the velocity canvas
     */
    Canvas getVelocityCanvas() {
        return velocityCanvas;
    }

    /**
     * Returns the placeholder label shown when no content is selected.
     * Visible for testing.
     *
     * @return the placeholder label
     */
    Label getPlaceholderLabel() {
        return placeholderLabel;
    }

    /**
     * Returns the Trim button. Visible for testing.
     *
     * @return the trim button
     */
    Button getTrimButton() {
        return trimBtn;
    }

    /**
     * Returns the Fade In button. Visible for testing.
     *
     * @return the fade-in button
     */
    Button getFadeInButton() {
        return fadeInBtn;
    }

    /**
     * Returns the Fade Out button. Visible for testing.
     *
     * @return the fade-out button
     */
    Button getFadeOutButton() {
        return fadeOutBtn;
    }

    // ── Edit tool API ────────────────────────────────────────────────────────

    /**
     * Returns the currently active edit tool.
     *
     * @return the active tool
     */
    public EditTool getActiveEditTool() {
        return activeEditTool;
    }

    /**
     * Sets the active edit tool and updates the editor UI (cursor and button
     * styling). This method is intended for external callers (e.g.
     * {@link MainController}) and does <em>not</em> fire the
     * {@link #setOnToolChanged(Consumer)} callback.
     *
     * @param tool the tool to activate
     */
    public void setActiveEditTool(EditTool tool) {
        applyActiveTool(tool);
    }

    /**
     * Registers a callback that is invoked whenever the user selects a tool
     * via the editor's own toolbar buttons. The callback receives the newly
     * selected {@link EditTool}.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnToolChanged(Consumer<EditTool> handler) {
        this.onToolChanged = handler;
    }

    // ── Audio handle API ─────────────────────────────────────────────────────

    /**
     * Registers a callback invoked when the Trim button is clicked.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnTrimAction(Runnable handler) {
        this.onTrimAction = handler;
    }

    /**
     * Registers a callback invoked when the Fade In button is clicked.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnFadeInAction(Runnable handler) {
        this.onFadeInAction = handler;
    }

    /**
     * Registers a callback invoked when the Fade Out button is clicked.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnFadeOutAction(Runnable handler) {
        this.onFadeOutAction = handler;
    }

    // ── Zoom API ─────────────────────────────────────────────────────────────

    /**
     * Returns the zoom level for this editor view.
     *
     * @return the zoom level
     */
    public ZoomLevel getZoomLevel() {
        return zoomLevel;
    }

    // ── Note API ─────────────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable view of the MIDI notes currently in the editor.
     *
     * @return the note list
     */
    public List<MidiNote> getNotes() {
        return Collections.unmodifiableList(notes);
    }

    /**
     * Returns the index of the currently selected note, or {@code -1} if none.
     *
     * @return the selected note index
     */
    public int getSelectedNoteIndex() {
        return selectedNoteIndex;
    }

    private void switchMode(Mode mode) {
        this.currentMode = mode;
        contentArea.getChildren().clear();
        switch (mode) {
            case MIDI -> {
                contentArea.getChildren().add(midiEditorPane);
                renderPianoRoll();
                renderVelocityLane();
            }
            case AUDIO -> contentArea.getChildren().add(audioEditorPane);
            case EMPTY -> contentArea.getChildren().add(placeholderLabel);
        }
    }

    // ── Tool bar ─────────────────────────────────────────────────────────────

    private HBox buildToolBar() {
        pointerBtn = new Button();
        pointerBtn.setGraphic(IconNode.of(DawIcon.MOVE, TOOLBAR_ICON_SIZE));
        pointerBtn.setTooltip(new Tooltip("Pointer"));
        pointerBtn.getStyleClass().add("editor-tool-button");
        pointerBtn.setOnAction(event -> onToolButtonClicked(EditTool.POINTER));

        pencilBtn = new Button();
        pencilBtn.setGraphic(IconNode.of(DawIcon.MARKER, TOOLBAR_ICON_SIZE));
        pencilBtn.setTooltip(new Tooltip("Pencil"));
        pencilBtn.getStyleClass().add("editor-tool-button");
        pencilBtn.setOnAction(event -> onToolButtonClicked(EditTool.PENCIL));

        eraserBtn = new Button();
        eraserBtn.setGraphic(IconNode.of(DawIcon.DELETE, TOOLBAR_ICON_SIZE));
        eraserBtn.setTooltip(new Tooltip("Eraser"));
        eraserBtn.getStyleClass().add("editor-tool-button");
        eraserBtn.setOnAction(event -> onToolButtonClicked(EditTool.ERASER));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button zoomInBtn = new Button();
        zoomInBtn.setGraphic(IconNode.of(DawIcon.ZOOM_IN, TOOLBAR_ICON_SIZE));
        zoomInBtn.setTooltip(new Tooltip("Zoom In"));
        zoomInBtn.getStyleClass().add("editor-tool-button");
        zoomInBtn.setOnAction(event -> onZoomIn());

        Button zoomOutBtn = new Button();
        zoomOutBtn.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, TOOLBAR_ICON_SIZE));
        zoomOutBtn.setTooltip(new Tooltip("Zoom Out"));
        zoomOutBtn.getStyleClass().add("editor-tool-button");
        zoomOutBtn.setOnAction(event -> onZoomOut());

        HBox bar = new HBox(4, pointerBtn, pencilBtn, eraserBtn, spacer, zoomInBtn, zoomOutBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 0, 4, 0));
        bar.getStyleClass().add("editor-toolbar");

        updateToolButtonStyles();
        return bar;
    }

    /**
     * Called when one of the editor's own tool buttons is clicked.
     * Updates the tool state and notifies the external listener.
     */
    private void onToolButtonClicked(EditTool tool) {
        applyActiveTool(tool);
        if (onToolChanged != null) {
            onToolChanged.accept(tool);
        }
    }

    /**
     * Sets the active tool, updates button styling, and changes the cursor.
     */
    private void applyActiveTool(EditTool tool) {
        if (tool == activeEditTool) {
            return;
        }
        activeEditTool = tool;
        updateToolButtonStyles();
        updateCursor();
    }

    /**
     * Applies the {@code .editor-tool-button-active} CSS class to the button
     * matching the active tool and removes it from the others.
     */
    private void updateToolButtonStyles() {
        Button[] buttons = { pointerBtn, pencilBtn, eraserBtn };
        EditTool[] tools = { EditTool.POINTER, EditTool.PENCIL, EditTool.ERASER };
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] == null) {
                continue;
            }
            if (tools[i] == activeEditTool) {
                if (!buttons[i].getStyleClass().contains("editor-tool-button-active")) {
                    buttons[i].getStyleClass().add("editor-tool-button-active");
                }
            } else {
                buttons[i].getStyleClass().remove("editor-tool-button-active");
            }
        }
    }

    /**
     * Updates the cursor on the piano roll canvas and waveform display
     * based on the active tool.
     */
    private void updateCursor() {
        Cursor cursor = switch (activeEditTool) {
            case POINTER -> Cursor.DEFAULT;
            case PENCIL -> Cursor.CROSSHAIR;
            case ERASER -> Cursor.HAND;
            default -> Cursor.DEFAULT;
        };
        pianoRollCanvas.setCursor(cursor);
        waveformDisplay.setCursor(cursor);
    }

    // ── MIDI editor ──────────────────────────────────────────────────────────

    private VBox buildMidiEditor() {
        Label midiLabel = new Label("MIDI Piano Roll");
        midiLabel.getStyleClass().add("panel-header");
        midiLabel.setGraphic(IconNode.of(DawIcon.PIANO, 14));
        midiLabel.setPadding(new Insets(0, 0, 4, 0));

        double gridHeight = TOTAL_KEYS * KEY_HEIGHT;
        pianoRollCanvas.setHeight(gridHeight);
        pianoRollCanvas.setWidth(PIANO_KEY_WIDTH + GRID_COLUMNS * BASE_COL_WIDTH);

        // Wire mouse handler for tool interactions on the piano roll
        pianoRollCanvas.setOnMousePressed(event -> onPianoRollClicked(event.getX(), event.getY()));

        ScrollPane scrollPane = new ScrollPane(pianoRollCanvas);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Label velocityLabel = new Label("Velocity");
        velocityLabel.getStyleClass().add("panel-header");
        velocityLabel.setPadding(new Insets(4, 0, 2, 0));

        velocityCanvas.setHeight(VELOCITY_BAR_HEIGHT);
        velocityCanvas.setWidth(PIANO_KEY_WIDTH + GRID_COLUMNS * BASE_COL_WIDTH);

        VBox pane = new VBox(4, midiLabel, scrollPane, velocityLabel, velocityCanvas);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    /**
     * Renders the piano roll grid on the canvas, including any placed notes.
     */
    private void renderPianoRoll() {
        double width = pianoRollCanvas.getWidth();
        double height = pianoRollCanvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        GraphicsContext gc = pianoRollCanvas.getGraphicsContext2D();

        // Fill background
        gc.setFill(GRID_BG);
        gc.fillRect(0, 0, width, height);

        double gridStartX = PIANO_KEY_WIDTH;
        double gridWidth = width - gridStartX;

        // Draw rows (one per MIDI note)
        for (int note = 0; note < TOTAL_KEYS; note++) {
            double y = note * KEY_HEIGHT;
            int noteInOctave = (TOTAL_KEYS - 1 - note) % NOTES_PER_OCTAVE;
            boolean isBlackKey = isBlackKey(noteInOctave);

            // Piano key background
            gc.setFill(isBlackKey ? BLACK_KEY_COLOR : WHITE_KEY_COLOR);
            gc.fillRect(0, y, PIANO_KEY_WIDTH, KEY_HEIGHT);

            // Grid row background (alternating for readability)
            gc.setFill(isBlackKey ? BLACK_KEY_COLOR : WHITE_KEY_COLOR);
            gc.fillRect(gridStartX, y, gridWidth, KEY_HEIGHT);

            // Horizontal grid line
            gc.setStroke(GRID_LINE);
            gc.setLineWidth(0.5);
            gc.strokeLine(gridStartX, y, width, y);

            // Octave boundary — stronger line
            if (noteInOctave == 0) {
                gc.setStroke(OCTAVE_LINE);
                gc.setLineWidth(1.0);
                gc.strokeLine(0, y, width, y);
            }

            // Key label (C notes only)
            if (noteInOctave == 0) {
                int octave = (TOTAL_KEYS - 1 - note) / NOTES_PER_OCTAVE;
                gc.setFill(KEY_LABEL_COLOR);
                gc.fillText("C" + octave, 4, y + KEY_HEIGHT - 2);
            }
        }

        // Piano key separator
        gc.setStroke(OCTAVE_LINE);
        gc.setLineWidth(1.0);
        gc.strokeLine(PIANO_KEY_WIDTH, 0, PIANO_KEY_WIDTH, height);

        // Vertical grid lines (time divisions)
        double colWidth = gridWidth / GRID_COLUMNS;
        for (int col = 0; col <= GRID_COLUMNS; col++) {
            double x = gridStartX + col * colWidth;
            boolean isBeat = col % 4 == 0;
            gc.setStroke(isBeat ? OCTAVE_LINE : GRID_LINE);
            gc.setLineWidth(isBeat ? 1.0 : 0.5);
            gc.strokeLine(x, 0, x, height);
        }

        // Draw placed MIDI notes
        renderNotes(gc, gridStartX, colWidth);
    }

    /**
     * Renders the velocity lane below the piano roll.
     */
    private void renderVelocityLane() {
        double width = velocityCanvas.getWidth();
        double height = velocityCanvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        GraphicsContext gc = velocityCanvas.getGraphicsContext2D();

        gc.setFill(VELOCITY_BG);
        gc.fillRect(0, 0, width, height);

        // Vertical grid lines matching the piano roll
        double gridStartX = PIANO_KEY_WIDTH;
        double gridWidth = width - gridStartX;
        double colWidth = gridWidth / GRID_COLUMNS;
        for (int col = 0; col <= GRID_COLUMNS; col++) {
            double x = gridStartX + col * colWidth;
            boolean isBeat = col % 4 == 0;
            gc.setStroke(isBeat ? OCTAVE_LINE : GRID_LINE);
            gc.setLineWidth(isBeat ? 1.0 : 0.5);
            gc.strokeLine(x, 0, x, height);
        }

        // Center guideline for velocity reference
        gc.setStroke(VELOCITY_BAR_COLOR);
        gc.setLineWidth(0.5);
        gc.strokeLine(gridStartX, height / 2, width, height / 2);

        // Draw velocity bars for placed notes
        renderVelocityBars(gc, gridStartX, colWidth, height);
    }

    /**
     * Draws MIDI notes as colored rectangles on the piano roll canvas.
     */
    private void renderNotes(GraphicsContext gc, double gridStartX, double colWidth) {
        for (int i = 0; i < notes.size(); i++) {
            MidiNote note = notes.get(i);
            double x = gridStartX + note.startColumn() * colWidth;
            double y = note.note() * KEY_HEIGHT;
            double w = note.durationColumns() * colWidth;
            double h = KEY_HEIGHT;

            gc.setFill(i == selectedNoteIndex ? NOTE_SELECTED_COLOR : NOTE_COLOR);
            gc.fillRect(x + 0.5, y + 0.5, w - 1, h - 1);
        }
    }

    /**
     * Draws velocity bars in the velocity lane for each placed MIDI note.
     */
    private void renderVelocityBars(GraphicsContext gc, double gridStartX,
                                    double colWidth, double laneHeight) {
        for (MidiNote note : notes) {
            double x = gridStartX + note.startColumn() * colWidth;
            double barHeight = (note.velocity() / (double) MidiNote.MAX_VELOCITY) * laneHeight;
            gc.setFill(VELOCITY_BAR_COLOR);
            gc.fillRect(x + 1, laneHeight - barHeight, colWidth - 2, barHeight);
        }
    }

    // ── Piano roll mouse interactions ────────────────────────────────────────

    /**
     * Handles a mouse click on the piano roll canvas. The behavior depends
     * on the currently active edit tool.
     */
    private void onPianoRollClicked(double x, double y) {
        if (x < PIANO_KEY_WIDTH) {
            return; // click is on the key labels, not the grid
        }

        double gridStartX = PIANO_KEY_WIDTH;
        double gridWidth = pianoRollCanvas.getWidth() - gridStartX;
        double colWidth = gridWidth / GRID_COLUMNS;

        int column = (int) ((x - gridStartX) / colWidth);
        int noteRow = (int) (y / KEY_HEIGHT);

        if (column < 0 || column >= GRID_COLUMNS || noteRow < 0 || noteRow >= TOTAL_KEYS) {
            return;
        }

        switch (activeEditTool) {
            case POINTER -> selectNoteAt(noteRow, column);
            case PENCIL -> insertNoteAt(noteRow, column);
            case ERASER -> eraseNoteAt(noteRow, column);
            default -> { /* scissors, glue — not applicable in editor */ }
        }
    }

    /**
     * Pointer tool: selects the note at the given grid position, or deselects
     * if no note is found.
     */
    private void selectNoteAt(int noteRow, int column) {
        selectedNoteIndex = -1;
        for (int i = 0; i < notes.size(); i++) {
            MidiNote n = notes.get(i);
            if (n.note() == noteRow
                    && column >= n.startColumn()
                    && column < n.startColumn() + n.durationColumns()) {
                selectedNoteIndex = i;
                break;
            }
        }
        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Pencil tool: inserts a new MIDI note at the given grid position with
     * default duration and velocity. If a note already exists at that exact
     * position, no duplicate is created.
     */
    private void insertNoteAt(int noteRow, int column) {
        // Prevent duplicate at same position
        for (MidiNote n : notes) {
            if (n.note() == noteRow && n.startColumn() == column) {
                return;
            }
        }
        notes.add(new MidiNote(noteRow, column, MidiNote.DEFAULT_DURATION, MidiNote.DEFAULT_VELOCITY));
        selectedNoteIndex = -1;
        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Eraser tool: removes the first note found at the given grid position.
     */
    private void eraseNoteAt(int noteRow, int column) {
        for (int i = 0; i < notes.size(); i++) {
            MidiNote n = notes.get(i);
            if (n.note() == noteRow
                    && column >= n.startColumn()
                    && column < n.startColumn() + n.durationColumns()) {
                notes.remove(i);
                if (selectedNoteIndex == i) {
                    selectedNoteIndex = -1;
                } else if (selectedNoteIndex > i) {
                    selectedNoteIndex--;
                }
                renderPianoRoll();
                renderVelocityLane();
                return;
            }
        }
    }

    // ── Zoom ─────────────────────────────────────────────────────────────────

    /**
     * Zooms in on the editor canvas by one step and redraws.
     */
    private void onZoomIn() {
        zoomLevel.zoomIn();
        applyZoom();
    }

    /**
     * Zooms out on the editor canvas by one step and redraws.
     */
    private void onZoomOut() {
        zoomLevel.zoomOut();
        applyZoom();
    }

    /**
     * Resizes the piano roll and velocity canvases based on the current zoom
     * level, then redraws both.
     */
    private void applyZoom() {
        double scaledColWidth = BASE_COL_WIDTH * zoomLevel.getLevel();
        double newWidth = PIANO_KEY_WIDTH + GRID_COLUMNS * scaledColWidth;
        pianoRollCanvas.setWidth(newWidth);
        velocityCanvas.setWidth(newWidth);
        if (currentMode == Mode.MIDI) {
            renderPianoRoll();
            renderVelocityLane();
        }
    }

    // ── Audio editor ─────────────────────────────────────────────────────────

    private VBox buildAudioEditor() {
        Label audioLabel = new Label("Audio Waveform");
        audioLabel.getStyleClass().add("panel-header");
        audioLabel.setGraphic(IconNode.of(DawIcon.WAVEFORM, 14));
        audioLabel.setPadding(new Insets(0, 0, 4, 0));

        waveformDisplay.setPrefHeight(200);
        waveformDisplay.setMinHeight(100);
        VBox.setVgrow(waveformDisplay, Priority.ALWAYS);

        HBox handles = buildAudioHandles();

        VBox pane = new VBox(4, audioLabel, waveformDisplay, handles);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    private HBox buildAudioHandles() {
        trimBtn = new Button("Trim");
        trimBtn.setGraphic(IconNode.of(DawIcon.TRIM, TOOLBAR_ICON_SIZE));
        trimBtn.setTooltip(new Tooltip("Trim selection"));
        trimBtn.getStyleClass().add("editor-tool-button");
        trimBtn.setOnAction(event -> {
            if (onTrimAction != null) {
                onTrimAction.run();
            }
        });

        fadeInBtn = new Button("Fade In");
        fadeInBtn.setGraphic(IconNode.of(DawIcon.FADE_IN, TOOLBAR_ICON_SIZE));
        fadeInBtn.setTooltip(new Tooltip("Apply fade in"));
        fadeInBtn.getStyleClass().add("editor-tool-button");
        fadeInBtn.setOnAction(event -> {
            if (onFadeInAction != null) {
                onFadeInAction.run();
            }
        });

        fadeOutBtn = new Button("Fade Out");
        fadeOutBtn.setGraphic(IconNode.of(DawIcon.FADE_OUT, TOOLBAR_ICON_SIZE));
        fadeOutBtn.setTooltip(new Tooltip("Apply fade out"));
        fadeOutBtn.getStyleClass().add("editor-tool-button");
        fadeOutBtn.setOnAction(event -> {
            if (onFadeOutAction != null) {
                onFadeOutAction.run();
            }
        });

        updateAudioHandleButtons();

        HBox handles = new HBox(4, trimBtn, fadeInBtn, fadeOutBtn);
        handles.setAlignment(Pos.CENTER_LEFT);
        handles.setPadding(new Insets(4, 0, 0, 0));
        handles.getStyleClass().add("editor-audio-handles");
        return handles;
    }

    /**
     * Enables or disables the Trim, Fade In, and Fade Out buttons based on
     * whether the selected track is an audio track with at least one clip.
     */
    private void updateAudioHandleButtons() {
        boolean disabled = selectedTrack == null
                || selectedTrack.getType() == TrackType.MIDI
                || selectedTrack.getClips().isEmpty();
        if (trimBtn != null) {
            trimBtn.setDisable(disabled);
        }
        if (fadeInBtn != null) {
            fadeInBtn.setDisable(disabled);
        }
        if (fadeOutBtn != null) {
            fadeOutBtn.setDisable(disabled);
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static boolean isBlackKey(int noteInOctave) {
        return noteInOctave == 1 || noteInOctave == 3 || noteInOctave == 6
                || noteInOctave == 8 || noteInOctave == 10;
    }
}
