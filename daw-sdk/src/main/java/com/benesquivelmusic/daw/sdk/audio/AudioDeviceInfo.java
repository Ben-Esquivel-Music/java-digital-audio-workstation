package com.benesquivelmusic.daw.sdk.audio;

import java.util.List;

/**
 * Describes an available audio input or output device.
 *
 * <h2>Channel counts may be genuinely unknown (story 316 review)</h2>
 * <p>Some driver models cannot report a channel count from an enumeration
 * pass. ASIO is the house example: {@code ASIOGetChannels} only answers
 * after {@code loadDriver} + {@code ASIOInit}, and loading every installed
 * driver just to populate a settings menu is unacceptable — an ASIO driver
 * may seize its hardware exclusively and may pop a modal control panel.
 * Such a backend must not invent a plausible number, and it must not report
 * {@code 0} either: {@code 0} is this record's honest statement that a
 * direction is <em>not offered</em>, and it took every ASIO driver out of
 * both the input and the output menus, leaving the user unable to select or
 * persist a specific driver at all.</p>
 *
 * <p>The third state is {@link #CHANNEL_COUNT_UNKNOWN}: the direction is
 * offered, the count is not yet known. Consumers must ask
 * {@link #hasKnownInputChannelCount()} / {@link #hasKnownOutputChannelCount()}
 * before reading a count arithmetically, or go through
 * {@link #clampInputChannels(int)} / {@link #clampOutputChannels(int)},
 * which degrade to "request what you asked for" when the count is unknown.
 * That is exactly the ASIO contract anyway: the request is clamped against
 * the driver's real counts inside {@code open()}, once {@code ASIOInit} has
 * made them knowable, and a request the driver cannot honour is a visible
 * failure rather than a silent one.</p>
 *
 * @param index               device index within the backend
 * @param name                human-readable device name
 * @param hostApi             host API name (e.g., "ALSA", "CoreAudio", "WASAPI", "Java Sound")
 * @param maxInputChannels    maximum number of input channels, {@code 0} when input is not
 *                            offered, or {@link #CHANNEL_COUNT_UNKNOWN} when input is offered
 *                            but the count cannot be known without loading the driver
 * @param maxOutputChannels   maximum number of output channels, {@code 0} when output is not
 *                            offered, or {@link #CHANNEL_COUNT_UNKNOWN} when output is offered
 *                            but the count cannot be known without loading the driver
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
     * Channel-count sentinel meaning "this direction is offered, but the
     * count cannot be known without loading the driver" (story 316 review).
     *
     * <p>It is deliberately the ONLY legal negative count: the canonical
     * constructor rejects anything below it, so a garbage or unread native
     * value can never masquerade as "unknown" and can never reach a caller
     * as a negative array size.</p>
     */
    public static final int CHANNEL_COUNT_UNKNOWN = -1;

    /**
     * Validates the channel counts against the sentinel's contract.
     *
     * @throws IllegalArgumentException if either count is below
     *         {@link #CHANNEL_COUNT_UNKNOWN}
     */
    public AudioDeviceInfo {
        requireLegalChannelCount(maxInputChannels, "maxInputChannels");
        requireLegalChannelCount(maxOutputChannels, "maxOutputChannels");
    }

    private static void requireLegalChannelCount(int count, String field) {
        if (count < CHANNEL_COUNT_UNKNOWN) {
            throw new IllegalArgumentException(field + " must be >= 0, or "
                    + CHANNEL_COUNT_UNKNOWN + " (CHANNEL_COUNT_UNKNOWN), but was " + count);
        }
    }

    /**
     * Describes a device whose identity is known but whose capabilities have
     * not been probed — an installed driver that has not been loaded.
     *
     * <p>Both directions are reported as {@link #CHANNEL_COUNT_UNKNOWN}, so
     * the device is selectable in either menu while claiming no count it
     * cannot substantiate. The sample rate and the latencies are reported as
     * zero because this record has no unknown sentinel for them; they are
     * presentational only and no code branches on them.</p>
     *
     * @param index   device index within the backend
     * @param name    human-readable device name
     * @param hostApi host API name
     * @return an unprobed device descriptor
     */
    public static AudioDeviceInfo unprobed(int index, String name, String hostApi) {
        return new AudioDeviceInfo(index, name, hostApi,
                CHANNEL_COUNT_UNKNOWN, CHANNEL_COUNT_UNKNOWN,
                0.0, List.of(), 0.0, 0.0);
    }

    /**
     * Returns {@code true} if {@link #maxInputChannels()} is a real count
     * rather than {@link #CHANNEL_COUNT_UNKNOWN}.
     *
     * @return true if the input channel count is known
     */
    public boolean hasKnownInputChannelCount() {
        return maxInputChannels != CHANNEL_COUNT_UNKNOWN;
    }

    /**
     * Returns {@code true} if {@link #maxOutputChannels()} is a real count
     * rather than {@link #CHANNEL_COUNT_UNKNOWN}.
     *
     * @return true if the output channel count is known
     */
    public boolean hasKnownOutputChannelCount() {
        return maxOutputChannels != CHANNEL_COUNT_UNKNOWN;
    }

    /**
     * Returns {@code true} if this device offers audio input — either a
     * known positive count, or a count that is not yet knowable.
     *
     * @return true if input is offered
     */
    public boolean supportsInput() {
        return !hasKnownInputChannelCount() || maxInputChannels > 0;
    }

    /**
     * Returns {@code true} if this device offers audio output — either a
     * known positive count, or a count that is not yet knowable.
     *
     * @return true if output is offered
     */
    public boolean supportsOutput() {
        return !hasKnownOutputChannelCount() || maxOutputChannels > 0;
    }

    /**
     * Clamps a requested input channel count to this device's capability.
     * An unknown count clamps to nothing: the request is returned unchanged
     * and the driver decides at open time.
     *
     * @param requestedChannels the channel count the caller would like
     * @return the count to open with
     */
    public int clampInputChannels(int requestedChannels) {
        return hasKnownInputChannelCount()
                ? Math.min(requestedChannels, maxInputChannels)
                : requestedChannels;
    }

    /**
     * Clamps a requested output channel count to this device's capability.
     * An unknown count clamps to nothing: the request is returned unchanged
     * and the driver decides at open time.
     *
     * @param requestedChannels the channel count the caller would like
     * @return the count to open with
     */
    public int clampOutputChannels(int requestedChannels) {
        return hasKnownOutputChannelCount()
                ? Math.min(requestedChannels, maxOutputChannels)
                : requestedChannels;
    }
}
