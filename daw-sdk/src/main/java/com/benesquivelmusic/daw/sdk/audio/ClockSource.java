package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * One hardware clock source reported by an {@link AudioBackend} for a
 * given {@link DeviceId}.
 *
 * <p>The {@code id} is the driver's own integer index for the source,
 * the same value the host passes to
 * {@link AudioBackend#selectClockSource(DeviceId, int)} (and the same
 * value the ASIO API exposes through
 * {@code ASIOGetClockSources(ASIOClockSource[], int* numSources)} /
 * {@code ASIOSetClockSource(int)}). The {@code name} is the
 * driver-reported display string ("Internal", "Word Clock In",
 * "S/PDIF Coax", "ADAT 1-8", "AES In"). The {@code current} flag is set
 * for the source the driver reports as currently active so the UI can
 * default to it. The {@link ClockKind} categorises the source so the
 * transport-bar badge can render the standard short label even when the
 * driver-provided name is unusual.</p>
 *
 * @param id      driver-defined integer index ({@code >= 0})
 * @param name    driver-reported display name (must not be blank)
 * @param current {@code true} when the driver reports this source as
 *                the currently selected one
 * @param kind    classification used by the UI (must not be null)
 */
public record ClockSource(int id, String name, boolean current, ClockKind kind) {

    public ClockSource {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (id < 0) {
            throw new IllegalArgumentException("id must not be negative: " + id);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
