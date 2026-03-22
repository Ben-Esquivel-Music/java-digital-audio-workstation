package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioDeviceInfoTest {

    @Test
    void shouldReportInputSupport() {
        var device = new AudioDeviceInfo(0, "Mic", "ALSA", 2, 0, 44100.0,
                List.of(SampleRate.HZ_44100), 5.0, 0.0);
        assertThat(device.supportsInput()).isTrue();
        assertThat(device.supportsOutput()).isFalse();
    }

    @Test
    void shouldReportOutputSupport() {
        var device = new AudioDeviceInfo(1, "Speakers", "CoreAudio", 0, 2, 48000.0,
                List.of(SampleRate.HZ_48000), 0.0, 5.0);
        assertThat(device.supportsInput()).isFalse();
        assertThat(device.supportsOutput()).isTrue();
    }

    @Test
    void shouldReportFullDuplex() {
        var device = new AudioDeviceInfo(2, "Interface", "WASAPI", 8, 8, 96000.0,
                List.of(SampleRate.HZ_44100, SampleRate.HZ_96000), 2.0, 2.0);
        assertThat(device.supportsInput()).isTrue();
        assertThat(device.supportsOutput()).isTrue();
    }

    @Test
    void shouldExposeAllFields() {
        var rates = List.of(SampleRate.HZ_44100, SampleRate.HZ_48000);
        var device = new AudioDeviceInfo(3, "Test", "JACK", 4, 6, 44100.0,
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
}
