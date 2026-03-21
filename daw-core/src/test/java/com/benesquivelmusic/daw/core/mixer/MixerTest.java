package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerTest {

    @Test
    void shouldStartWithEmptyChannelListAndMaster() {
        var mixer = new Mixer();

        assertThat(mixer.getChannelCount()).isZero();
        assertThat(mixer.getChannels()).isEmpty();
        assertThat(mixer.getMasterChannel()).isNotNull();
        assertThat(mixer.getMasterChannel().getName()).isEqualTo("Master");
    }

    @Test
    void shouldAddAndRemoveChannels() {
        var mixer = new Mixer();
        var ch1 = new MixerChannel("Channel 1");
        var ch2 = new MixerChannel("Channel 2");

        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        assertThat(mixer.getChannelCount()).isEqualTo(2);
        assertThat(mixer.getChannels()).containsExactly(ch1, ch2);

        assertThat(mixer.removeChannel(ch1)).isTrue();
        assertThat(mixer.getChannelCount()).isEqualTo(1);
        assertThat(mixer.getChannels()).containsExactly(ch2);
    }

    @Test
    void shouldReturnUnmodifiableChannelList() {
        var mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));

        assertThatThrownBy(() -> mixer.getChannels().add(new MixerChannel("Illegal")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
