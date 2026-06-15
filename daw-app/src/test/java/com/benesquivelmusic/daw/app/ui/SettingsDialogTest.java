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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class SettingsDialogTest {

    private <T> T runOnFxThread(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (error.get() != null) {
            throw error.get();
        }
        return ref.get();
    }

    @Test
    void applySettingsShouldInvokeChangeListener() throws Exception {
        AtomicBoolean listenerInvoked = new AtomicBoolean(false);
        AtomicReference<SettingsModel> receivedModel = new AtomicReference<>();

        runOnFxThread(() -> {
            Preferences prefs = Preferences.userRoot().node("settingsDialogTest_" + System.nanoTime());
            SettingsModel model = new SettingsModel(prefs);
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setSettingsChangeListener(m -> {
                listenerInvoked.set(true);
                receivedModel.set(m);
            });
            dialog.applySettings();
            return null;
        });

        assertThat(listenerInvoked.get()).isTrue();
        assertThat(receivedModel.get()).isNotNull();
    }

    @Test
    void applySettingsShouldNotFailWithoutListener() throws Exception {

        runOnFxThread(() -> {
            Preferences prefs = Preferences.userRoot().node("settingsDialogTest_" + System.nanoTime());
            SettingsModel model = new SettingsModel(prefs);
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.applySettings();
            return null;
        });
    }

    @Test
    void applySettingsShouldPersistValuesBeforeNotifyingListener() throws Exception {
        AtomicReference<Double> tempoAtCallback = new AtomicReference<>();

        runOnFxThread(() -> {
            Preferences prefs = Preferences.userRoot().node("settingsDialogTest_" + System.nanoTime());
            SettingsModel model = new SettingsModel(prefs);
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setSettingsChangeListener(m -> tempoAtCallback.set(m.getDefaultTempo()));
            dialog.applySettings();
            return null;
        });

        assertThat(tempoAtCallback.get()).isNotNull();
        assertThat(tempoAtCallback.get()).isCloseTo(120.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void listenerShouldReceiveUpdatedUiScale() throws Exception {
        AtomicReference<Double> scaleAtCallback = new AtomicReference<>();

        runOnFxThread(() -> {
            Preferences prefs = Preferences.userRoot().node("settingsDialogTest_" + System.nanoTime());
            SettingsModel model = new SettingsModel(prefs);
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setSettingsChangeListener(m -> scaleAtCallback.set(m.getUiScale()));
            dialog.applySettings();
            return null;
        });

        assertThat(scaleAtCallback.get()).isNotNull();
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
     * expected values are the manager defaults the dialog seeds its controls from,
     * captured on the FX thread immediately before the apply so they cannot drift.</p>
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
                // The dialog seeds its controls from the live managers; capture
                // those exact values so the assertions can't drift from the seed.
                expectedTheme.set(ThemeManager.getDefault().getActiveTheme().name());
                expectedDensity.set(DensityManager.getDefault().getActiveDensity().name());
                expectedMotion.set(MotionManager.getDefault().isReduceMotion());

                Preferences prefs = Preferences.userRoot().node("settingsDialogTest_" + System.nanoTime());
                SettingsModel model = new SettingsModel(prefs);
                SettingsDialog dialog = new SettingsDialog(model);
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
