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

    @Test
    void anUnprobedDriverIsListedAndSaysItsChannelCountIsNotYetKnown() {
        // Story 316 review (R4): an enumerated ASIO driver reports
        // CHANNEL_COUNT_UNKNOWN because nothing has loaded it. It must still
        // be offered as an input, and the row must not render the sentinel
        // as "-1 ch".
        AudioDeviceInfo unprobed = AudioDeviceInfo.unprobed(4, "Driver B", "ASIO");

        assertThat(List.of(outputDevice(0, "Speakers"), unprobed).stream()
                .filter(AudioDeviceInfo::supportsInput)
                .toList())
                .extracting(AudioDeviceInfo::name)
                .containsExactly("Driver B");
        assertThat(InputPortSelectionDialog.channelSummary(unprobed))
                .as("the row never prints a count the app has not read from a driver")
                .doesNotContain("-1")
                .isEqualTo("channel count unknown until opened");
        assertThat(InputPortSelectionDialog.channelSummary(inputDevice(0, "USB Mic")))
                .as("a known count is still rendered as a number")
                .isEqualTo("2 ch");
    }

    @Test
    void anUnprobedDriverRendersItsUnknownRateAndLatencyAsUnknownRatherThanZero() {
        // Story 316 re-review: the unknown channel count made unprobed ASIO
        // drivers VISIBLE in this list for the first time, and the row then
        // formatted their placeholder 0.0 sample rate and 0.0 latency
        // straight through as "0 Hz | 0.0 ms" — two capabilities fabricated
        // for a driver nobody has loaded. AudioDeviceInfo.unprobed documents
        // those zeros as "the record has no unknown sentinel for them", not
        // as measurements.
        AudioDeviceInfo unprobed = AudioDeviceInfo.unprobed(4, "Driver B", "ASIO");

        assertThat(InputPortSelectionDialog.sampleRateSummary(unprobed))
                .as("an unprobed driver's placeholder rate must not print as a rate")
                .doesNotContain("0")
                .doesNotContain("Hz")
                .isEqualTo(InputPortSelectionDialog.unknownValue());
        assertThat(InputPortSelectionDialog.inputLatencySummary(unprobed))
                .as("nor its placeholder latency as a latency — 0.0 ms is a claim no "
                        + "audio interface can honour")
                .doesNotContain("0")
                .doesNotContain("ms")
                .isEqualTo(InputPortSelectionDialog.unknownValue());
    }

    @Test
    void aProbedDeviceStillRendersItsRealRateAndLatency() {
        // The positive control for the test above: the unknown rendering must
        // not swallow values the backend really reported.
        AudioDeviceInfo probed = inputDevice(0, "USB Mic");

        assertThat(InputPortSelectionDialog.sampleRateSummary(probed))
                .isEqualTo("44100 Hz");
        assertThat(InputPortSelectionDialog.inputLatencySummary(probed))
                .isEqualTo("5.0 ms");
    }

    @Test
    void theUnknownTokenIsTheOneTheRestOfTheAppAlreadyUses() {
        // Reuse, not a fork: the audio utility panel already renders an
        // unavailable backend / latency from audio.utility.unavailable, and a
        // second hard-coded token here would drift the moment that one
        // changed.
        String shared = java.util.ResourceBundle.getBundle(
                        "com.benesquivelmusic.daw.app.i18n.Messages", java.util.Locale.ROOT)
                .getString("audio.utility.unavailable");

        assertThat(InputPortSelectionDialog.unknownValue()).isEqualTo(shared);
    }
}
