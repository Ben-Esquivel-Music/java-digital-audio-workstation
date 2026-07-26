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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SettingsDialog.applySettings()} contract over the story-306
 * Rail &amp; Pane shell: the {@code SettingsChangeListener} is notified
 * after every apply, values edited through the shell's generic
 * {@link SettingRow}s are persisted to {@link SettingsModel} <em>before</em>
 * the listener runs, and the story-294 {@link UiEvent.SettingsApplied}
 * publish stays well-formed. Pre-306 these tests leaned on the old
 * dialog's unconditional re-write of its seeded combo values; under the
 * §6.2 write-on-dirty contract each write assertion drives a real shell
 * row edit instead.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingsDialogTest {

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
                .node("settingsDialogTest_" + System.nanoTime());
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

    @Test
    void applySettingsShouldInvokeChangeListener() throws Exception {
        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        AtomicReference<SettingsModel> receivedModel = new AtomicReference<>();

        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            dialog.setSettingsChangeListener(received -> {
                listenerInvoked.set(true);
                receivedModel.set(received);
            });
            settingRow(dialog, "audio", "audio.applyLatencyCompensation")
                    .setValue(!m.isApplyLatencyCompensation());
            dialog.applySettings();
            return m;
        });

        assertThat(listenerInvoked.get()).isTrue();
        assertThat(receivedModel.get()).isNotNull();
        assertThat(receivedModel.get()).isSameAs(model);
    }

    @Test
    void applySettingsShouldNotFailWithoutListener() throws Exception {
        SettingsModel model = runOnFxThread(() -> {
            SettingsModel m = newModel();
            SettingsDialog dialog = new SettingsDialog(m);
            boolean edited = !m.isApplyLatencyCompensation();
            settingRow(dialog, "audio", "audio.applyLatencyCompensation").setValue(edited);
            dialog.applySettings();
            return m;
        });

        // No listener registered — the whole write path must still run.
        assertThat(model.isApplyLatencyCompensation())
                .isNotEqualTo(newModel().isApplyLatencyCompensation());
    }

    @Test
    void applySettingsShouldPersistValuesBeforeNotifyingListener() throws Exception {
        AtomicReference<Double> tempoAtCallback = new AtomicReference<>();

        runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            dialog.setSettingsChangeListener(m -> tempoAtCallback.set(m.getDefaultTempo()));
            // The TEXT row delivers a raw String; the pending 150 must be
            // written to the model BEFORE the listener observes it.
            settingRow(dialog, "project", "project.defaultTempo").setValue("150");
            dialog.applySettings();
            return null;
        });

        assertThat(tempoAtCallback.get()).isNotNull();
        assertThat(tempoAtCallback.get())
                .isCloseTo(150.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void listenerShouldReceiveUpdatedUiScale() throws Exception {
        AtomicReference<Double> scaleAtCallback = new AtomicReference<>();

        runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(newModel());
            dialog.setSettingsChangeListener(m -> scaleAtCallback.set(m.getUiScale()));
            // SLIDER rows carry Double pending values (§6.2 write-on-dirty).
            settingRow(dialog, "appearance", "appearance.uiScale").setValue(1.5);
            dialog.applySettings();
            return null;
        });

        assertThat(scaleAtCallback.get()).isNotNull();
        assertThat(scaleAtCallback.get())
                .isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.01));
    }

    /**
     * Story 294 — the PRODUCER side of the appearance-settings migration: applying
     * the dialog now publishes a {@link UiEvent.SettingsApplied} onto the shared
     * {@code EventBus} instead of poking the three managers directly (Control
     * Synchronization Design Book §6.7). {@code SettingsSurfaceWiringTest} proves
     * the subscriber side; this proves the dialog actually emits a well-formed
     * event with the right values in the right record order.
     *
     * <p>Discriminating against an arg-swap or a dropped publish: {@code themeId}
     * must round-trip through {@link ThemeManager.Theme#valueOf} and
     * {@code densityId} through {@link DensityMode#valueOf}, so swapping the two
     * (a {@code DensityMode} name landing in {@code themeId}) would fail. The
     * expected values are the live-manager values the story-306 shell seeds its
     * appearance rows from, captured on the FX thread immediately before the
     * apply so they cannot drift — with zero pending edits the publish is
     * unconditional and carries the managers' current values.</p>
     */
    @Test
    void applySettingsPublishesWellFormedSettingsAppliedEvent() throws Exception {
        EventBus previous = EventBusPublisher.getDefault();
        // ON_UI_THREAD subscribers run inline on the publish thread — no toolkit
        // needed for the capture (mirrors SettingsSurfaceWiringTest's bus).
        DefaultEventBus bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        AtomicReference<UiEvent.SettingsApplied> captured = new AtomicReference<>();
        bus.on(UiEvent.SettingsApplied.class, DispatchMode.ON_UI_THREAD, captured::set);
        EventBusPublisher.setDefault(bus);

        AtomicReference<String> expectedTheme = new AtomicReference<>();
        AtomicReference<String> expectedDensity = new AtomicReference<>();
        AtomicBoolean expectedMotion = new AtomicBoolean();
        try {
            runOnFxThread(() -> {
                // The shell seeds its rows from the live managers; capture
                // those exact values so the assertions can't drift from the seed.
                expectedTheme.set(ThemeManager.getDefault().getActiveTheme().name());
                expectedDensity.set(DensityManager.getDefault().getActiveDensity().name());
                expectedMotion.set(MotionManager.getDefault().isReduceMotion());

                SettingsDialog dialog = new SettingsDialog(newModel());
                dialog.applySettings();
                return null;
            });

            // Delivery is async on a bus worker thread — poll the captured state.
            waitUntil(() -> captured.get() != null);
            UiEvent.SettingsApplied event = captured.get();
            assertThat(event).as("applySettings publishes a SettingsApplied event").isNotNull();
            assertThat(event.timestamp()).isNotNull();
            assertThat(event.themeId()).isEqualTo(expectedTheme.get());
            assertThat(event.densityId()).isEqualTo(expectedDensity.get());
            assertThat(event.reduceMotion()).isEqualTo(expectedMotion.get());
            // Right record order: themeId parses as a Theme, densityId as a DensityMode.
            assertThat(ThemeManager.Theme.valueOf(event.themeId())).isNotNull();
            assertThat(DensityMode.valueOf(event.densityId())).isNotNull();
        } finally {
            EventBusPublisher.setDefault(previous);
            bus.close();
        }
    }

    /** Polls {@code condition} up to five seconds; returns as soon as it holds. */
    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
