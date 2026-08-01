package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Display-safe metadata returned by the active ASIO driver's
 * {@code ASIOInit(ASIODriverInfo*)} call.
 *
 * @param name          initialized driver name
 * @param asioVersion   ASIO host API version reported by the driver
 * @param driverVersion vendor-defined driver version number
 * @param statusMessage optional driver status/error text; empty when none
 */
public record AsioDriverInfo(
        String name,
        int asioVersion,
        int driverVersion,
        String statusMessage
) {
    public AsioDriverInfo {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(statusMessage, "statusMessage must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
