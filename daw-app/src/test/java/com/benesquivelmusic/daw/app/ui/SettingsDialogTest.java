package com.benesquivelmusic.daw.app.ui;

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
}
