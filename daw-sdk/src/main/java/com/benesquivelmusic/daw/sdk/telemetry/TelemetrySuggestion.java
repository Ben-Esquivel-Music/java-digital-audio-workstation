package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Sealed interface representing an actionable suggestion from the
 * sound wave telemetry engine.
 *
 * <p>The telemetry engine analyzes room acoustics and microphone
 * placement to produce concrete suggestions for improving recording
 * quality. Each suggestion variant carries the relevant context.</p>
 */
public sealed interface TelemetrySuggestion
        permits TelemetrySuggestion.AdjustMicPosition,
                TelemetrySuggestion.AdjustMicAngle,
                TelemetrySuggestion.AddDampening,
                TelemetrySuggestion.RemoveDampening,
                TelemetrySuggestion.MoveSoundSource {

    /** Returns a human-readable description of this suggestion. */
    String description();

    /**
     * Suggests moving a microphone to a better position.
     *
     * @param microphoneName  the microphone to move
     * @param suggestedPosition the recommended new position
     * @param reason          why the move is recommended
     */
    record AdjustMicPosition(String microphoneName, Position3D suggestedPosition, String reason)
            implements TelemetrySuggestion {

        public AdjustMicPosition {
            Objects.requireNonNull(microphoneName, "microphoneName must not be null");
            Objects.requireNonNull(suggestedPosition, "suggestedPosition must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public String description() {
            return "Move microphone '%s' to (%.2f, %.2f, %.2f): %s".formatted(
                    microphoneName, suggestedPosition.x(), suggestedPosition.y(),
                    suggestedPosition.z(), reason);
        }
    }

    /**
     * Suggests rotating a microphone to a better angle.
     *
     * @param microphoneName    the microphone to rotate
     * @param suggestedAzimuth  the recommended azimuth in degrees
     * @param suggestedElevation the recommended elevation in degrees
     * @param reason            why the rotation is recommended
     */
    record AdjustMicAngle(String microphoneName, double suggestedAzimuth,
                          double suggestedElevation, String reason)
            implements TelemetrySuggestion {

        public AdjustMicAngle {
            Objects.requireNonNull(microphoneName, "microphoneName must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public String description() {
            return "Rotate microphone '%s' to azimuth=%.1f°, elevation=%.1f°: %s".formatted(
                    microphoneName, suggestedAzimuth, suggestedElevation, reason);
        }
    }

    /**
     * Suggests adding sound dampening material to a room surface.
     *
     * @param surfaceDescription which surface needs dampening
     * @param reason             why dampening is recommended
     */
    record AddDampening(String surfaceDescription, String reason)
            implements TelemetrySuggestion {

        public AddDampening {
            Objects.requireNonNull(surfaceDescription, "surfaceDescription must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public String description() {
            return "Add dampening to %s: %s".formatted(surfaceDescription, reason);
        }
    }

    /**
     * Suggests removing or reducing sound dampening from a room surface.
     *
     * @param surfaceDescription which surface has excessive dampening
     * @param reason             why removing dampening is recommended
     */
    record RemoveDampening(String surfaceDescription, String reason)
            implements TelemetrySuggestion {

        public RemoveDampening {
            Objects.requireNonNull(surfaceDescription, "surfaceDescription must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public String description() {
            return "Reduce dampening on %s: %s".formatted(surfaceDescription, reason);
        }
    }

    /**
     * Suggests moving a sound source (typically a monitor speaker) to a
     * different position to mitigate Speaker Boundary Interference Response
     * (SBIR) notches caused by the source being too close to a boundary.
     *
     * @param sourceName        the sound source to move
     * @param suggestedPosition the recommended new position
     * @param reason            why the move is recommended (e.g. predicted
     *                          notch frequency / depth and the offending
     *                          boundary)
     */
    record MoveSoundSource(String sourceName, Position3D suggestedPosition, String reason)
            implements TelemetrySuggestion {

        public MoveSoundSource {
            Objects.requireNonNull(sourceName, "sourceName must not be null");
            Objects.requireNonNull(suggestedPosition, "suggestedPosition must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public String description() {
            return "Move source '%s' to (%.2f, %.2f, %.2f): %s".formatted(
                    sourceName, suggestedPosition.x(), suggestedPosition.y(),
                    suggestedPosition.z(), reason);
        }
    }
}
