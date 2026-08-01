package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 309 — §5.7 tier 4, the ONE guarded bulk action: General ▸ Reset
 * &amp; profiles ▸ "Restore all defaults" prompts for confirmation
 * (injectable supplier seam — never a real modal here); confirming
 * resets EVERY descriptor to its default and drives the SAME
 * {@code applySettings()} path as manual edits (persisted defaults are
 * asserted, not just pending state); cancelling changes nothing at all.
 * Every other reset tier (per-setting 306, per-group 307, per-category
 * 308) stays unguarded and pending-only.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RestoreAllDefaultsIsGuardedTest {

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
        Preferences prefs = Preferences.userRoot()
                .node("restoreAllDefaultsIsGuardedTest_" + System.nanoTime());
        return new SettingsModel(prefs);
    }

    @Test
    void restoreAllShouldPromptAndOnlyApplyDefaultsOnConfirm() throws Exception {
        runOnFxThread(() -> {
            // Persist NON-defaults across several categories BEFORE the
            // dialog opens, so the rows seed dirty-relative-to-default.
            SettingsModel model = newModel();
            model.setDefaultTempo(150.0);
            model.setUiScale(2.0);
            model.setBufferSize(512);
            DawAction rebindable = DawAction.values()[0];
            KeyCombination custom = new KeyCodeCombination(
                    KeyCode.F24, KeyCombination.SHORTCUT_DOWN);
            model.getKeyBindingManager().setBinding(rebindable, custom);
            // A NULL-default action too (nine DawActions ship unbound):
            // restore-all must CLEAR a custom binding here — falling back
            // to the persisted binding would let it silently survive.
            DawAction nullDefaultAction = DawAction.TOGGLE_PRE_ROLL;
            assertThat(nullDefaultAction.defaultBinding())
                    .as("precondition: TOGGLE_PRE_ROLL has no default binding")
                    .isNull();
            KeyCombination customOnNullDefault = new KeyCodeCombination(
                    KeyCode.F23, KeyCombination.SHORTCUT_DOWN);
            model.getKeyBindingManager().setBinding(
                    nullDefaultAction, customOnNullDefault);

            SettingsDialog dialog = new SettingsDialog(model);
            AtomicInteger prompts = new AtomicInteger();
            dialog.setRestoreAllConfirm(() -> {
                prompts.incrementAndGet();
                return false; // CANCEL
            });

            // Realize General so the Reset & profiles affordance is live.
            // The DialogPane already sits in the dialog's own scene —
            // applyCss/layout on it attach the skins for the lookup.
            dialog.selectCategory("general");
            dialog.getDialogPane().applyCss();
            dialog.getDialogPane().layout();
            Node node = dialog.getDialogPane().lookup("#settings-restore-all-defaults");
            assertThat(node)
                    .as("General ▸ Reset & profiles carries the tier-4 action")
                    .isInstanceOf(Button.class);
            Button restoreAll = (Button) node;

            // ── Cancel: the action PROMPTED and nothing changed. ──
            restoreAll.fire();
            assertThat(prompts.get())
                    .as("the tier-4 action is guarded by a confirm")
                    .isEqualTo(1);
            assertThat(model.getDefaultTempo()).isCloseTo(150.0, within(0.01));
            assertThat(model.getUiScale()).isCloseTo(2.0, within(0.01));
            assertThat(model.getBufferSize()).isEqualTo(512);
            assertThat(model.getKeyBindingManager().getBinding(rebindable))
                    .contains(custom);
            assertThat(model.getKeyBindingManager().getBinding(nullDefaultAction))
                    .contains(customOnNullDefault);
            SettingsShell shell = dialog.getShell();
            assertThat(shell.pendingEdits())
                    .as("cancel leaves not even pending state behind")
                    .isEmpty();

            // ── Confirm: every descriptor resets AND persists. ──
            dialog.setRestoreAllConfirm(() -> {
                prompts.incrementAndGet();
                return true; // CONFIRM
            });
            restoreAll.fire();
            assertThat(prompts.get()).isEqualTo(2);

            SettingsModel defaults = newModel();
            assertThat(model.getDefaultTempo())
                    .as("project.defaultTempo persisted back to default")
                    .isCloseTo(defaults.getDefaultTempo(), within(0.01));
            assertThat(model.getUiScale())
                    .as("appearance.uiScale persisted back to default")
                    .isCloseTo(defaults.getUiScale(), within(0.01));
            assertThat(model.getBufferSize())
                    .as("audio.bufferSize persisted back to default")
                    .isEqualTo(defaults.getBufferSize());
            assertThat(model.getKeyBindingManager().getBinding(rebindable))
                    .as("the customized key binding persisted back to its default")
                    .isEqualTo(Optional.ofNullable(rebindable.defaultBinding()));
            assertThat(model.getKeyBindingManager().getBinding(nullDefaultAction))
                    .as("a custom binding on a null-default action is EMPTY "
                            + "after restore-all — persisted, not just pending")
                    .isEmpty();
            assertThat(shell.settingRow("keybinding." + nullDefaultAction.name())
                    .orElseThrow().getValue())
                    .as("the null-default key row reads its (null) default")
                    .isNull();

            // Spot-check rows across categories: every row reads its
            // descriptor default (post-apply the shell re-seeded from the
            // persisted authorities) and no pending edit survives.
            for (String id : new String[] {
                    "audio.bufferSize", "appearance.uiScale", "project.defaultTempo",
                    "metronome.clickGain", "backups.keepDaily", "plugins.scanPaths"}) {
                SettingRow row = shell.settingRow(id).orElseThrow();
                assertThat(row.getValue())
                        .as("row %s reads its descriptor default after restore-all", id)
                        .isEqualTo(row.descriptor().defaultValue());
            }
            assertThat(shell.pendingEdits())
                    .as("the tier-4 restore drove the real Apply path — "
                            + "nothing is left pending")
                    .isEmpty();
            assertThat(shell.dirtyProperty().get()).isFalse();
            return null;
        });
    }

    /**
     * The reset tiers below tier 4 stay unguarded: a per-category reset
     * never consults the confirm supplier.
     */
    @Test
    void lowerResetTiersShouldStayUnguarded() throws Exception {
        runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            AtomicInteger prompts = new AtomicInteger();
            dialog.setRestoreAllConfirm(() -> {
                prompts.incrementAndGet();
                return false;
            });

            dialog.getShell().resetCategory("appearance");
            dialog.getShell().resetGroup("project", "defaults");
            assertThat(prompts.get())
                    .as("tiers 1–3 never prompt (§5.7 — only the bulk "
                            + "restore-all is guarded)")
                    .isZero();
            return null;
        });
    }
}
