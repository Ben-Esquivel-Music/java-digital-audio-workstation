package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.midi.MidiCcEvent;
import com.benesquivelmusic.daw.core.midi.MidiCcLane;
import com.benesquivelmusic.daw.core.midi.MidiCcLaneType;
import com.benesquivelmusic.daw.core.midi.MidiCcRamp;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SetCcValueAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MIDI piano-roll editor view for detailed note editing.
 *
 * <p>Provides a piano roll grid with note display (pitch on Y-axis,
 * time on X-axis), note velocity display, and tool selection integration
 * (pointer, pencil, eraser). Supports note add/delete/move/resize with
 * mouse interaction and undo/redo.</p>
 */
final class MidiEditorView extends VBox {

    private static final int PIANO_ROLL_OCTAVES = 8;
    private static final int NOTES_PER_OCTAVE = 12;
    static final int TOTAL_KEYS = PIANO_ROLL_OCTAVES * NOTES_PER_OCTAVE;
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

    private final Canvas pianoRollCanvas;
    private final Canvas velocityCanvas;
    private final Label velocityLabel;
    private final ComboBox<MidiCcLaneType> laneTypeCombo;

    // ── CC lane state (issue: piano-roll Velocity/CC lanes) ────────────────
    /** The clip currently displayed; used to look up CC lane configuration. */
    private MidiClip currentClip;
    /** The active bottom-pane lane type. Defaults to per-note velocity. */
    private MidiCcLaneType activeLaneType = MidiCcLaneType.VELOCITY;
    /** Last-selected breakpoint columns, used by the ramp helper (R key). */
    private int rampSelectionLeftColumn = -1;
    private int rampSelectionRightColumn = -1;

    // ── Note state ───────────────────────────────────────────────────────────
    private final List<MidiNote> notes = new ArrayList<>();
    private int selectedNoteIndex = -1;

    // ── Snap state ──────────────────────────────────────────────────────────
    private boolean snapEnabled;
    private GridResolution gridResolution = GridResolution.QUARTER;
    private int beatsPerBar = 4;

    // ── Undo ────────────────────────────────────────────────────────────────
    private UndoManager undoManager;

    // ── Tool state ──────────────────────────────────────────────────────────
    private EditTool activeEditTool = EditTool.POINTER;

    // ── Drag state (pointer tool: move/resize) ─────────────────────────────
    private boolean dragging;
    private boolean resizing;
    private int dragStartColumn;
    private int dragStartNoteRow;
    private MidiNote dragOriginalNote;

    // ── Velocity-drag coalescing state ──────────────────────────────────────
    // Captures the original note on mouse-press so that a continuous drag
    // produces only a single undo step (captured original → final value),
    // matching how note move/resize already works.
    private int velocityDragNoteIndex = -1;
    private MidiNote velocityDragOriginal;

    /**
     * Creates a new MIDI editor view with piano roll and velocity lane.
     */
    MidiEditorView() {
        Label midiLabel = new Label("MIDI Piano Roll");
        midiLabel.getStyleClass().add("panel-header");
        midiLabel.setGraphic(IconNode.of(DawIcon.PIANO, 14));
        midiLabel.setPadding(new Insets(0, 0, 4, 0));

        double gridHeight = TOTAL_KEYS * KEY_HEIGHT;
        pianoRollCanvas = new Canvas();
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

        Label velLabel = new Label("Velocity");
        velLabel.getStyleClass().add("panel-header");
        velLabel.setPadding(new Insets(4, 0, 2, 0));
        this.velocityLabel = velLabel;

        // Lane-type selector — switches the bottom pane between per-note
        // velocity bars and breakpoint-based CC lanes (mod wheel,
        // expression, sustain, pitch bend, or any arbitrary CC).
        laneTypeCombo = new ComboBox<>();
        laneTypeCombo.getItems().addAll(MidiCcLaneType.values());
        laneTypeCombo.setValue(MidiCcLaneType.VELOCITY);
        laneTypeCombo.valueProperty().addListener((obs, oldT, newT) -> {
            if (newT != null) {
                setActiveLaneType(newT);
            }
        });

        HBox laneHeader = new HBox(8, velLabel, laneTypeCombo);
        laneHeader.setPadding(new Insets(4, 0, 2, 0));

        velocityCanvas = new Canvas();
        velocityCanvas.setHeight(VELOCITY_BAR_HEIGHT);
        velocityCanvas.setWidth(PIANO_KEY_WIDTH + GRID_COLUMNS * BASE_COL_WIDTH);
        velocityCanvas.setOnMousePressed(event -> onVelocityLanePressed(event.getX(), event.getY()));
        velocityCanvas.setOnMouseDragged(event -> onVelocityLaneDragged(event.getX(), event.getY()));
        velocityCanvas.setOnMouseReleased(event -> onVelocityLaneReleased());

        getChildren().addAll(midiLabel, scrollPane, laneHeader, velocityCanvas);
        setSpacing(4);
    }

    // ── Canvas accessors (for testing) ──────────────────────────────────────

    Canvas getPianoRollCanvas() {
        return pianoRollCanvas;
    }

    Canvas getVelocityCanvas() {
        return velocityCanvas;
    }

    // ── Tool state ──────────────────────────────────────────────────────────

    void setActiveEditTool(EditTool tool) {
        this.activeEditTool = tool;
        updateCursor();
    }

    private void updateCursor() {
        Cursor cursor = switch (activeEditTool) {
            case POINTER -> Cursor.DEFAULT;
            case PENCIL -> Cursor.CROSSHAIR;
            case ERASER -> Cursor.HAND;
            default -> Cursor.DEFAULT;
        };
        pianoRollCanvas.setCursor(cursor);
    }

    // ── Snap-to-grid state ──────────────────────────────────────────────────

    void setSnapState(boolean snapEnabled, GridResolution gridResolution, int beatsPerBar) {
        this.snapEnabled = snapEnabled;
        this.gridResolution = gridResolution;
        this.beatsPerBar = beatsPerBar;
    }

    boolean isSnapEnabled() {
        return snapEnabled;
    }

    GridResolution getGridResolution() {
        return gridResolution;
    }

    // ── Undo ────────────────────────────────────────────────────────────────

    void setUndoManager(UndoManager undoManager) {
        this.undoManager = undoManager;
    }

    UndoManager getUndoManager() {
        return undoManager;
    }

    // ── Note API ────────────────────────────────────────────────────────────

    List<MidiNote> getNotes() {
        return Collections.unmodifiableList(notes);
    }

    int getSelectedNoteIndex() {
        return selectedNoteIndex;
    }

    // ── MIDI clip sync API ──────────────────────────────────────────────────

    void loadFromMidiClip(MidiClip midiClip) {
        this.currentClip = midiClip;
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
        renderPianoRoll();
        renderVelocityLane();
    }

    void addRecordedNote(MidiNoteData noteData) {
        int rowIndex = midiNoteNumberToRow(noteData.noteNumber());
        if (rowIndex >= 0 && rowIndex < TOTAL_KEYS) {
            notes.add(new MidiNote(rowIndex, noteData.startColumn(),
                    noteData.durationColumns(), noteData.velocity()));
            renderPianoRoll();
            renderVelocityLane();
        }
    }

    // ── Note editing ────────────────────────────────────────────────────────

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

    // ── Zoom ────────────────────────────────────────────────────────────────

    void applyZoom(double zoomFactor, boolean render) {
        double scaledColWidth = BASE_COL_WIDTH * zoomFactor;
        double newWidth = PIANO_KEY_WIDTH + GRID_COLUMNS * scaledColWidth;
        pianoRollCanvas.setWidth(newWidth);
        velocityCanvas.setWidth(newWidth);
        if (render) {
            renderPianoRoll();
            renderVelocityLane();
        }
    }

    // ── Piano roll rendering ────────────────────────────────────────────────

    void renderPianoRoll() {
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

        if (activeLaneType == MidiCcLaneType.VELOCITY) {
            renderVelocityBars(gc, gridStartX, colWidth, height);
        } else {
            renderCcBreakpoints(gc, gridStartX, colWidth, height);
        }
    }

    /**
     * Renders the active CC lane (mod wheel, expression, sustain, pitch
     * bend, or arbitrary CC) as a poly-line of breakpoints with line-segment
     * interpolation, matching the look of an automation lane.
     */
    private void renderCcBreakpoints(GraphicsContext gc, double gridStartX,
                                     double colWidth, double laneHeight) {
        MidiCcLane lane = findActiveCcLane();
        if (lane == null) {
            return;
        }
        int maxValue = lane.isHighResolution() || activeLaneType == MidiCcLaneType.PITCH_BEND
                ? MidiCcEvent.MAX_14BIT
                : MidiCcEvent.MAX_7BIT;
        List<MidiCcEvent> events = lane.getEvents();
        if (events.isEmpty()) {
            return;
        }
        gc.setStroke(VELOCITY_BAR_COLOR);
        gc.setLineWidth(1.5);
        double prevX = -1, prevY = -1;
        for (MidiCcEvent ev : events) {
            double x = gridStartX + ev.column() * colWidth;
            double ratio = maxValue == 0 ? 0 : ev.value() / (double) maxValue;
            double y = laneHeight - ratio * laneHeight;
            if (prevX >= 0) {
                gc.strokeLine(prevX, prevY, x, y);
            }
            gc.setFill(VELOCITY_BAR_COLOR);
            gc.fillOval(x - 3, y - 3, 6, 6);
            prevX = x;
            prevY = y;
        }
    }

    /**
     * Locates the {@link MidiCcLane} on the current clip whose type
     * matches {@link #activeLaneType}, or {@code null} when none exists.
     */
    private MidiCcLane findActiveCcLane() {
        if (currentClip == null || activeLaneType == MidiCcLaneType.VELOCITY) {
            return null;
        }
        for (MidiCcLane lane : currentClip.getCcLanes()) {
            if (lane.getType() == activeLaneType) {
                return lane;
            }
        }
        return null;
    }

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

    int snapColumn(int column) {
        double beatPosition = column * BEATS_PER_COLUMN;
        double snapped = SnapQuantizer.quantize(beatPosition, gridResolution, beatsPerBar);
        int snappedColumn = (int) Math.round(snapped / BEATS_PER_COLUMN);
        return Math.max(0, Math.min(snappedColumn, (int) GRID_COLUMNS - 1));
    }

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

    // ── Velocity lane mouse handlers (coalesced undo) ──────────────────────

    /**
     * Converts a velocity-canvas x/y into a column, or returns {@code -1}
     * when outside the grid area.
     */
    private int velocityCanvasColumn(double x) {
        if (x < PIANO_KEY_WIDTH) return -1;
        double gridStartX = PIANO_KEY_WIDTH;
        double gridWidth = velocityCanvas.getWidth() - gridStartX;
        double colWidth = gridWidth / GRID_COLUMNS;
        int column = (int) ((x - gridStartX) / colWidth);
        return (column < 0 || column >= GRID_COLUMNS) ? -1 : column;
    }

    private void onVelocityLanePressed(double x, double y) {
        int column = velocityCanvasColumn(x);
        if (column < 0) return;
        double laneHeight = velocityCanvas.getHeight();

        if (activeLaneType != MidiCcLaneType.VELOCITY) {
            onCcLaneClicked(column, y, laneHeight);
            return;
        }

        // Find the note at this column and capture its original velocity
        // so a drag produces only one undo step.
        velocityDragNoteIndex = -1;
        velocityDragOriginal = null;
        for (int i = 0; i < notes.size(); i++) {
            MidiNote n = notes.get(i);
            if (column >= n.startColumn()
                    && column < n.startColumn() + n.durationColumns()) {
                velocityDragNoteIndex = i;
                velocityDragOriginal = n;
                break;
            }
        }
        if (velocityDragNoteIndex < 0) return;

        double ratio = Math.max(0.0, Math.min(1.0, 1.0 - (y / laneHeight)));
        int newVelocity = (int) Math.round(ratio * MidiNote.MAX_VELOCITY);
        selectedNoteIndex = velocityDragNoteIndex;
        // During drag, update visually without recording undo steps.
        MidiNote current = notes.get(velocityDragNoteIndex);
        notes.set(velocityDragNoteIndex,
                new MidiNote(current.note(), current.startColumn(),
                        current.durationColumns(), newVelocity));
        renderPianoRoll();
        renderVelocityLane();
    }

    private void onVelocityLaneDragged(double x, double y) {
        if (activeLaneType != MidiCcLaneType.VELOCITY) {
            int column = velocityCanvasColumn(x);
            if (column >= 0) {
                onCcLaneClicked(column, y, velocityCanvas.getHeight());
            }
            return;
        }
        if (velocityDragNoteIndex < 0 || velocityDragOriginal == null) return;

        double laneHeight = velocityCanvas.getHeight();
        double ratio = Math.max(0.0, Math.min(1.0, 1.0 - (y / laneHeight)));
        int newVelocity = (int) Math.round(ratio * MidiNote.MAX_VELOCITY);
        MidiNote current = notes.get(velocityDragNoteIndex);
        notes.set(velocityDragNoteIndex,
                new MidiNote(current.note(), current.startColumn(),
                        current.durationColumns(), newVelocity));
        renderPianoRoll();
        renderVelocityLane();
    }

    private void onVelocityLaneReleased() {
        if (velocityDragNoteIndex < 0 || velocityDragOriginal == null) {
            return;
        }
        MidiNote finalNote = notes.get(velocityDragNoteIndex);
        if (finalNote.velocity() != velocityDragOriginal.velocity()) {
            // Revert to original, then apply as a single undo step.
            notes.set(velocityDragNoteIndex, velocityDragOriginal);
            selectedNoteIndex = velocityDragNoteIndex;
            setSelectedNoteVelocity(finalNote.velocity());
        }
        velocityDragNoteIndex = -1;
        velocityDragOriginal = null;
    }

    /**
     * Handles a click/drag on the bottom pane while a CC lane is active.
     * Adds (or replaces) a breakpoint at the clicked column and value,
     * remembering the column for later use by the ramp helper.
     */
    private void onCcLaneClicked(int column, double y, double laneHeight) {
        MidiCcLane lane = findActiveCcLane();
        if (lane == null) {
            // Auto-create the lane on first interaction so that switching
            // the dropdown and clicking actually edits something.
            // For ARBITRARY_CC, don't auto-create — a proper CC number
            // must be configured first (via a future UI dialog).
            if (currentClip == null || activeLaneType == MidiCcLaneType.ARBITRARY_CC) {
                return;
            }
            lane = MidiCcLane.preset(activeLaneType, false);
            currentClip.addCcLane(lane);
        }

        int maxValue = lane.isHighResolution()
                || activeLaneType == MidiCcLaneType.PITCH_BEND
                ? MidiCcEvent.MAX_14BIT
                : MidiCcEvent.MAX_7BIT;
        double ratio = 1.0 - (y / laneHeight);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int newValue = (int) Math.round(ratio * maxValue);
        MidiCcEvent ev = new MidiCcEvent(column, newValue);
        if (undoManager != null) {
            undoManager.execute(new SetCcValueAction(lane, ev));
        } else {
            // Fall through: insert directly when no undo manager is wired.
            new SetCcValueAction(lane, ev).execute();
        }

        // Remember this column for the R-key ramp helper.
        rampSelectionLeftColumn = rampSelectionRightColumn;
        rampSelectionRightColumn = column;

        renderVelocityLane();
    }

    /**
     * Public hook used by the editor's keyboard shortcut handler to insert
     * a linear ramp of breakpoints between the two most recently clicked
     * breakpoints in the active CC lane.
     *
     * <p>This implements the issue's "select two breakpoints, R inserts a
     * line between them at configurable density" feature.</p>
     *
     * @param stepColumns spacing between generated breakpoints (≥ 1)
     * @return the number of breakpoints inserted
     */
    int insertRampBetweenSelection(int stepColumns) {
        if (stepColumns < 1) {
            return 0;
        }
        MidiCcLane lane = findActiveCcLane();
        if (lane == null
                || rampSelectionLeftColumn < 0
                || rampSelectionRightColumn < 0
                || rampSelectionLeftColumn == rampSelectionRightColumn) {
            return 0;
        }
        int leftCol = Math.min(rampSelectionLeftColumn, rampSelectionRightColumn);
        int rightCol = Math.max(rampSelectionLeftColumn, rampSelectionRightColumn);
        MidiCcEvent left = null;
        MidiCcEvent right = null;
        for (MidiCcEvent ev : lane.getEvents()) {
            if (ev.column() == leftCol) left = ev;
            if (ev.column() == rightCol) right = ev;
        }
        if (left == null || right == null) {
            return 0;
        }
        List<MidiCcEvent> ramp = MidiCcRamp.generate(left, right, stepColumns);
        for (MidiCcEvent r : ramp) {
            if (undoManager != null) {
                undoManager.execute(new SetCcValueAction(lane, r));
            } else {
                new SetCcValueAction(lane, r).execute();
            }
        }
        renderVelocityLane();
        return ramp.size();
    }

    /**
     * Switches the bottom pane between Velocity and the various CC lane
     * types. Updates the lane title and re-renders.
     *
     * @param type the new active lane type
     */
    void setActiveLaneType(MidiCcLaneType type) {
        this.activeLaneType = type;
        velocityLabel.setText(switch (type) {
            case VELOCITY     -> "Velocity";
            case MOD_WHEEL    -> "Mod Wheel (CC 1)";
            case EXPRESSION   -> "Expression (CC 11)";
            case SUSTAIN      -> "Sustain (CC 64)";
            case PITCH_BEND   -> "Pitch Bend";
            case ARBITRARY_CC -> "CC";
        });
        rampSelectionLeftColumn = -1;
        rampSelectionRightColumn = -1;
        if (laneTypeCombo.getValue() != type) {
            laneTypeCombo.setValue(type);
        }
        renderVelocityLane();
    }

    MidiCcLaneType getActiveLaneType() {
        return activeLaneType;
    }

    ComboBox<MidiCcLaneType> getLaneTypeCombo() {
        return laneTypeCombo;
    }

    /** Test-only hook used by unit tests to seed the ramp selection. */
    void setRampSelectionForTest(int leftColumn, int rightColumn) {
        this.rampSelectionLeftColumn = leftColumn;
        this.rampSelectionRightColumn = rightColumn;
    }

    // ── Utility: MIDI note number ↔ piano roll row ──────────────────────────

    static int midiNoteNumberToRow(int noteNumber) {
        int row = TOTAL_KEYS - 1 - noteNumber;
        if (row < 0 || row >= TOTAL_KEYS) {
            return -1;
        }
        return row;
    }

    static int rowToMidiNoteNumber(int row) {
        return TOTAL_KEYS - 1 - row;
    }

    private static boolean isBlackKey(int noteInOctave) {
        return noteInOctave == 1 || noteInOctave == 3 || noteInOctave == 6
                || noteInOctave == 8 || noteInOctave == 10;
    }

    // ── Undoable note-list actions ──────────────────────────────────────────

    static final class NoteListAddAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final MidiNote note;
        private int addedIndex = -1;
        NoteListAddAction(List<MidiNote> notes, MidiNote note) {
            this.notes = notes;
            this.note = note;
        }
        @Override public String description() { return "Add MIDI Note"; }
        @Override public void execute() {
            addedIndex = notes.size();
            notes.add(note);
        }
        @Override public void undo() {
            if (addedIndex >= 0 && addedIndex < notes.size()
                    && notes.get(addedIndex) == note) {
                notes.remove(addedIndex);
            }
        }
    }

    static final class NoteListRemoveAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final MidiNote note;
        private final int index;
        NoteListRemoveAction(List<MidiNote> notes, MidiNote note, int index) {
            this.notes = notes;
            this.note = note;
            this.index = index;
        }
        @Override public String description() { return "Delete MIDI Note"; }
        @Override public void execute() {
            if (index >= 0 && index < notes.size()
                    && notes.get(index) == note) {
                notes.remove(index);
            }
        }
        @Override public void undo() {
            if (index >= 0 && index <= notes.size()) {
                notes.add(index, note);
            } else {
                notes.add(note);
            }
        }
    }

    static final class NoteListMoveAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final int index;
        private final MidiNote original;
        private final MidiNote moved;
        NoteListMoveAction(List<MidiNote> notes, int index, MidiNote original, MidiNote moved) {
            this.notes = notes;
            this.index = index;
            this.original = original;
            this.moved = moved;
        }
        @Override public String description() { return "Move MIDI Note"; }
        @Override public void execute() { if (index >= 0 && index < notes.size()) notes.set(index, moved); }
        @Override public void undo() { if (index >= 0 && index < notes.size()) notes.set(index, original); }
    }

    static final class NoteListResizeAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final int index;
        private final MidiNote original;
        private final MidiNote resized;
        NoteListResizeAction(List<MidiNote> notes, int index, MidiNote original, MidiNote resized) {
            this.notes = notes;
            this.index = index;
            this.original = original;
            this.resized = resized;
        }
        @Override public String description() { return "Resize MIDI Note"; }
        @Override public void execute() { if (index >= 0 && index < notes.size()) notes.set(index, resized); }
        @Override public void undo() { if (index >= 0 && index < notes.size()) notes.set(index, original); }
    }

    static final class NoteListVelocityAction implements UndoableAction {
        private final List<MidiNote> notes;
        private final int index;
        private final MidiNote original;
        private final MidiNote updated;
        NoteListVelocityAction(List<MidiNote> notes, int index, MidiNote original, MidiNote updated) {
            this.notes = notes;
            this.index = index;
            this.original = original;
            this.updated = updated;
        }
        @Override public String description() { return "Set Note Velocity"; }
        @Override public void execute() { if (index >= 0 && index < notes.size()) notes.set(index, updated); }
        @Override public void undo() { if (index >= 0 && index < notes.size()) notes.set(index, original); }
    }
}
