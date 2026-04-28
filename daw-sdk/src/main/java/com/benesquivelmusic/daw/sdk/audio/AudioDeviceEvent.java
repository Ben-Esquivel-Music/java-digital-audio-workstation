package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Sealed event published by an {@link AudioBackend} when the host OS or
 * vendor driver reports that a device has arrived, gone away, or
 * changed its native format.
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
public sealed interface AudioDeviceEvent {

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
}
