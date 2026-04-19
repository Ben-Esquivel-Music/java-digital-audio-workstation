package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry.NodeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyTelemetryTest {

    @Test
    void shouldExposeAllComponents() {
        LatencyTelemetry t = new LatencyTelemetry("track:Drums", NodeKind.TRACK, 2048, "Drums");
        assertThat(t.nodeId()).isEqualTo("track:Drums");
        assertThat(t.kind()).isEqualTo(NodeKind.TRACK);
        assertThat(t.samples()).isEqualTo(2048);
        assertThat(t.reportedBy()).isEqualTo("Drums");
    }

    @Test
    void shouldConvertSamplesToMillis() {
        LatencyTelemetry t = new LatencyTelemetry("plugin:Limiter", NodeKind.PLUGIN, 2048, "Mastering Limiter");
        // 2048 / 48000 * 1000 ≈ 42.667 ms
        assertThat(t.millis(48_000.0)).isEqualTo((2048.0 / 48_000.0) * 1_000.0);
    }

    @Test
    void shouldAllowZeroLatency() {
        LatencyTelemetry t = new LatencyTelemetry("bus:Master", NodeKind.MASTER, 0, "Master");
        assertThat(t.samples()).isZero();
        assertThat(t.millis(44_100.0)).isZero();
    }

    @Test
    void shouldRejectNegativeSamples() {
        assertThatThrownBy(() -> new LatencyTelemetry("id", NodeKind.PLUGIN, -1, "plug"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullFields() {
        assertThatThrownBy(() -> new LatencyTelemetry(null, NodeKind.PLUGIN, 0, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LatencyTelemetry("id", null, 0, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LatencyTelemetry("id", NodeKind.PLUGIN, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidSampleRateForMillisConversion() {
        LatencyTelemetry t = new LatencyTelemetry("id", NodeKind.PLUGIN, 100, "x");
        assertThatThrownBy(() -> t.millis(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.millis(-48_000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nodeKindShouldEnumerateAllGraphKinds() {
        assertThat(NodeKind.values())
                .containsExactly(NodeKind.PLUGIN, NodeKind.TRACK, NodeKind.BUS, NodeKind.SEND, NodeKind.MASTER);
    }
}
