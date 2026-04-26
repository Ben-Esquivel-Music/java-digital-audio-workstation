package com.benesquivelmusic.daw.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerChannelTest {

    @Test
    void of_createsDefaultChannel() {
        MixerChannel c = MixerChannel.of("Drums");
        assertThat(c.name()).isEqualTo("Drums");
        assertThat(c.volume()).isEqualTo(1.0);
        assertThat(c.pan()).isEqualTo(0.0);
        assertThat(c.muted()).isFalse();
        assertThat(c.solo()).isFalse();
        assertThat(c.phaseInverted()).isFalse();
        assertThat(c.sends()).isEmpty();
    }

    @Test
    void sendsList_isImmutable() {
        Send s = Send.of(UUID.randomUUID());
        MixerChannel c = MixerChannel.of("X").withSends(List.of(s));
        assertThatThrownBy(() -> c.sends().add(s))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withers_returnNewInstance() {
        MixerChannel a = MixerChannel.of("X");
        MixerChannel b = a.withMuted(true).withVolume(0.25);
        assertThat(a.muted()).isFalse();
        assertThat(b.muted()).isTrue();
        assertThat(b.volume()).isEqualTo(0.25);
    }

    @Test
    void invalidVolumeRange() {
        assertThatThrownBy(() -> MixerChannel.of("X").withVolume(2.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
