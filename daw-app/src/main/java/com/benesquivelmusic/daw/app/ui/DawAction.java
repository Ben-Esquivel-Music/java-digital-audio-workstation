package com.benesquivelmusic.daw.app.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * Enumerates every bindable user action in the DAW application.
 *
 * <p>Each constant carries a human-readable display name, a category for
 * grouping in the Key Bindings settings UI, and a default {@link KeyCombination}
 * shortcut. The default can be {@code null} for actions that have no factory
 * shortcut.</p>
 */
public enum DawAction {

    // ── Transport ────────────────────────────────────────────────────────────
    PLAY_STOP("Play / Stop", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.SPACE)),
    STOP("Stop", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.ESCAPE)),
    RECORD("Record", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.R)),
    SKIP_TO_START("Skip to Start", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.HOME)),
    SKIP_TO_END("Skip to End", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.END)),
    TOGGLE_LOOP("Toggle Loop", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.L)),
    TOGGLE_METRONOME("Toggle Metronome", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.M)),
    SET_PUNCH_IN("Set Punch In", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.I)),
    SET_PUNCH_OUT("Set Punch Out", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.O)),
    TOGGLE_PUNCH("Toggle Punch", Category.TRANSPORT,
            new KeyCodeCombination(KeyCode.P, KeyCombination.SHIFT_DOWN)),

    // ── Editing ──────────────────────────────────────────────────────────────
    UNDO("Undo", Category.EDITING,
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN)),
    REDO("Redo", Category.EDITING,
            new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    SAVE("Save", Category.EDITING,
            new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN)),
    NEW_PROJECT("New Project", Category.EDITING,
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN)),
    OPEN_PROJECT("Open Project", Category.EDITING,
            new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN)),
    TOGGLE_SNAP("Toggle Snap", Category.EDITING,
            new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    COPY("Copy", Category.EDITING,
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)),
    CUT("Cut", Category.EDITING,
            new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN)),
    PASTE("Paste", Category.EDITING,
            new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN)),
    DUPLICATE("Duplicate", Category.EDITING,
            new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN)),
    DELETE_SELECTION("Delete Selection", Category.EDITING,
            new KeyCodeCombination(KeyCode.DELETE)),
    RIPPLE_MODE_OFF("Ripple Off", Category.EDITING,
            new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHIFT_DOWN)),
    RIPPLE_MODE_PER_TRACK("Ripple Per Track", Category.EDITING,
            new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHIFT_DOWN)),
    RIPPLE_MODE_ALL_TRACKS("Ripple All Tracks", Category.EDITING,
            new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHIFT_DOWN)),
    // Story 139 — slip the selected clip's content by the current grid step.
    SLIP_LEFT_GRID("Slip Left (Grid)", Category.EDITING,
            new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHIFT_DOWN)),
    SLIP_RIGHT_GRID("Slip Right (Grid)", Category.EDITING,
            new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHIFT_DOWN)),
    // Story 139 — sample-level slip nudge (Ctrl+Shift+Arrow).
    SLIP_LEFT_FINE("Slip Left (Sample)", Category.EDITING,
            new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    SLIP_RIGHT_FINE("Slip Right (Sample)", Category.EDITING,
            new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    // Issue 566 — nudge the selected clips on the timeline by the configured
    // NudgeSettings. Ctrl+Arrow nudges by 1×; Ctrl+Alt+Arrow nudges by 10×
    // (the issue's suggested Ctrl+Shift+Arrow is already taken by slip-fine);
    // Alt+Arrow nudges by exactly one audio sample.
    NUDGE_LEFT("Nudge Left", Category.EDITING,
            new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN)),
    NUDGE_RIGHT("Nudge Right", Category.EDITING,
            new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN)),
    NUDGE_LEFT_LARGE("Nudge Left (10×)", Category.EDITING,
            new KeyCodeCombination(KeyCode.LEFT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)),
    NUDGE_RIGHT_LARGE("Nudge Right (10×)", Category.EDITING,
            new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)),
    NUDGE_LEFT_SAMPLE("Nudge Left (Sample)", Category.EDITING,
            new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN)),
    NUDGE_RIGHT_SAMPLE("Nudge Right (Sample)", Category.EDITING,
            new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN)),

    // ── Session interchange ─────────────────────────────────────────────────
    IMPORT_SESSION("Import Session", Category.EDITING,
            new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    EXPORT_SESSION("Export Session", Category.EDITING,
            new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),

    // ── Audio file import ───────────────────────────────────────────────────
    IMPORT_AUDIO_FILE("Import Audio File", Category.EDITING,
            new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN)),

    // ── Track operations ─────────────────────────────────────────────────────
    ADD_AUDIO_TRACK("Add Audio Track", Category.TRACKS,
            new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    ADD_MIDI_TRACK("Add MIDI Track", Category.TRACKS,
            new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),

    // ── Edit Tools ───────────────────────────────────────────────────────────
    TOOL_POINTER("Pointer Tool", Category.TOOLS,
            new KeyCodeCombination(KeyCode.V)),
    TOOL_PENCIL("Pencil Tool", Category.TOOLS,
            new KeyCodeCombination(KeyCode.P)),
    TOOL_ERASER("Eraser Tool", Category.TOOLS,
            new KeyCodeCombination(KeyCode.E)),
    TOOL_SCISSORS("Scissors Tool", Category.TOOLS,
            new KeyCodeCombination(KeyCode.C)),
    TOOL_GLUE("Glue Tool", Category.TOOLS,
            new KeyCodeCombination(KeyCode.G)),

    // ── Navigation / Zoom ────────────────────────────────────────────────────
    ZOOM_IN("Zoom In", Category.NAVIGATION,
            new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN)),
    ZOOM_OUT("Zoom Out", Category.NAVIGATION,
            new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN)),
    ZOOM_TO_FIT("Zoom to Fit", Category.NAVIGATION,
            new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN)),

    // ── View switching ───────────────────────────────────────────────────────
    VIEW_ARRANGEMENT("Arrangement View", Category.VIEWS,
            new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN)),
    VIEW_MIXER("Mixer View", Category.VIEWS,
            new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN)),
    VIEW_EDITOR("Editor View", Category.VIEWS,
            new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN)),
    VIEW_MASTERING("Mastering View", Category.VIEWS,
            new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.SHORTCUT_DOWN)),
    TOGGLE_BROWSER("Toggle Browser", Category.VIEWS,
            new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN)),
    TOGGLE_HISTORY("Toggle Undo History", Category.VIEWS,
            new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    TOGGLE_NOTIFICATION_HISTORY("Toggle Notification History", Category.VIEWS,
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    TOGGLE_VISUALIZATIONS("Toggle Visualizations", Category.VIEWS,
            new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),

    // ── Application ──────────────────────────────────────────────────────────
    OPEN_SETTINGS("Settings", Category.APPLICATION,
            new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));

    /**
     * Logical grouping for the settings UI.
     */
    public enum Category {
        TRANSPORT("Transport"),
        EDITING("Editing"),
        TRACKS("Tracks"),
        TOOLS("Tools"),
        NAVIGATION("Navigation"),
        VIEWS("Views"),
        APPLICATION("Application");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        /** Returns a human-readable name for this category. */
        public String displayName() {
            return displayName;
        }
    }

    private final String displayName;
    private final Category category;
    private final KeyCombination defaultBinding;

    DawAction(String displayName, Category category, KeyCombination defaultBinding) {
        this.displayName = displayName;
        this.category = category;
        this.defaultBinding = defaultBinding;
    }

    /** Returns a human-readable name suitable for display in menus and settings. */
    public String displayName() {
        return displayName;
    }

    /** Returns the category this action belongs to. */
    public Category category() {
        return category;
    }

    /**
     * Returns the default (factory) key combination for this action,
     * or {@code null} if no default is defined.
     */
    public KeyCombination defaultBinding() {
        return defaultBinding;
    }
}
