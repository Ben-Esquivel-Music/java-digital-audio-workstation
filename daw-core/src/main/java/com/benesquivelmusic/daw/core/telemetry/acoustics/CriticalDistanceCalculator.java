package com.benesquivelmusic.daw.core.telemetry.acoustics;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.SoundWaveTelemetryEngine;
import com.benesquivelmusic.daw.sdk.telemetry.CriticalDistanceSnapshot;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SourceDirectivity;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Computes the <em>critical distance</em> — the distance from a sound
 * source at which the direct-sound energy equals the reverberant-field
 * energy — for every source in a {@link RoomConfiguration}.
 *
 * <p>For a source of directivity factor {@code Q} in a diffuse room of
 * volume {@code V} and reverberation time {@code T60}, critical
 * distance is
 *
 * <pre>d_c = 0.141 · √(Q · V / (π · T60))</pre>
 *
 * (Beranek / Kuttruff). Inside the {@code d_c} sphere the direct field
 * dominates (high clarity, low reverberant colouration); outside it the
 * reverberant field dominates and clarity falls off.</p>
 *
 * <p>The direct-to-reverberant ratio (D/R) at a listener distance
 * {@code r} follows from the same model and is reported in dB via
 * {@link #directToReverberantRatioDb(double, double)}:
 *
 * <pre>D/R = 20 · log₁₀(d_c / r)</pre></p>
 *
 * <p>This calculator is stateless and thread-safe.</p>
 */
public final class CriticalDistanceCalculator {

    /**
     * Leading coefficient in the critical-distance formula —
     * {@code 0.141 ≈ √(1/4π) · √γ}, the classical Beranek factor.
     */
    public static final double COEFFICIENT = 0.141;

    /** Creates a new critical-distance calculator. */
    public CriticalDistanceCalculator() {
        // stateless
    }

    // ------------------------------------------------------------------
    // Core formula
    // ------------------------------------------------------------------

    /**
     * Returns the critical distance in metres for a source of
     * directivity {@code Q} in a room of volume {@code volumeM3} with
     * reverberation time {@code rt60Seconds}.
     *
     * @param q           directivity factor (must be &gt; 0; 1.0 for omni)
     * @param volumeM3    room volume in m³ (must be &gt; 0)
     * @param rt60Seconds RT60 in seconds (must be &gt; 0)
     * @return {@code d_c} in metres
     */
    public static double criticalDistanceMeters(
            double q, double volumeM3, double rt60Seconds) {
        requirePositiveFinite(q, "q");
        requirePositiveFinite(volumeM3, "volumeM3");
        requirePositiveFinite(rt60Seconds, "rt60Seconds");
        return COEFFICIENT * Math.sqrt(q * volumeM3 / (Math.PI * rt60Seconds));
    }

    /**
     * Returns the direct-to-reverberant ratio in dB for a listener at
     * {@code distanceMeters} from a source whose critical distance is
     * {@code criticalDistanceMeters}.
     *
     * <p>Positive values indicate the direct field dominates; negative
     * values indicate the reverberant field dominates. The ratio is
     * clamped at ±80&nbsp;dB so callers rendering it as a bar or colour
     * need not special-case near-zero distances.</p>
     *
     * @param distanceMeters         listener distance (&gt; 0)
     * @param criticalDistanceMeters {@code d_c} for this source (&gt; 0)
     * @return D/R in dB
     */
    public static double directToReverberantRatioDb(
            double distanceMeters, double criticalDistanceMeters) {
        requirePositiveFinite(distanceMeters, "distanceMeters");
        requirePositiveFinite(criticalDistanceMeters, "criticalDistanceMeters");
        double db = 20.0 * Math.log10(criticalDistanceMeters / distanceMeters);
        return Math.max(-80.0, Math.min(80.0, db));
    }

    /**
     * Returns a deterministic {@link UUID} identifying {@code source}.
     * Two sources with the same name collapse to the same id — which is
     * the desired behaviour for project-level overlays (the UI keys on
     * source name) and is stable across serialize/deserialize cycles.
     */
    public static UUID sourceId(SoundSource source) {
        Objects.requireNonNull(source, "source must not be null");
        return UUID.nameUUIDFromBytes(
                source.name().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Whole-room calculations
    // ------------------------------------------------------------------

    /**
     * Returns one {@link CriticalDistanceSnapshot} per source in
     * {@code config}. Directivity is taken from
     * {@link RoomConfiguration#getSourceDirectivity(String)}, and RT60
     * is estimated via Sabine from the room's materials.
     *
     * @param config the room configuration
     * @return per-source snapshots, in source order (never {@code null})
     */
    public List<CriticalDistanceSnapshot> calculate(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        List<SoundSource> sources = config.getSoundSources();
        if (sources.isEmpty()) return List.of();

        RoomDimensions dims = config.getDimensions();
        SurfaceMaterialMap materials = config.getMaterialMap();
        double rt60 = SoundWaveTelemetryEngine.estimateRt60(dims, materials);
        double volume = dims.volume();

        List<CriticalDistanceSnapshot> out = new ArrayList<>(sources.size());
        for (SoundSource source : sources) {
            SourceDirectivity directivity =
                    config.getSourceDirectivity(source.name());
            double dc = criticalDistanceMeters(
                    directivity.q(), volume, rt60);
            out.add(new CriticalDistanceSnapshot(
                    sourceId(source), dc, directivity));
        }
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    /**
     * Emits a {@link TelemetrySuggestion.AdjustMicPosition} for every
     * (mic, source) pair where the microphone lies in the reverberant
     * field of the source — i.e. {@code r &gt; d_c}. The suggested new
     * position moves the microphone to 0.9 × {@code d_c} along the line
     * from source to mic so the listener sits clearly inside the direct
     * field.
     *
     * @param config the room configuration
     * @return move-mic suggestions (possibly empty, never {@code null})
     */
    public List<TelemetrySuggestion> suggestMitigations(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        List<MicrophonePlacement> mics = config.getMicrophones();
        List<SoundSource> sources = config.getSoundSources();
        if (mics.isEmpty() || sources.isEmpty()) return List.of();

        List<CriticalDistanceSnapshot> snapshots = calculate(config);
        List<TelemetrySuggestion> out = new ArrayList<>();
        for (MicrophonePlacement mic : mics) {
            for (int i = 0; i < sources.size(); i++) {
                SoundSource src = sources.get(i);
                CriticalDistanceSnapshot snap = snapshots.get(i);
                double dc = snap.distanceMeters();
                double r = mic.position().distanceTo(src.position());
                if (r <= dc || dc <= 0) continue;

                double drDb = directToReverberantRatioDb(r, dc);
                Position3D suggested = movedTowardSource(
                        mic.position(), src.position(), 0.9 * dc);
                String reason = ("Mic '%s' is in the reverberant field of "
                        + "source '%s' (%.2f m > d_c %.2f m, D/R %.1f dB). "
                        + "Move closer for more direct sound, or add "
                        + "broadband absorption to reduce RT60.")
                        .formatted(mic.name(), src.name(), r, dc, drDb);
                out.add(new TelemetrySuggestion.AdjustMicPosition(
                        mic.name(), suggested, reason));
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Position3D movedTowardSource(
            Position3D mic, Position3D source, double targetDistanceFromSource) {
        double dx = mic.x() - source.x();
        double dy = mic.y() - source.y();
        double dz = mic.z() - source.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1.0e-9) return mic;
        double scale = targetDistanceFromSource / len;
        return new Position3D(
                source.x() + dx * scale,
                source.y() + dy * scale,
                source.z() + dz * scale);
    }

    private static void requirePositiveFinite(double v, String name) {
        if (!(v > 0) || Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(
                    name + " must be a finite positive number: " + v);
        }
    }
}
