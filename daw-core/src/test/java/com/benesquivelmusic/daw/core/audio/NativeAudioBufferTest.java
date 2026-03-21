package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeAudioBufferTest {

    @Test
    void shouldCreateSilentBuffer() {
        try (var buffer = new NativeAudioBuffer(2, 128)) {
            assertThat(buffer.getChannels()).isEqualTo(2);
            assertThat(buffer.getFrames()).isEqualTo(128);
            assertThat(buffer.getSample(0, 0)).isZero();
            assertThat(buffer.getSample(1, 127)).isZero();
        }
    }

    @Test
    void shouldSetAndGetSamples() {
        try (var buffer = new NativeAudioBuffer(1, 4)) {
            buffer.setSample(0, 0, 0.5f);
            buffer.setSample(0, 1, -0.5f);
            buffer.setSample(0, 2, 1.0f);
            buffer.setSample(0, 3, -1.0f);

            assertThat(buffer.getSample(0, 0)).isEqualTo(0.5f);
            assertThat(buffer.getSample(0, 1)).isEqualTo(-0.5f);
            assertThat(buffer.getSample(0, 2)).isEqualTo(1.0f);
            assertThat(buffer.getSample(0, 3)).isEqualTo(-1.0f);
        }
    }

    @Test
    void shouldClearBuffer() {
        try (var buffer = new NativeAudioBuffer(2, 4)) {
            buffer.setSample(0, 0, 0.9f);
            buffer.setSample(1, 3, -0.9f);

            buffer.clear();

            assertThat(buffer.getSample(0, 0)).isZero();
            assertThat(buffer.getSample(1, 3)).isZero();
        }
    }

    @Test
    void shouldProvideChannelData() {
        try (var buffer = new NativeAudioBuffer(2, 3)) {
            buffer.setSample(0, 0, 0.1f);
            buffer.setSample(0, 1, 0.2f);
            buffer.setSample(0, 2, 0.3f);

            float[] data = buffer.getChannelData(0);
            assertThat(data).containsExactly(0.1f, 0.2f, 0.3f);
        }
    }

    @Test
    void shouldSetChannelData() {
        try (var buffer = new NativeAudioBuffer(1, 3)) {
            buffer.setChannelData(0, new float[]{0.4f, 0.5f, 0.6f});

            assertThat(buffer.getSample(0, 0)).isEqualTo(0.4f);
            assertThat(buffer.getSample(0, 1)).isEqualTo(0.5f);
            assertThat(buffer.getSample(0, 2)).isEqualTo(0.6f);
        }
    }

    @Test
    void shouldReportCorrectSizeBytes() {
        try (var buffer = new NativeAudioBuffer(2, 256)) {
            assertThat(buffer.sizeBytes()).isEqualTo(2L * 256 * Float.BYTES);
        }
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        assertThatThrownBy(() -> new NativeAudioBuffer(0, 128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveFrames() {
        assertThatThrownBy(() -> new NativeAudioBuffer(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectOutOfBoundsChannel() {
        try (var buffer = new NativeAudioBuffer(2, 4)) {
            assertThatThrownBy(() -> buffer.getSample(2, 0))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void shouldRejectOutOfBoundsFrame() {
        try (var buffer = new NativeAudioBuffer(2, 4)) {
            assertThatThrownBy(() -> buffer.getSample(0, 4))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void shouldRejectShortChannelData() {
        try (var buffer = new NativeAudioBuffer(1, 4)) {
            assertThatThrownBy(() -> buffer.setChannelData(0, new float[]{0.1f, 0.2f}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldExposeMemorySegment() {
        try (var buffer = new NativeAudioBuffer(1, 4)) {
            assertThat(buffer.segment()).isNotNull();
            assertThat(buffer.segment().byteSize()).isEqualTo(4L * Float.BYTES);
        }
    }
}
