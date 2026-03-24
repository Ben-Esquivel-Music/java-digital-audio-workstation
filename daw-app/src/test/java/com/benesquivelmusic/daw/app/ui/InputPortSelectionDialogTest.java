package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InputPortSelectionDialogTest {

    private static AudioDeviceInfo inputDevice(int index, String name) {
        return new AudioDeviceInfo(index, name, "ALSA", 2, 0, 44100.0,
                List.of(SampleRate.HZ_44100), 5.0, 0.0);
    }

    private static AudioDeviceInfo outputDevice(int index, String name) {
        return new AudioDeviceInfo(index, name, "ALSA", 0, 2, 44100.0,
                List.of(SampleRate.HZ_44100), 0.0, 5.0);
    }

    private static AudioDeviceInfo duplexDevice(int index, String name) {
        return new AudioDeviceInfo(index, name, "ALSA", 2, 2, 48000.0,
                List.of(SampleRate.HZ_48000), 5.0, 5.0);
    }

    @Test
    void shouldFilterToInputDevicesOnly() {
        List<AudioDeviceInfo> all = List.of(
                inputDevice(0, "USB Mic"),
                outputDevice(1, "Speakers"),
                duplexDevice(2, "Audio Interface"),
                outputDevice(3, "HDMI Output")
        );

        // The dialog filters using AudioDeviceInfo::supportsInput
        List<AudioDeviceInfo> inputDevices = all.stream()
                .filter(AudioDeviceInfo::supportsInput)
                .toList();

        assertThat(inputDevices).hasSize(2);
        assertThat(inputDevices).extracting(AudioDeviceInfo::name)
                .containsExactly("USB Mic", "Audio Interface");
    }

    @Test
    void shouldReturnEmptyListWhenNoInputDevices() {
        List<AudioDeviceInfo> allOutputOnly = List.of(
                outputDevice(0, "Speakers"),
                outputDevice(1, "HDMI Output")
        );

        List<AudioDeviceInfo> inputDevices = allOutputOnly.stream()
                .filter(AudioDeviceInfo::supportsInput)
                .toList();

        assertThat(inputDevices).isEmpty();
    }

    @Test
    void shouldIncludeAllDevicesWhenAllAreInputs() {
        List<AudioDeviceInfo> allInputs = List.of(
                inputDevice(0, "Mic 1"),
                inputDevice(1, "Mic 2"),
                duplexDevice(2, "Interface")
        );

        List<AudioDeviceInfo> inputDevices = allInputs.stream()
                .filter(AudioDeviceInfo::supportsInput)
                .toList();

        assertThat(inputDevices).hasSize(3);
    }
}
