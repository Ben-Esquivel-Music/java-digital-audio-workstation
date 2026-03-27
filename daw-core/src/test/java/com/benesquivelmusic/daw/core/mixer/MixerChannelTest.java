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

    // ── Send management tests ───────────────────────────────────────────────

    @Test
    void shouldStartWithEmptySends() {
        MixerChannel channel = new MixerChannel("Ch");
        assertThat(channel.getSends()).isEmpty();
    }

    @Test
    void shouldAddSend() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target, 0.5, SendMode.POST_FADER);

        channel.addSend(send);

        assertThat(channel.getSends()).hasSize(1);
        assertThat(channel.getSends().get(0)).isSameAs(send);
    }

    @Test
    void shouldRemoveSend() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target, 0.5, SendMode.POST_FADER);
        channel.addSend(send);

        boolean removed = channel.removeSend(send);

        assertThat(removed).isTrue();
        assertThat(channel.getSends()).isEmpty();
    }

    @Test
    void shouldGetSendForTarget() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel bus1 = new MixerChannel("Bus1");
        MixerChannel bus2 = new MixerChannel("Bus2");
        Send send1 = new Send(bus1, 0.3, SendMode.POST_FADER);
        Send send2 = new Send(bus2, 0.7, SendMode.PRE_FADER);
        channel.addSend(send1);
        channel.addSend(send2);

        assertThat(channel.getSendForTarget(bus1)).isSameAs(send1);
        assertThat(channel.getSendForTarget(bus2)).isSameAs(send2);
    }

    @Test
    void shouldReturnNullForUnknownSendTarget() {
        MixerChannel channel = new MixerChannel("Ch");
        MixerChannel unknownBus = new MixerChannel("Unknown");

        assertThat(channel.getSendForTarget(unknownBus)).isNull();
    }

    @Test
    void shouldReturnUnmodifiableSendList() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThatThrownBy(() -> channel.getSends().add(new Send(new MixerChannel("Bus"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullSend() {
        MixerChannel channel = new MixerChannel("Ch");

        assertThatThrownBy(() -> channel.addSend(null))
                .isInstanceOf(NullPointerException.class);
    }
}
