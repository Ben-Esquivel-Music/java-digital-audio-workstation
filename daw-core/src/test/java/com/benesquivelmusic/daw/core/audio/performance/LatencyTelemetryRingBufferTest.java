package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.core.audio.performance.LatencyTelemetryRingBuffer.LatencySnapshot;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry.NodeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyTelemetryRingBufferTest {

    @Test
    void shouldPublishAndPollSnapshot() {
        LatencyTelemetryRingBuffer rb = new LatencyTelemetryRingBuffer(4);
        assertThat(rb.capacity()).isEqualTo(4);

        assertThat(rb.publish("track-1", NodeKind.TRACK, 128, "FabFilter Pro-Q")).isTrue();
        LatencySnapshot s = rb.poll();
        assertThat(s).isNotNull();
        assertThat(s.nodeId()).isEqualTo("track-1");
        assertThat(s.kind()).isEqualTo(NodeKind.TRACK);
        assertThat(s.samples()).isEqualTo(128);
        assertThat(s.reportedBy()).isEqualTo("FabFilter Pro-Q");

        LatencyTelemetry lt = s.toImmutable();
        assertThat(lt).isEqualTo(new LatencyTelemetry("track-1", NodeKind.TRACK, 128, "FabFilter Pro-Q"));

        assertThat(rb.poll()).isNull();
    }

    @Test
    void shouldRejectInvalidPublishInsteadOfThrowing() {
        LatencyTelemetryRingBuffer rb = new LatencyTelemetryRingBuffer(2);
        assertThat(rb.publish(null, NodeKind.BUS, 0, "x")).isFalse();
        assertThat(rb.publish("a", null, 0, "x")).isFalse();
        assertThat(rb.publish("a", NodeKind.BUS, -1, "x")).isFalse();
        assertThat(rb.publish("a", NodeKind.BUS, 0, null)).isFalse();
    }

    @Test
    void shouldReturnFalseWhenFull() {
        LatencyTelemetryRingBuffer rb = new LatencyTelemetryRingBuffer(2);
        assertThat(rb.publish("a", NodeKind.BUS, 0, "x")).isTrue();
        assertThat(rb.publish("b", NodeKind.BUS, 0, "x")).isTrue();
        assertThat(rb.publish("c", NodeKind.BUS, 0, "x")).isFalse();
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new LatencyTelemetryRingBuffer(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
