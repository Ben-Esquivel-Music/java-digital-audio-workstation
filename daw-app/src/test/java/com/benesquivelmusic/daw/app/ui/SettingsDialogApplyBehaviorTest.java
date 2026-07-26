package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 306 — the behavioural survivors of the retired
 * {@code SettingsDialogUnchangedTest} (the story-305 "no visible change"
 * guard). Story 306 IS the visible change, so the tab-title and pinned
 * label-string tests died with the {@code TabPane}; what must NOT change
 * is the Apply write path, re-proven here over the Rail &amp; Pane shell:
 * an editless Apply round-trips the canonical model defaults, the tempo
 * TEXT row keeps the old silent-drop semantics (out-of-range and NaN
 * never reach the model, in-range text is written), and the story-294
 * {@link UiEvent.SettingsApplied} publish stays well-formed.
 *
 * <p>The old dialog re-wrote its seeded combo values on every Apply;
 * under the §6.2 write-on-dirty contract an untouched dialog writes
 * nothing at all — the defaults round-trip assertion now pins that an
 * editless Apply cannot corrupt the model. The tempo edits go through
 * the generic {@code project.defaultTempo} {@link SettingRow} (whose TEXT
 * editor delivers raw Strings), replacing the old Project-tab
 * {@code TextField} traversal.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingsDialogApplyBehaviorTest {

    private static final String TEMPO_ID = "project.defaultTempo";

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
                .node("settingsDialogApplyBehaviorTest_" + System.nanoTime());
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

    /**
     * The generic row rendering {@code project.defaultTempo} — the
     * replacement for the old "the Project tab's only TextField" helper.
     */
    private static SettingRow tempoRow(SettingsDialog dialog) {
        return settingRow(dialog, "project", TEMPO_ID);
    }

    @Test
    void applyWithUntouchedRowsShouldKeepModelDefaults() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            dialog.applySettings();
            return m;
        });

        // The rows seed from the live model, so applying without edits
        // must round-trip the canonical defaults (compared against a fresh
        // model over an empty node, never literals). Under write-on-dirty
        // nothing is written here — this pins exactly that.
        SettingsModel defaults = newModel();
        assertThat(model.getSampleRate()).isEqualTo(defaults.getSampleRate());
        assertThat(model.getBitDepth()).isEqualTo(defaults.getBitDepth());
        assertThat(model.getBufferSize()).isEqualTo(defaults.getBufferSize());
        assertThat(model.getAutoSaveIntervalSeconds())
                .isEqualTo(defaults.getAutoSaveIntervalSeconds());
        assertThat(model.getDefaultTempo())
                .isCloseTo(defaults.getDefaultTempo(), within(0.01));
        assertThat(model.isUseJournaledPersistence())
                .isEqualTo(defaults.isUseJournaledPersistence());
        assertThat(model.getUiScale()).isCloseTo(defaults.getUiScale(), within(0.01));
        assertThat(model.getPluginScanPaths()).isEqualTo(defaults.getPluginScanPaths());
    }

    @Test
    void applyShouldSilentlyDropOutOfRangeTempo() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            tempoRow(dialog).setValue("1000");
            dialog.applySettings();
            return m;
        });

        // The descriptor-validator delegation must keep the old semantics:
        // an out-of-range tempo is dropped silently, the model keeps its
        // default (120.0).
        assertThat(model.getDefaultTempo())
                .isCloseTo(newModel().getDefaultTempo(), within(0.01));
    }

    @Test
    void applyShouldSilentlyDropNaNTempo() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            tempoRow(dialog).setValue("NaN");
            dialog.applySettings();
            return m;
        });

        // The setter guard's range comparisons are both false for NaN, so
        // it cannot reject it — the call site must. Pre-305 the inline
        // range check dropped NaN silently; the model keeps its default.
        assertThat(model.getDefaultTempo())
                .isCloseTo(newModel().getDefaultTempo(), within(0.01));
    }

    @Test
    void applyShouldWriteInRangeTempo() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            tempoRow(dialog).setValue("150");
            dialog.applySettings();
            return m;
        });

        assertThat(model.getDefaultTempo()).isCloseTo(150.0, within(0.01));
    }

    /**
     * The story-294 producer contract must survive the shell swap: Apply
     * still publishes one well-formed {@link UiEvent.SettingsApplied} with
     * the manager-seeded values (the EventBus-swap capture idiom from
     * {@code SettingsDialogTest#applySettingsPublishesWellFormedSettingsAppliedEvent}).
     */
    @Test
    void applyShouldPublishWellFormedSettingsAppliedEvent() throws Exception {
        EventBus previous = EventBusPublisher.getDefault();
        // ON_UI_THREAD subscribers run inline on the publish thread — no
        // toolkit needed for the capture.
        DefaultEventBus bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        AtomicReference<UiEvent.SettingsApplied> captured = new AtomicReference<>();
        bus.on(UiEvent.SettingsApplied.class, DispatchMode.ON_UI_THREAD, captured::set);
        EventBusPublisher.setDefault(bus);

        AtomicReference<String> expectedTheme = new AtomicReference<>();
        AtomicReference<String> expectedDensity = new AtomicReference<>();
        AtomicBoolean expectedMotion = new AtomicBoolean();
        try {
            runOnFxThread(() -> {
                // The shell seeds its rows from the live managers;
                // capture those exact values so the assertions can't drift.
                expectedTheme.set(ThemeManager.getDefault().getActiveTheme().name());
                expectedDensity.set(DensityManager.getDefault().getActiveDensity().name());
                expectedMotion.set(MotionManager.getDefault().isReduceMotion());

                SettingsDialog dialog = new SettingsDialog(newModel());
                dialog.applySettings();
                return null;
            });

            // Delivery is async on a bus worker thread — poll the capture.
            waitUntil(() -> captured.get() != null);
            UiEvent.SettingsApplied event = captured.get();
            assertThat(event)
                    .as("applySettings still publishes a SettingsApplied event")
                    .isNotNull();
            assertThat(event.timestamp()).isNotNull();
            assertThat(event.themeId()).isEqualTo(expectedTheme.get());
            assertThat(event.densityId()).isEqualTo(expectedDensity.get());
            assertThat(event.reduceMotion()).isEqualTo(expectedMotion.get());
            // Right record order: themeId parses as a Theme, densityId as a
            // DensityMode — an arg swap fails here.
            assertThat(ThemeManager.Theme.valueOf(event.themeId())).isNotNull();
            assertThat(DensityMode.valueOf(event.densityId())).isNotNull();
        } finally {
            EventBusPublisher.setDefault(previous);
            bus.close();
        }
    }

    /**
     * Regression (story-306 fix): the {@link UiEvent.SettingsApplied}
     * publish is delivered asynchronously (the managers subscribe
     * {@code ON_UI_THREAD} — a later FX pulse), so the synchronous
     * post-apply {@code refreshFromPersisted()} must NOT re-seed the
     * appearance rows from the still-stale managers. The rows keep the
     * values the Apply just published, the shell reads clean against
     * them, and re-selecting the applied value stays clean. No bus is
     * installed here, so the managers never see the publish at all —
     * exactly the mid-flight window the last-applied overlay covers.
     */
    @Test
    void applyShouldKeepEditedAppearanceRowsAtTheirAppliedValues() throws Exception {
        runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            SettingsShell shell = dialog.getShell();
            SettingRow themeRow = settingRow(dialog, "appearance", ThemeManager.PREF_KEY);
            SettingRow motionRow = settingRow(dialog, "appearance", MotionManager.PREF_KEY);

            ThemeManager.Theme before = ThemeManager.getDefault().getActiveTheme();
            ThemeManager.Theme edited = Arrays.stream(ThemeManager.Theme.values())
                    .filter(theme -> theme != before)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Need at least two themes"));
            boolean motionEdited = !MotionManager.getDefault().isReduceMotion();

            themeRow.setValue(edited);
            motionRow.setValue(motionEdited);
            dialog.applySettings();

            assertThat(themeRow.getValue())
                    .as("theme row keeps the just-applied value (no snap-back "
                            + "to the not-yet-updated manager)")
                    .isEqualTo(edited);
            assertThat(motionRow.getValue())
                    .as("reduce-motion row keeps the just-applied value")
                    .isEqualTo(motionEdited);
            assertThat(shell.dirtyProperty().get())
                    .as("shell is clean against the applied values")
                    .isFalse();

            // The applied value is the new diff baseline: moving away is
            // dirty, re-selecting it is clean — never the stale manager value.
            themeRow.setValue(before);
            assertThat(shell.dirtyProperty().get())
                    .as("editing away from the applied theme is dirty")
                    .isTrue();
            themeRow.setValue(edited);
            assertThat(shell.dirtyProperty().get())
                    .as("re-selecting the applied theme is clean")
                    .isFalse();
            return null;
        });
    }

    /** Polls {@code condition} up to five seconds; returns as soon as it holds. */
    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
