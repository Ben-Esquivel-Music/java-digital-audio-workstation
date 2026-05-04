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
 * by {@code DefaultAudioEngineController}: when an override is set,
 * {@code reportedLatency()} returns a {@link RoundTripLatency} whose
 * {@code totalFrames()} equals the override; when the override is
 * cleared the driver-reported value is returned unchanged.</p>
 */
class AudioEngineControllerLatencyOverrideTest {

    /**
     * Bare-bones in-memory controller mirroring the
     * {@code DefaultAudioEngineController} override semantics.
     * Only the methods relevant to this test are wired; everything
     * else falls back to the interface defaults.
     */
    private static final class FakeController implements AudioEngineController {
        private final RoundTripLatency driver;
        private Integer override;

        FakeController(RoundTripLatency driver) {
            this.driver = driver;
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
            return override == null ? driver : new RoundTripLatency(override, 0, 0);
        }

        @Override
        public RoundTripLatency driverReportedLatency() {
            return driver;
        }

        @Override
        public Optional<Integer> latencyOverrideFrames() {
            return Optional.ofNullable(override);
        }

        @Override
        public void setLatencyOverrideFrames(Optional<Integer> frames) {
            this.override = frames.orElse(null);
        }
    }

    @Test
    void shouldReturnDriverReportedWhenNoOverride() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16);
        FakeController c = new FakeController(driver);
        assertThat(c.reportedLatency()).isEqualTo(driver);
        assertThat(c.driverReportedLatency()).isEqualTo(driver);
        assertThat(c.latencyOverrideFrames()).isEmpty();
    }

    @Test
    void shouldReturnOverrideTotalWhenOverrideIsSet() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16); // total = 208
        FakeController c = new FakeController(driver);

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
        FakeController c = new FakeController(driver);

        c.setLatencyOverrideFrames(Optional.of(360));
        c.setLatencyOverrideFrames(Optional.empty());

        assertThat(c.reportedLatency()).isEqualTo(driver);
        assertThat(c.latencyOverrideFrames()).isEmpty();
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
