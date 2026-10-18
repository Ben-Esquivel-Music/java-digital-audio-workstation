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
 * Story 328 (book §5.6) — the three declared punch actions are no longer
 * dead controls: {@link KeyboardShortcutController#buildActionHandlers()}
 * registers each of {@code SET_PUNCH_IN}, {@code SET_PUNCH_OUT} and
 * {@code TOGGLE_PUNCH}, and each handler reaches its own {@code Host}
 * callback (the production host dispatches the matching
 * {@code TransportCommand}; the command→handler routing is covered by
 * {@code CoreTransportIntentHandlerPunchTest}). Template:
 * {@link PerformanceStageF11Test}.
 */
final class PunchShortcutHandlerTest {

    @Test
    void factoryBindingsAreTheStoryMandatedKeys() {
        assertThat(DawAction.SET_PUNCH_IN.defaultBinding()).isEqualTo(new KeyCodeCombination(KeyCode.I));
        assertThat(DawAction.SET_PUNCH_OUT.defaultBinding()).isEqualTo(new KeyCodeCombination(KeyCode.O));
        assertThat(DawAction.TOGGLE_PUNCH.defaultBinding())
                .isEqualTo(new KeyCodeCombination(KeyCode.P, KeyCombination.SHIFT_DOWN));
    }

    @Test
    void eachPunchActionIsRegisteredAndReachesItsOwnHostCallback() {
        List<String> calls = new ArrayList<>();
        KeyboardShortcutController controller = new KeyboardShortcutController(
                new KeyBindingManager(Preferences.userRoot().node("punch_" + System.nanoTime())),
                new KsHostStub(calls));
        Map<DawAction, Runnable> handlers = controller.buildActionHandlers();

        assertThat(handlers).containsKeys(DawAction.SET_PUNCH_IN, DawAction.SET_PUNCH_OUT, DawAction.TOGGLE_PUNCH);

        handlers.get(DawAction.SET_PUNCH_IN).run();
        handlers.get(DawAction.SET_PUNCH_OUT).run();
        handlers.get(DawAction.TOGGLE_PUNCH).run();

        assertThat(calls).containsExactly("onSetPunchIn", "onSetPunchOut", "onTogglePunch");
    }

    /** Minimal host: records the punch callbacks, no-ops everything else. */
    private static final class KsHostStub implements KeyboardShortcutController.Host {
        private final List<String> calls;

        KsHostStub(List<String> calls) {
            this.calls = calls;
        }

        @Override public void onSetPunchIn() { calls.add("onSetPunchIn"); }
        @Override public void onSetPunchOut() { calls.add("onSetPunchOut"); }
        @Override public void onTogglePunch() { calls.add("onTogglePunch"); }

        @Override public TransportState transportState() { return null; }
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onSkipBack() { }
        @Override public void onSkipForward() { }
        @Override public void onToggleLoop() { }
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
