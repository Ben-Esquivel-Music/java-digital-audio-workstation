package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AudioSettingsDialog} — verifies initial state from the
 * settings model, device/sample-rate filtering, latency display, and the
 * apply-configuration path via a stub {@link AudioEngineController}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioSettingsDialogTest {

    private SettingsModel model;
    private StubAudioEngineController stub;

    @BeforeEach
    void setUp() {
        Preferences prefs = Preferences.userRoot().node("audioSettingsDialogTest_" + System.nanoTime());
        model = new SettingsModel(prefs);
        stub = new StubAudioEngineController();
    }

    @Test
    void shouldPopulateBackendsFromController() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getBackendCombo().getItems()).containsExactly("PortAudio", "Java Sound");
    }

    @Test
    void shouldDefaultBackendToActiveWhenModelEmpty() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getBackendCombo().getValue()).isEqualTo("PortAudio");
    }

    @Test
    void shouldPreferPersistedBackend() throws Exception {
        model.setAudioBackend("Java Sound");
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getBackendCombo().getValue()).isEqualTo("Java Sound");
    }

    @Test
    void shouldListInputAndOutputDevices() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getInputDeviceCombo().getItems()).contains("(default)", "USB Mic");
        assertThat(dialog.getOutputDeviceCombo().getItems()).contains("(default)", "Main Out");
    }

    @Test
    void shouldFilterSampleRatesByDeviceSupport() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> dialog.getOutputDeviceCombo().setValue("Main Out"));
        // Main Out supports only 44.1 and 48 kHz in the stub
        assertThat(dialog.getFilteredSampleRates()).containsExactly(44_100, 48_000);
    }

    @Test
    void shouldShowAllSampleRatesForDefaultDevice() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> dialog.getOutputDeviceCombo().setValue("(default)"));
        assertThat(dialog.getFilteredSampleRates())
                .containsExactly(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
    }

    @Test
    void shouldComputeLatencyLabelFromBufferAndSampleRate() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> {
            dialog.getSampleRateCombo().setValue(48_000);
            dialog.getBufferSizeCombo().setValue(128);
        });
        // 128 / 48000 * 1000 ≈ 2.666 ms per buffer
        String label = dialog.getBufferLatencyLabel().getText();
        assertThat(label).contains("2.7 ms buffer");
    }

    @Test
    void shouldPersistSelectionsOnApply() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> {
            dialog.getBackendCombo().setValue("Java Sound");
            dialog.getSampleRateCombo().setValue(48_000);
            dialog.getBufferSizeCombo().setValue(256);
            dialog.applyNow();
        });
        assertThat(model.getAudioBackend()).isEqualTo("Java Sound");
        assertThat(model.getSampleRate()).isEqualTo(48_000.0);
        assertThat(model.getBufferSize()).isEqualTo(256);
        assertThat(stub.applyCount).isEqualTo(1);
        assertThat(stub.lastRequest.sampleRate()).isEqualTo(SampleRate.HZ_48000);
        assertThat(stub.lastRequest.bufferSize()).isEqualTo(BufferSize.SAMPLES_256);
    }

    @Test
    void shouldInvokeTestTone() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> {
            dialog.getOutputDeviceCombo().setValue("Main Out");
            dialog.fireTestTone();
        });
        assertThat(stub.toneCount).isEqualTo(1);
        assertThat(stub.lastToneDevice).isEqualTo("Main Out");
    }

    @Test
    void shouldTolerateNullController() throws Exception {
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, null));
        assertThat(dialog.getBackendCombo().getItems()).contains("Java Sound");
        assertThat(dialog.getCpuLoadLabel().getText()).contains("—");
    }

    @Test
    void shouldShowCpuLoadFromController() throws Exception {
        stub.cpuLoad = 42.5;
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getCpuLoadLabel().getText()).contains("42.5");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> T onFxThread(java.util.function.Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw new RuntimeException("FX thread action failed", err.get());
        }
        return ref.get();
    }

    private static void runOnFxAndWait(Runnable action) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw new RuntimeException("FX thread action failed", err.get());
        }
    }

    // ── Stub controller ──────────────────────────────────────────────────────

    private static final class StubAudioEngineController implements AudioEngineController {

        final List<AudioDeviceInfo> devices = List.of(
                new AudioDeviceInfo(0, "USB Mic", "PortAudio", 2, 0,
                        48_000.0,
                        List.of(SampleRate.HZ_44100, SampleRate.HZ_48000, SampleRate.HZ_96000),
                        0.5, 0.5),
                new AudioDeviceInfo(1, "Main Out", "PortAudio", 0, 2,
                        44_100.0,
                        List.of(SampleRate.HZ_44100, SampleRate.HZ_48000),
                        0.3, 0.3));

        double cpuLoad = -1.0;
        int applyCount;
        int toneCount;
        Request lastRequest;
        String lastToneDevice;

        @Override
        public String getActiveBackendName() {
            return "PortAudio";
        }

        @Override
        public List<String> getAvailableBackendNames() {
            return List.of("PortAudio", "Java Sound");
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return devices;
        }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            return devices;
        }

        @Override
        public double getCpuLoadPercent() {
            return cpuLoad;
        }

        @Override
        public void applyConfiguration(Request request) {
            applyCount++;
            lastRequest = request;
        }

        @Override
        public void playTestTone(String outputDeviceName) {
            toneCount++;
            lastToneDevice = outputDeviceName;
        }
    }
}
