package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

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
