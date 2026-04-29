package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;
import java.util.Optional;

/**
 * Sealed event published by an {@link AudioBackend} when the host OS or
 * vendor driver reports that a device has arrived, gone away, changed
 * its native format, or asked the host to drop and reopen the stream
 * with a renegotiated format.
 *
 * <p>USB audio interfaces enumerate and unenumerate freely: a yanked
 * cable, a sleeping laptop, a powered USB hub cycling, or a driver
 * crash all surface as the device "going away" mid-session. Each OS
 * gives us a structured signal for that — ASIO's
 * {@code kAsioResetRequest}, WASAPI's
 * {@code IMMNotificationClient::OnDeviceStateChanged}, CoreAudio's
 * {@code kAudioHardwarePropertyDevices} listener, JACK's server-shutdown
 * notification — and {@link AudioBackend#deviceEvents()} unifies all of
 * those into a single {@link java.util.concurrent.Flow.Publisher} that
 * the application layer can subscribe to.</p>
 *
 * <p>An {@code AudioDeviceEvent} is delivered on a backend-owned thread
 * (typically the OS notification thread, never the audio callback
 * thread). Subscribers must not block.</p>
 *
 * @see AudioBackend#deviceEvents()
 */
public sealed interface AudioDeviceEvent
        permits AudioDeviceEvent.DeviceArrived,
                AudioDeviceEvent.DeviceRemoved,
                AudioDeviceEvent.DeviceFormatChanged,
                AudioDeviceEvent.FormatChangeRequested {

    /**
     * Returns the device the event refers to. Never {@code null}.
     *
     * @return the affected device id
     */
    DeviceId device();

    /**
     * Emitted when a device has just appeared on the host (USB plug-in,
     * driver enabled, JACK server start). The application uses this to
     * automatically reopen the previously selected device once the
     * matching identity (vendor + product + serial when available,
     * fall back to friendly name) reappears.
     *
     * @param device id of the device that has just arrived; never null
     */
    record DeviceArrived(DeviceId device) implements AudioDeviceEvent {
        public DeviceArrived {
            Objects.requireNonNull(device, "device must not be null");
        }
    }

    /**
     * Emitted when a device has gone away (USB unplug, driver crash,
     * device disabled, JACK server shutdown). The application transitions
     * the engine to {@code DEVICE_LOST}, halts the render thread, and
     * persists any in-progress recording take.
     *
     * @param device id of the device that just disappeared; never null
     */
    record DeviceRemoved(DeviceId device) implements AudioDeviceEvent {
        public DeviceRemoved {
            Objects.requireNonNull(device, "device must not be null");
        }
    }

    /**
     * Emitted when the driver renegotiates the device's native format
     * mid-session (sample rate change, channel count change, buffer
     * size change). On ASIO this is
     * {@code kAsioBufferSizeChange} / {@code kAsioResyncRequest}; on
     * CoreAudio it is the corresponding property listener; on WASAPI
     * shared mode it follows a mixer-format change.
     *
     * @param device    id of the device whose format changed; never null
     * @param newFormat the device's new native format; never null
     */
    record DeviceFormatChanged(DeviceId device, AudioFormat newFormat)
            implements AudioDeviceEvent {
        public DeviceFormatChanged {
            Objects.requireNonNull(device, "device must not be null");
            Objects.requireNonNull(newFormat, "newFormat must not be null");
        }
    }

    /**
     * Emitted when the vendor driver asks the host to drop the current
     * stream and reopen with a renegotiated format — story 218
     * ("Driver-Initiated Reset Request Handling for Mid-Session Format
     * Changes").
     *
     * <p>Distinct from {@link DeviceFormatChanged}: that event represents
     * a <em>completed</em> format change observed by the driver after
     * the fact, while {@code FormatChangeRequested} is the pending
     * request that requires a host-side reopen. The application's
     * controller pauses transport, drains the render queue, closes the
     * stream, re-queries device capabilities, reopens with
     * {@link #proposedFormat()} (or the existing settings when empty),
     * and resumes in {@code STOPPED}.</p>
     *
     * <p>Per-OS sources (translated by the native backends):</p>
     * <ul>
     *   <li>ASIO &mdash; {@code kAsioResetRequest} (with a fresh
     *       {@code ASIOGetSampleRate}), {@code kAsioBufferSizeChange},
     *       {@code kAsioResyncRequest} from the host-callback set
     *       installed on driver open.</li>
     *   <li>CoreAudio &mdash; property listeners on
     *       {@code kAudioDevicePropertyNominalSampleRate},
     *       {@code kAudioDevicePropertyBufferFrameSize}, and
     *       {@code kAudioDevicePropertyClockSource}.</li>
     *   <li>WASAPI &mdash;
     *       {@code IMMNotificationClient::OnPropertyValueChanged} on the
     *       active endpoint, plus full {@code IAudioClient}
     *       invalidation from the audio engine.</li>
     * </ul>
     *
     * <p>{@code proposedFormat} is the new format the driver is moving
     * to when the backend can determine it from the underlying signal
     * (for example ASIO's {@code kAsioBufferSizeChange} ships the new
     * frame count, and CoreAudio's nominal-sample-rate listener ships
     * the new rate). It is {@link Optional#empty()} when the underlying
     * signal does not carry the new format (for example a generic
     * {@code kAsioResetRequest} or a WASAPI {@code IAudioClient}
     * invalidation), in which case the host re-queries the backend's
     * capabilities and reopens with the existing settings.</p>
     *
     * @param device         id of the affected device; never null
     * @param proposedFormat the format the driver is moving to, when known;
     *                       never null (use {@link Optional#empty()} for
     *                       unknown)
     * @param reason         why the driver is asking for a reset; never null
     */
    record FormatChangeRequested(
            DeviceId device,
            Optional<AudioFormat> proposedFormat,
            FormatChangeReason reason) implements AudioDeviceEvent {
        public FormatChangeRequested {
            Objects.requireNonNull(device, "device must not be null");
            Objects.requireNonNull(proposedFormat, "proposedFormat must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
