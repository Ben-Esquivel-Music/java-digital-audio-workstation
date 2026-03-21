package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioBufferTest {

    @Test
    void shouldCreateSilentBuffer() {
        var buffer = new AudioBuffer(2, 128);

        assertThat(buffer.getChannels()).isEqualTo(2);
        assertThat(buffer.getFrames()).isEqualTo(128);
        assertThat(buffer.getSample(0, 0)).isZero();
        assertThat(buffer.getSample(1, 127)).isZero();
    }

    @Test
    void shouldSetAndGetSamples() {
        var buffer = new AudioBuffer(1, 4);
        buffer.setSample(0, 0, 0.5f);
        buffer.setSample(0, 1, -0.5f);
        buffer.setSample(0, 2, 1.0f);
        buffer.setSample(0, 3, -1.0f);

        assertThat(buffer.getSample(0, 0)).isEqualTo(0.5f);
        assertThat(buffer.getSample(0, 1)).isEqualTo(-0.5f);
        assertThat(buffer.getSample(0, 2)).isEqualTo(1.0f);
        assertThat(buffer.getSample(0, 3)).isEqualTo(-1.0f);
    }

    @Test
    void shouldClearBuffer() {
        var buffer = new AudioBuffer(2, 4);
        buffer.setSample(0, 0, 0.9f);
        buffer.setSample(1, 3, -0.9f);

        buffer.clear();

        assertThat(buffer.getSample(0, 0)).isZero();
        assertThat(buffer.getSample(1, 3)).isZero();
    }

    @Test
    void shouldProvideChannelDataArray() {
        var buffer = new AudioBuffer(2, 2);
        buffer.setSample(0, 0, 0.1f);
        buffer.setSample(0, 1, 0.2f);

        float[] channelData = buffer.getChannelData(0);
        assertThat(channelData).containsExactly(0.1f, 0.2f);
    }

    @Test
    void shouldRejectNonPositiveChannels() {
        assertThatThrownBy(() -> new AudioBuffer(0, 128))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveFrames() {
        assertThatThrownBy(() -> new AudioBuffer(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
