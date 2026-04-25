package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;

import java.util.Objects;

/**
 * A time-stamped spatial metadata sample parsed from an ADM
 * {@code audioBlockFormat} element during ADM BWF import.
 *
 * <p>Each {@code audioBlockFormat} in an ADM BWF file describes the
 * position, size and gain of an {@code audioObject} at a particular
 * point in time. When imported, these become automation points on the
 * object's {@code position} (x/y/z), {@code size} and {@code gain}
 * lanes.</p>
 *
 * @param timeSeconds the time of this sample, relative to the start of the file, in seconds
 * @param metadata    the object metadata at this time
 */
public record AdmAutomationPoint(double timeSeconds, ObjectMetadata metadata) {

    public AdmAutomationPoint {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (timeSeconds < 0.0) {
            throw new IllegalArgumentException("timeSeconds must not be negative: " + timeSeconds);
        }
    }
}
