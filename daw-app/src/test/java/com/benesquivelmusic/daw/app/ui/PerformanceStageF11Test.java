package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.edit.RippleMode;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 280 — pressing {@code F11} activates the Performance Stage view.
 *
 * <p>The wiring under test is:</p>
 * <ol>
 *   <li>{@link DawAction#VIEW_PERFORMANCE_STAGE} declares {@code F11} as its
 *       factory key binding.</li>
 *   <li>{@link KeyboardShortcutController#buildActionHandlers()} maps that
 *       action to a handler that calls {@code host.switchView(PERFORMANCE_STAGE)}
 *       (i.e. the same call exercised end-to-end by
 *       {@link PerformanceStageActivationTest}).</li>
 * </ol>
 *
 * <p>Together with {@link PerformanceStageActivationTest} (which proves
 * {@code switchView(PERFORMANCE_STAGE)} activates the stage), this test
 * closes the full {@code F11 → handler → switchView → activate} chain.
 * Sits in the {@code ui} package so it can construct
 * {@link KeyboardShortcutController} via its package-private constructor.</p>
 */
final class PerformanceStageF11Test {

    @Test
    void f11IsTheFactoryBindingForThePerformanceStageViewAction() {
        assertThat(DawAction.VIEW_PERFORMANCE_STAGE.defaultBinding())
                .as("F11 is the story-mandated shortcut for Performance Stage")
                .isEqualTo(new KeyCodeCombination(KeyCode.F11));
    }

    @Test
    void f11ActionHandlerSwitchesToPerformanceStageView() {
        AtomicReference<DawView> switchedTo = new AtomicReference<>();
        KeyboardShortcutController controller = new KeyboardShortcutController(
                new KeyBindingManager(
                        Preferences.userRoot().node("psF11_" + System.nanoTime())),
                new KsHostStub(switchedTo::set));

        Runnable f11Handler = controller.buildActionHandlers()
                .get(DawAction.VIEW_PERFORMANCE_STAGE);

        assertThat(f11Handler)
                .as("KeyboardShortcutController maps VIEW_PERFORMANCE_STAGE to a handler")
                .isNotNull();
        f11Handler.run();

        assertThat(switchedTo.get())
                .as("F11 handler invokes host.switchView(PERFORMANCE_STAGE)")
                .isEqualTo(DawView.PERFORMANCE_STAGE);
    }

    /**
     * Minimal {@link KeyboardShortcutController.Host} that captures the
     * {@code switchView} target. Every other method is a no-op — the
     * action under test only invokes {@code switchView}.
     */
    private static final class KsHostStub implements KeyboardShortcutController.Host {
        private final Consumer<DawView> switchViewCapture;

        KsHostStub(Consumer<DawView> switchViewCapture) {
            this.switchViewCapture = switchViewCapture;
        }

        @Override public void switchView(DawView view) { switchViewCapture.accept(view); }

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
