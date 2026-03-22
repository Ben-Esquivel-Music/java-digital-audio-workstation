package com.benesquivelmusic.daw.sdk.audio;

/**
 * Configuration for opening an audio stream.
 *
 * @param inputDeviceIndex  the input device index, or {@code -1} for no input
 * @param outputDeviceIndex the output device index, or {@code -1} for no output
 * @param inputChannels     the number of input channels to use
 * @param outputChannels    the number of output channels to use
 * @param sampleRate        the desired sample rate
 * @param bufferSize        the desired buffer size in sample frames
 */
public record AudioStreamConfig(
        int inputDeviceIndex,
        int outputDeviceIndex,
        int inputChannels,
        int outputChannels,
        SampleRate sampleRate,
        BufferSize bufferSize
) {

    public AudioStreamConfig {
        if (inputDeviceIndex < -1) {
            throw new IllegalArgumentException("inputDeviceIndex must be >= -1: " + inputDeviceIndex);
        }
        if (outputDeviceIndex < -1) {
            throw new IllegalArgumentException("outputDeviceIndex must be >= -1: " + outputDeviceIndex);
        }
        if (inputChannels < 0) {
            throw new IllegalArgumentException("inputChannels must be >= 0: " + inputChannels);
        }
        if (outputChannels < 0) {
            throw new IllegalArgumentException("outputChannels must be >= 0: " + outputChannels);
        }
        if (inputChannels == 0 && outputChannels == 0) {
            throw new IllegalArgumentException("At least one of inputChannels or outputChannels must be > 0");
        }
    }

    /**
     * Returns true if this configuration includes audio input.
     *
     * @return true if input channels are configured
     */
    public boolean hasInput() {
        return inputChannels > 0 && inputDeviceIndex >= 0;
    }

    /**
     * Returns true if this configuration includes audio output.
     *
     * @return true if output channels are configured
     */
    public boolean hasOutput() {
        return outputChannels > 0 && outputDeviceIndex >= 0;
    }
}
