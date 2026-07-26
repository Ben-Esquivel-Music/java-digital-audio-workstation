package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javafx.scene.control.ComboBox;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The legacy live-only audio utilities remain available in unified Settings. */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioAncillaryControlsTest {

    @Test
    void automaticBackendIsRenderedAsAnExplicitChoice() throws Exception {
        SettingsModel model = Story307TestSupport.model("audioAutomaticBackend");

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.show();
            var row = dialog.getShell().settingRow("audio.backend").orElseThrow();
            row.applyCss();
            @SuppressWarnings("unchecked")
            ComboBox<Object> combo = (ComboBox<Object>) row.lookup(".combo-box");
            assertThat(combo).isNotNull();
            assertThat(combo.getConverter().toString(combo.getValue()))
                    .isEqualTo("Automatic (backend default)");
            dialog.hide();
            return null;
        });
    }

    @Test
    void deviceUtilitiesAndPerformanceTelemetryUseTheAttachedController() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.exposeControlPanel = true;
        ClockSource internal = new ClockSource(
                1, "Internal", true, new ClockKind.Internal());
        ClockSource wordClock = new ClockSource(
                2, "Word Clock", false, new ClockKind.WordClock());
        controller.clockSources = List.of(internal, wordClock);
        SettingsModel model = Story307TestSupport.model("audioAncillaryUtilities");
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            return null;
        });
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getClockSourceCombo().getItems().size(),
                2, 5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getControlPanelButton().isDisable()).isFalse();
            assertThat(dialog.getLatencyValue().getText()).contains("round-trip");
            assertThat(dialog.getCpuLoadValue().getText()).isEqualTo("CPU: 42.5%");
            assertThat(dialog.getThreadsInUseValue().getText()).isEqualTo("Threads: 3 / 8");
            dialog.getTestToneButton().fire();
            return null;
        });
        assertThat(controller.testTonePlayed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                () -> !dialogRef.get().getControlPanelButton().isDisable(),
                true, 5, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            dialogRef.get().getControlPanelButton().fire();
            return null;
        });
        assertThat(controller.controlPanelOpened.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getClockSourceCombo().getItems().size(),
                2, 5, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            dialogRef.get().getClockSourceCombo().setValue(wordClock);
            return null;
        });
        assertThat(controller.clockSourceSelected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.selectedClockSource).hasValue(2);
    }

    @Test
    void wasapiExclusiveModeRidesTheExistingBackendSetting() throws Exception {
        SettingsModel model = Story307TestSupport.model("audioAncillaryWasapi");

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.getShell().settingRow("audio.backend").orElseThrow()
                    .setValue("WASAPI");
            assertThat(dialog.getWasapiExclusiveCheck().isVisible()).isTrue();
            dialog.getWasapiExclusiveCheck().setSelected(true);
            assertThat(dialog.getShell().settingRow("audio.backend").orElseThrow().getValue())
                    .isEqualTo("WASAPI (Exclusive)");
            return null;
        });
    }

    @Test
    void driverPanelAndEngineReconfigurationShareOneNativeOperationLane()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.exposeControlPanel = true;
        controller.blockControlPanel = true;
        controller.releaseControlPanel = new java.util.concurrent.CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("audioSerializedUtilities");
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getControlPanelButton().fire();
            return null;
        });
        assertThat(controller.controlPanelOpened.await(5, TimeUnit.SECONDS)).isTrue();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getControlPanelButton().isDisable()).isTrue();
            int replacement = model.getBufferSize() == 256 ? 512 : 256;
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(replacement);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationCount.get()).isZero();

        controller.releaseControlPanel.countDown();
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.maxConcurrentNativeOperations.get()).isEqualTo(1);
        Story307TestSupport.onFx(() -> {
            dialogRef.get().setAudioEngineController(null);
            return null;
        });
    }

    @Test
    void testToneRunsOnAVirtualNonFxThreadAndSerializesWithReconfiguration()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockTestTone = true;
        controller.releaseTestTone = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("audioSerializedTestTone");
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getTestToneButton().fire();
            return null;
        });
        assertThat(controller.testToneEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.testToneThread.get().isVirtual()).isTrue();
        assertThat(controller.testToneOnFxThread).isFalse();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialogRef.get();
            assertThat(dialog.getTestToneButton().isDisable()).isTrue();
            int replacement = model.getBufferSize() == 256 ? 512 : 256;
            dialog.getShell().settingRow("audio.bufferSize").orElseThrow()
                    .setValue(replacement);
            dialog.applySettings();
            return null;
        });
        assertThat(controller.configurationCount).hasValue(0);

        controller.releaseTestTone.countDown();
        assertThat(controller.testTonePlayed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.configurationApplied.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.maxConcurrentNativeOperations).hasValue(1);
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getTestToneButton().isDisable(),
                false, 5, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            dialogRef.get().setAudioEngineController(null);
            return null;
        });
    }

    @Test
    void testToneFailureIsMarshalledToTheVisibleOperationNotice() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failTestTone = true;
        SettingsModel model = Story307TestSupport.model("audioTestToneFailure");
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.getTestToneButton().fire();
            return null;
        });

        assertThat(controller.testToneEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getShell().isOperationNoticeVisible(),
                true, 5, TimeUnit.SECONDS)).isTrue();
        Story307TestSupport.onFx(() -> {
            assertThat(dialogRef.get().getShell().operationNoticeText())
                    .isEqualTo("Could not play the test tone: test tone failed");
            return null;
        });
        assertThat(Story307TestSupport.awaitFxValue(
                () -> dialogRef.get().getTestToneButton().isDisable(),
                false, 5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void latencyReadoutUsesDriverReportedRoundTripFrames() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.reportedLatency = new RoundTripLatency(300, 400, 77);
        SettingsModel model = Story307TestSupport.model("audioDriverLatencyFrames");

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            assertThat(dialog.getLatencyValue().getText())
                    .startsWith("777 frames · ");
            dialog.setAudioEngineController(null);
            return null;
        });
    }

    @Test
    void latencyReadoutFallsBackToSelectedBufferFrames() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        SettingsModel model = Story307TestSupport.model("audioFallbackLatencyFrames");
        model.setBufferSize(1_024);

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            assertThat(dialog.getLatencyValue().getText())
                    .startsWith("1024 frames · ");
            dialog.setAudioEngineController(null);
            return null;
        });
    }

    @Test
    void everyDriverDeviceEventRefreshesCapabilitiesAndSubscriptionClosesWithDialog()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        ManualDeviceEventPublisher publisher = new ManualDeviceEventPublisher();
        controller.deviceEventPublisher = publisher;
        SettingsModel model = Story307TestSupport.model("audioAncillaryDeviceEvents");
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            return null;
        });
        long initialDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (controller.deviceEnumerationCount.get() < 1
                && System.nanoTime() < initialDeadline) {
            Thread.sleep(10);
        }
        int beforeEvent = controller.deviceEnumerationCount.get();

        DeviceId device = new DeviceId("Java Sound", "Default Output");
        List<AudioDeviceEvent> events = List.of(
                new AudioDeviceEvent.DeviceArrived(device),
                new AudioDeviceEvent.DeviceRemoved(device),
                new AudioDeviceEvent.DeviceFormatChanged(
                        device, new AudioFormat(48_000, 2, 24)),
                new AudioDeviceEvent.FormatChangeRequested(
                        device, Optional.empty(), new FormatChangeReason.BufferSizeChange()));
        for (AudioDeviceEvent event : events) {
            int before = controller.deviceEnumerationCount.get();
            publisher.emit(event);
            long refreshDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (controller.deviceEnumerationCount.get() <= before
                    && System.nanoTime() < refreshDeadline) {
                Thread.sleep(10);
            }
            assertThat(controller.deviceEnumerationCount.get()).isGreaterThan(before);
        }
        assertThat(controller.deviceEnumerationCount.get()).isGreaterThan(beforeEvent);

        Story307TestSupport.onFx(() -> {
            dialogRef.get().show();
            dialogRef.get().hide();
            return null;
        });
        assertThat(publisher.cancelled).isTrue();
    }

    @Test
    void aSubscriptionArrivingAfterDialogCloseIsCancelledImmediately() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        ManualDeviceEventPublisher publisher = new ManualDeviceEventPublisher(true);
        controller.deviceEventPublisher = publisher;
        SettingsModel model = Story307TestSupport.model("audioLateDeviceSubscription");
        AtomicReference<SettingsDialog> dialogRef = new AtomicReference<>();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialogRef.set(dialog);
            dialog.setAudioEngineController(controller);
            dialog.show();
            dialog.hide();
            return null;
        });
        publisher.connect();

        assertThat(publisher.cancelled).isTrue();
    }

    private static final class ManualDeviceEventPublisher
            implements Flow.Publisher<AudioDeviceEvent> {
        private final AtomicReference<Flow.Subscriber<? super AudioDeviceEvent>> subscriber =
                new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final boolean delayed;

        private ManualDeviceEventPublisher() {
            this(false);
        }

        private ManualDeviceEventPublisher(boolean delayed) {
            this.delayed = delayed;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AudioDeviceEvent> newSubscriber) {
            subscriber.set(newSubscriber);
            if (!delayed) {
                connect();
            }
        }

        private void connect() {
            Flow.Subscriber<? super AudioDeviceEvent> target = subscriber.get();
            assertThat(target).isNotNull();
            target.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { }
                @Override public void cancel() { cancelled.set(true); }
            });
        }

        private void emit(AudioDeviceEvent event) {
            Flow.Subscriber<? super AudioDeviceEvent> target = subscriber.get();
            if (target != null && !cancelled.get()) {
                target.onNext(event);
            }
        }
    }
}
