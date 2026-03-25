package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerChannelTest {

    @Test
    void shouldCreateChannelWithDefaults() {
        MixerChannel channel = new MixerChannel("Guitar");

        assertThat(channel.getName()).isEqualTo("Guitar");
        assertThat(channel.getVolume()).isEqualTo(1.0);
        assertThat(channel.getPan()).isEqualTo(0.0);
        assertThat(channel.isMuted()).isFalse();
        assertThat(channel.isSolo()).isFalse();
        assertThat(channel.isPhaseInverted()).isFalse();
    }

    @Test
    void shouldSetVolumeWithinRange() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.setVolume(0.0);
        assertThat(channel.getVolume()).isEqualTo(0.0);
        channel.setVolume(1.0);
        assertThat(channel.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidVolume() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setVolume(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetPanWithinRange() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.setPan(-1.0);
        assertThat(channel.getPan()).isEqualTo(-1.0);
        channel.setPan(1.0);
        assertThat(channel.getPan()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidPan() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setPan(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setPan(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefaultSendLevelToZero() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThat(channel.getSendLevel()).isEqualTo(0.0);
    }

    @Test
    void shouldSetSendLevelWithinRange() {
        MixerChannel channel = new MixerChannel("Ch");
        channel.setSendLevel(0.0);
        assertThat(channel.getSendLevel()).isEqualTo(0.0);
        channel.setSendLevel(0.5);
        assertThat(channel.getSendLevel()).isEqualTo(0.5);
        channel.setSendLevel(1.0);
        assertThat(channel.getSendLevel()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidSendLevel() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThatThrownBy(() -> channel.setSendLevel(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> channel.setSendLevel(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldTogglePhaseInverted() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThat(channel.isPhaseInverted()).isFalse();
        channel.setPhaseInverted(true);
        assertThat(channel.isPhaseInverted()).isTrue();
        channel.setPhaseInverted(false);
        assertThat(channel.isPhaseInverted()).isFalse();
    }
}
