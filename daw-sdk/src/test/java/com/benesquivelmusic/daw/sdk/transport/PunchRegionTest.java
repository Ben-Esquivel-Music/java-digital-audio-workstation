package com.benesquivelmusic.daw.sdk.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PunchRegionTest {

    @Test
    void shouldConstructWithValidBoundaries() {
        PunchRegion region = new PunchRegion(100L, 200L, true);

        assertThat(region.startFrames()).isEqualTo(100L);
        assertThat(region.endFrames()).isEqualTo(200L);
        assertThat(region.enabled()).isTrue();
        assertThat(region.durationFrames()).isEqualTo(100L);
    }

    @Test
    void shouldRejectNegativeStart() {
        assertThatThrownBy(() -> new PunchRegion(-1L, 100L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startFrames");
    }

    @Test
    void shouldRejectEndLessThanOrEqualToStart() {
        assertThatThrownBy(() -> new PunchRegion(100L, 100L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endFrames");
        assertThatThrownBy(() -> new PunchRegion(100L, 50L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endFrames");
    }

    @Test
    void enabledFactoryShouldSetEnabledTrue() {
        PunchRegion region = PunchRegion.enabled(10L, 20L);

        assertThat(region.enabled()).isTrue();
        assertThat(region.startFrames()).isEqualTo(10L);
        assertThat(region.endFrames()).isEqualTo(20L);
    }

    @Test
    void withEnabledShouldReturnCopyWithFlagChanged() {
        PunchRegion original = new PunchRegion(10L, 20L, true);

        PunchRegion disabled = original.withEnabled(false);

        assertThat(disabled.startFrames()).isEqualTo(10L);
        assertThat(disabled.endFrames()).isEqualTo(20L);
        assertThat(disabled.enabled()).isFalse();
        // original unchanged
        assertThat(original.enabled()).isTrue();
    }

    @Test
    void containsFrameShouldBeHalfOpen() {
        PunchRegion region = new PunchRegion(100L, 200L, true);

        assertThat(region.containsFrame(99L)).isFalse();
        assertThat(region.containsFrame(100L)).isTrue();  // inclusive start
        assertThat(region.containsFrame(150L)).isTrue();
        assertThat(region.containsFrame(199L)).isTrue();
        assertThat(region.containsFrame(200L)).isFalse(); // exclusive end
    }
}
