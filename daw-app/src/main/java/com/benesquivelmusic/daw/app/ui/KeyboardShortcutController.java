package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;
import javafx.collections.ObservableMap;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Registers global keyboard shortcuts by mapping {@link DawAction} values to
 * handler callbacks via the {@link KeyBindingManager}.
 *
 * <p>Extracted from {@code MainController} to separate keyboard shortcut
 * registration from the main coordinator.</p>
 */
final class KeyboardShortcutController {

    private static final Logger LOG = Logger.getLogger(KeyboardShortcutController.class.getName());

    interface Host {
        TransportState transportState();
        void onPlay();
        void onStop();
        void onRecord();
        void onSkipBack();
        void onSkipForward();
        void onToggleLoop();
        void onToggleMetronome();
        void onUndo();
        void onRedo();
        void onSaveProject();
        void onNewProject();
        void onOpenProject();
        void onImportSession();
        void onExportSession();
        void onImportAudioFile();
        void onToggleSnap();
        void onAddAudioTrack();
        void onAddMidiTrack();
        void selectEditTool(EditTool tool);
        void onZoomIn();
        void onZoomOut();
        void onZoomToFit();
        void switchView(DawView view);
        void onToggleBrowser();
        void onToggleHistory();
        void onToggleNotificationHistory();
        void onToggleVisualizations();
        void onOpenSettings();
        void onCopy();
        void onCut();
        void onPaste();
        void onDuplicate();
        void onDeleteSelection();
        void setRippleMode(RippleMode mode);
        /** Slip the selected clip one grid step to the left (Story 139). */
        void onSlipLeftByGrid();
        /** Slip the selected clip one grid step to the right (Story 139). */
        void onSlipRightByGrid();
        /** Slip the selected clip by the finest quantum to the left (Story 139). */
        void onSlipLeftByFine();
        /** Slip the selected clip by the finest quantum to the right (Story 139). */
        void onSlipRightByFine();
        /** Nudge the selection left by the configured NudgeSettings (Issue 566). */
        void onNudgeLeft();
        /** Nudge the selection right by the configured NudgeSettings (Issue 566). */
        void onNudgeRight();
        /** Nudge the selection left by 10× the configured NudgeSettings (Issue 566). */
        void onNudgeLeftLarge();
        /** Nudge the selection right by 10× the configured NudgeSettings (Issue 566). */
        void onNudgeRightLarge();
        /** Nudge the selection left by a single audio sample (Issue 566). */
        void onNudgeLeftSample();
        /** Nudge the selection right by a single audio sample (Issue 566). */
        void onNudgeRightSample();
        /** Toggle lane fold on the focused track (Issue 568). */
        void onToggleFoldFocusedTrack();
        /** Toggle lane fold on every track containing a selected clip (Issue 568). */
        void onToggleFoldSelectedTracks();
        /** Master "Fold all automation" toggle (Issue 568). */
        void onFoldAllAutomation();
        /**
         * Toggle the searchable Command Palette. Bound to {@code Ctrl+K} via
         * {@link DawAction#OPEN_COMMAND_PALETTE} and to a fixed
         * {@code Ctrl+Shift+P} accelerator.
         */
        void onToggleCommandPalette();
    }

    private final KeyBindingManager keyBindingManager;
    private final Host host;

    KeyboardShortcutController(KeyBindingManager keyBindingManager, Host host) {
        this.keyBindingManager = keyBindingManager;
        this.host = host;
    }

    /**
     * Builds the (action → handler) map used both for accelerator registration
     * and for sourcing {@link CommandPaletteView} entries. Exposed
     * package-private so the {@code MainController} can share the same
     * handlers with the command palette without duplicating the wiring.
     */
    Map<DawAction, Runnable> buildActionHandlers() {
        Map<DawAction, Runnable> actionHandlers = new EnumMap<>(DawAction.class);
        actionHandlers.put(DawAction.PLAY_STOP, () -> {
            if (host.transportState() == TransportState.PLAYING) {
                host.onStop();
            } else {
                host.onPlay();
            }
        });
        actionHandlers.put(DawAction.STOP, host::onStop);
        actionHandlers.put(DawAction.RECORD, host::onRecord);
        actionHandlers.put(DawAction.SKIP_TO_START, host::onSkipBack);
        actionHandlers.put(DawAction.SKIP_TO_END, host::onSkipForward);
        actionHandlers.put(DawAction.TOGGLE_LOOP, host::onToggleLoop);
        actionHandlers.put(DawAction.TOGGLE_METRONOME, host::onToggleMetronome);
        actionHandlers.put(DawAction.UNDO, host::onUndo);
        actionHandlers.put(DawAction.REDO, host::onRedo);
        actionHandlers.put(DawAction.SAVE, host::onSaveProject);
        actionHandlers.put(DawAction.NEW_PROJECT, host::onNewProject);
        actionHandlers.put(DawAction.OPEN_PROJECT, host::onOpenProject);
        actionHandlers.put(DawAction.IMPORT_SESSION, host::onImportSession);
        actionHandlers.put(DawAction.EXPORT_SESSION, host::onExportSession);
        actionHandlers.put(DawAction.IMPORT_AUDIO_FILE, host::onImportAudioFile);
        actionHandlers.put(DawAction.TOGGLE_SNAP, host::onToggleSnap);
        actionHandlers.put(DawAction.ADD_AUDIO_TRACK, host::onAddAudioTrack);
        actionHandlers.put(DawAction.ADD_MIDI_TRACK, host::onAddMidiTrack);
        actionHandlers.put(DawAction.TOOL_POINTER, () -> host.selectEditTool(EditTool.POINTER));
        actionHandlers.put(DawAction.TOOL_PENCIL, () -> host.selectEditTool(EditTool.PENCIL));
        actionHandlers.put(DawAction.TOOL_ERASER, () -> host.selectEditTool(EditTool.ERASER));
        actionHandlers.put(DawAction.TOOL_SCISSORS, () -> host.selectEditTool(EditTool.SCISSORS));
        actionHandlers.put(DawAction.TOOL_GLUE, () -> host.selectEditTool(EditTool.GLUE));
        actionHandlers.put(DawAction.ZOOM_IN, host::onZoomIn);
        actionHandlers.put(DawAction.ZOOM_OUT, host::onZoomOut);
        actionHandlers.put(DawAction.ZOOM_TO_FIT, host::onZoomToFit);
        actionHandlers.put(DawAction.VIEW_ARRANGEMENT, () -> host.switchView(DawView.ARRANGEMENT));
        actionHandlers.put(DawAction.VIEW_MIXER, () -> host.switchView(DawView.MIXER));
        actionHandlers.put(DawAction.VIEW_EDITOR, () -> host.switchView(DawView.EDITOR));
        actionHandlers.put(DawAction.VIEW_MASTERING, () -> host.switchView(DawView.MASTERING));
        actionHandlers.put(DawAction.TOGGLE_BROWSER, host::onToggleBrowser);
        actionHandlers.put(DawAction.TOGGLE_HISTORY, host::onToggleHistory);
        actionHandlers.put(DawAction.TOGGLE_NOTIFICATION_HISTORY, host::onToggleNotificationHistory);
        actionHandlers.put(DawAction.TOGGLE_VISUALIZATIONS, host::onToggleVisualizations);
        actionHandlers.put(DawAction.OPEN_SETTINGS, host::onOpenSettings);
        actionHandlers.put(DawAction.COPY, host::onCopy);
        actionHandlers.put(DawAction.CUT, host::onCut);
        actionHandlers.put(DawAction.PASTE, host::onPaste);
        actionHandlers.put(DawAction.DUPLICATE, host::onDuplicate);
        actionHandlers.put(DawAction.DELETE_SELECTION, host::onDeleteSelection);
        actionHandlers.put(DawAction.RIPPLE_MODE_OFF, () -> host.setRippleMode(RippleMode.OFF));
        actionHandlers.put(DawAction.RIPPLE_MODE_PER_TRACK, () -> host.setRippleMode(RippleMode.PER_TRACK));
        actionHandlers.put(DawAction.RIPPLE_MODE_ALL_TRACKS, () -> host.setRippleMode(RippleMode.ALL_TRACKS));
        actionHandlers.put(DawAction.SLIP_LEFT_GRID, host::onSlipLeftByGrid);
        actionHandlers.put(DawAction.SLIP_RIGHT_GRID, host::onSlipRightByGrid);
        actionHandlers.put(DawAction.SLIP_LEFT_FINE, host::onSlipLeftByFine);
        actionHandlers.put(DawAction.SLIP_RIGHT_FINE, host::onSlipRightByFine);
        actionHandlers.put(DawAction.NUDGE_LEFT, host::onNudgeLeft);
        actionHandlers.put(DawAction.NUDGE_RIGHT, host::onNudgeRight);
        actionHandlers.put(DawAction.NUDGE_LEFT_LARGE, host::onNudgeLeftLarge);
        actionHandlers.put(DawAction.NUDGE_RIGHT_LARGE, host::onNudgeRightLarge);
        actionHandlers.put(DawAction.NUDGE_LEFT_SAMPLE, host::onNudgeLeftSample);
        actionHandlers.put(DawAction.NUDGE_RIGHT_SAMPLE, host::onNudgeRightSample);
        actionHandlers.put(DawAction.TOGGLE_FOLD_FOCUSED_TRACK, host::onToggleFoldFocusedTrack);
        actionHandlers.put(DawAction.TOGGLE_FOLD_SELECTED_TRACKS, host::onToggleFoldSelectedTracks);
        actionHandlers.put(DawAction.FOLD_ALL_AUTOMATION, host::onFoldAllAutomation);
        actionHandlers.put(DawAction.OPEN_COMMAND_PALETTE, host::onToggleCommandPalette);
        return actionHandlers;
    }

    void register(Scene scene) {
        if (scene == null) {
            return;
        }
        ObservableMap<KeyCombination, Runnable> accelerators = scene.getAccelerators();

        Map<DawAction, Runnable> actionHandlers = buildActionHandlers();

        for (DawAction action : DawAction.values()) {
            Runnable handler = actionHandlers.get(action);
            if (handler == null) {
                continue;
            }
            Optional<KeyCombination> binding = keyBindingManager.getBinding(action);
            binding.ifPresent(kc -> accelerators.put(kc, handler));
        }

        // Register Ctrl+Shift+P as a fixed additional accelerator for the
        // command palette, in addition to the user-rebindable Ctrl+K
        // (DawAction.OPEN_COMMAND_PALETTE). This mirrors VS Code's
        // longstanding muscle-memory binding.
        // Only register if no other DawAction is already bound to that
        // combination — otherwise we would silently override a user-
        // configured binding, violating the KeyBindingManager contract.
        Runnable paletteHandler = actionHandlers.get(DawAction.OPEN_COMMAND_PALETTE);
        if (paletteHandler != null) {
            KeyCombination ctrlShiftP = new javafx.scene.input.KeyCodeCombination(
                    javafx.scene.input.KeyCode.P,
                    KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
            Optional<DawAction> conflict = keyBindingManager
                    .getActionForBinding(ctrlShiftP);
            if (conflict.isEmpty() || conflict.get() == DawAction.OPEN_COMMAND_PALETTE) {
                accelerators.put(ctrlShiftP, paletteHandler);
            } else {
                LOG.fine("Skipping fixed Ctrl+Shift+P palette accelerator — "
                        + "already bound to " + conflict.get().displayName());
            }
        }

        LOG.fine("Registered keyboard shortcuts");
    }
}
