package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Startup and dialog entry points share the one authoritative audio-settings model. */
@ExtendWith(JavaFxToolkitExtension.class)
class MainControllerAudioSettingsWiringTest {

    @Test
    void startupAppliesPersistedAudioConfigurationOnAVirtualThread() throws Exception {
        SettingsModel model = Story307TestSupport.model("startupAudioConfiguration");
        model.setAudioBackend("WASAPI (Exclusive)");
        model.setAudioInputDevice("Studio In");
        model.setAudioOutputDevice("Studio Out");
        model.setSampleRate(96_000);
        model.setBufferSize(256);
        model.setBitDepth(24);
        model.setWorkerPoolSize(6);
        model.setSrcQuality(QualityTier.HIGH);
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();

        Thread worker = MainController.applyStartupAudioSettings(model, controller);

        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).isFalse();
        assertThat(controller.applyThread.get()).isNotNull();
        assertThat(controller.applyThread.get().isVirtual()).isTrue();
        assertThat(controller.lastRequest.get()).isEqualTo(new AudioEngineController.Request(
                "WASAPI (Exclusive)", "Studio In", "Studio Out",
                SampleRate.HZ_96000, 256, 24, 6));
        assertThat(controller.lastSrcQuality).hasValue(QualityTier.HIGH);
    }

    @Test
    void blankPersistedBackendKeepsTheProvisionedBackendNotTheHonestActiveOne()
            throws Exception {
        // Story 316 review: at startup nothing is streaming, so the honest
        // getActiveBackendName() answers BACKEND_NONE. A blank persisted
        // backend means "keep what is provisioned", which must therefore
        // resolve through getProvisionedBackendName() — otherwise startup
        // would apply a "None" backend and wipe the provisioned ladder.
        SettingsModel model = Story307TestSupport.model("startupBlankBackend");
        model.setAudioBackend("");
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.activeBackend = AudioEngineController.BACKEND_NONE;
        controller.provisionedBackend = "ASIO";

        Thread worker = MainController.applyStartupAudioSettings(model, controller);

        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(controller.lastRequest.get().backendName())
                .as("a blank persisted backend resolves to the PROVISIONED backend")
                .isEqualTo("ASIO");
    }

    @Test
    void startupConfigurationFailurePostsAnActionableErrorThroughTheSeam()
            throws Exception {
        SettingsModel model = Story307TestSupport.model("startupFailureNotification");
        model.setAudioBackend("ASIO");
        model.setAudioInputDevice("Studio Interface In");
        model.setAudioOutputDevice("Studio Interface Out");
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failNextConfiguration = true;
        AtomicReference<NotificationLevel> level = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        AtomicReference<String> actionLabel = new AtomicReference<>();
        AtomicReference<Runnable> action = new AtomicReference<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        AtomicInteger settingsOpens = new AtomicInteger();

        Thread worker = MainController.applyStartupAudioSettings(
                model,
                controller,
                (shownLevel, shownMessage, shownActionLabel, shownAction) -> {
                    producerThread.set(Thread.currentThread());
                    level.set(shownLevel);
                    message.set(shownMessage);
                    actionLabel.set(shownActionLabel);
                    action.set(shownAction);
                },
                settingsOpens::incrementAndGet);

        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).isFalse();
        assertThat(producerThread.get()).isSameAs(worker);
        assertThat(producerThread.get().isVirtual()).isTrue();
        assertThat(level).hasValue(NotificationLevel.ERROR);
        assertThat(message.get())
                .contains("ASIO", "Studio Interface In", "Studio Interface Out",
                        "configuration failed")
                .doesNotContain(IllegalStateException.class.getName());
        assertThat(actionLabel).hasValue("Open Audio Settings");
        assertThat(action.get()).isNotNull();

        action.get().run();
        assertThat(settingsOpens).hasValue(1);
    }

    @Test
    void settingsDialogUsesTheModelObservedByLiveRuntimeConsumers() throws Exception {
        SettingsModel shared = Story307TestSupport.model("sharedMainSettingsModel");
        MainController mainController = new MainController();
        setField(mainController, "settingsModel", shared);

        SettingsDialog dialog = Story307TestSupport.onFx(mainController::createSettingsDialog);

        assertThat(dialog.getModel()).isSameAs(shared);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
