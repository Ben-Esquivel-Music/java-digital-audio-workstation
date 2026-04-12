package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TransportState;
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
    }

    private final KeyBindingManager keyBindingManager;
    private final Host host;

    KeyboardShortcutController(KeyBindingManager keyBindingManager, Host host) {
        this.keyBindingManager = keyBindingManager;
        this.host = host;
    }

    void register(Scene scene) {
        if (scene == null) {
            return;
        }
        ObservableMap<KeyCombination, Runnable> accelerators = scene.getAccelerators();

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

        for (DawAction action : DawAction.values()) {
            Runnable handler = actionHandlers.get(action);
            if (handler == null) {
                continue;
            }
            Optional<KeyCombination> binding = keyBindingManager.getBinding(action);
            binding.ifPresent(kc -> accelerators.put(kc, handler));
        }

        LOG.fine("Registered keyboard shortcuts");
    }
}
