package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Applying Audio rows obeys the descriptor apply classes off the FX thread. */
@ExtendWith(JavaFxToolkitExtension.class)
class EngineReconfiguresOnApplyTest {

    @Test
    void changedBufferReconfiguresActiveBackendOnAVirtualNonFxThread() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        SettingsModel model = Story307TestSupport.model("engineReconfigureBuffer");
        int changedBuffer = model.getBufferSize() == 256 ? 512 : 256;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });

        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.configurationCount.get()).isOne();
        assertThat(controller.lastRequest.get().bufferFrames()).isEqualTo(changedBuffer);
        assertThat(controller.lastRequest.get().backendName()).isEqualTo("Java Sound");
        assertThat(controller.applyThread.get().isVirtual()).isTrue();
        assertThat(controller.applyThread.get().getName())
                .startsWith("settings-audio-reconfigure");
    }

    @Test
    void backendOnlyPersistsAndShowsRestartWithoutLiveReconfigure() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        SettingsModel model = Story307TestSupport.model("engineReconfigureBackend");
        String changedBackend = model.getAudioBackend().equals("ASIO")
                ? "Java Sound" : "ASIO";

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            SettingsShell shell = dialog.getShell();
            shell.settingRow("audio.backend").orElseThrow().setValue(changedBackend);
            assertThat(shell.isRestartBannerVisible()).isTrue();
            dialog.applySettings();
            return null;
        });

        assertThat(model.getAudioBackend()).isEqualTo(changedBackend);
        assertThat(controller.configurationCount.get()).isZero();
    }

    @Test
    void backendAndBufferPersistFutureBackendButReconfigureCurrentBackendAndDevices()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        SettingsModel model = Story307TestSupport.model("engineReconfigureCombined");
        model.setAudioBackend("Java Sound");
        model.setAudioInputDevice("Current input");
        model.setAudioOutputDevice("Current output");
        int changedBuffer = model.getBufferSize() == 256 ? 512 : 256;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.backend").orElseThrow().setValue("ASIO");
            dialog.getShell().settingRow("audio.inputDevice").orElseThrow()
                    .setValue("Future input");
            dialog.getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue("Future output");
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });

        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                model::getAudioBackend, "ASIO", 5, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getAudioBackend()).isEqualTo("ASIO");
        assertThat(model.getAudioInputDevice()).isEqualTo("Future input");
        assertThat(model.getAudioOutputDevice()).isEqualTo("Future output");
        assertThat(controller.lastRequest.get().backendName()).isEqualTo("Java Sound");
        assertThat(controller.lastRequest.get().inputDeviceName()).isEqualTo("Current input");
        assertThat(controller.lastRequest.get().outputDeviceName()).isEqualTo("Current output");
    }

    @Test
    void backendAndEndpointChangesWaitForRestartWithoutReconfiguringCurrentBackend()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        SettingsModel model = Story307TestSupport.model("backendEndpointsRestartOnly");
        model.setAudioBackend("Java Sound");
        model.setAudioInputDevice("Current input");
        model.setAudioOutputDevice("Current output");

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.backend").orElseThrow().setValue("ASIO");
            dialog.getShell().settingRow("audio.inputDevice").orElseThrow()
                    .setValue("Future input");
            dialog.getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue("Future output");
            dialog.applySettings();
            return null;
        });

        assertThat(model.getAudioBackend()).isEqualTo("ASIO");
        assertThat(model.getAudioInputDevice()).isEqualTo("Future input");
        assertThat(model.getAudioOutputDevice()).isEqualTo("Future output");
        assertThat(controller.configurationCount).hasValue(0);
    }

    @Test
    void queuedAppliesPreserveDeferredSampleAndMixBeforeLaterBufferChange()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockFirstConfiguration = true;
        controller.releaseFirstConfiguration = new CountDownLatch(1);
        controller.expectedConfigurations = new CountDownLatch(3);
        SettingsModel model = Story307TestSupport.model("engineReconfigureFifo");
        int changedBitDepth = model.getBitDepth() == 24 ? 32 : 24;
        double changedRate = model.getSampleRate() == 96_000 ? 48_000 : 96_000;
        int changedBuffer = model.getBufferSize() == 512 ? 256 : 512;
        MixPrecision changedPrecision = model.getMixPrecision() == MixPrecision.DOUBLE_64
                ? MixPrecision.FLOAT_32 : MixPrecision.DOUBLE_64;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.bitDepth").orElseThrow()
                    .setValue(changedBitDepth);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.firstConfigurationEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.getShell().settingRow("audio.mixPrecision").orElseThrow()
                    .setValue(changedPrecision);
            dialog.applySettings();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });

        controller.releaseFirstConfiguration.countDown();
        assertThat(controller.expectedConfigurations.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.mixPrecisionApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getSampleRate()).isEqualTo(changedRate);
        assertThat(model.getMixPrecision()).isEqualTo(changedPrecision);
        assertThat(model.getBufferSize()).isEqualTo(changedBuffer);
        assertThat(controller.lastMixPrecision.get()).isEqualTo(changedPrecision);
        assertThat(controller.requests).hasSize(3);
        assertThat(controller.requests.getLast().sampleRate().getHz()).isEqualTo((int) changedRate);
        assertThat(controller.requests.getLast().bufferFrames()).isEqualTo(changedBuffer);
    }

    @Test
    void laterApplyKeepsLiveDevicesWhilePersistedBackendAwaitsRestart() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.devicesByBackend.put("Java Sound", List.of(device("Current device")));
        controller.devicesByBackend.put("ASIO", List.of(device("Future device")));
        SettingsModel model = Story307TestSupport.model("engineReconfigureSequentialBackend");
        model.setAudioBackend("Java Sound");
        model.setAudioInputDevice("Current device");
        model.setAudioOutputDevice("Current device");
        int firstBuffer = model.getBufferSize() == 256 ? 512 : 256;
        int secondBuffer = firstBuffer == 512 ? 1024 : 512;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.backend").orElseThrow().setValue("ASIO");
            dialog.getShell().settingRow("audio.inputDevice").orElseThrow()
                    .setValue("Future device");
            dialog.getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue("Future device");
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(firstBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        controller.configurationApplied = new CountDownLatch(1);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(secondBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(model.getAudioBackend()).isEqualTo("ASIO");
        assertThat(model.getAudioInputDevice()).isEqualTo("Future device");
        assertThat(model.getAudioOutputDevice()).isEqualTo("Future device");
        assertThat(controller.requests).hasSize(2);
        assertThat(controller.requests.getLast().backendName()).isEqualTo("Java Sound");
        assertThat(controller.requests.getLast().inputDeviceName()).isEqualTo("Current device");
        assertThat(controller.requests.getLast().outputDeviceName()).isEqualTo("Current device");
        assertThat(controller.requests.getLast().bufferFrames()).isEqualTo(secondBuffer);
    }

    @Test
    void reconfigureWaitsForInFlightDeviceEnumerationToCancel() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockDeviceEnumeration = true;
        controller.releaseDeviceEnumeration = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("engineEnumerationIsolation");
        int changedBuffer = model.getBufferSize() == 512 ? 256 : 512;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            return null;
        });
        assertThat(controller.deviceEnumerationEntered.await(5, TimeUnit.SECONDS)).isTrue();
        controller.blockDeviceEnumeration = false;

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });

        assertThat(controller.configurationApplied.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.reconfiguredWhileEnumerating).isFalse();
        controller.releaseDeviceEnumeration.countDown();
    }

    @Test
    void deviceRestartRequestedMidReconfigureWaitsUntilQueueDrains() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockFirstConfiguration = true;
        controller.releaseFirstConfiguration = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("engineEnumerationSuspension");
        int changedBuffer = model.getBufferSize() == 512 ? 256 : 512;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            return null;
        });
        assertThat(controller.deviceEnumerationEntered.await(5, TimeUnit.SECONDS)).isTrue();
        long enumerationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (controller.enumeratingDevices.get()
                && System.nanoTime() < enumerationDeadline) {
            Thread.sleep(10);
        }
        assertThat(controller.enumeratingDevices).isFalse();
        controller.deviceEnumerationEntered = new CountDownLatch(1);

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.firstConfigurationEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            dialogRef.get().getShell().settingRow("audio.outputDevice").orElseThrow()
                    .setValue("Queued output");
            return null;
        });
        assertThat(controller.deviceEnumerationEntered.await(250, TimeUnit.MILLISECONDS))
                .as("enumeration remains suspended during applyConfiguration")
                .isFalse();

        controller.releaseFirstConfiguration.countDown();
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.deviceEnumerationEntered.await(5, TimeUnit.SECONDS))
                .as("latest enumeration restarts after the audio queue drains")
                .isTrue();
        assertThat(controller.reconfiguredWhileEnumerating).isFalse();
    }

    @Test
    void rejectedSampleRateIsNotPersistedOrReconfigured() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.rejectSampleRate = true;
        SettingsModel model = Story307TestSupport.model("engineReconfigureRejectedRate");
        double originalRate = model.getSampleRate();
        double changedRate = originalRate == 48_000 ? 44_100 : 48_000;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.applySettings();
            return null;
        });

        assertThat(controller.sampleRateAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().settingRow("audio.sampleRate")
                        .orElseThrow().getValue(),
                originalRate,
                5,
                TimeUnit.SECONDS)).isTrue();
        assertThat(model.getSampleRate()).isEqualTo(originalRate);
        assertThat(controller.configurationCount.get()).isZero();
    }

    @Test
    void laterBufferApplyUsesLiveRateWhenEarlierQueuedRateIsRejected() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.rejectSampleRate = true;
        controller.blockSampleRate = true;
        controller.releaseSampleRate = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("engineRejectedRateDependency");
        double originalRate = model.getSampleRate();
        double rejectedRate = originalRate == 96_000 ? 48_000 : 96_000;
        int changedBuffer = model.getBufferSize() == 512 ? 256 : 512;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(rejectedRate);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.sampleRateEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.applySettings();
            return null;
        });
        controller.releaseSampleRate.countDown();

        assertThat(controller.configurationApplied.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getSampleRate()).isEqualTo(originalRate);
        assertThat(Story307TestSupport.awaitFxValue(
                model::getBufferSize, changedBuffer, 5, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getBufferSize()).isEqualTo(changedBuffer);
        assertThat(controller.lastRequest.get().sampleRate().getHz()).isEqualTo((int) originalRate);
        assertThat(controller.lastRequest.get().bufferFrames()).isEqualTo(changedBuffer);
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 5, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            assertThat(dialogRef.get().getShell().operationNoticeText())
                    .contains("rejected by the audio driver");
            return null;
        });
    }

    @Test
    void deferredListenerRunsOnlyAfterSampleRatePersistenceCommits() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockSampleRate = true;
        controller.releaseSampleRate = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("engineDeferredListener");
        double originalRate = model.getSampleRate();
        double changedRate = originalRate == 96_000 ? 48_000 : 96_000;
        AtomicInteger listenerCalls = new AtomicInteger();
        CountDownLatch listenerCalled = new CountDownLatch(1);

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.setSettingsChangeListener(_ -> {
                listenerCalls.incrementAndGet();
                listenerCalled.countDown();
            });
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.sampleRateEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getSampleRate()).isEqualTo(originalRate);
        assertThat(listenerCalls).hasValue(0);

        controller.releaseSampleRate.countDown();
        assertThat(listenerCalled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getSampleRate()).isEqualTo(changedRate);
        assertThat(listenerCalls).hasValue(1);
    }

    @Test
    void unexpectedPreflightFailureClearsOverlayAndShowsVisibleError() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failSampleRateUnexpectedly = true;
        SettingsModel model = Story307TestSupport.model("engineUnexpectedPreflightFailure");
        double originalRate = model.getSampleRate();
        double changedRate = originalRate == 96_000 ? 48_000 : 96_000;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.applySettings();
            return null;
        });

        assertThat(controller.sampleRateAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 5, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getSampleRate()).isEqualTo(originalRate);
        Story307TestSupport.onFx(() -> {
            SettingsShell shell = dialogRef.get().getShell();
            assertThat(shell.settingRow("audio.sampleRate").orElseThrow().getValue())
                    .isEqualTo(originalRate);
            assertThat(shell.operationNoticeText()).contains("unexpected sample-rate failure");
            return null;
        });
    }

    @Test
    void failedEngineReconfigureRollsBackPersistenceRuntimeAndListener() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failNextConfiguration = true;
        SettingsModel model = Story307TestSupport.model("engineTransactionalRollback");
        int originalBuffer = model.getBufferSize();
        int changedBuffer = originalBuffer == 256 ? 512 : 256;
        MixPrecision originalPrecision = model.getMixPrecision();
        MixPrecision changedPrecision = originalPrecision == MixPrecision.DOUBLE_64
                ? MixPrecision.FLOAT_32 : MixPrecision.DOUBLE_64;
        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.setSettingsChangeListener(_ -> listenerCalls.incrementAndGet());
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.getShell().settingRow("audio.mixPrecision").orElseThrow()
                    .setValue(changedPrecision);
            dialog.applySettings();
            return null;
        });

        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 10, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getBufferSize()).isEqualTo(originalBuffer);
        assertThat(model.getMixPrecision()).isEqualTo(originalPrecision);
        assertThat(listenerCalls).hasValue(0);
        assertThat(controller.requests).hasSize(2);
        assertThat(controller.requests.getFirst().bufferFrames()).isEqualTo(changedBuffer);
        assertThat(controller.requests.getLast().bufferFrames()).isEqualTo(originalBuffer);
        assertThat(controller.lastMixPrecision).hasValue(originalPrecision);
        Story307TestSupport.onFx(() -> {
            assertThat(dialogRef.get().getShell().settingRow("audio.bufferSize")
                    .orElseThrow().getValue()).isEqualTo(originalBuffer);
            return null;
        });
    }

    @Test
    void twoQueuedFailuresNeverPersistAnUnsuccessfulOverlay() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockFirstConfiguration = true;
        controller.releaseFirstConfiguration = new CountDownLatch(1);
        controller.expectedConfigurations = new CountDownLatch(4);
        SettingsModel model = Story307TestSupport.model("queuedTransactionalFailures");
        int originalBuffer = model.getBufferSize();
        int firstBuffer = originalBuffer == 256 ? 512 : 256;
        int secondBuffer = firstBuffer == 1024 ? 2048 : 1024;
        controller.rejectedBufferFrames.add(firstBuffer);
        controller.rejectedBufferFrames.add(secondBuffer);
        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.setSettingsChangeListener(_ -> listenerCalls.incrementAndGet());
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(firstBuffer);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.firstConfigurationEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(secondBuffer);
            dialog.applySettings();
            return null;
        });
        controller.releaseFirstConfiguration.countDown();

        assertThat(controller.expectedConfigurations.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().settingRow("audio.bufferSize")
                        .orElseThrow().getValue(),
                originalBuffer, 10, TimeUnit.SECONDS)).isTrue();
        assertThat(model.getBufferSize()).isEqualTo(originalBuffer);
        assertThat(listenerCalls).hasValue(0);
        assertThat(controller.requests).extracting(AudioEngineController.Request::bufferFrames)
                .containsExactly(firstBuffer, originalBuffer, secondBuffer, originalBuffer);
    }

    @Test
    void okKeepsDialogOpenWhenAsynchronousSampleRateIsRejected() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.rejectSampleRate = true;
        controller.blockSampleRate = true;
        controller.releaseSampleRate = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("engineOkRejectedRate");
        double changedRate = model.getSampleRate() == 96_000 ? 48_000 : 96_000;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.sampleRate").orElseThrow()
                    .setValue(changedRate);
            dialog.show();
            dialog.applyAndCloseWhenReady();
            assertThat(dialog.getDialogPane().getScene().getWindow().isShowing()).isTrue();
            return null;
        });
        assertThat(controller.sampleRateEntered.await(5, TimeUnit.SECONDS)).isTrue();
        controller.releaseSampleRate.countDown();
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 5, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getDialogPane().getScene().getWindow().isShowing()).isTrue();
            dialog.hide();
            return null;
        });
    }

    @Test
    void dirtyCloseApplyKeepsDialogOpenWhenEngineReconfigurationFails() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failNextConfiguration = true;
        SettingsModel model = Story307TestSupport.model("engineDirtyCloseFailure");
        int changedBuffer = model.getBufferSize() == 256 ? 512 : 256;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.APPLY);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.show();
            dialog.close();
            assertThat(dialog.getDialogPane().getScene().getWindow().isShowing()).isTrue();
            return null;
        });

        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 10, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getDialogPane().getScene().getWindow().isShowing()).isTrue();
            dialog.hide();
            return null;
        });
    }

    @Test
    void closeAfterFooterApplyWaitsForBlockedFailureAndKeepsDialogVisible()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockFirstConfiguration = true;
        controller.releaseFirstConfiguration = new CountDownLatch(1);
        controller.failNextConfiguration = true;
        SettingsModel model = Story307TestSupport.model("engineApplyThenCloseFailure");
        int changedBuffer = model.getBufferSize() == 256 ? 512 : 256;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.show();
            dialog.applySettings();
            return null;
        });
        assertThat(controller.firstConfigurationEntered.await(5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getShell().dirtyProperty().get()).isFalse();
            dialog.close();
            assertThat(dialog.getDialogPane().getScene().getWindow().isShowing()).isTrue();
            return null;
        });
        controller.releaseFirstConfiguration.countDown();

        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 10, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getDialogPane().getScene().getWindow().isShowing()).isTrue();
            dialog.hide();
            return null;
        });
    }

    @Test
    void cleanOkAfterFailedApplyClearsFailureLatchAndCloses() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failNextConfiguration = true;
        SettingsModel model = Story307TestSupport.model("engineFailureThenCleanOk");
        int changedBuffer = model.getBufferSize() == 256 ? 512 : 256;
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(changedBuffer);
            dialog.show();
            dialog.applySettings();
            return null;
        });
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 10, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getShell().dirtyProperty().get()).isFalse();
            dialog.applyAndCloseWhenReady();
            return null;
        });
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().isShowing(),
                false, 10, TimeUnit.SECONDS)).isTrue();
    }

    private static AudioDeviceInfo device(String name) {
        return new AudioDeviceInfo(0, name, "test", 2, 2, 48_000,
                List.of(SampleRate.fromHz(48_000)), 2, 2);
    }
}
