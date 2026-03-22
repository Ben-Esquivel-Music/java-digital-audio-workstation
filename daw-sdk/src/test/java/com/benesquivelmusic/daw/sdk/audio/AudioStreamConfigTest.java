package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioStreamConfigTest {

    @Test
    void shouldCreateOutputOnlyConfig() {
        AudioStreamConfig config = new AudioStreamConfig(-1, 0, 0, 2,
                SampleRate.HZ_44100, BufferSize.SAMPLES_256);
        assertThat(config.hasInput()).isFalse();
        assertThat(config.hasOutput()).isTrue();
    }

    @Test
    void shouldCreateInputOnlyConfig() {
        AudioStreamConfig config = new AudioStreamConfig(0, -1, 2, 0,
                SampleRate.HZ_48000, BufferSize.SAMPLES_128);
        assertThat(config.hasInput()).isTrue();
        assertThat(config.hasOutput()).isFalse();
    }

    @Test
    void shouldCreateFullDuplexConfig() {
        AudioStreamConfig config = new AudioStreamConfig(0, 1, 2, 2,
                SampleRate.HZ_96000, BufferSize.SAMPLES_64);
        assertThat(config.hasInput()).isTrue();
        assertThat(config.hasOutput()).isTrue();
    }

    @Test
    void shouldRejectBothChannelsZero() {
        assertThatThrownBy(() -> new AudioStreamConfig(0, 0, 0, 0,
                SampleRate.HZ_44100, BufferSize.SAMPLES_256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeInputChannels() {
        assertThatThrownBy(() -> new AudioStreamConfig(0, 0, -1, 2,
                SampleRate.HZ_44100, BufferSize.SAMPLES_256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDeviceIndex() {
        assertThatThrownBy(() -> new AudioStreamConfig(-2, 0, 0, 2,
                SampleRate.HZ_44100, BufferSize.SAMPLES_256))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
