package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
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
 * Story 305 — the no-UI-change guard. Backing {@code SettingsDialog}'s
 * labels and the tempo validator with {@code SettingsCatalogue}
 * descriptors must be invisible: the five tabs keep their exact titles,
 * every pinned label string still renders byte-identical, Apply still
 * writes the {@code SettingsModel} with the old silent-drop tempo
 * semantics, and the well-formed {@link UiEvent.SettingsApplied} publish
 * (story 294) still fires. If anything here changes, the story is wrong.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingsDialogUnchangedTest {

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
                .node("settingsDialogUnchangedTest_" + System.nanoTime());
        return new SettingsModel(prefs);
    }

    /**
     * Walks a tab-content graph collecting every {@link Labeled} text
     * (covers both {@code Label} and {@code CheckBox}). {@code ScrollPane}
     * content is not a child until a skin attaches, so it is followed
     * explicitly.
     */
    private static void collectLabeledTexts(Node node, List<String> into) {
        if (node == null) {
            return;
        }
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            into.add(labeled.getText());
        }
        if (node instanceof ScrollPane scrollPane) {
            collectLabeledTexts(scrollPane.getContent(), into);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabeledTexts(child, into);
            }
        }
    }

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

    /** The Project tab carries exactly one {@code TextField}: the tempo field. */
    private static TextField tempoField(SettingsDialog dialog) {
        TabPane tabPane = (TabPane) dialog.getDialogPane().getContent();
        Tab projectTab = tabPane.getTabs().stream()
                .filter(tab -> "Project".equals(tab.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Project tab"));
        List<TextField> fields = new ArrayList<>();
        collectInstances(projectTab.getContent(), TextField.class, fields);
        assertThat(fields).as("the Project tab's only TextField is the tempo field").hasSize(1);
        return fields.get(0);
    }

    @Test
    void fiveTabsShouldKeepTheirExactTitlesInOrder() throws Exception {
        List<String> titles = runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            TabPane tabPane = (TabPane) dialog.getDialogPane().getContent();
            return tabPane.getTabs().stream().map(Tab::getText).toList();
        });

        assertThat(titles).containsExactly(
                "Audio", "Project", "Appearance", "Key Bindings", "Plugins");
    }

    @Test
    void pinnedLabelStringsShouldStillRenderByteIdentical() throws Exception {
        List<String> texts = runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            TabPane tabPane = (TabPane) dialog.getDialogPane().getContent();
            List<String> collected = new ArrayList<>();
            for (Tab tab : tabPane.getTabs()) {
                collectLabeledTexts(tab.getContent(), collected);
            }
            return collected;
        });

        assertThat(texts).contains(
                "Sample Rate (Hz):",
                "Bit Depth:",
                "Buffer Size (frames):",
                "Auto-Save Interval (seconds):",
                "Default Tempo (BPM):",
                "Use journaled persistence (write-ahead journal & crash recovery)",
                "Theme:",
                "UI Scale:",
                "Density:",
                "Reduce Motion",
                "Plugin Scan Paths:",
                // The untouched blanket hint — replacing it with per-setting
                // apply-class truth on screen is story 306/307, not 305.
                "Changes to audio settings may require a restart.");
    }

    @Test
    void applyWithUntouchedControlsShouldKeepModelDefaults() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            dialog.applySettings();
            return m;
        });

        // The controls seed from the live model, so applying without edits
        // must round-trip the canonical defaults (compared against a fresh
        // model over an empty node, never literals).
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
    void applyShouldStillSilentlyDropOutOfRangeTempo() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            tempoField(dialog).setText("1000");
            dialog.applySettings();
            return m;
        });

        // The descriptor-validator swap must keep the old semantics: an
        // out-of-range tempo is dropped silently, the model keeps 120.0.
        assertThat(model.getDefaultTempo()).isCloseTo(120.0, within(0.01));
    }

    @Test
    void applyShouldStillSilentlyDropNaNTempo() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            tempoField(dialog).setText("NaN");
            dialog.applySettings();
            return m;
        });

        // The setter guard's range comparisons are both false for NaN, so
        // it cannot reject it — the call site must. Pre-305 the inline
        // range check dropped NaN silently; the model keeps 120.0.
        assertThat(model.getDefaultTempo()).isCloseTo(120.0, within(0.01));
    }

    @Test
    void applyShouldStillWriteInRangeTempo() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            tempoField(dialog).setText("150");
            dialog.applySettings();
            return m;
        });

        assertThat(model.getDefaultTempo()).isCloseTo(150.0, within(0.01));
    }

    /**
     * The story-294 producer contract must survive the label wiring: Apply
     * still publishes one well-formed {@link UiEvent.SettingsApplied} with
     * the manager-seeded values (the EventBus-swap capture idiom from
     * {@code SettingsDialogTest#applySettingsPublishesWellFormedSettingsAppliedEvent}).
     */
    @Test
    void applyShouldStillPublishWellFormedSettingsAppliedEvent() throws Exception {
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
                // The dialog seeds its controls from the live managers;
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
