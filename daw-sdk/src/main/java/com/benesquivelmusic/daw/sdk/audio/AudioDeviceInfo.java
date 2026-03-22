package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;

/**
 * Describes an available audio input or output device.
 *
 * @param index               device index within the backend
 * @param name                human-readable device name
 * @param hostApi             host API name (e.g., "ALSA", "CoreAudio", "WASAPI", "Java Sound")
 * @param maxInputChannels    maximum number of input channels (0 if output-only)
 * @param maxOutputChannels   maximum number of output channels (0 if input-only)
 * @param defaultSampleRate   the device's default sample rate in Hz
 * @param supportedSampleRates sample rates the device can operate at
 * @param defaultLowInputLatencyMs  default low-latency input latency in ms
 * @param defaultLowOutputLatencyMs default low-latency output latency in ms
 */
public record AudioDeviceInfo(
        int index,
        String name,
        String hostApi,
        int maxInputChannels,
        int maxOutputChannels,
        double defaultSampleRate,
        List<SampleRate> supportedSampleRates,
        double defaultLowInputLatencyMs,
        double defaultLowOutputLatencyMs
) {

    /**
     * Returns {@code true} if this device supports audio input.
     *
     * @return true if input is supported
     */
    public boolean supportsInput() {
        return maxInputChannels > 0;
    }

    /**
     * Returns {@code true} if this device supports audio output.
     *
     * @return true if output is supported
     */
    public boolean supportsOutput() {
        return maxOutputChannels > 0;
    }
}
