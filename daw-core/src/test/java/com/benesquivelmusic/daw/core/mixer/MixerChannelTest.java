package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerChannelTest {

    @Test
    void shouldCreateChannelWithDefaults() {
        var channel = new MixerChannel("Guitar");

        assertThat(channel.getName()).isEqualTo("Guitar");
        assertThat(channel.getVolume()).isEqualTo(1.0);
        assertThat(channel.getPan()).isEqualTo(0.0);
        assertThat(channel.isMuted()).isFalse();
        assertThat(channel.isSolo()).isFalse();
    }

    @Test
    void shouldSetVolumeWithinRange() {
        var channel = new MixerChannel("Ch");
        channel.setVolume(0.0);
        assertThat(channel.getVolume()).isEqualTo(0.0);
        channel.setVolume(1.0);
        assertThat(channel.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidVolume() {
        var channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setVolume(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetPanWithinRange() {
        var channel = new MixerChannel("Ch");
        channel.setPan(-1.0);
        assertThat(channel.getPan()).isEqualTo(-1.0);
        channel.setPan(1.0);
        assertThat(channel.getPan()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidPan() {
        var channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setPan(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setPan(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
