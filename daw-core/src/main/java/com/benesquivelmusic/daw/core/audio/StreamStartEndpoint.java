package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.Objects;

/**
 * Immutable backend endpoint identity captured at an audio lifecycle boundary.
 *
 * <p>This is snapshot data, not a live open-stream query. In particular,
 * {@link StreamStartFailure} may carry an endpoint whose handle has already
 * been unwound or whose provision has since been replaced. Keeping the pair
 * together prevents reporting a backend from one transition with a device
 * from another.</p>
 *
 * @param backendName backend display name; never blank
 * @param device      attempted device identity
 */
public record StreamStartEndpoint(String backendName, DeviceId device) {

    /** Validates endpoint identity at the lifecycle boundary. */
    public StreamStartEndpoint {
        Objects.requireNonNull(backendName, "backendName must not be null");
        Objects.requireNonNull(device, "device must not be null");
        if (backendName.isBlank()) {
            throw new IllegalArgumentException("backendName must not be blank");
        }
    }
}
