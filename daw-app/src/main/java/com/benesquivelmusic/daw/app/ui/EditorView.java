package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * Editor view for detailed MIDI piano-roll editing and audio waveform editing.
 *
 * <p>This is a thin container that detects the selected clip type (audio vs.
 * MIDI) and delegates to the appropriate sub-view:</p>
 * <ul>
 *   <li>{@link MidiEditorView} — Piano roll grid with note display, velocity
 *       editing, and MIDI-specific toolbar controls.</li>
 *   <li>{@link AudioEditorView} — Waveform display with selection, trim,
 *       and fade handles.</li>
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

    /**
     * The number of beats each grid column represents. With 32 columns
     * covering 8 beats (2 bars of 4/4), each column equals a sixteenth note.
     */
    static final double BEATS_PER_COLUMN = MidiEditorView.BEATS_PER_COLUMN;

    private final Label header;
    private final StackPane contentArea;
    private final Label placeholderLabel;
    private final HBox toolBar;

    private final MidiEditorView midiEditorView;
    private final AudioEditorView audioEditorView;

    private Mode currentMode = Mode.EMPTY;
    private Track selectedTrack;

    // ── Tool state ───────────────────────────────────────────────────────────
    private EditTool activeEditTool = EditTool.POINTER;
    private Button pointerBtn;
    private Button pencilBtn;
    private Button eraserBtn;
    private Consumer<EditTool> onToolChanged;

    // ── Zoom state ───────────────────────────────────────────────────────────
    private final ZoomLevel zoomLevel = new ZoomLevel();

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

        // MIDI editor sub-view
        midiEditorView = new MidiEditorView();

        // Audio editor sub-view
        audioEditorView = new AudioEditorView();

        // Content area wraps placeholder / midi / audio
        contentArea = new StackPane(placeholderLabel);
        contentArea.getStyleClass().add("content-area");
        VBox.setVgrow(contentArea, Priority.ALWAYS);

        getChildren().addAll(header, toolBar, contentArea);
        setPadding(new Insets(8));

        // Enable keyboard events for Delete key
        setFocusTraversable(true);
        setOnKeyPressed(event -> {
            if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE)
                    && currentMode == Mode.MIDI) {
                midiEditorView.deleteSelectedNote();
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
            midiEditorView.loadFromMidiClip(track.getMidiClip());
            switchMode(Mode.MIDI);
        } else {
            switchMode(Mode.AUDIO);
        }
        audioEditorView.setSelectedTrack(track);
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
        return audioEditorView.getWaveformDisplay();
    }

    /**
     * Returns the piano roll canvas used in MIDI editor mode.
     * Visible for testing.
     *
     * @return the piano roll canvas
     */
    Canvas getPianoRollCanvas() {
        return midiEditorView.getPianoRollCanvas();
    }

    /**
     * Returns the velocity canvas used in MIDI editor mode.
     * Visible for testing.
     *
     * @return the velocity canvas
     */
    Canvas getVelocityCanvas() {
        return midiEditorView.getVelocityCanvas();
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
        return audioEditorView.getTrimButton();
    }

    /**
     * Returns the Fade In button. Visible for testing.
     *
     * @return the fade-in button
     */
    Button getFadeInButton() {
        return audioEditorView.getFadeInButton();
    }

    /**
     * Returns the Fade Out button. Visible for testing.
     *
     * @return the fade-out button
     */
    Button getFadeOutButton() {
        return audioEditorView.getFadeOutButton();
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
        midiEditorView.setSnapState(snapEnabled, gridResolution, beatsPerBar);
    }

    /**
     * Returns whether snap-to-grid is currently enabled for this editor view.
     *
     * @return {@code true} if snap is enabled
     */
    public boolean isSnapEnabled() {
        return midiEditorView.isSnapEnabled();
    }

    /**
     * Returns the active grid resolution for this editor view.
     *
     * @return the grid resolution
     */
    public GridResolution getGridResolution() {
        return midiEditorView.getGridResolution();
    }

    // ── Audio handle API ─────────────────────────────────────────────────────

    /**
     * Registers a callback invoked when the Trim button is clicked.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnTrimAction(Runnable handler) {
        audioEditorView.setOnTrimAction(handler);
    }

    /**
     * Registers a callback invoked when the Fade In button is clicked.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnFadeInAction(Runnable handler) {
        audioEditorView.setOnFadeInAction(handler);
    }

    /**
     * Registers a callback invoked when the Fade Out button is clicked.
     *
     * @param handler the callback, or {@code null} to clear
     */
    public void setOnFadeOutAction(Runnable handler) {
        audioEditorView.setOnFadeOutAction(handler);
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
        return midiEditorView.getNotes();
    }

    /**
     * Returns the index of the currently selected note, or {@code -1} if none.
     *
     * @return the selected note index
     */
    public int getSelectedNoteIndex() {
        return midiEditorView.getSelectedNoteIndex();
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
        midiEditorView.setUndoManager(undoManager);
    }

    /**
     * Returns the current {@link UndoManager}, or {@code null} if none.
     *
     * @return the undo manager
     */
    public UndoManager getUndoManager() {
        return midiEditorView.getUndoManager();
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
        midiEditorView.loadFromMidiClip(midiClip);
    }

    /**
     * Adds a single note to the editor display, typically called during
     * real-time MIDI recording to show incoming notes on the piano roll.
     *
     * @param noteData the recorded note data
     */
    public void addRecordedNote(MidiNoteData noteData) {
        midiEditorView.addRecordedNote(noteData);
    }

    /**
     * Deletes the currently selected note. Called by the Delete key handler.
     */
    void deleteSelectedNote() {
        midiEditorView.deleteSelectedNote();
    }

    /**
     * Moves the currently selected note to the given row and column.
     * Intended for external callers (e.g., tests) and programmatic use.
     *
     * @param newRow    the new row index
     * @param newColumn the new start column
     */
    void moveSelectedNote(int newRow, int newColumn) {
        midiEditorView.moveSelectedNote(newRow, newColumn);
    }

    /**
     * Resizes the currently selected note to the given duration.
     *
     * @param newDuration the new duration in grid columns (≥ 1)
     */
    void resizeSelectedNote(int newDuration) {
        midiEditorView.resizeSelectedNote(newDuration);
    }

    /**
     * Sets the velocity of the currently selected note.
     *
     * @param newVelocity the new velocity (0–127)
     */
    void setSelectedNoteVelocity(int newVelocity) {
        midiEditorView.setSelectedNoteVelocity(newVelocity);
    }

    // ── Snap column (package-private for tests) ─────────────────────────────

    /**
     * Snaps a grid column index to the nearest column aligned with the active
     * {@link GridResolution}. The column is converted to a beat position,
     * quantized, and converted back.
     */
    int snapColumn(int column) {
        return midiEditorView.snapColumn(column);
    }

    // ── Utility: MIDI note number ↔ piano roll row ──────────────────────────

    static int midiNoteNumberToRow(int noteNumber) {
        return MidiEditorView.midiNoteNumberToRow(noteNumber);
    }

    static int rowToMidiNoteNumber(int row) {
        return MidiEditorView.rowToMidiNoteNumber(row);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void switchMode(Mode mode) {
        this.currentMode = mode;
        contentArea.getChildren().clear();
        switch (mode) {
            case MIDI -> {
                contentArea.getChildren().add(midiEditorView);
                midiEditorView.renderPianoRoll();
            }
            case AUDIO -> contentArea.getChildren().add(audioEditorView);
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

    private void onToolButtonClicked(EditTool tool) {
        applyActiveTool(tool);
        if (onToolChanged != null) {
            onToolChanged.accept(tool);
        }
    }

    private void applyActiveTool(EditTool tool) {
        if (tool == activeEditTool) {
            return;
        }
        activeEditTool = tool;
        updateToolButtonStyles();
        midiEditorView.setActiveEditTool(tool);
        audioEditorView.setActiveEditTool(tool);
    }

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

    // ── Zoom ─────────────────────────────────────────────────────────────────

    private void onZoomIn() {
        zoomLevel.zoomIn();
        applyZoom();
    }

    private void onZoomOut() {
        zoomLevel.zoomOut();
        applyZoom();
    }

    private void applyZoom() {
        midiEditorView.applyZoom(zoomLevel.getLevel());
    }
}
