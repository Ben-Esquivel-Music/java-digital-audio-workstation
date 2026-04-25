package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmbisonicDecoderTest {

    @Test
    void stereoUhjShouldHaveTwoOutputChannels() {
        AmbisonicDecoder decoder = new AmbisonicDecoder.StereoUhj();
        assertThat(decoder.outputChannelCount()).isEqualTo(2);
        assertThat(decoder.displayName()).isEqualTo("Stereo (UHJ)");
    }

    @Test
    void binaural5ShouldHaveTwoOutputChannels() {
        AmbisonicDecoder decoder = new AmbisonicDecoder.Binaural5();
        assertThat(decoder.outputChannelCount()).isEqualTo(2);
        assertThat(decoder.displayName()).contains("Binaural");
    }

    @Test
    void binauralHrtfShouldExposeProfileAndTwoOutputChannels() {
        AmbisonicDecoder.BinauralHrtf decoder =
                new AmbisonicDecoder.BinauralHrtf(HrtfProfile.MEDIUM);
        assertThat(decoder.profile()).isEqualTo(HrtfProfile.MEDIUM);
        assertThat(decoder.outputChannelCount()).isEqualTo(2);
        assertThat(decoder.displayName()).contains("Medium");
    }

    @Test
    void binauralHrtfShouldRejectNullProfile() {
        assertThatThrownBy(() -> new AmbisonicDecoder.BinauralHrtf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void loudspeakerRigShouldUseLayoutChannelCount() {
        AmbisonicDecoder decoder = new AmbisonicDecoder.LoudspeakerRig(SpeakerLayout.LAYOUT_7_1_4);
        assertThat(decoder.outputChannelCount()).isEqualTo(12);
        assertThat(decoder.displayName()).isEqualTo("Loudspeaker 7.1.4");
    }

    @Test
    void loudspeakerRigStereoShouldHaveTwoChannels() {
        AmbisonicDecoder decoder = new AmbisonicDecoder.LoudspeakerRig(SpeakerLayout.LAYOUT_5_1);
        assertThat(decoder.outputChannelCount()).isEqualTo(6);
    }

    @Test
    void loudspeakerRigShouldRejectNullLayout() {
        assertThatThrownBy(() -> new AmbisonicDecoder.LoudspeakerRig(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("layout");
    }

    @Test
    void shouldSupportExhaustivePatternMatching() {
        AmbisonicDecoder decoder = new AmbisonicDecoder.LoudspeakerRig(SpeakerLayout.LAYOUT_5_1);
        int channels = switch (decoder) {
            case AmbisonicDecoder.StereoUhj uhj -> uhj.outputChannelCount();
            case AmbisonicDecoder.Binaural5 b5 -> b5.outputChannelCount();
            case AmbisonicDecoder.BinauralHrtf hrtf -> hrtf.outputChannelCount();
            case AmbisonicDecoder.LoudspeakerRig rig -> rig.layout().channelCount();
        };
        assertThat(channels).isEqualTo(6);
    }
}
