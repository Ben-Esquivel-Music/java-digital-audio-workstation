package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendTest {

    @Test
    void shouldCreateSendWithDefaults() {
        MixerChannel target = new MixerChannel("Reverb Return");
        Send send = new Send(target);

        assertThat(send.getTarget()).isSameAs(target);
        assertThat(send.getLevel()).isEqualTo(0.0);
        assertThat(send.getMode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void shouldCreateSendWithAllParameters() {
        MixerChannel target = new MixerChannel("Delay Return");
        Send send = new Send(target, 0.75, SendMode.PRE_FADER);

        assertThat(send.getTarget()).isSameAs(target);
        assertThat(send.getLevel()).isEqualTo(0.75);
        assertThat(send.getMode()).isEqualTo(SendMode.PRE_FADER);
    }

    @Test
    void shouldSetLevel() {
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target);

        send.setLevel(0.5);
        assertThat(send.getLevel()).isEqualTo(0.5);

        send.setLevel(0.0);
        assertThat(send.getLevel()).isEqualTo(0.0);

        send.setLevel(1.0);
        assertThat(send.getLevel()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidLevel() {
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target);

        assertThatThrownBy(() -> send.setLevel(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send.setLevel(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidLevelInConstructor() {
        MixerChannel target = new MixerChannel("Bus");

        assertThatThrownBy(() -> new Send(target, -0.1, SendMode.POST_FADER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Send(target, 1.1, SendMode.POST_FADER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetMode() {
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target);

        send.setMode(SendMode.PRE_FADER);
        assertThat(send.getMode()).isEqualTo(SendMode.PRE_FADER);

        send.setMode(SendMode.POST_FADER);
        assertThat(send.getMode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void shouldRejectNullTarget() {
        assertThatThrownBy(() -> new Send(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMode() {
        MixerChannel target = new MixerChannel("Bus");

        assertThatThrownBy(() -> new Send(target, 0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullModeOnSet() {
        MixerChannel target = new MixerChannel("Bus");
        Send send = new Send(target);

        assertThatThrownBy(() -> send.setMode(null))
                .isInstanceOf(NullPointerException.class);
    }
}
