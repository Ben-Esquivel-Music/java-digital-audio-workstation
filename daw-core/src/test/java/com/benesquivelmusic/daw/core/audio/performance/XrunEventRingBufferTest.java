package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.core.audio.performance.XrunEventRingBuffer.Kind;
import com.benesquivelmusic.daw.core.audio.performance.XrunEventRingBuffer.XrunSnapshot;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XrunEventRingBufferTest {

    @Test
    void shouldRoundUpCapacityToPowerOfTwo() {
        XrunEventRingBuffer rb = new XrunEventRingBuffer(5);
        assertThat(rb.capacity()).isEqualTo(8);
    }

    @Test
    void shouldPublishAndPollBufferLate() {
        XrunEventRingBuffer rb = new XrunEventRingBuffer(4);
        assertThat(rb.publishBufferLate(42L, 1_500_000L)).isTrue();

        XrunSnapshot s = rb.poll();
        assertThat(s).isNotNull();
        assertThat(s.kind()).isEqualTo(Kind.BUFFER_LATE);
        assertThat(s.frameIndex()).isEqualTo(42L);
        assertThat(s.deadlineMissNanos()).isEqualTo(1_500_000L);

        XrunEvent event = s.toImmutable();
        assertThat(event).isInstanceOf(XrunEvent.BufferLate.class);

        assertThat(rb.poll()).isNull(); // drained
    }

    @Test
    void shouldPublishAndPollGraphOverload() {
        XrunEventRingBuffer rb = new XrunEventRingBuffer(2);
        rb.publishGraphOverload("track-7", 1.25);

        XrunSnapshot s = rb.poll();
        assertThat(s).isNotNull();
        assertThat(s.kind()).isEqualTo(Kind.GRAPH_OVERLOAD);
        assertThat(s.offendingNodeId()).isEqualTo("track-7");
        assertThat(s.cpuFraction()).isEqualTo(1.25);
        assertThat(s.toImmutable()).isInstanceOf(XrunEvent.GraphOverload.class);
    }

    @Test
    void shouldPublishAndPollBufferDropped() {
        XrunEventRingBuffer rb = new XrunEventRingBuffer(1);
        assertThat(rb.publishBufferDropped(9L)).isTrue();
        XrunSnapshot s = rb.poll();
        assertThat(s.kind()).isEqualTo(Kind.BUFFER_DROPPED);
        assertThat(s.toImmutable()).isEqualTo(new XrunEvent.BufferDropped(9L));
    }

    @Test
    void shouldReturnFalseWhenFull() {
        XrunEventRingBuffer rb = new XrunEventRingBuffer(2); // capacity 2 (power of two)
        assertThat(rb.publishBufferDropped(1)).isTrue();
        assertThat(rb.publishBufferDropped(2)).isTrue();
        assertThat(rb.publishBufferDropped(3)).isFalse();

        // Drain one; should accept again.
        rb.poll();
        assertThat(rb.publishBufferDropped(3)).isTrue();
    }

    @Test
    void rejectsInvalidArgumentsInsteadOfThrowingOnAudioThread() {
        XrunEventRingBuffer rb = new XrunEventRingBuffer(2);
        assertThat(rb.publishBufferLate(0, -1L)).isFalse();          // negative deadline miss
        assertThat(rb.publishGraphOverload(null, 0.5)).isFalse();    // null node id
        assertThat(rb.publishGraphOverload("x", -0.1)).isFalse();    // negative CPU
        assertThat(rb.publishGraphOverload("x", Double.NaN)).isFalse();
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThatThrownBy(() -> new XrunEventRingBuffer(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
