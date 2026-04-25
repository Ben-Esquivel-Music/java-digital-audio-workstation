package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionConfig;
import com.benesquivelmusic.daw.core.spatial.objectbased.AtmosSessionValidator;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class AtmosSessionConfigDialogTest {

    private AtmosSessionConfigDialog createOnFxThread() throws Exception {
        AtomicReference<AtmosSessionConfigDialog> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new AtmosSessionConfigDialog());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private AtmosSessionConfigDialog createOnFxThread(AtmosSessionConfig config) throws Exception {
        AtomicReference<AtmosSessionConfigDialog> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new AtmosSessionConfigDialog(config));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRejectNullConfig() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                new AtmosSessionConfigDialog(null);
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        assertThat(thrown.get()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCreateWithDefaultConfig() throws Exception {
        AtmosSessionConfigDialog dialog = createOnFxThread();

        assertThat(dialog).isNotNull();
        assertThat(dialog.getConfig()).isNotNull();
        assertThat(dialog.getConfig().getLayout()).isEqualTo(SpeakerLayout.LAYOUT_7_1_4);
    }

    @Test
    void shouldExposeLayoutCombo() throws Exception {
        AtmosSessionConfigDialog dialog = createOnFxThread();

        assertThat(dialog.getLayoutCombo()).isNotNull();
        assertThat(dialog.getLayoutCombo().getItems()).hasSize(5);
        assertThat(dialog.getLayoutCombo().getItems()).contains("9.1.6", "7.1.4", "5.1.4", "5.1", "Stereo");
        assertThat(dialog.getLayoutCombo().getValue()).isEqualTo("7.1.4");
    }

    @Test
    void shouldExposeSampleRateCombo() throws Exception {
        AtmosSessionConfigDialog dialog = createOnFxThread();

        assertThat(dialog.getSampleRateCombo()).isNotNull();
        assertThat(dialog.getSampleRateCombo().getValue()).isEqualTo("48000");
    }

    @Test
    void shouldExposeBitDepthCombo() throws Exception {
        AtmosSessionConfigDialog dialog = createOnFxThread();

        assertThat(dialog.getBitDepthCombo()).isNotNull();
        assertThat(dialog.getBitDepthCombo().getValue()).isEqualTo("24");
    }

    @Test
    void shouldExposeTrackCountLabel() throws Exception {
        AtmosSessionConfigDialog dialog = createOnFxThread();

        assertThat(dialog.getTrackCountLabel()).isNotNull();
        assertThat(dialog.getTrackCountLabel().getText())
                .contains("0 / " + AtmosSessionValidator.MAX_TOTAL_TRACKS);
    }

    @Test
    void shouldShowValidationResult() throws Exception {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.R));
        AtmosSessionConfigDialog dialog = createOnFxThread(config);

        runOnFxThread(dialog::runValidation);

        assertThat(dialog.getValidationLabel().getText()).contains("valid");
    }

    @Test
    void shouldShowValidationErrors() throws Exception {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));
        AtmosSessionConfigDialog dialog = createOnFxThread(config);

        runOnFxThread(dialog::runValidation);

        assertThat(dialog.getValidationLabel().getText()).contains("Duplicate");
    }

    @Test
    void shouldApplyConfigFromDialogFields() throws Exception {
        AtmosSessionConfigDialog dialog = createOnFxThread();

        runOnFxThread(() -> {
            dialog.getSampleRateCombo().setValue("96000");
            dialog.getBitDepthCombo().setValue("32");
            dialog.applyToConfig();
        });

        assertThat(dialog.getConfig().getSampleRate()).isEqualTo(96000);
        assertThat(dialog.getConfig().getBitDepth()).isEqualTo(32);
    }

    @Test
    void shouldInitFromExistingConfig() throws Exception {
        AtmosSessionConfig config = new AtmosSessionConfig(
                SpeakerLayout.LAYOUT_5_1_4, 96000, 32);
        config.addBedChannel(new BedChannel("bed-L", SpeakerLabel.L));
        config.addAudioObject(new AudioObject("obj-1",
                new ObjectMetadata(0.5, 0.3, 0.1, 0.2, 0.8)));
        AtmosSessionConfigDialog dialog = createOnFxThread(config);

        assertThat(dialog.getLayoutCombo().getValue()).isEqualTo("5.1.4");
        assertThat(dialog.getSampleRateCombo().getValue()).isEqualTo("96000");
        assertThat(dialog.getBitDepthCombo().getValue()).isEqualTo("32");
        assertThat(dialog.getBedChannelContainer().getChildren()).hasSize(1);
        assertThat(dialog.getObjectContainer().getChildren()).hasSize(1);
    }

    @Test
    void shouldNotifyExportRequestListener() throws Exception {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        AtmosSessionConfigDialog dialog = createOnFxThread(config);

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        AtomicReference<AtmosSessionConfig> receivedConfig = new AtomicReference<>();

        runOnFxThread(() -> {
            dialog.setExportRequestListener(c -> {
                listenerCalled.set(true);
                receivedConfig.set(c);
            });
            dialog.applyConfigAndExport();
        });

        assertThat(listenerCalled.get()).isTrue();
        assertThat(receivedConfig.get()).isSameAs(config);
    }

    @Test
    void shouldBlockExportOnValidationFailure() throws Exception {
        AtmosSessionConfig config = new AtmosSessionConfig();
        config.addBedChannel(new BedChannel("t1", SpeakerLabel.L));
        config.addBedChannel(new BedChannel("t2", SpeakerLabel.L));
        AtmosSessionConfigDialog dialog = createOnFxThread(config);

        AtomicBoolean listenerCalled = new AtomicBoolean(false);

        runOnFxThread(() -> {
            dialog.setExportRequestListener(c -> listenerCalled.set(true));
            dialog.applyConfigAndExport();
        });

        assertThat(listenerCalled.get()).isFalse();
        assertThat(dialog.getValidationLabel().getText()).contains("Duplicate");
    }
}
