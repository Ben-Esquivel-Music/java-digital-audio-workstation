package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioDeviceInfoTest {

    @Test
    void shouldReportInputSupport() {
        AudioDeviceInfo device = new AudioDeviceInfo(0, "Mic", "ALSA", 2, 0, 44100.0,
                List.of(SampleRate.HZ_44100), 5.0, 0.0);
        assertThat(device.supportsInput()).isTrue();
        assertThat(device.supportsOutput()).isFalse();
    }

    @Test
    void shouldReportOutputSupport() {
        AudioDeviceInfo device = new AudioDeviceInfo(1, "Speakers", "CoreAudio", 0, 2, 48000.0,
                List.of(SampleRate.HZ_48000), 0.0, 5.0);
        assertThat(device.supportsInput()).isFalse();
        assertThat(device.supportsOutput()).isTrue();
    }

    @Test
    void shouldReportFullDuplex() {
        AudioDeviceInfo device = new AudioDeviceInfo(2, "Interface", "WASAPI", 8, 8, 96000.0,
                List.of(SampleRate.HZ_44100, SampleRate.HZ_96000), 2.0, 2.0);
        assertThat(device.supportsInput()).isTrue();
        assertThat(device.supportsOutput()).isTrue();
    }

    @Test
    void shouldExposeAllFields() {
        List<SampleRate> rates = List.of(SampleRate.HZ_44100, SampleRate.HZ_48000);
        AudioDeviceInfo device = new AudioDeviceInfo(3, "Test", "JACK", 4, 6, 44100.0,
                rates, 1.5, 2.5);
        assertThat(device.index()).isEqualTo(3);
        assertThat(device.name()).isEqualTo("Test");
        assertThat(device.hostApi()).isEqualTo("JACK");
        assertThat(device.maxInputChannels()).isEqualTo(4);
        assertThat(device.maxOutputChannels()).isEqualTo(6);
        assertThat(device.defaultSampleRate()).isEqualTo(44100.0);
        assertThat(device.supportedSampleRates()).isEqualTo(rates);
        assertThat(device.defaultLowInputLatencyMs()).isEqualTo(1.5);
        assertThat(device.defaultLowOutputLatencyMs()).isEqualTo(2.5);
    }

    @Test
    void anUnprobedDeviceOffersBothDirectionsWithoutClaimingAChannelCount() {
        // Story 316 review: an installed-but-unloaded ASIO driver. Zero would
        // mean "direction not offered" and filtered it out of both settings
        // menus; any positive number would be an invention.
        AudioDeviceInfo driver = AudioDeviceInfo.unprobed(2, "Driver B", "ASIO");

        assertThat(driver.supportsOutput())
                .as("an unprobed driver is selectable as an output")
                .isTrue();
        assertThat(driver.supportsInput())
                .as("and as an input — neither direction is known to be absent")
                .isTrue();
        assertThat(driver.hasKnownOutputChannelCount()).isFalse();
        assertThat(driver.hasKnownInputChannelCount()).isFalse();
        assertThat(driver.maxOutputChannels())
                .as("the count is the unknown sentinel, never a fabricated number")
                .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN)
                .isNotPositive();
        assertThat(driver.maxInputChannels())
                .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN)
                .isNotPositive();
        assertThat(driver.index()).isEqualTo(2);
        assertThat(driver.name()).isEqualTo("Driver B");
        assertThat(driver.hostApi()).isEqualTo("ASIO");
    }

    @Test
    void anUnknownCountClampsToTheRequestWhileAKnownOneClampsDown() {
        AudioDeviceInfo unprobed = AudioDeviceInfo.unprobed(0, "Driver B", "ASIO");
        AudioDeviceInfo monoMic = new AudioDeviceInfo(1, "Mono Mic", "WASAPI", 1, 0,
                48000.0, List.of(SampleRate.HZ_48000), 0.0, 0.0);

        assertThat(unprobed.clampInputChannels(2))
                .as("an unknown count cannot clamp: the driver decides at open time")
                .isEqualTo(2);
        assertThat(unprobed.clampOutputChannels(8)).isEqualTo(8);
        assertThat(monoMic.clampInputChannels(2))
                .as("a known count still clamps down")
                .isEqualTo(1);
        assertThat(monoMic.clampOutputChannels(2))
                .as("a direction that is not offered clamps to nothing")
                .isZero();
    }

    @Test
    void aNegativeCountOtherThanTheSentinelIsRejected() {
        // The sentinel is the ONLY legal negative, so garbage cannot
        // masquerade as "unknown" and reach a caller as an array size.
        assertThatThrownBy(() -> new AudioDeviceInfo(0, "Broken", "PortAudio", -2, 2,
                48000.0, List.of(), 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInputChannels")
                .hasMessageContaining("-2");
        assertThatThrownBy(() -> new AudioDeviceInfo(0, "Broken", "PortAudio", 2, -7,
                48000.0, List.of(), 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputChannels")
                .hasMessageContaining("-7");
    }
}
