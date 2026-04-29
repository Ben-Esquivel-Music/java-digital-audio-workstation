package com.benesquivelmusic.daw.sdk.audio;

/**
 * Sealed taxonomy of the reasons a vendor driver may ask the host to drop
 * the current stream and reopen with a renegotiated format — the
 * {@code reason} attached to every
 * {@link AudioDeviceEvent.FormatChangeRequested} event (story 218).
 *
 * <p>Each case maps onto the structured signal a specific OS / driver
 * raises when the user changes a setting in the vendor's native control
 * panel mid-session (the {@code openControlPanel()} flow from story 212):</p>
 * <ul>
 *   <li>{@link BufferSizeChange} — ASIO's
 *       {@code kAsioBufferSizeChange} callback or CoreAudio's
 *       {@code kAudioDevicePropertyBufferFrameSize} listener fires
 *       after the user picks a new buffer-size entry from the driver
 *       panel's dropdown.</li>
 *   <li>{@link SampleRateChange} — ASIO's {@code kAsioResetRequest} with
 *       a new {@code ASIOGetSampleRate} reading, CoreAudio's
 *       {@code kAudioDevicePropertyNominalSampleRate} listener, or
 *       WASAPI's mix-format-changed
 *       {@code IMMNotificationClient::OnPropertyValueChanged} fires
 *       after the driver renegotiates the device clock to a new rate
 *       (typically 48&nbsp;kHz &harr; 44.1&nbsp;kHz).</li>
 *   <li>{@link ClockSourceChange} — ASIO's {@code kAsioResyncRequest}
 *       or CoreAudio's {@code kAudioDevicePropertyClockSource} listener
 *       fires when the user re-targets the device to a different word-
 *       clock / S/PDIF / ADAT lock source (see story "Hardware Clock
 *       Source Selection").</li>
 *   <li>{@link DriverReset} — the catch-all corresponding to ASIO's
 *       {@code kAsioResetRequest} (USB streaming-mode change, USB hub
 *       cycle, vendor utility "reset" button) or WASAPI's full
 *       {@code IAudioClient} invalidation. The host must rebuild the
 *       stream from scratch.</li>
 * </ul>
 *
 * <p>The four cases are stateless tag types — they carry no fields
 * beyond the kind itself. Records (rather than enum constants) are used
 * so consumers can pattern-match in an exhaustive {@code switch}
 * expression alongside record-payload events:
 *
 * <pre>{@code
 * switch (reason) {
 *     case FormatChangeReason.BufferSizeChange()  -> ...
 *     case FormatChangeReason.SampleRateChange()  -> ...
 *     case FormatChangeReason.ClockSourceChange() -> ...
 *     case FormatChangeReason.DriverReset()       -> ...
 * }
 * }</pre>
 */
public sealed interface FormatChangeReason
        permits FormatChangeReason.BufferSizeChange,
                FormatChangeReason.SampleRateChange,
                FormatChangeReason.ClockSourceChange,
                FormatChangeReason.DriverReset {

    /**
     * The driver renegotiated the buffer size — typically the user picked
     * a different entry from the driver panel's "Samples per buffer"
     * dropdown. ASIO source: {@code kAsioBufferSizeChange}. CoreAudio
     * source: {@code kAudioDevicePropertyBufferFrameSize}.
     */
    record BufferSizeChange() implements FormatChangeReason {}

    /**
     * The driver renegotiated the sample rate. ASIO source:
     * {@code kAsioResetRequest} with a new {@code ASIOGetSampleRate}.
     * CoreAudio source: {@code kAudioDevicePropertyNominalSampleRate}.
     * WASAPI source: {@code IMMNotificationClient::OnPropertyValueChanged}
     * with the device's mix-format property key.
     */
    record SampleRateChange() implements FormatChangeReason {}

    /**
     * The user re-targeted the device's clock to a different external
     * source (word clock, S/PDIF, ADAT). ASIO source:
     * {@code kAsioResyncRequest}. CoreAudio source:
     * {@code kAudioDevicePropertyClockSource}.
     */
    record ClockSourceChange() implements FormatChangeReason {}

    /**
     * The driver issued a generic reset — USB streaming-mode change,
     * vendor utility "reset" button, USB hub cycle. ASIO source:
     * {@code kAsioResetRequest}. WASAPI source: full
     * {@code IAudioClient} invalidation.
     */
    record DriverReset() implements FormatChangeReason {}
}
