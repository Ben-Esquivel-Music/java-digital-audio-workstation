package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeAudioBufferPoolTest {

    @Test
    void shouldPreAllocateAndAcquireReleaseOffHeapBuffers() {
        try (NativeAudioBufferPool pool = new NativeAudioBufferPool(2, 2, 64)) {
            assertThat(pool.capacity()).isEqualTo(2);
            assertThat(pool.available()).isEqualTo(2);
            assertThat(pool.getChannels()).isEqualTo(2);
            assertThat(pool.getFrames()).isEqualTo(64);

            NativeAudioBufferPool.PooledBuffer a = pool.acquire();
            assertThat(a).isNotNull();
            assertThat(a.channels()).isEqualTo(2);
            assertThat(a.frames()).isEqualTo(64);
            // Off-heap: segment must not be the null segment and must be native.
            MemorySegment seg = a.segment();
            assertThat(seg.byteSize()).isEqualTo(2L * 64 * Float.BYTES);
            assertThat(seg.isNative()).isTrue();

            // Channel slices alias the full segment, zero copy.
            MemorySegment ch0 = a.channel(0);
            assertThat(ch0.byteSize()).isEqualTo(64L * Float.BYTES);

            assertThat(pool.release(a)).isTrue();
            assertThat(pool.available()).isEqualTo(2);
        }
    }

    @Test
    void acquireReturnsNullWhenExhaustedRatherThanAllocating() {
        try (NativeAudioBufferPool pool = new NativeAudioBufferPool(1, 1, 8)) {
            NativeAudioBufferPool.PooledBuffer b = pool.acquire();
            assertThat(b).isNotNull();
            assertThat(pool.acquire()).isNull();
        }
    }

    @Test
    void acquiredBufferIsClearedEvenAfterReuse() {
        try (NativeAudioBufferPool pool = new NativeAudioBufferPool(1, 1, 4)) {
            NativeAudioBufferPool.PooledBuffer b = pool.acquire();
            b.setSample(0, 0, 0.9f);
            b.setSample(0, 3, -0.7f);

            pool.release(b);
            NativeAudioBufferPool.PooledBuffer b2 = pool.acquire();
            assertThat(b2.getSample(0, 0)).isZero();
            assertThat(b2.getSample(0, 3)).isZero();
        }
    }

    @Test
    void closingPoolFreesNativeMemoryAndBlocksAcquire() {
        NativeAudioBufferPool pool = new NativeAudioBufferPool(1, 1, 4);
        pool.close();
        assertThat(pool.isClosed()).isTrue();
        // Idempotent.
        pool.close();
        assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> new NativeAudioBufferPool(0, 1, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NativeAudioBufferPool(1, 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NativeAudioBufferPool(1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void poolBuffersAreContiguousAndIndependent() {
        try (NativeAudioBufferPool pool = new NativeAudioBufferPool(3, 1, 4)) {
            NativeAudioBufferPool.PooledBuffer a = pool.acquire();
            NativeAudioBufferPool.PooledBuffer b = pool.acquire();
            a.setSample(0, 0, 0.25f);
            b.setSample(0, 0, -0.5f);
            assertThat(a.getSample(0, 0)).isEqualTo(0.25f);
            assertThat(b.getSample(0, 0)).isEqualTo(-0.5f);
        }
    }

    @Test
    void releaseNullBufferThrowsNullPointerException() {
        try (NativeAudioBufferPool pool = new NativeAudioBufferPool(1, 1, 4)) {
            assertThatThrownBy(() -> pool.release(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void releaseForeignBufferReturnsFalse() {
        try (NativeAudioBufferPool poolA = new NativeAudioBufferPool(1, 1, 4);
             NativeAudioBufferPool poolB = new NativeAudioBufferPool(1, 1, 4)) {
            NativeAudioBufferPool.PooledBuffer bufA = poolA.acquire();
            assertThat(bufA).isNotNull();
            // Returning a buffer from poolA into poolB must be rejected.
            assertThat(poolB.release(bufA)).isFalse();
            // poolB should still be full (nothing was returned).
            assertThat(poolB.available()).isEqualTo(1);
        }
    }
}

