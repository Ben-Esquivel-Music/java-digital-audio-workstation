package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioBufferPoolTest {

    @Test
    void shouldCreatePoolWithCorrectCapacity() {
        var pool = new AudioBufferPool(4, 2, 256);

        assertThat(pool.capacity()).isEqualTo(4);
        assertThat(pool.available()).isEqualTo(4);
        assertThat(pool.getChannels()).isEqualTo(2);
        assertThat(pool.getFrames()).isEqualTo(256);
    }

    @Test
    void shouldAcquireAndReleaseBuffers() {
        var pool = new AudioBufferPool(2, 1, 128);

        var buf1 = pool.acquire();
        assertThat(buf1).isNotNull();
        assertThat(pool.available()).isEqualTo(1);

        var buf2 = pool.acquire();
        assertThat(buf2).isNotNull();
        assertThat(pool.available()).isZero();

        // Pool is exhausted
        assertThat(pool.acquire()).isNull();

        // Release one back
        assertThat(pool.release(buf1)).isTrue();
        assertThat(pool.available()).isEqualTo(1);

        // Can acquire again
        var buf3 = pool.acquire();
        assertThat(buf3).isNotNull();
    }

    @Test
    void shouldReturnClearedBuffers() {
        var pool = new AudioBufferPool(1, 1, 4);

        var buf = pool.acquire();
        assertThat(buf).isNotNull();

        // Write some data
        buf.setSample(0, 0, 0.5f);
        buf.setSample(0, 1, 0.7f);

        // Return and re-acquire
        pool.release(buf);
        var reacquired = pool.acquire();
        assertThat(reacquired).isNotNull();

        // Should be cleared
        assertThat(reacquired.getSample(0, 0)).isZero();
        assertThat(reacquired.getSample(0, 1)).isZero();
    }

    @Test
    void shouldReturnFalseWhenReleasingToFullPool() {
        var pool = new AudioBufferPool(1, 1, 4);

        // Pool is already full (nothing acquired)
        assertThat(pool.release(new AudioBuffer(1, 4))).isFalse();
    }

    @Test
    void shouldRejectInvalidPoolSize() {
        assertThatThrownBy(() -> new AudioBufferPool(0, 2, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidChannels() {
        assertThatThrownBy(() -> new AudioBufferPool(4, 0, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidFrames() {
        assertThatThrownBy(() -> new AudioBufferPool(4, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProvideBuffersWithCorrectDimensions() {
        var pool = new AudioBufferPool(2, 2, 512);

        var buf = pool.acquire();
        assertThat(buf).isNotNull();
        assertThat(buf.getChannels()).isEqualTo(2);
        assertThat(buf.getFrames()).isEqualTo(512);
    }
}
