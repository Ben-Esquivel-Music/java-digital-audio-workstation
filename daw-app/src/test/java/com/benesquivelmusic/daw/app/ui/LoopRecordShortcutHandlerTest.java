package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 328 (book §5.6) — the loop-record toggle has a shortcut:
 * {@code DawAction.TOGGLE_LOOP_RECORD} is bound to {@code Shift+L} beside
 * the loop toggle's {@code L} (uniqueness across every action is pinned by
 * {@code DawActionTest.defaultBindingsShouldBeUniqueAcrossActions}),
 * {@link KeyboardShortcutController#buildActionHandlers()} registers it,
 * and the handler reaches its own {@code Host} callback (the production host
 * dispatches {@code ToggleLoopRecordCommand}). Template:
 * {@link PunchShortcutHandlerTest}.
 */
final class LoopRecordShortcutHandlerTest {

    @Test
    void factoryBindingIsShiftLBesideTheLoopToggle() {
        assertThat(DawAction.TOGGLE_LOOP_RECORD.defaultBinding())
                .isEqualTo(new KeyCodeCombination(KeyCode.L, KeyCombination.SHIFT_DOWN));
        assertThat(DawAction.TOGGLE_LOOP_RECORD.category()).isEqualTo(DawAction.Category.TRANSPORT);
        assertThat(DawAction.TOGGLE_LOOP.defaultBinding()).isEqualTo(new KeyCodeCombination(KeyCode.L));
    }

    @Test
    void toggleLoopRecordIsRegisteredAndReachesItsOwnHostCallback() {
        List<String> calls = new ArrayList<>();
        KeyboardShortcutController controller = new KeyboardShortcutController(
                new KeyBindingManager(Preferences.userRoot().node("looprec_" + System.nanoTime())),
                new KsHostStub(calls));
        Map<DawAction, Runnable> handlers = controller.buildActionHandlers();

        assertThat(handlers).containsKey(DawAction.TOGGLE_LOOP_RECORD);

        handlers.get(DawAction.TOGGLE_LOOP_RECORD).run();
        handlers.get(DawAction.TOGGLE_LOOP).run();

        assertThat(calls).containsExactly("onToggleLoopRecord", "onToggleLoop");
    }

    /** Minimal host: records the loop callbacks, no-ops everything else. */
    private static final class KsHostStub implements KeyboardShortcutController.Host {
        private final List<String> calls;

        KsHostStub(List<String> calls) {
            this.calls = calls;
        }

        @Override public void onToggleLoopRecord() { calls.add("onToggleLoopRecord"); }
        @Override public void onToggleLoop() { calls.add("onToggleLoop"); }

        @Override public TransportState transportState() { return null; }
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onSkipBack() { }
        @Override public void onSkipForward() { }
        @Override public void onToggleMetronome() { }
        @Override public void onUndo() { }
        @Override public void onRedo() { }
        @Override public void onSaveProject() { }
        @Override public void onNewProject() { }
        @Override public void onOpenProject() { }
        @Override public void onImportSession() { }
        @Override public void onExportSession() { }
        @Override public void onImportAudioFile() { }
        @Override public void onToggleSnap() { }
        @Override public void onAddAudioTrack() { }
        @Override public void onAddMidiTrack() { }
        @Override public void selectEditTool(EditTool tool) { }
        @Override public void onZoomIn() { }
        @Override public void onZoomOut() { }
        @Override public void onZoomToFit() { }
        @Override public void switchView(DawView view) { }
        @Override public void onToggleBrowser() { }
        @Override public void onToggleHistory() { }
        @Override public void onToggleNotificationHistory() { }
        @Override public void onToggleVisualizations() { }
        @Override public void onOpenSettings() { }
        @Override public void onCopy() { }
        @Override public void onCut() { }
        @Override public void onPaste() { }
        @Override public void onDuplicate() { }
        @Override public void onDeleteSelection() { }
        @Override public void setRippleMode(RippleMode mode) { }
        @Override public void onSlipLeftByGrid() { }
        @Override public void onSlipRightByGrid() { }
        @Override public void onSlipLeftByFine() { }
        @Override public void onSlipRightByFine() { }
        @Override public void onNudgeLeft() { }
        @Override public void onNudgeRight() { }
        @Override public void onNudgeLeftLarge() { }
        @Override public void onNudgeRightLarge() { }
        @Override public void onNudgeLeftSample() { }
        @Override public void onNudgeRightSample() { }
        @Override public void onToggleFoldFocusedTrack() { }
        @Override public void onToggleFoldSelectedTracks() { }
        @Override public void onFoldAllAutomation() { }
        @Override public void onToggleCommandPalette() { }
    }
}
