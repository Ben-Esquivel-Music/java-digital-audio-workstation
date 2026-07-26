package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.ScrollPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 306 — dirty/apply generalised to <em>every</em> setting (Settings
 * View Design Book §6.2): an edit to any non-key-binding row makes the
 * shell dirty and enables the footer Apply; Apply writes the value through
 * {@link SettingsModel} and clears dirty; closing dirty runs the
 * Apply / Discard / Cancel prompt (via the package-private
 * {@code setDirtyClosePrompt} seam), where DISCARD closes without writing
 * and CANCEL vetoes the close.
 *
 * <p>The tests drive the real footer buttons ({@code Button#fire()} is a
 * no-op while disabled, so a fired Apply also proves it was enabled) and
 * the real {@code DIALOG_CLOSE_REQUEST} path — in JavaFX 26
 * {@code Dialog.close()} fires that event and aborts when it is consumed,
 * so the dialog's {@code setOnCloseRequest} guard is THE universal close
 * gate. Everything runs headless on the FX thread; no Stage is shown
 * ({@code HeavyweightDialog.close()} guards {@code stage.isShowing()}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DirtyApplyGeneralisedTest {

    private static final String LATENCY_ID = "audio.applyLatencyCompensation";

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
        assertThat(latch.await(5, TimeUnit.SECONDS))
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
        Preferences prefs = Preferences.userRoot()
                .node("dirtyApplyGeneralisedTest_" + System.nanoTime());
        return new SettingsModel(prefs);
    }

    /**
     * Walks a node graph collecting instances of {@code type}.
     * {@code ScrollPane} content is not a child until a skin attaches, so
     * it is followed explicitly (headless — no skins are realized).
     */
    private static <T extends Node> void collectInstances(Node node, Class<T> type, List<T> into) {
        if (node == null) {
            return;
        }
        if (type.isInstance(node)) {
            into.add(type.cast(node));
        }
        if (node instanceof ScrollPane scrollPane) {
            collectInstances(scrollPane.getContent(), type, into);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectInstances(child, type, into);
            }
        }
    }

    /**
     * Selects {@code categoryId} on the rail (attaching its pane to the
     * center scroll graph) and returns the {@link SettingRow} rendering
     * {@code settingId}.
     */
    private static SettingRow settingRow(SettingsDialog dialog, String categoryId,
                                         String settingId) {
        SettingsShell shell = dialog.getShell();
        shell.navRail().setSelectedCategoryId(categoryId);
        List<SettingRow> rows = new ArrayList<>();
        collectInstances(shell, SettingRow.class, rows);
        return rows.stream()
                .filter(row -> settingId.equals(row.descriptor().id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + settingId));
    }

    /** The shell footer's buttons (Cancel / Apply / OK after the spacer). */
    private static List<Button> footerButtons(SettingsDialog dialog) {
        Parent footer = (Parent) dialog.getShell().getBottom();
        List<Button> buttons = new ArrayList<>();
        for (Node child : footer.getChildrenUnmodifiable()) {
            if (child instanceof Button button) {
                buttons.add(button);
            }
        }
        assertThat(buttons).as("footer carries Cancel / Apply / OK").hasSize(3);
        return buttons;
    }

    /** Apply is the footer button that is neither the cancel nor the default button. */
    private static Button applyButton(SettingsDialog dialog) {
        return footerButtons(dialog).stream()
                .filter(button -> !button.isCancelButton() && !button.isDefaultButton())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Apply button in the footer"));
    }

    private static Button cancelButton(SettingsDialog dialog) {
        return footerButtons(dialog).stream()
                .filter(Button::isCancelButton)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Cancel button in the footer"));
    }

    /**
     * The full §6.2 flow over a NON-key-binding setting: an edit enables
     * the previously disabled Apply, firing Apply writes through
     * {@link SettingsModel} and clears dirty (Apply disables again), and a
     * dirty close with an injected DISCARD prompt closes without writing —
     * the model keeps the <em>applied</em> value, not the re-edited one.
     */
    @Test
    void editEnablesApplyApplyWritesThroughModelAndDiscardClosesWithoutWriting()
            throws Exception {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            SettingsDialog dialog = new SettingsDialog(model);
            SettingsShell shell = dialog.getShell();
            SettingRow row = settingRow(dialog, "audio", LATENCY_ID);
            boolean initial = model.isApplyLatencyCompensation();
            Button apply = applyButton(dialog);

            assertThat(shell.dirtyProperty().get()).as("clean at open").isFalse();
            assertThat(apply.isDisabled()).as("Apply disabled while clean").isTrue();

            // §6.2 — an edit to ANY setting (not just a key binding) is
            // held as a pending value; nothing is written yet.
            row.setValue(!initial);
            assertThat(shell.dirtyProperty().get()).as("edit marks the shell dirty").isTrue();
            assertThat(apply.isDisabled()).as("Apply enabled once dirty").isFalse();
            assertThat(model.isApplyLatencyCompensation())
                    .as("pending edit is not yet written")
                    .isEqualTo(initial);

            // The real footer path: fire() is a no-op on a disabled button,
            // so a successful write below also re-proves Apply was enabled.
            apply.fire();
            assertThat(model.isApplyLatencyCompensation())
                    .as("Apply writes the edit through SettingsModel")
                    .isEqualTo(!initial);
            assertThat(shell.dirtyProperty().get()).as("Apply clears dirty").isFalse();
            assertThat(apply.isDisabled()).as("Apply disabled again after Apply").isTrue();

            // Dirty again (back towards the pre-apply value), then close
            // via the footer Cancel with an injected DISCARD prompt.
            row.setValue(initial);
            assertThat(shell.dirtyProperty().get()).isTrue();
            AtomicInteger promptRuns = new AtomicInteger();
            dialog.setDirtyClosePrompt(() -> {
                promptRuns.incrementAndGet();
                return SettingsDialog.DirtyChoice.DISCARD;
            });
            cancelButton(dialog).fire();

            // Exactly once: DISCARD re-seeds the shell before the close
            // proceeds, so the close-request re-entry of the guard (JavaFX
            // 26 Dialog.close() always fires DIALOG_CLOSE_REQUEST) finds a
            // clean shell and must not prompt a second time.
            assertThat(promptRuns.get())
                    .as("dirty close ran the prompt exactly once")
                    .isEqualTo(1);
            assertThat(model.isApplyLatencyCompensation())
                    .as("DISCARD closes without writing — the applied value stands")
                    .isEqualTo(!initial);
            assertThat(shell.dirtyProperty().get())
                    .as("DISCARD drops the pending edits")
                    .isFalse();
            return null;
        });
    }

    /**
     * The [X]/Esc path surfaces as {@code DIALOG_CLOSE_REQUEST}; a CANCEL
     * choice from the prompt consumes it (the dialog stays open) and the
     * pending edit survives untouched. Consumption is asserted on the fired
     * PAYLOAD: the event is constructed with the dialog as its source and
     * the dialog's {@code EventHandlerManager} uses the dialog as its event
     * source, so {@code fixEventSource} (javafx-base 26,
     * {@code EventHandlerManager.java:238-243}) makes no defensive copy —
     * {@code isConsumed()} on this very object reflects the guard's veto.
     * No {@code getSource()} identity is asserted.
     */
    @Test
    void dirtyCloseRequestWithCancelChoiceIsVetoedAndKeepsPendingEdits() throws Exception {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            SettingsDialog dialog = new SettingsDialog(model);
            SettingRow row = settingRow(dialog, "audio", LATENCY_ID);
            boolean initial = model.isApplyLatencyCompensation();
            row.setValue(!initial);

            AtomicInteger promptRuns = new AtomicInteger();
            dialog.setDirtyClosePrompt(() -> {
                promptRuns.incrementAndGet();
                return SettingsDialog.DirtyChoice.CANCEL;
            });

            DialogEvent closeRequest =
                    new DialogEvent(dialog, DialogEvent.DIALOG_CLOSE_REQUEST);
            Event.fireEvent(dialog, closeRequest);

            assertThat(promptRuns.get()).as("dirty close request ran the prompt").isEqualTo(1);
            assertThat(closeRequest.isConsumed())
                    .as("CANCEL choice consumes the close request (dialog stays open)")
                    .isTrue();
            assertThat(dialog.getShell().pendingEdits())
                    .as("CANCEL keeps the pending edit intact")
                    .containsEntry(LATENCY_ID, !initial);
            assertThat(model.isApplyLatencyCompensation())
                    .as("nothing was written")
                    .isEqualTo(initial);
            return null;
        });
    }

    /**
     * Regression (story-306 fix): the tempo TEXT editor delivers raw
     * Strings while the persisted value is a boxed Double, so the §6.2
     * diff runs on the canonical comparison value — typing text
     * numerically equal to the persisted tempo must NOT leave the shell
     * stuck dirty (nor the ↺ marker showing), while a real numeric edit
     * and unparseable text remain pending.
     */
    @Test
    void numericTempoTextEqualToPersistedValueIsNotDirty() throws Exception {
        runOnFxThread(() -> {
            SettingsModel model = newModel();
            SettingsDialog dialog = new SettingsDialog(model);
            SettingsShell shell = dialog.getShell();
            SettingRow tempo = settingRow(dialog, "project", "project.defaultTempo");

            tempo.setValue("140");
            assertThat(shell.dirtyProperty().get())
                    .as("a real tempo edit is dirty")
                    .isTrue();
            assertThat(tempo.modifiedProperty().get())
                    .as("the reset marker shows for a real edit")
                    .isTrue();

            // Typing the persisted value back ("120.0") — the String must
            // compare equal to the boxed Double, not stick as dirty forever.
            tempo.setValue(String.valueOf(model.getDefaultTempo()));
            assertThat(shell.dirtyProperty().get())
                    .as("text numerically equal to the persisted tempo is not a pending edit")
                    .isFalse();
            assertThat(tempo.modifiedProperty().get())
                    .as("the reset marker agrees — the text equals the default")
                    .isFalse();

            // Unparseable text is a genuine (apply-time silently dropped) edit.
            tempo.setValue("not a tempo");
            assertThat(shell.dirtyProperty().get())
                    .as("unparseable text stays a pending edit")
                    .isTrue();
            return null;
        });
    }

    /** A clean close must not prompt — the guard only fires with pending edits. */
    @Test
    void cleanCloseDoesNotRunThePrompt() throws Exception {
        runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            AtomicInteger promptRuns = new AtomicInteger();
            dialog.setDirtyClosePrompt(() -> {
                promptRuns.incrementAndGet();
                return SettingsDialog.DirtyChoice.CANCEL;
            });
            cancelButton(dialog).fire();
            assertThat(promptRuns.get()).as("no prompt while clean").isZero();
            return null;
        });
    }
}
