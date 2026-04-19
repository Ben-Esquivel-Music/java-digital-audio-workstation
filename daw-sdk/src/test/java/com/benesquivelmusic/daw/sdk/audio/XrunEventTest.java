package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XrunEventTest {

    @Test
    void bufferLateCarriesFrameAndDeadlineMiss() {
        XrunEvent.BufferLate e = new XrunEvent.BufferLate(42L, Duration.ofMillis(3));
        assertThat(e.frameIndex()).isEqualTo(42L);
        assertThat(e.deadlineMiss()).isEqualTo(Duration.ofMillis(3));
    }

    @Test
    void bufferLateRejectsNegativeDeadlineMiss() {
        assertThatThrownBy(() -> new XrunEvent.BufferLate(0L, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bufferLateRejectsNullDeadlineMiss() {
        assertThatThrownBy(() -> new XrunEvent.BufferLate(0L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bufferDroppedCarriesFrameIndex() {
        XrunEvent.BufferDropped e = new XrunEvent.BufferDropped(7L);
        assertThat(e.frameIndex()).isEqualTo(7L);
    }

    @Test
    void graphOverloadCarriesNodeIdAndCpuFraction() {
        XrunEvent.GraphOverload e = new XrunEvent.GraphOverload("reverb-1", 1.25);
        assertThat(e.offendingNodeId()).isEqualTo("reverb-1");
        assertThat(e.cpuFraction()).isEqualTo(1.25);
        assertThat(e.frameIndex()).isEqualTo(-1L);
    }

    @Test
    void graphOverloadRejectsNullNodeId() {
        assertThatThrownBy(() -> new XrunEvent.GraphOverload(null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void graphOverloadRejectsNegativeCpuFraction() {
        assertThatThrownBy(() -> new XrunEvent.GraphOverload("n", -0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void graphOverloadRejectsNaNCpuFraction() {
        assertThatThrownBy(() -> new XrunEvent.GraphOverload("n", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternMatchingExhaustiveOverSealedHierarchy() {
        // Exhaustive switch over the sealed hierarchy — compiles only if
        // all permitted subtypes are handled (Project Amber, JEP 441).
        XrunEvent e = new XrunEvent.BufferLate(1L, Duration.ofNanos(500));
        String tag = switch (e) {
            case XrunEvent.BufferLate bl     -> "late-" + bl.frameIndex();
            case XrunEvent.BufferDropped bd  -> "drop-" + bd.frameIndex();
            case XrunEvent.GraphOverload go  -> "overload-" + go.offendingNodeId();
        };
        assertThat(tag).isEqualTo("late-1");
    }
}
