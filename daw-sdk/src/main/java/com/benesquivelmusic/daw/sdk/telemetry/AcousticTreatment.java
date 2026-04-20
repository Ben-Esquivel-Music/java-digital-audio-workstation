package com.benesquivelmusic.daw.sdk.telemetry;

import java.awt.geom.Rectangle2D;
import java.util.Objects;

/**
 * A proposed or installed acoustic treatment at a specific location inside
 * a room.
 *
 * <p>Produced by a treatment advisor (typically
 * {@code com.benesquivelmusic.daw.core.telemetry.advisor.TreatmentAdvisor})
 * and either rendered as a suggestion in the UI or persisted on the
 * {@code RoomConfiguration} once the user marks it as &quot;applied&quot;.</p>
 *
 * <p>Treatments are modeled as full-rectangle material swaps: placing this
 * treatment replaces the material of the underlying surface patch bounded
 * by {@link #sizeMeters()} centered on {@link #location()}.</p>
 *
 * <p>{@link #predictedImprovementLufs()} is a rough estimate of the
 * integrated perceptual gain (in LUFS-equivalent) delivered by the
 * treatment. It is not an exact psychoacoustic measurement — the advisor
 * uses it purely as a ranking key so multiple suggestions can be sorted by
 * expected acoustic value-per-panel.</p>
 *
 * @param kind                     what kind of treatment this is
 * @param location                 where on the room this treatment is attached
 * @param sizeMeters               panel width &times; height in meters
 * @param predictedImprovementLufs estimated perceptual improvement in LUFS
 */
public record AcousticTreatment(
        TreatmentKind kind,
        WallAttachment location,
        Rectangle2D sizeMeters,
        double predictedImprovementLufs) {

    public AcousticTreatment {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(sizeMeters, "sizeMeters must not be null");
        if (sizeMeters.getWidth() <= 0 || sizeMeters.getHeight() <= 0) {
            throw new IllegalArgumentException(
                    "sizeMeters must have positive width and height: " + sizeMeters);
        }
        if (Double.isNaN(predictedImprovementLufs)
                || Double.isInfinite(predictedImprovementLufs)) {
            throw new IllegalArgumentException(
                    "predictedImprovementLufs must be finite: " + predictedImprovementLufs);
        }
    }
}
