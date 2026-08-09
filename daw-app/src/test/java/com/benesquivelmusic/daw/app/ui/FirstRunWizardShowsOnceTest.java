package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.settings.FirstRunWizard;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §4.D first-run wizard (Concept D): with the first-run
 * flag unset the auto-show decision is true and the wizard sequences
 * audio → appearance → project-defaults over generic {@link SettingRow}
 * instances (the same §5.4 rows, never a parallel form); completing —
 * and, separately, skipping — sets the flag, so it never auto-shows
 * again; and the General ▸ Startup re-open seam exists on the settings
 * dialog.
 *
 * <p>Tested at the decision seam ({@code FirstRunWizard.shouldAutoShow})
 * and the pane API — headless, no window. The literal
 * {@code DawApplication.start} → {@code MainController
 * .showWelcomeIfNoProjectOpen} auto-show wiring is not exercisable
 * in-process (MainController is never FXML-loaded in tests — the known
 * literal-test gap); the decision seam plus the {@code showSetupWizard}
 * glue it calls are what this class pins.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FirstRunWizardShowsOnceTest {

    private static final List<String> AUDIO_STEP = List.of(
            "audio.backend", "audio.inputDevice", "audio.outputDevice",
            "audio.applyLatencyCompensation");
    private static final List<String> APPEARANCE_STEP = List.of(
            ThemeManager.PREF_KEY, DensityManager.PREF_KEY,
            "appearance.uiScale", MotionManager.PREF_KEY);
    private static final List<String> PROJECT_STEP = List.of(
            "project.defaultTempo", "project.autoSaveIntervalSeconds");

    private <T> T runOnFxThread(Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                // Capture AssertionError too — an FX-thread assertion must
                // fail the test, not vanish into the FX exception handler.
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(30, TimeUnit.SECONDS))
                .as("FX task completed within the timeout")
                .isTrue();
        Throwable thrown = error.get();
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown instanceof Exception e) {
            throw e;
        }
        return ref.get();
    }

    private static SettingsModel newModel() {
        return new SettingsModel(newPreferences("firstRunWizardShowsOnceTest"));
    }

    private static Preferences newPreferences(String prefix) {
        return Preferences.userRoot().node(prefix + "_" + System.nanoTime());
    }

    private static List<String> rowIds(FirstRunWizard wizard) {
        return wizard.currentStepRows().stream()
                .map(row -> row.descriptor().id())
                .toList();
    }

    @Test
    void autoShowDecisionShouldFollowTheFirstRunFlag() {
        SettingsModel model = newModel();
        assertThat(FirstRunWizard.shouldAutoShow(model))
                .as("a fresh install auto-shows the wizard")
                .isTrue();
        model.setFirstRunWizardCompleted(true);
        assertThat(FirstRunWizard.shouldAutoShow(model))
                .as("once the flag is set the wizard never auto-shows again")
                .isFalse();
        assertThat(FirstRunWizard.shouldAutoShow(newModel()))
                .as("the flag is per-preferences-node state")
                .isTrue();
    }

    @Test
    void wizardShouldSequenceTheThreeStepsOverSettingRowsAndFinishSetsTheFlag()
            throws Exception {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            FirstRunWizard wizard = new FirstRunWizard(
                    SettingsCatalogue.create(), model, null /* no enumeration */);

            // Linear sequence: audio → appearance → project → done.
            assertThat(wizard.getCurrentStep()).isZero();
            assertThat(rowIds(wizard)).containsExactlyElementsOf(AUDIO_STEP);
            wizard.nextStep();
            assertThat(rowIds(wizard)).containsExactlyElementsOf(APPEARANCE_STEP);
            wizard.previousStep();
            assertThat(rowIds(wizard))
                    .as("back is linear too — no branching")
                    .containsExactlyElementsOf(AUDIO_STEP);
            wizard.nextStep();
            wizard.nextStep();
            assertThat(rowIds(wizard)).containsExactlyElementsOf(PROJECT_STEP);

            // EDIT a step row: the map handed to onFinished must carry the
            // edited ROW VALUE — collectedEdits() returning descriptor
            // defaults would silently factory-reset every wizard-step
            // setting on Finish while keeping the keySet identical.
            SettingRow tempoRow = wizard.currentStepRows().stream()
                    .filter(row -> "project.defaultTempo".equals(row.descriptor().id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no tempo row"));
            assertThat(tempoRow.descriptor().defaultValue())
                    .as("precondition: 150.0 is a NON-default tempo")
                    .isNotEqualTo(150.0);
            tempoRow.setValue(150.0);

            wizard.nextStep();
            assertThat(wizard.getCurrentStep())
                    .as("the trailing page is \"you're set up\"")
                    .isEqualTo(3);
            assertThat(wizard.currentStepRows()).isEmpty();

            // Every step renders SettingRow instances (§5.4 — the same
            // generic rows, not new forms).
            wizard.previousStep();
            assertThat(wizard.currentStepRows())
                    .isNotEmpty()
                    .allSatisfy(row -> assertThat(row).isInstanceOf(SettingRow.class));
            wizard.nextStep();

            AtomicReference<Map<String, Object>> finished = new AtomicReference<>();
            wizard.setOnFinished(finished::set);
            wizard.finish();

            assertThat(model.isFirstRunWizardCompleted())
                    .as("completing sets the flag")
                    .isTrue();
            assertThat(FirstRunWizard.shouldAutoShow(model))
                    .as("the next launch does not auto-show")
                    .isFalse();
            assertThat(finished.get())
                    .as("finish hands the collected values to the apply glue")
                    .isNotNull();
            assertThat(finished.get().keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            List.of(AUDIO_STEP, APPEARANCE_STEP, PROJECT_STEP).stream()
                                    .flatMap(List::stream)
                                    .toList());
            assertThat(finished.get())
                    .as("finish hands the EDITED row value to the apply glue "
                            + "— never the descriptor default")
                    .containsEntry("project.defaultTempo", 150.0);
            return null;
        });
    }

    @Test
    void outcomeIsPersistedOnlyAfterItsCallbackSucceeds() throws Exception {
        runOnFxThread(() -> {
            Preferences finishedPreferences = newPreferences("finishedOutcomeOrdering");
            SettingsModel finishedModel = new SettingsModel(finishedPreferences);
            FirstRunWizard finishedWizard = new FirstRunWizard(
                    SettingsCatalogue.create(), finishedModel, null);
            AtomicBoolean finishSawTerminalState = new AtomicBoolean();
            finishedWizard.setOnFinished(_ -> finishSawTerminalState.set(
                    finishedModel.isFirstRunWizardCompleted()
                            || finishedWizard.hasOutcome()));

            finishedWizard.finish();

            assertThat(finishSawTerminalState)
                    .as("the apply callback runs before either terminal state")
                    .isFalse();
            assertThat(finishedModel.isFirstRunWizardCompleted()).isTrue();
            assertThat(new SettingsModel(finishedPreferences)
                    .isFirstRunWizardCompleted()).isTrue();
            assertThat(finishedWizard.hasOutcome()).isTrue();

            SettingsModel skippedModel = newModel();
            FirstRunWizard skippedWizard = new FirstRunWizard(
                    SettingsCatalogue.create(), skippedModel, null);
            AtomicBoolean skipSawTerminalState = new AtomicBoolean();
            skippedWizard.setOnSkipped(() -> skipSawTerminalState.set(
                    skippedModel.isFirstRunWizardCompleted()
                            || skippedWizard.hasOutcome()));

            skippedWizard.skip();

            assertThat(skipSawTerminalState)
                    .as("the Skip callback runs before either terminal state")
                    .isFalse();
            assertThat(skippedModel.isFirstRunWizardCompleted()).isTrue();
            assertThat(skippedWizard.hasOutcome()).isTrue();
            assertThat(skippedWizard.wasFinished()).isFalse();

            Preferences failedPreferences = newPreferences("failedOutcomeOrdering");
            SettingsModel failedModel = new SettingsModel(failedPreferences);
            FirstRunWizard failedWizard = new FirstRunWizard(
                    SettingsCatalogue.create(), failedModel, null);
            failedWizard.setOnFinished(_ -> {
                throw new IllegalStateException("ASIO control panel rejected the setup");
            });

            failedWizard.finish();

            assertThat(failedModel.isFirstRunWizardCompleted())
                    .as("a throwing apply callback never publishes completion")
                    .isFalse();
            assertThat(new SettingsModel(failedPreferences)
                    .isFirstRunWizardCompleted())
                    .as("the failed callback does not persist completion either")
                    .isFalse();
            assertThat(failedWizard.hasOutcome()).isFalse();
            assertThat(failedWizard.isOutcomeErrorVisible()).isTrue();
            assertThat(failedWizard.outcomeErrorText())
                    .contains("ASIO control panel rejected the setup")
                    .contains("Finish again");

            SettingsModel abandonedModel = newModel();
            FirstRunWizard abandonedWizard = new FirstRunWizard(
                    SettingsCatalogue.create(), abandonedModel, null);
            abandonedWizard.close();
            abandonedWizard.skipIfNoOutcome();
            assertThat(abandonedModel.isFirstRunWizardCompleted())
                    .as("plain teardown is abandonment, not a host dismissal outcome")
                    .isFalse();
            assertThat(abandonedWizard.hasOutcome()).isFalse();
            return null;
        });
    }

    /**
     * Story 309 review P1 — an abnormal close ([X] or the host dialog's
     * header close glyph) triggers neither {@code finish()} nor
     * {@code skip()}; the host's {@code skipIfNoOutcome()} safety net
     * must treat the dismissal as Skip so the first-run flag is still
     * set and the wizard never auto-nags on later launches.
     */
    @Test
    void abnormalCloseWithoutAnOutcomeShouldCountAsSkip() throws Exception {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            FirstRunWizard wizard = new FirstRunWizard(
                    SettingsCatalogue.create(), model, null);
            AtomicInteger skips = new AtomicInteger();
            wizard.setOnSkipped(skips::incrementAndGet);

            assertThat(wizard.hasOutcome())
                    .as("[X]-dismissal records no outcome of its own")
                    .isFalse();
            // The host-side abnormal-close hook: showSetupWizard calls
            // this after showAndWait() returns.
            wizard.skipIfNoOutcome();

            assertThat(wizard.hasOutcome()).isTrue();
            assertThat(model.isFirstRunWizardCompleted())
                    .as("the outcome-less path still sets the flag — the "
                            + "wizard never auto-nags")
                    .isTrue();
            assertThat(FirstRunWizard.shouldAutoShow(model)).isFalse();
            assertThat(skips.get())
                    .as("abnormal close routed through the Skip path")
                    .isEqualTo(1);

            // Idempotent — a second safety-net call is a no-op.
            wizard.skipIfNoOutcome();
            assertThat(skips.get()).isEqualTo(1);

            // And after a real Finish the safety net must NOT fire Skip.
            SettingsModel finishedModel = newModel();
            FirstRunWizard finishedWizard = new FirstRunWizard(
                    SettingsCatalogue.create(), finishedModel, null);
            AtomicBoolean skippedAfterFinish = new AtomicBoolean();
            finishedWizard.setOnSkipped(() -> skippedAfterFinish.set(true));
            finishedWizard.finish();
            finishedWizard.skipIfNoOutcome();
            assertThat(finishedWizard.hasOutcome()).isTrue();
            assertThat(skippedAfterFinish)
                    .as("a finished wizard's safety net is a no-op")
                    .isFalse();
            return null;
        });
    }

    @Test
    void skippingShouldSetTheFlagAndApplyNothing() throws Exception {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            FirstRunWizard wizard = new FirstRunWizard(
                    SettingsCatalogue.create(), model, null);
            AtomicBoolean skipped = new AtomicBoolean();
            AtomicBoolean finishedRan = new AtomicBoolean();
            wizard.setOnSkipped(() -> skipped.set(true));
            wizard.setOnFinished(_ -> finishedRan.set(true));

            wizard.skip();

            assertThat(model.isFirstRunWizardCompleted())
                    .as("skipping also sets the flag — the wizard never "
                            + "auto-shows twice")
                    .isTrue();
            assertThat(skipped).isTrue();
            assertThat(finishedRan)
                    .as("skip applies nothing — the finish path never runs")
                    .isFalse();
            return null;
        });
    }

    @Test
    void generalStartupShouldCarryTheReopenSeam() throws Exception {
        runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            AtomicBoolean opened = new AtomicBoolean();
            dialog.setOnRunSetupWizard(() -> opened.set(true));

            dialog.selectCategory("general");
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            Node node = dialog.getDialogPane().lookup("#settings-run-setup-wizard");
            assertThat(node)
                    .as("General ▸ Startup carries \"Run setup wizard…\"")
                    .isInstanceOf(Button.class);

            ((Button) node).fire();
            assertThat(opened)
                    .as("the button routes through the dialog's re-open seam")
                    .isTrue();
            return null;
        });
    }
}
