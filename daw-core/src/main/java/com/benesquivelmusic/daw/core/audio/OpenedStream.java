package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.Objects;

/**
 * The stream the engine last opened ({@link AudioEngine#lastOpenedStream()}),
 * whether or not its start succeeded. It survives the stream's close; a
 * resume of a paused stream is the same stream and keeps it.
 *
 * @param serial     greater for every later open of the same engine; never reused
 * @param device     the device the stream opened on (the winning rung's)
 * @param sampleRate the engine's sample rate at the open, in Hz
 */
public record OpenedStream(long serial, DeviceId device, int sampleRate) {

    /** Validates the device. */
    public OpenedStream {
        Objects.requireNonNull(device, "device must not be null");
    }
}
