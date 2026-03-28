package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
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

    /**
     * The number of beats each grid column represents. With 32 columns
     * covering 8 beats (2 bars of 4/4), each column equals a sixteenth note.
     */
    static final double BEATS_PER_COLUMN = 0.25;

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

    // ── Snap state ──────────────────────────────────────────────────────────
    private boolean snapEnabled;
    private GridResolution gridResolution = GridResolution.QUARTER;
    private int beatsPerBar = 4;

    // ── Note state ───────────────────────────────────────────────────────────
    private final List<MidiNote> notes = new ArrayList<>();
    private int selectedNoteIndex = -1;

    // ── Undo ────────────────────────────────────────────────────────────────
    private UndoManager undoManager;

    // ── Drag state (pointer tool: move/resize) ─────────────────────────────
    private boolean dragging;
    private boolean resizing;
    private int dragStartColumn;
    private int dragStartNoteRow;
    private MidiNote dragOriginalNote;

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

        // Enable keyboard events for Delete key
        setFocusTraversable(true);
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelectedNote();
            }
        });
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
            loadFromMidiClip(track.getMidiClip());
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

    // ── Snap-to-grid API ─────────────────────────────────────────────────────

    /**
     * Updates the snap-to-grid state used by note placement and other editing
     * operations within this editor view.
     *
     * @param snapEnabled    whether snap-to-grid is enabled
     * @param gridResolution the active grid resolution
     * @param beatsPerBar    the number of beats per bar (time-signature numerator)
     */
    public void setSnapState(boolean snapEnabled, GridResolution gridResolution, int beatsPerBar) {
        this.snapEnabled = snapEnabled;
        this.gridResolution = gridResolution;
        this.beatsPerBar = beatsPerBar;
    }

    /**
     * Returns whether snap-to-grid is currently enabled for this editor view.
     *
     * @return {@code true} if snap is enabled
     */
    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    /**
     * Returns the active grid resolution for this editor view.
     *
     * @return the grid resolution
     */
    public GridResolution getGridResolution() {
        return gridResolution;
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

    // ── Undo API ─────────────────────────────────────────────────────────────

    /**
     * Sets the {@link UndoManager} used for undoable note editing operations.
     * When set, note add/remove/move/resize/velocity operations are executed
     * through the undo manager, enabling undo/redo.
     *
     * @param undoManager the undo manager, or {@code null} to disable undo
     */
    public void setUndoManager(UndoManager undoManager) {
        this.undoManager = undoManager;
    }

    /**
     * Returns the current {@link UndoManager}, or {@code null} if none.
     *
     * @return the undo manager
     */
    public UndoManager getUndoManager() {
        return undoManager;
    }

    // ── MIDI clip sync API ──────────────────────────────────────────────────

    /**
     * Loads notes from the given {@link MidiClip} into the editor for display.
     * Existing notes are cleared. Notes are converted from MIDI note numbers
     * to piano roll row indices.
     *
     * @param midiClip the clip to load notes from
     */
    public void loadFromMidiClip(MidiClip midiClip) {
        notes.clear();
        selectedNoteIndex = -1;
        if (midiClip != null) {
            for (MidiNoteData noteData : midiClip.getNotes()) {
                int rowIndex = midiNoteNumberToRow(noteData.noteNumber());
                if (rowIndex >= 0 && rowIndex < TOTAL_KEYS) {
                    notes.add(new MidiNote(rowIndex, noteData.startColumn(),
                            noteData.durationColumns(), noteData.velocity()));
                }
            }
        }
        if (currentMode == Mode.MIDI) {
            renderPianoRoll();
            renderVelocityLane();
        }
    }

    /**
     * Adds a single note to the editor display, typically called during
     * real-time MIDI recording to show incoming notes on the piano roll.
     *
     * @param noteData the recorded note data
     */
    public void addRecordedNote(MidiNoteData noteData) {
        int rowIndex = midiNoteNumberToRow(noteData.noteNumber());
        if (rowIndex >= 0 && rowIndex < TOTAL_KEYS) {
            notes.add(new MidiNote(rowIndex, noteData.startColumn(),
                    noteData.durationColumns(), noteData.velocity()));
            if (currentMode == Mode.MIDI) {
                renderPianoRoll();
                renderVelocityLane();
            }
        }
    }

    /**
     * Deletes the currently selected note. Called by the Delete key handler.
     */
    void deleteSelectedNote() {
        if (selectedNoteIndex >= 0 && selectedNoteIndex < notes.size()) {
            MidiNote removed = notes.get(selectedNoteIndex);
            int indexToRemove = selectedNoteIndex;
            if (undoManager != null) {
                undoManager.execute(new NoteListRemoveAction(notes, removed, indexToRemove));
            } else {
                notes.remove(indexToRemove);
            }
            selectedNoteIndex = -1;
            renderPianoRoll();
            renderVelocityLane();
        }
    }

    /**
     * Moves the currently selected note to the given row and column.
     * Intended for external callers (e.g., tests) and programmatic use.
     *
     * @param newRow    the new row index
     * @param newColumn the new start column
     */
    void moveSelectedNote(int newRow, int newColumn) {
        if (selectedNoteIndex < 0 || selectedNoteIndex >= notes.size()) {
            return;
        }
        MidiNote original = notes.get(selectedNoteIndex);
        if (newRow < 0 || newRow >= TOTAL_KEYS || newColumn < 0) {
            return;
        }
        MidiNote moved = new MidiNote(newRow, newColumn,
                original.durationColumns(), original.velocity());
        if (undoManager != null) {
            undoManager.execute(new NoteListMoveAction(notes, selectedNoteIndex,
                    original, moved));
        } else {
            notes.set(selectedNoteIndex, moved);
        }
        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Resizes the currently selected note to the given duration.
     *
     * @param newDuration the new duration in grid columns (≥ 1)
     */
    void resizeSelectedNote(int newDuration) {
        if (selectedNoteIndex < 0 || selectedNoteIndex >= notes.size()) {
            return;
        }
        if (newDuration < 1) {
            return;
        }
        MidiNote original = notes.get(selectedNoteIndex);
        MidiNote resized = new MidiNote(original.note(), original.startColumn(),
                newDuration, original.velocity());
        if (undoManager != null) {
            undoManager.execute(new NoteListResizeAction(notes, selectedNoteIndex,
                    original, resized));
        } else {
            notes.set(selectedNoteIndex, resized);
        }
        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Sets the velocity of the currently selected note.
     *
     * @param newVelocity the new velocity (0–127)
     */
    void setSelectedNoteVelocity(int newVelocity) {
        if (selectedNoteIndex < 0 || selectedNoteIndex >= notes.size()) {
            return;
        }
        if (newVelocity < 0 || newVelocity > MidiNote.MAX_VELOCITY) {
            return;
        }
        MidiNote original = notes.get(selectedNoteIndex);
        MidiNote updated = new MidiNote(original.note(), original.startColumn(),
                original.durationColumns(), newVelocity);
        if (undoManager != null) {
            undoManager.execute(new NoteListVelocityAction(notes, selectedNoteIndex,
                    original, updated));
        } else {
            notes.set(selectedNoteIndex, updated);
        }
        renderPianoRoll();
        renderVelocityLane();
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
        pianoRollCanvas.setOnMouseDragged(event -> onPianoRollDragged(event.getX(), event.getY()));
        pianoRollCanvas.setOnMouseReleased(event -> onPianoRollReleased(event.getX(), event.getY()));

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
        velocityCanvas.setOnMousePressed(event -> onVelocityLaneClicked(event.getX(), event.getY()));

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

        if (snapEnabled) {
            column = snapColumn(column);
        }

        switch (activeEditTool) {
            case POINTER -> selectNoteAt(noteRow, column);
            case PENCIL -> insertNoteAt(noteRow, column);
            case ERASER -> eraseNoteAt(noteRow, column);
            default -> { /* scissors, glue — not applicable in editor */ }
        }
    }

    /**
     * Snaps a grid column index to the nearest column aligned with the active
     * {@link GridResolution}. The column is converted to a beat position,
     * quantized, and converted back.
     */
    int snapColumn(int column) {
        double beatPosition = column * BEATS_PER_COLUMN;
        double snapped = SnapQuantizer.quantize(beatPosition, gridResolution, beatsPerBar);
        int snappedColumn = (int) Math.round(snapped / BEATS_PER_COLUMN);
        return Math.max(0, Math.min(snappedColumn, (int) GRID_COLUMNS - 1));
    }

    /**
     * Pointer tool: selects the note at the given grid position, or deselects
     * if no note is found. Also initializes drag state for move/resize.
     */
    private void selectNoteAt(int noteRow, int column) {
        selectedNoteIndex = -1;
        for (int i = 0; i < notes.size(); i++) {
            MidiNote n = notes.get(i);
            if (n.note() == noteRow
                    && column >= n.startColumn()
                    && column < n.startColumn() + n.durationColumns()) {
                selectedNoteIndex = i;
                dragOriginalNote = n;
                dragStartColumn = column;
                dragStartNoteRow = noteRow;
                // Detect if click is near the right edge (resize handle)
                int rightEdgeCol = n.startColumn() + n.durationColumns() - 1;
                resizing = (column == rightEdgeCol);
                dragging = !resizing;
                break;
            }
        }
        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Pencil tool: inserts a new MIDI note at the given grid position with
     * default duration and velocity. If a note already exists at that exact
     * position, no duplicate is created. The operation is undoable.
     */
    private void insertNoteAt(int noteRow, int column) {
        // Prevent duplicate at same position
        for (MidiNote n : notes) {
            if (n.note() == noteRow && n.startColumn() == column) {
                return;
            }
        }
        MidiNote newNote = new MidiNote(noteRow, column, MidiNote.DEFAULT_DURATION, MidiNote.DEFAULT_VELOCITY);
        if (undoManager != null) {
            undoManager.execute(new NoteListAddAction(notes, newNote));
        } else {
            notes.add(newNote);
        }
        selectedNoteIndex = -1;
        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Eraser tool: removes the first note found at the given grid position.
     * The operation is undoable.
     */
    private void eraseNoteAt(int noteRow, int column) {
        for (int i = 0; i < notes.size(); i++) {
            MidiNote n = notes.get(i);
            if (n.note() == noteRow
                    && column >= n.startColumn()
                    && column < n.startColumn() + n.durationColumns()) {
                MidiNote removed = notes.get(i);
                if (undoManager != null) {
                    undoManager.execute(new NoteListRemoveAction(notes, removed, i));
                } else {
                    notes.remove(i);
                }
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

    // ── Piano roll drag/release handlers ─────────────────────────────────────

    /**
     * Handles mouse drag on the piano roll canvas. When the pointer tool is
     * active and a note is selected, drags move or resize the note visually.
     */
    private void onPianoRollDragged(double x, double y) {
        if (activeEditTool != EditTool.POINTER) {
            return;
        }
        if (selectedNoteIndex < 0 || selectedNoteIndex >= notes.size()) {
            return;
        }
        if (dragOriginalNote == null) {
            return;
        }
        if (x < PIANO_KEY_WIDTH) {
            return;
        }

        double gridStartX = PIANO_KEY_WIDTH;
        double gridWidth = pianoRollCanvas.getWidth() - gridStartX;
        double colWidth = gridWidth / GRID_COLUMNS;

        int column = (int) ((x - gridStartX) / colWidth);
        int noteRow = (int) (y / KEY_HEIGHT);

        column = Math.max(0, Math.min(column, (int) GRID_COLUMNS - 1));
        noteRow = Math.max(0, Math.min(noteRow, TOTAL_KEYS - 1));

        if (snapEnabled) {
            column = snapColumn(column);
        }

        if (resizing) {
            // Resize: adjust duration based on how far right the mouse moved
            int newEndColumn = column + 1;
            int newDuration = Math.max(1, newEndColumn - dragOriginalNote.startColumn());
            MidiNote resized = new MidiNote(dragOriginalNote.note(),
                    dragOriginalNote.startColumn(), newDuration,
                    dragOriginalNote.velocity());
            notes.set(selectedNoteIndex, resized);
        } else if (dragging) {
            // Move: shift by delta from drag start
            int deltaCol = column - dragStartColumn;
            int deltaRow = noteRow - dragStartNoteRow;
            int newCol = Math.max(0, dragOriginalNote.startColumn() + deltaCol);
            int newRow = Math.max(0, Math.min(dragOriginalNote.note() + deltaRow,
                    TOTAL_KEYS - 1));
            newCol = Math.min(newCol, (int) GRID_COLUMNS - 1);
            MidiNote moved = new MidiNote(newRow, newCol,
                    dragOriginalNote.durationColumns(),
                    dragOriginalNote.velocity());
            notes.set(selectedNoteIndex, moved);
        }

        renderPianoRoll();
        renderVelocityLane();
    }

    /**
     * Handles mouse release on the piano roll canvas. Commits the
     * move or resize operation as an undoable action.
     */
    private void onPianoRollReleased(double x, double y) {
        if (activeEditTool != EditTool.POINTER) {
            return;
        }
        if (selectedNoteIndex < 0 || selectedNoteIndex >= notes.size()) {
            dragging = false;
            resizing = false;
            dragOriginalNote = null;
            return;
        }
        if (dragOriginalNote == null) {
            dragging = false;
            resizing = false;
            return;
        }

        MidiNote currentNote = notes.get(selectedNoteIndex);
        if (!currentNote.equals(dragOriginalNote)) {
            // The note was actually moved/resized — wrap in undo
            // First, revert the visual change so the action can re-apply it
            notes.set(selectedNoteIndex, dragOriginalNote);
            if (resizing) {
                MidiNote original = dragOriginalNote;
                MidiNote resized = currentNote;
                if (undoManager != null) {
                    undoManager.execute(new NoteListResizeAction(notes,
                            selectedNoteIndex, original, resized));
                } else {
                    notes.set(selectedNoteIndex, resized);
                }
            } else if (dragging) {
                MidiNote original = dragOriginalNote;
                MidiNote moved = currentNote;
                if (undoManager != null) {
                    undoManager.execute(new NoteListMoveAction(notes,
                            selectedNoteIndex, original, moved));
                } else {
                    notes.set(selectedNoteIndex, moved);
                }
            }
            renderPianoRoll();
            renderVelocityLane();
        }

        dragging = false;
        resizing = false;
        dragOriginalNote = null;
    }

    // ── Velocity lane interaction ────────────────────────────────────────────

    /**
     * Handles a click on the velocity lane. Finds the note at the clicked
     * column and sets its velocity based on the click Y position.
     */
    private void onVelocityLaneClicked(double x, double y) {
        if (x < PIANO_KEY_WIDTH) {
            return;
        }
        double gridStartX = PIANO_KEY_WIDTH;
        double gridWidth = velocityCanvas.getWidth() - gridStartX;
        double colWidth = gridWidth / GRID_COLUMNS;
        double laneHeight = velocityCanvas.getHeight();

        int column = (int) ((x - gridStartX) / colWidth);
        if (column < 0 || column >= GRID_COLUMNS) {
            return;
        }

        // Find the note at this column
        int noteIndex = -1;
        for (int i = 0; i < notes.size(); i++) {
            MidiNote n = notes.get(i);
            if (column >= n.startColumn()
                    && column < n.startColumn() + n.durationColumns()) {
                noteIndex = i;
                break;
            }
        }
        if (noteIndex < 0) {
            return;
        }

        // Calculate new velocity from click position (bottom = 0, top = 127)
        double ratio = 1.0 - (y / laneHeight);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int newVelocity = (int) Math.round(ratio * MidiNote.MAX_VELOCITY);

        selectedNoteIndex = noteIndex;
        setSelectedNoteVelocity(newVelocity);
    }

    // ── Utility: MIDI note number ↔ piano roll row ──────────────────────────

    /**
     * Converts a MIDI note number (0–127) to a piano roll row index.
     * Row 0 is the highest pitch, row {@code TOTAL_KEYS - 1} is the lowest.
     *
     * @param noteNumber the MIDI note number
     * @return the row index, or {@code -1} if out of the piano roll range
     */
    static int midiNoteNumberToRow(int noteNumber) {
        int row = TOTAL_KEYS - 1 - noteNumber;
        if (row < 0 || row >= TOTAL_KEYS) {
            return -1;
        }
        return row;
    }

    /**
     * Converts a piano roll row index to a MIDI note number.
     *
     * @param row the row index (0 = highest pitch)
     * @return the MIDI note number
     */
    static int rowToMidiNoteNumber(int row) {
        return TOTAL_KEYS - 1 - row;
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

    // ── Undoable note-list actions ──────────────────────────────────────────

    /**
     * Undoable action that adds a note to the editor's note list.
     */
    private static final class NoteListAddAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final MidiNote note;

        NoteListAddAction(List<MidiNote> notes, MidiNote note) {
            this.notes = notes;
            this.note = note;
        }

        @Override
        public String description() {
            return "Add MIDI Note";
        }

        @Override
        public void execute() {
            notes.add(note);
        }

        @Override
        public void undo() {
            notes.remove(note);
        }
    }

    /**
     * Undoable action that removes a note from the editor's note list.
     */
    private static final class NoteListRemoveAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final MidiNote note;
        private final int index;

        NoteListRemoveAction(List<MidiNote> notes, MidiNote note, int index) {
            this.notes = notes;
            this.note = note;
            this.index = index;
        }

        @Override
        public String description() {
            return "Delete MIDI Note";
        }

        @Override
        public void execute() {
            notes.remove(note);
        }

        @Override
        public void undo() {
            if (index >= 0 && index <= notes.size()) {
                notes.add(index, note);
            } else {
                notes.add(note);
            }
        }
    }

    /**
     * Undoable action that moves a note in the editor's note list.
     */
    private static final class NoteListMoveAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final int index;
        private final MidiNote original;
        private final MidiNote moved;

        NoteListMoveAction(List<MidiNote> notes, int index,
                           MidiNote original, MidiNote moved) {
            this.notes = notes;
            this.index = index;
            this.original = original;
            this.moved = moved;
        }

        @Override
        public String description() {
            return "Move MIDI Note";
        }

        @Override
        public void execute() {
            if (index >= 0 && index < notes.size()) {
                notes.set(index, moved);
            }
        }

        @Override
        public void undo() {
            if (index >= 0 && index < notes.size()) {
                notes.set(index, original);
            }
        }
    }

    /**
     * Undoable action that resizes a note in the editor's note list.
     */
    private static final class NoteListResizeAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final int index;
        private final MidiNote original;
        private final MidiNote resized;

        NoteListResizeAction(List<MidiNote> notes, int index,
                             MidiNote original, MidiNote resized) {
            this.notes = notes;
            this.index = index;
            this.original = original;
            this.resized = resized;
        }

        @Override
        public String description() {
            return "Resize MIDI Note";
        }

        @Override
        public void execute() {
            if (index >= 0 && index < notes.size()) {
                notes.set(index, resized);
            }
        }

        @Override
        public void undo() {
            if (index >= 0 && index < notes.size()) {
                notes.set(index, original);
            }
        }
    }

    /**
     * Undoable action that changes the velocity of a note in the editor's
     * note list.
     */
    private static final class NoteListVelocityAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final int index;
        private final MidiNote original;
        private final MidiNote updated;

        NoteListVelocityAction(List<MidiNote> notes, int index,
                               MidiNote original, MidiNote updated) {
            this.notes = notes;
            this.index = index;
            this.original = original;
            this.updated = updated;
        }

        @Override
        public String description() {
            return "Set Note Velocity";
        }

        @Override
        public void execute() {
            if (index >= 0 && index < notes.size()) {
                notes.set(index, updated);
            }
        }

        @Override
        public void undo() {
            if (index >= 0 && index < notes.size()) {
                notes.set(index, original);
            }
        }
    }
}
