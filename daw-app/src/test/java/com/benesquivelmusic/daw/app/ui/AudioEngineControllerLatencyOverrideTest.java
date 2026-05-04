package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the per-device latency-override plumbing on
 * {@link AudioEngineController} default methods.
 *
 * <p>The default {@link AudioEngineController#reportedLatency()}
 * returns {@link RoundTripLatency#UNKNOWN}; the popup-supporting
 * methods {@link AudioEngineController#driverReportedLatency()},
 * {@link AudioEngineController#latencyOverrideFrames()}, and
 * {@link AudioEngineController#setLatencyOverrideFrames(Optional)}
 * have safe defaults so test stubs continue to compile.</p>
 *
 * <p>An in-memory implementation here demonstrates the contract used
 * by {@code DefaultAudioEngineController}: when an override is set
 * for the active device, {@code reportedLatency()} returns a
 * {@link RoundTripLatency} whose {@code totalFrames()} equals the
 * override; switching devices or clearing the override restores the
 * driver-reported value.</p>
 */
class AudioEngineControllerLatencyOverrideTest {

    /**
     * Bare-bones in-memory controller mirroring the
     * {@code DefaultAudioEngineController} per-device override
     * semantics. Keyed by a simulated active device.
     */
    private static final class FakeController implements AudioEngineController {
        private final RoundTripLatency driver;
        private final java.util.Map<String, Integer> overrides = new java.util.HashMap<>();
        private String activeDeviceKey;

        FakeController(RoundTripLatency driver, String activeDeviceKey) {
            this.driver = driver;
            this.activeDeviceKey = activeDeviceKey;
        }

        void setActiveDeviceKey(String key) {
            this.activeDeviceKey = key;
        }

        @Override public String getActiveBackendName() { return BACKEND_NONE; }
        @Override public java.util.List<String> getAvailableBackendNames() { return java.util.List.of(); }
        @Override public java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo> listDevices() {
            return java.util.List.of();
        }
        @Override public java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo> listDevices(String n) {
            return java.util.List.of();
        }
        @Override public double getCpuLoadPercent() { return -1.0; }
        @Override public void applyConfiguration(Request request) { }
        @Override public void playTestTone(String outputDeviceName) { }

        @Override
        public RoundTripLatency reportedLatency() {
            Integer override = activeDeviceKey != null ? overrides.get(activeDeviceKey) : null;
            return override == null ? driver : new RoundTripLatency(override, 0, 0);
        }

        @Override
        public RoundTripLatency driverReportedLatency() {
            return driver;
        }

        @Override
        public Optional<Integer> latencyOverrideFrames() {
            return Optional.ofNullable(
                    activeDeviceKey != null ? overrides.get(activeDeviceKey) : null);
        }

        @Override
        public void setLatencyOverrideFrames(Optional<Integer> frames) {
            if (activeDeviceKey == null) return;
            if (frames.isPresent()) {
                overrides.put(activeDeviceKey, frames.get());
            } else {
                overrides.remove(activeDeviceKey);
            }
        }
    }

    @Test
    void shouldReturnDriverReportedWhenNoOverride() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16);
        FakeController c = new FakeController(driver, "ASIO|Scarlett");
        assertThat(c.reportedLatency()).isEqualTo(driver);
        assertThat(c.driverReportedLatency()).isEqualTo(driver);
        assertThat(c.latencyOverrideFrames()).isEmpty();
    }

    @Test
    void shouldReturnOverrideTotalWhenOverrideIsSet() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16); // total = 208
        FakeController c = new FakeController(driver, "ASIO|Scarlett");

        c.setLatencyOverrideFrames(Optional.of(360));

        // reportedLatency() folds the override into total = 360.
        assertThat(c.reportedLatency().totalFrames()).isEqualTo(360);
        // driverReportedLatency() always returns the driver report unchanged.
        assertThat(c.driverReportedLatency()).isEqualTo(driver);
        assertThat(c.latencyOverrideFrames()).contains(360);
    }

    @Test
    void clearingOverrideShouldRestoreDriverReportedValue() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16);
        FakeController c = new FakeController(driver, "ASIO|Scarlett");

        c.setLatencyOverrideFrames(Optional.of(360));
        c.setLatencyOverrideFrames(Optional.empty());

        assertThat(c.reportedLatency()).isEqualTo(driver);
        assertThat(c.latencyOverrideFrames()).isEmpty();
    }

    @Test
    void overrideShouldNotLeakAcrossDevices() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16);
        FakeController c = new FakeController(driver, "ASIO|Scarlett");

        // Set override for device A.
        c.setLatencyOverrideFrames(Optional.of(360));
        assertThat(c.reportedLatency().totalFrames()).isEqualTo(360);

        // Switch to device B — no override there.
        c.setActiveDeviceKey("WASAPI|Apollo");
        assertThat(c.reportedLatency()).isEqualTo(driver);
        assertThat(c.latencyOverrideFrames()).isEmpty();

        // Switch back to device A — override still present.
        c.setActiveDeviceKey("ASIO|Scarlett");
        assertThat(c.latencyOverrideFrames()).contains(360);
    }

    @Test
    void defaultControllerImplementationsHaveSafeDefaults() {
        AudioEngineController stub = new AudioEngineController() {
            @Override public String getActiveBackendName() { return BACKEND_NONE; }
            @Override public java.util.List<String> getAvailableBackendNames() { return java.util.List.of(); }
            @Override public java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo> listDevices() {
                return java.util.List.of();
            }
            @Override public java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo> listDevices(String n) {
                return java.util.List.of();
            }
            @Override public double getCpuLoadPercent() { return -1.0; }
            @Override public void applyConfiguration(Request request) { }
            @Override public void playTestTone(String outputDeviceName) { }
        };

        assertThat(stub.reportedLatency()).isEqualTo(RoundTripLatency.UNKNOWN);
        assertThat(stub.driverReportedLatency()).isEqualTo(RoundTripLatency.UNKNOWN);
        assertThat(stub.latencyOverrideFrames()).isEmpty();
        // setLatencyOverrideFrames is a no-op default; calling it must not throw.
        stub.setLatencyOverrideFrames(Optional.of(208));
        assertThat(stub.latencyOverrideFrames()).isEmpty(); // default impl ignores the call
    }
}
