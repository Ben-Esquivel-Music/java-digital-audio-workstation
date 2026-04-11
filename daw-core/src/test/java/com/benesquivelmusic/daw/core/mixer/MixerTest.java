package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

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

    @Test
    void shouldHaveAuxBus() {
        Mixer mixer = new Mixer();
        assertThat(mixer.getAuxBus()).isNotNull();
        assertThat(mixer.getAuxBus().getName()).isEqualTo("Reverb Return");
    }

    @Test
    void shouldRouteSendToAuxBuffer() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setSendLevel(0.5);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, -1.0f}}};
        float[][] output = {{0.0f, 0.0f}};
        float[][] auxOutput = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 2);

        assertThat(auxOutput[0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(auxOutput[0][1]).isEqualTo(-0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldNotRouteSendWhenSendLevelIsZero() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setSendLevel(0.0);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][] auxOutput = {{0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 1);

        assertThat(auxOutput[0][0]).isEqualTo(0.0f);
    }

    @Test
    void shouldApplyAuxBusVolumeToSendOutput() {
        Mixer mixer = new Mixer();
        mixer.getAuxBus().setVolume(0.5);
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setSendLevel(1.0);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][] auxOutput = {{0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 1);

        assertThat(auxOutput[0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldSilenceAuxOutputWhenAuxBusIsMuted() {
        Mixer mixer = new Mixer();
        mixer.getAuxBus().setMuted(true);
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setSendLevel(1.0);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][] auxOutput = {{0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 1);

        assertThat(auxOutput[0][0]).isEqualTo(0.0f);
    }

    @Test
    void shouldNotRouteSendForMutedChannel() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setSendLevel(1.0);
        ch.setMuted(true);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][] auxOutput = {{0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 1);

        assertThat(auxOutput[0][0]).isEqualTo(0.0f);
    }

    @Test
    void shouldSumMultipleChannelSendsIntoAuxBuffer() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        ch1.setSendLevel(0.5);
        MixerChannel ch2 = new MixerChannel("Ch2");
        ch2.setSendLevel(0.3);
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        float[][][] channelBuffers = {
                {{1.0f}},
                {{1.0f}}
        };
        float[][] output = {{0.0f}};
        float[][] auxOutput = {{0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 1);

        // 1.0 * 0.5 + 1.0 * 0.3 = 0.8
        assertThat(auxOutput[0][0]).isEqualTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    // ── Multiple return bus tests ───────────────────────────────────────────

    @Test
    void shouldStartWithOneDefaultReturnBus() {
        Mixer mixer = new Mixer();
        assertThat(mixer.getReturnBuses()).hasSize(1);
        assertThat(mixer.getReturnBuses().get(0).getName()).isEqualTo("Reverb Return");
        assertThat(mixer.getAuxBus()).isSameAs(mixer.getReturnBuses().get(0));
    }

    @Test
    void shouldAddReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");

        assertThat(mixer.getReturnBuses()).hasSize(2);
        assertThat(delayBus.getName()).isEqualTo("Delay Return");
        assertThat(mixer.getReturnBuses()).contains(delayBus);
    }

    @Test
    void shouldRemoveReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");

        boolean removed = mixer.removeReturnBus(delayBus);

        assertThat(removed).isTrue();
        assertThat(mixer.getReturnBuses()).hasSize(1);
    }

    @Test
    void shouldRemoveSendsWhenReturnBusIsRemoved() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addSend(new Send(delayBus, 0.5, SendMode.POST_FADER));
        mixer.addChannel(ch);

        mixer.removeReturnBus(delayBus);

        assertThat(ch.getSends()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableReturnBusList() {
        Mixer mixer = new Mixer();
        assertThatThrownBy(() -> mixer.getReturnBuses().add(new MixerChannel("Illegal")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReAddExistingReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        mixer.removeReturnBus(delayBus);
        assertThat(mixer.getReturnBuses()).hasSize(1);

        mixer.addReturnBus(delayBus);
        assertThat(mixer.getReturnBuses()).hasSize(2);
        assertThat(mixer.getReturnBuses()).contains(delayBus);
    }

    @Test
    void shouldNotDuplicateReturnBusOnReAdd() {
        Mixer mixer = new Mixer();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        assertThat(mixer.getReturnBuses()).hasSize(2);

        mixer.addReturnBus(delayBus);
        assertThat(mixer.getReturnBuses()).hasSize(2);
    }

    // ── Multi-bus mixDown tests ─────────────────────────────────────────────

    @Test
    void shouldRoutePreFaderSendToReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setVolume(0.5);
        ch.addSend(new Send(reverbBus, 0.8, SendMode.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        // Pre-fader: send level * raw signal, then return bus volume (1.0)
        assertThat(returnBuffers[0][0][0]).isEqualTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldRoutePostFaderSendToReturnBus() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setVolume(0.5);
        ch.addSend(new Send(reverbBus, 0.8, SendMode.POST_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        // Post-fader: channel volume * send level * raw signal, then return bus volume (1.0)
        assertThat(returnBuffers[0][0][0]).isEqualTo(0.4f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldSumReturnBusIntoMasterOutput() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addSend(new Send(reverbBus, 1.0, SendMode.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        // Main output = channel (1.0) + return bus (1.0) = 2.0
        assertThat(output[0][0]).isEqualTo(2.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldRouteToMultipleReturnBuses() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        MixerChannel delayBus = mixer.addReturnBus("Delay Return");
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addSend(new Send(reverbBus, 0.5, SendMode.PRE_FADER));
        ch.addSend(new Send(delayBus, 0.3, SendMode.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}, {{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        assertThat(returnBuffers[0][0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(returnBuffers[1][0][0]).isEqualTo(0.3f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyReturnBusVolumeInMultiBusMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        reverbBus.setVolume(0.5);
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addSend(new Send(reverbBus, 1.0, SendMode.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        assertThat(returnBuffers[0][0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldMuteReturnBusInMultiBusMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        reverbBus.setMuted(true);
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addSend(new Send(reverbBus, 1.0, SendMode.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        assertThat(returnBuffers[0][0][0]).isEqualTo(0.0f);
        // Master output should only have the channel's direct contribution
        assertThat(output[0][0]).isEqualTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldNotRouteSendForMutedChannelInMultiBusMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addSend(new Send(reverbBus, 1.0, SendMode.PRE_FADER));
        ch.setMuted(true);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        assertThat(returnBuffers[0][0][0]).isEqualTo(0.0f);
    }

    @Test
    void shouldSumMultipleChannelSendsInMultiBusMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();

        MixerChannel ch1 = new MixerChannel("Ch1");
        ch1.addSend(new Send(reverbBus, 0.5, SendMode.PRE_FADER));
        mixer.addChannel(ch1);

        MixerChannel ch2 = new MixerChannel("Ch2");
        ch2.addSend(new Send(reverbBus, 0.3, SendMode.PRE_FADER));
        mixer.addChannel(ch2);

        float[][][] channelBuffers = {{{1.0f}}, {{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        // 1.0 * 0.5 + 1.0 * 0.3 = 0.8
        assertThat(returnBuffers[0][0][0]).isEqualTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    // ── Move channel tests ──────────────────────────────────────────────────

    @Test
    void shouldMoveChannelForward() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        MixerChannel ch3 = new MixerChannel("Ch3");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);
        mixer.addChannel(ch3);

        mixer.moveChannel(0, 2);

        assertThat(mixer.getChannels()).containsExactly(ch2, ch3, ch1);
    }

    @Test
    void shouldMoveChannelBackward() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        MixerChannel ch3 = new MixerChannel("Ch3");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);
        mixer.addChannel(ch3);

        mixer.moveChannel(2, 0);

        assertThat(mixer.getChannels()).containsExactly(ch3, ch1, ch2);
    }

    @Test
    void shouldNoOpWhenMoveChannelToSameIndex() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        mixer.moveChannel(1, 1);

        assertThat(mixer.getChannels()).containsExactly(ch1, ch2);
    }

    @Test
    void shouldRejectMoveChannelWithNegativeFromIndex() {
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));

        assertThatThrownBy(() -> mixer.moveChannel(-1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldRejectMoveChannelWithFromIndexOutOfRange() {
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));

        assertThatThrownBy(() -> mixer.moveChannel(1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldRejectMoveChannelWithToIndexOutOfRange() {
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));

        assertThatThrownBy(() -> mixer.moveChannel(0, 1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // ── Insert effects during live playback tests ──────────────────────────

    @Test
    void shouldApplyInsertEffectsDuringMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new GainProcessor(0.5f)));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, -1.0f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        // Insert halves the signal, then volume (1.0) and master volume (1.0) preserve it
        assertThat(output[0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][1]).isEqualTo(-0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldBypassInsertEffectPassingAudioUnchanged() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new GainProcessor(0.0f)));
        ch.setInsertBypassed(0, true);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, 0.5f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        // Bypassed: zero-gain processor is skipped, audio passes through unchanged
        assertThat(output[0][0]).isEqualTo(1.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][1]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyMultipleInsertsInSeriesOrder() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        // First insert: halve the signal (1.0 -> 0.5)
        ch.addInsert(new InsertSlot("Gain 0.5", new GainProcessor(0.5f)));
        // Second insert: halve again (0.5 -> 0.25)
        ch.addInsert(new InsertSlot("Gain 0.5", new GainProcessor(0.5f)));
        ch.prepareEffectsChain(1, 2);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, 0.8f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        // Two 0.5x gains in series: 1.0 * 0.5 * 0.5 = 0.25
        assertThat(output[0][0]).isEqualTo(0.25f, org.assertj.core.data.Offset.offset(1e-6f));
        // 0.8 * 0.5 * 0.5 = 0.2
        assertThat(output[0][1]).isEqualTo(0.2f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyInsertEffectsInMultiBusMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel reverbBus = mixer.getAuxBus();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new GainProcessor(0.5f)));
        ch.addSend(new Send(reverbBus, 1.0, SendMode.PRE_FADER));
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f}}};
        float[][] output = {{0.0f}};
        float[][][] returnBuffers = {{{0.0f}}};

        mixer.mixDown(channelBuffers, output, returnBuffers, 1);

        // Insert halves the signal; pre-fader send taps post-effects audio
        assertThat(returnBuffers[0][0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyInsertEffectsInAuxSendMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new GainProcessor(0.5f)));
        ch.setSendLevel(1.0);
        mixer.addChannel(ch);

        float[][][] channelBuffers = {{{1.0f, -1.0f}}};
        float[][] output = {{0.0f, 0.0f}};
        float[][] auxOutput = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, auxOutput, 2);

        // Insert halves the signal; aux send taps post-insert audio
        assertThat(auxOutput[0][0]).isEqualTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(auxOutput[0][1]).isEqualTo(-0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldApplyInsertEffectsOnStereoChannelBuffers() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new GainProcessor(0.5f)));
        mixer.addChannel(ch);

        // Stereo source into stereo output
        float[][][] channelBuffers = {{{1.0f}, {0.8f}}};
        float[][] output = {{0.0f}, {0.0f}};

        mixer.mixDown(channelBuffers, output, 1);

        // Center pan (default): constant-power cos(π/4) ≈ 0.707
        float expected = (float) Math.cos(Math.PI / 4.0);
        // Left channel: 1.0 * 0.5 (insert) * 0.707 (pan)
        assertThat(output[0][0]).isCloseTo(0.5f * expected, org.assertj.core.data.Offset.offset(1e-5f));
        // Right channel: 0.8 * 0.5 (insert) * 0.707 (pan)
        assertThat(output[1][0]).isCloseTo(0.4f * expected, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void shouldApplyInsertEffectsCombinedWithChannelVolume() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new GainProcessor(0.5f)));
        ch.setVolume(0.5);
        mixer.addChannel(ch);

        // Mono source into mono output
        float[][][] channelBuffers = {{{1.0f, -1.0f}}};
        float[][] output = {{0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 2);

        // 1.0 * 0.5 (insert) * 0.5 (volume) = 0.25
        assertThat(output[0][0]).isEqualTo(0.25f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][1]).isEqualTo(-0.25f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldPrepareForPlaybackAllocatingIntermediateBuffers() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain 0.5", new GainProcessor(0.5f)));
        ch.addInsert(new InsertSlot("Gain 0.5", new GainProcessor(0.5f)));
        mixer.addChannel(ch);

        mixer.prepareForPlayback(1, 4);

        float[][][] channelBuffers = {{{1.0f, 0.8f, 0.6f, 0.4f}}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 4);

        assertThat(output[0][0]).isEqualTo(0.25f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][2]).isEqualTo(0.15f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    // --- Test processor ---

    private record GainProcessor(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] = inputBuffer[ch][i] * gain;
                }
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 1;
        }

        @Override
        public int getOutputChannelCount() {
            return 1;
        }
    }

    /**
     * A processor that reports latency but passes audio through unmodified.
     * Used for testing plugin delay compensation.
     */
    private record LatencyProcessor(int latency) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override public void reset() {}
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }

        @Override
        public int getLatencySamples() {
            return latency;
        }
    }

    // --- Plugin Delay Compensation tests ---

    @Test
    void shouldReportZeroSystemLatencyWithNoInserts() {
        Mixer mixer = new Mixer();
        mixer.addChannel(new MixerChannel("Ch1"));
        mixer.addChannel(new MixerChannel("Ch2"));

        assertThat(mixer.getSystemLatencySamples()).isZero();
    }

    @Test
    void shouldReportCorrectSystemLatency() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(256)));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        assertThat(mixer.getSystemLatencySamples()).isEqualTo(256);
    }

    @Test
    void channelWithNoInsertsShouldGetMaximumCompensation() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("NoInserts");
        MixerChannel ch2 = new MixerChannel("WithInserts");
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(100)));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        var pdc = mixer.getDelayCompensation();
        assertThat(pdc.getChannelCompensationSamples(0)).isEqualTo(100);
        assertThat(pdc.getChannelCompensationSamples(1)).isZero();
    }

    @Test
    void channelWithHighestLatencyShouldGetZeroCompensation() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Low");
        MixerChannel ch2 = new MixerChannel("High");
        ch1.addInsert(new InsertSlot("Low", new LatencyProcessor(50)));
        ch2.addInsert(new InsertSlot("High", new LatencyProcessor(300)));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        var pdc = mixer.getDelayCompensation();
        assertThat(pdc.getChannelCompensationSamples(1)).isZero();
        assertThat(pdc.getChannelCompensationSamples(0)).isEqualTo(250);
    }

    @Test
    void compensationShouldUpdateWhenInsertsAreAdded() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        assertThat(mixer.getSystemLatencySamples()).isZero();

        // Adding an insert should trigger recalculation
        ch1.addInsert(new InsertSlot("Latent", new LatencyProcessor(512)));

        assertThat(mixer.getSystemLatencySamples()).isEqualTo(512);
        assertThat(mixer.getDelayCompensation().getChannelCompensationSamples(0)).isZero();
        assertThat(mixer.getDelayCompensation().getChannelCompensationSamples(1)).isEqualTo(512);
    }

    @Test
    void compensationShouldUpdateWhenInsertsAreRemoved() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        ch1.addInsert(new InsertSlot("Latent", new LatencyProcessor(100)));
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(200)));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        assertThat(mixer.getSystemLatencySamples()).isEqualTo(200);

        // Remove ch2's insert
        ch2.removeInsert(0);

        assertThat(mixer.getSystemLatencySamples()).isEqualTo(100);
    }

    @Test
    void compensationShouldUpdateWhenInsertIsBypassed() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(256)));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);

        assertThat(mixer.getSystemLatencySamples()).isEqualTo(256);

        // Bypass ch2's insert should remove its latency
        ch2.setInsertBypassed(0, true);

        assertThat(mixer.getSystemLatencySamples()).isZero();
    }

    @Test
    void compensationShouldDelayChannelAudioDuringMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("NoInserts");
        MixerChannel ch2 = new MixerChannel("WithInserts");
        ch2.addInsert(new InsertSlot("Latent", new LatencyProcessor(2)));
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);
        mixer.prepareForPlayback(1, 4);

        // Ch1 has no inserts → gets 2-sample compensation delay
        // Ch2 has 2-sample latency → gets 0-sample compensation
        float[][][] channelBuffers = {
                {{1.0f, 2.0f, 3.0f, 4.0f}},  // ch1: will be delayed
                {{5.0f, 6.0f, 7.0f, 8.0f}}   // ch2: no delay
        };
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};

        mixer.mixDown(channelBuffers, output, 4);

        // Ch1 audio is delayed by 2 samples: [0, 0, 1, 2]
        // Ch2 audio passes through: [5, 6, 7, 8]
        // Sum: [5, 6, 8, 10]
        assertThat(output[0][0]).isEqualTo(5.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][1]).isEqualTo(6.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][2]).isEqualTo(8.0f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(output[0][3]).isEqualTo(10.0f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void compensationShouldIncludeReturnBusLatency() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        mixer.addChannel(ch);

        // Add latency to the default return bus
        mixer.getAuxBus().addInsert(new InsertSlot("Reverb", new LatencyProcessor(128)));

        assertThat(mixer.getSystemLatencySamples()).isEqualTo(128);
        assertThat(mixer.getDelayCompensation().getChannelCompensationSamples(0)).isEqualTo(128);
    }
}
