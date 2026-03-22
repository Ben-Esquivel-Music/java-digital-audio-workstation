package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerTest {

    @Test
    void shouldStartWithEmptyChannelListAndMaster() {
        Mixer mixer = new Mixer();

        assertThat(mixer.getChannelCount()).isZero();
        assertThat(mixer.getChannels()).isEmpty();
        assertThat(mixer.getMasterChannel()).isNotNull();
        assertThat(mixer.getMasterChannel().getName()).isEqualTo("Master");
    }

    @Test
    void shouldAddAndRemoveChannels() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Channel 1");
        MixerChannel ch2 = new MixerChannel("Channel 2");

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
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));

        assertThatThrownBy(() -> mixer.getChannels().add(new MixerChannel("Illegal")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldMixDownSingleChannel() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, 0.5f, -0.5f}}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 3);

        assertThat(output[0]).containsExactly(1.0f, 0.5f, -0.5f);
    }

    @Test
    void shouldMixDownMultipleChannels() {
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));
        mixer.addChannel(new MixerChannel("Ch2"));

        float[][][] channelBuffers = {
                {{0.3f, 0.2f}},
                {{0.4f, 0.1f}}
        };
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        assertThat(output[0][0]).isEqualTo(0.7f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][1]).isEqualTo(0.3f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyChannelVolume() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setVolume(0.5);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, -1.0f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        assertThat(output[0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][1]).isEqualTo(-0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldMuteChannel() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setMuted(true);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, 0.5f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        assertThat(output[0]).containsExactly(0.0f, 0.0f);
    }

    @Test
    void shouldSoloChannel() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        ch2.setSolo(true);
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        float[][][] channelBuffers = {
                {{0.5f}},
                {{0.8f}}
        };
        float[][] output = {{0.0f}};

        mixer.mixDown(channelBuffers, output, 1);

        // Only ch2 (solo) should be heard
        assertThat(output[0][0]).isEqualTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyMasterVolume() {
        Mixer mixer = new Mixer();
        mixer.getMasterChannel().setVolume(0.5);
        MixerChannel ch = new MixerChannel("Ch1");
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};

        mixer.mixDown(channelBuffers, output, 1);

        assertThat(output[0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldSilenceOutputWhenMasterIsMuted() {
        Mixer mixer = new Mixer();
        mixer.getMasterChannel().setMuted(true);
        MixerChannel ch = new MixerChannel("Ch1");
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, 0.5f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        assertThat(output[0]).containsExactly(0.0f, 0.0f);
    }

    @Test
    void shouldHandleEmptyMixDown() {
        Mixer mixer = new Mixer();

        float[][][] channelBuffers = {};
        float[][] output = {{0.5f, 0.5f}};

        mixer.mixDown(channelBuffers, output, 2);

        // Should be silent after mixDown
        assertThat(output[0]).containsExactly(0.0f, 0.0f);
    }

    @Test
    void shouldApplyPanFullLeft() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setPan(-1.0); // full left
        mixer.addChannel(ch);

        // Mono source into stereo output
        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}, {0.0f}};

        mixer.mixDown(channelBuffers, output, 1);

        // Full left: all signal in left channel, none in right
        assertThat(output[0][0]).isEqualTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[1][0]).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyPanFullRight() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setPan(1.0); // full right
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}, {0.0f}};

        mixer.mixDown(channelBuffers, output, 1);

        // Full right: no signal in left channel, all in right
        assertThat(output[0][0]).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[1][0]).isEqualTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyPanCenter() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setPan(0.0); // center
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}, {0.0f}};

        mixer.mixDown(channelBuffers, output, 1);

        // Center: equal power in both channels (cos(π/4) = sin(π/4) ≈ 0.707)
        float expected = (float) Math.cos(Math.PI / 4.0);
        assertThat(output[0][0]).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(output[1][0]).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-5f));
    }
}
