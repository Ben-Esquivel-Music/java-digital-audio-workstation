package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void shouldShowUnionOfCanonicalAndDeviceSupportedRates() throws Exception {
        // Story 213: the dropdown is the *union* of canonical rates and
        // the device's supported rates, with unsupported rates greyed out
        // and tooltipped (rather than filtered out entirely).
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> dialog.getOutputDeviceCombo().setValue("Main Out"));
        // Main Out reports support only for 44.1 / 48 kHz; the menu still
        // shows every canonical rate so the user can see them all.
        assertThat(dialog.getFilteredSampleRates())
                .containsExactly(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);
        // But only the device-supported subset is in currentSupportedRates,
        // which the cell factory uses to grey unsupported rows.
        assertThat(dialog.getCurrentSupportedSampleRates())
                .containsExactlyInAnyOrder(44_100, 48_000);
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
        assertThat(stub.lastRequest.bufferFrames()).isEqualTo(256);
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

    @Test
    void controlPanelButtonShouldBeDisabledWhenBackendHasNoNativePanel() throws Exception {
        // Default stub returns Optional.empty() from openControlPanel
        stub.controlPanelAction = Optional.empty();
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getOpenControlPanelButton().isDisable()).isTrue();
        assertThat(dialog.getOpenControlPanelButton().getTooltip().getText())
                .contains("no native control panel");
    }

    @Test
    void controlPanelButtonShouldBeEnabledWhenBackendExposesPanel() throws Exception {
        stub.controlPanelAction = Optional.of(() -> stub.controlPanelInvocations.incrementAndGet());
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getOpenControlPanelButton().isDisable()).isFalse();
        assertThat(dialog.getOpenControlPanelButton().getTooltip().getText())
                .contains("USB streaming");
    }

    @Test
    void firingControlPanelShouldInvokeBackendAndRefreshDeviceCapabilities() throws Exception {
        // The MockAudioBackend story: clicking "Open Driver Control Panel"
        // runs the backend's runnable and the dialog re-queries device
        // capabilities afterwards so any change the user made in the
        // vendor UI (sample rates, buffer sizes) is reflected.
        stub.controlPanelAction = Optional.of(() -> stub.controlPanelInvocations.incrementAndGet());
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        int devicesBefore = stub.listDevicesCalls;
        AtomicReference<Boolean> ran = new AtomicReference<>();
        runOnFxAndWait(() -> ran.set(dialog.fireOpenControlPanelSync()));
        assertThat(ran.get()).isTrue();
        assertThat(stub.controlPanelInvocations.get()).isEqualTo(1);
        // listDevices(backendName) is called by refreshDevicesForBackend
        assertThat(stub.listDevicesCalls).isGreaterThan(devicesBefore);
    }

    @Test
    void controlPanelButtonShouldBeHiddenInHeadlessMode() throws Exception {
        // Headless mode is the default on CI runners (java.awt.headless=true
        // is set by surefire in this project). The button is hidden so the
        // dialog gracefully degrades on headless test runs.
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            return; // skip when running with a real display
        }
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getOpenControlPanelButton().isVisible()).isFalse();
        assertThat(dialog.getOpenControlPanelButton().isManaged()).isFalse();
    }

    // ── Story 213: driver-reported buffer size + sample-rate enumeration ─────

    @Test
    void bufferSizeDropdownShouldExpandFromDriverReportedRange() throws Exception {
        // Story 213 requirement: a fake backend exposing
        // BufferSizeRange(96, 384, 192, 96) produces a dropdown of
        // {96, 192, 288, 384}, with 192 (preferred) preselected.
        stub.bufferRanges.put("Main Out", new BufferSizeRange(96, 384, 192, 96));
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> dialog.getOutputDeviceCombo().setValue("Main Out"));
        assertThat(dialog.getBufferSizeOptions()).containsExactly(96, 192, 288, 384);
        assertThat(dialog.getBufferSizeCombo().getValue()).isEqualTo(192);
    }

    @Test
    void persistedUnsupportedSampleRateShouldFallBackWithNotification() throws Exception {
        // Story 213: if a persisted setting is no longer in the supported
        // set (e.g. user changed driver mode), fall back to preferred and
        // notify via NotificationManager.
        //
        // Same strategy as the buffer-size test: construct with a
        // permissive set, attach the listener, then narrow the set and
        // re-trigger via a device toggle.
        model.setAudioOutputDevice("Main Out");

        CopyOnWriteArrayList<String> notifications = new CopyOnWriteArrayList<>();
        AudioSettingsDialog dialog = onFxThread(() -> {
            AudioSettingsDialog d = new AudioSettingsDialog(model, stub);
            d.setNotificationListener(notifications::add);
            return d;
        });
        // User picks 96 kHz while the device still reports support for it.
        stub.supportedRates.put("Main Out", Set.of(44_100, 48_000, 96_000));
        runOnFxAndWait(() -> {
            dialog.getOutputDeviceCombo().setValue("(default)");
            dialog.getOutputDeviceCombo().setValue("Main Out");
            dialog.getSampleRateCombo().setValue(96_000);
        });
        // Now driver mode changes (e.g. user returns from control panel)
        // and 96 kHz is no longer accepted — dialog must fall back.
        stub.supportedRates.put("Main Out", Set.of(44_100, 48_000));
        runOnFxAndWait(() -> {
            dialog.getOutputDeviceCombo().setValue("(default)");
            dialog.getOutputDeviceCombo().setValue("Main Out");
        });
        assertThat(stub.supportedRates.get("Main Out"))
                .contains(dialog.getSampleRateCombo().getValue());
        assertThat(notifications).anyMatch(s -> s.contains("not supported"));
    }

    @Test
    void persistedUnsupportedBufferSizeShouldFallBackToPreferred() throws Exception {
        // Story 213: an unsupported persisted buffer size must fall back
        // to the BufferSizeRange's preferred value with a notification.
        //
        // Strategy: construct the dialog while the device reports the
        // default range (so the persisted 256-frame buffer is accepted
        // and the constructor's first refresh emits no notification),
        // attach the listener, then narrow the range to one that does
        // not accept 256 frames and re-trigger via a device-toggle —
        // simulating the user returning from the native control panel
        // after changing the driver's buffer-size table.
        model.setAudioOutputDevice("Main Out");

        CopyOnWriteArrayList<String> notifications = new CopyOnWriteArrayList<>();
        AudioSettingsDialog dialog = onFxThread(() -> {
            AudioSettingsDialog d = new AudioSettingsDialog(model, stub);
            d.setNotificationListener(notifications::add);
            return d;
        });
        // Now narrow the driver-reported range so 256 (the persisted
        // value) is no longer accepted and the dialog must fall back.
        stub.bufferRanges.put("Main Out", new BufferSizeRange(96, 384, 192, 96));
        runOnFxAndWait(() -> {
            dialog.getOutputDeviceCombo().setValue("(default)");
            dialog.getOutputDeviceCombo().setValue("Main Out");
        });
        assertThat(dialog.getBufferSizeOptions()).containsExactly(96, 192, 288, 384);
        assertThat(dialog.getBufferSizeCombo().getValue()).isEqualTo(192);
        assertThat(notifications).anyMatch(s -> s.contains("not supported"));
    }

    @Test
    void wasapiCheckboxShouldRefreshDeviceCapabilitiesWhenToggled() throws Exception {
        // Story 213: the dialog refreshes both lists when the WASAPI
        // mode toggle changes. We simulate that by registering different
        // supported-rate sets for the different "backend names" the
        // checkbox produces.
        stub.availableBackends = List.of("WASAPI", "Java Sound");
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        runOnFxAndWait(() -> dialog.getBackendCombo().setValue("WASAPI"));
        // Checkbox is visible for WASAPI backends.
        assertThat(dialog.getWasapiExclusiveCheck().isVisible()).isTrue();
        int beforeToggle = stub.bufferSizeRangeCalls;
        runOnFxAndWait(() -> dialog.getWasapiExclusiveCheck().setSelected(true));
        // Toggling triggered another query for the buffer-size range.
        assertThat(stub.bufferSizeRangeCalls).isGreaterThan(beforeToggle);
    }

    @Test
    void clockSourceComboIsDisabledWhenBackendReportsNone() throws Exception {
        // WASAPI / JACK / the JDK mixer all run at the OS / server clock
        // and never report clock sources. The combo must be disabled and
        // tooltipped so the user understands the option does not apply.
        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getClockSourceCombo().isDisabled()).isTrue();
        assertThat(dialog.getClockSourceCombo().getItems()).isEmpty();
    }

    @Test
    void clockSourceComboIsPopulatedWhenBackendReportsSources() throws Exception {
        // Three sources in the order an ASIO driver typically reports
        // them: internal crystal, word-clock BNC, S/PDIF coax.
        ClockSource internal = new ClockSource(0, "Internal", true, new ClockKind.Internal());
        ClockSource wordClock = new ClockSource(1, "Word Clock", false, new ClockKind.WordClock());
        ClockSource spdif = new ClockSource(2, "S/PDIF", false, new ClockKind.Spdif());
        stub.clockSources = List.of(internal, wordClock, spdif);

        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        assertThat(dialog.getClockSourceCombo().getItems()).hasSize(3);
        assertThat(dialog.getClockSourceCombo().isDisabled()).isFalse();
        // The combo defaults to whichever source the driver reports as current.
        assertThat(dialog.getClockSourceCombo().getValue()).isEqualTo(internal);
    }

    @Test
    void selectingClockSourceForwardsToControllerAndReQueriesCapabilities() throws Exception {
        // Some interfaces (RME Fireface, Antelope Discrete) only allow
        // 44.1 / 48 kHz when locked to S/PDIF, but the full canonical
        // ladder when locked to the internal crystal. The dialog must
        // re-query capabilities after a clock-source change.
        ClockSource internal = new ClockSource(0, "Internal", true, new ClockKind.Internal());
        ClockSource spdif = new ClockSource(2, "S/PDIF", false, new ClockKind.Spdif());
        stub.clockSources = List.of(internal, spdif);

        AudioSettingsDialog dialog = onFxThread(() -> new AudioSettingsDialog(model, stub));
        int rangeCallsBefore = stub.bufferSizeRangeCalls;
        runOnFxAndWait(() -> dialog.getClockSourceCombo().setValue(spdif));

        assertThat(stub.lastSelectedClockSourceId).isEqualTo(2);
        assertThat(stub.bufferSizeRangeCalls).isGreaterThan(rangeCallsBefore);
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
        int listDevicesCalls;
        int bufferSizeRangeCalls;
        Optional<Runnable> controlPanelAction = Optional.empty();
        AtomicInteger controlPanelInvocations = new AtomicInteger();
        Request lastRequest;
        String lastToneDevice;
        List<String> availableBackends = List.of("PortAudio", "Java Sound");

        /** Per-device buffer-size range overrides (story 213). */
        final Map<String, BufferSizeRange> bufferRanges = new HashMap<>();
        /** Per-device supported sample-rate overrides (story 213). */
        final Map<String, Set<Integer>> supportedRates = new HashMap<>();
        /** Clock sources reported to the dialog (Hardware Clock Source story). */
        List<ClockSource> clockSources = List.of();
        /** Last clock source id passed to {@link #selectClockSource}; -1 when never called. */
        int lastSelectedClockSourceId = -1;

        @Override
        public String getActiveBackendName() {
            return "PortAudio";
        }

        @Override
        public List<String> getAvailableBackendNames() {
            return availableBackends;
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            listDevicesCalls++;
            return devices;
        }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            listDevicesCalls++;
            return devices;
        }

        @Override
        public Optional<Runnable> openControlPanel() {
            return controlPanelAction;
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

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            bufferSizeRangeCalls++;
            BufferSizeRange override = bufferRanges.get(keyOf(outputDeviceName));
            return override != null ? override : AudioEngineController.super.bufferSizeRange(backendName, outputDeviceName);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            Set<Integer> override = supportedRates.get(keyOf(outputDeviceName));
            if (override != null) {
                return override;
            }
            // Fall back to a per-device list derived from AudioDeviceInfo
            // so existing test setup keeps working without explicit overrides.
            for (AudioDeviceInfo info : devices) {
                if (info.name().equals(outputDeviceName)) {
                    Set<Integer> rates = new LinkedHashSet<>();
                    for (SampleRate r : info.supportedSampleRates()) {
                        rates.add(r.getHz());
                    }
                    return rates;
                }
            }
            return AudioEngineController.super.supportedSampleRates(backendName, outputDeviceName);
        }

        private static String keyOf(String outputDeviceName) {
            return outputDeviceName == null ? "" : outputDeviceName;
        }

        @Override
        public List<ClockSource> clockSources(String backendName, String outputDeviceName) {
            return clockSources;
        }

        @Override
        public void selectClockSource(String backendName, String outputDeviceName, int sourceId) {
            lastSelectedClockSourceId = sourceId;
        }
    }
}
