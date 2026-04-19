package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Stable, backend-scoped identifier for an audio device.
 *
 * <p>Device indices reported by native audio APIs (ASIO, CoreAudio,
 * WASAPI, JACK, Java Sound) are not stable across reboots, so selections
 * persisted to disk must key on a string name. The {@code backend}
 * component disambiguates identically-named devices that can appear
 * under two different backends on the same host.</p>
 *
 * @param backend name of the backend that owns the device
 *                (for example {@code "ASIO"}, {@code "JACK"},
 *                {@code "Java Sound"})
 * @param name    human-readable device name as reported by the backend
 *                (must not be empty)
 */
public record DeviceId(String backend, String name) {

    public DeviceId {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (backend.isBlank()) {
            throw new IllegalArgumentException("backend must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Returns a device id that represents "whatever this backend considers
     * its default device". Useful for users who do not care which physical
     * device the backend opens.
     *
     * @param backend name of the backend; must not be null or blank
     * @return the default device id for the backend
     */
    public static DeviceId defaultFor(String backend) {
        return new DeviceId(backend, "<default>");
    }

    /**
     * Returns {@code true} if this id points at the backend's default device.
     *
     * @return true when {@link #name()} equals {@code "<default>"}
     */
    public boolean isDefault() {
        return "<default>".equals(name);
    }
}
