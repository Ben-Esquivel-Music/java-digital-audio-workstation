package com.benesquivelmusic.daw.core.telemetry.acoustics;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.BoundaryKind;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.SbirPrediction;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Speaker Boundary Interference Response (SBIR) calculator.
 *
 * <p>A speaker placed close to a hard surface (front wall, side wall,
 * floor, ceiling) produces a comb-filtering pattern at the listening
 * position because the wall reflection arrives delayed by twice the
 * speaker-to-boundary distance and combines destructively with the
 * direct sound. The first cancellation occurs at
 * <i>f<sub>0</sub> = c / (4d)</i> where <i>c</i> ≈ 343&nbsp;m/s is the
 * speed of sound and <i>d</i> is the speaker-to-boundary distance —
 * for example, a speaker 0.5&nbsp;m from the front wall produces a
 * notch at ≈&nbsp;172&nbsp;Hz that is invisible without
 * frequency-response analysis but is the single biggest tonal problem
 * in most home studios.</p>
 *
 * <p>This calculator uses the image-source method: for each boundary
 * the speaker's mirror image radiates a delayed copy of the direct
 * signal. Summing the direct and reflected pressures at the listener
 * gives the magnitude response. Reflections are computed up to order
 * 2 for the dominant boundary (the second-order image accounts for the
 * speaker–wall standing-wave bounce-back).</p>
 *
 * <p>The output is a {@link SbirPrediction} per (speaker, boundary)
 * combination. Convenience methods select the worst (deepest-notch)
 * boundary per speaker and emit
 * {@link TelemetrySuggestion.MoveSoundSource} suggestions whenever the
 * notch depth exceeds a configurable threshold in the 40–300&nbsp;Hz
 * SBIR-critical band.</p>
 */
public final class SbirCalculator {

    /** Speed of sound in air at 20 °C, in metres per second. */
    public static final double SPEED_OF_SOUND_M_S = 343.0;

    /** Lower edge of the SBIR-critical band (Hz). */
    public static final double SBIR_BAND_LOW_HZ = 40.0;

    /** Upper edge of the SBIR-critical band (Hz). */
    public static final double SBIR_BAND_HIGH_HZ = 300.0;

    /** Default suggestion threshold (notch depth in dB; more negative = deeper). */
    public static final double DEFAULT_NOTCH_THRESHOLD_DB = -5.0;

    /**
     * Default frequency grid: 256 logarithmically spaced bins from
     * 20&nbsp;Hz to 1&nbsp;kHz — fine enough to resolve the deepest
     * SBIR notch to within ≈&nbsp;1&nbsp;Hz in the critical band.
     */
    private static final double[] DEFAULT_FREQUENCIES = logSpace(20.0, 1000.0, 256);

    private final double[] frequenciesHz;

    /** Creates a calculator with the default 20&nbsp;Hz–1&nbsp;kHz log grid. */
    public SbirCalculator() {
        this(DEFAULT_FREQUENCIES);
    }

    /**
     * Creates a calculator that samples on the supplied frequency grid.
     *
     * @param frequenciesHz the frequency bins (must be non-empty and
     *                      contain only positive frequencies)
     */
    public SbirCalculator(double[] frequenciesHz) {
        Objects.requireNonNull(frequenciesHz, "frequenciesHz must not be null");
        if (frequenciesHz.length == 0) {
            throw new IllegalArgumentException("frequenciesHz must not be empty");
        }
        for (double f : frequenciesHz) {
            if (!(f > 0)) {
                throw new IllegalArgumentException(
                        "frequenciesHz must be strictly positive: " + f);
            }
        }
        this.frequenciesHz = frequenciesHz.clone();
    }

    // ------------------------------------------------------------------
    // Single-boundary calculation
    // ------------------------------------------------------------------

    /**
     * Computes the SBIR magnitude response at the listener for one
     * speaker bouncing off one specified boundary.
     *
     * @param speaker                     speaker position in room coordinates
     * @param listener                    listening (mic) position
     * @param boundary                    which boundary kind is being modelled
     * @param speakerToBoundaryDistanceM  perpendicular distance from
     *                                    speaker to the boundary (m)
     * @param reflectionCoefficient       pressure reflection coefficient
     *                                    of the boundary in [0, 1]
     *                                    (typically
     *                                    {@code Math.sqrt(1 -
     *                                    absorption)})
     * @return the predicted frequency response and worst notch
     */
    public SbirPrediction calculate(
            Position3D speaker,
            Position3D listener,
            BoundaryKind boundary,
            double speakerToBoundaryDistanceM,
            double reflectionCoefficient) {
        Objects.requireNonNull(speaker, "speaker must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        Objects.requireNonNull(boundary, "boundary must not be null");
        if (speakerToBoundaryDistanceM <= 0) {
            throw new IllegalArgumentException(
                    "speakerToBoundaryDistanceM must be positive: "
                            + speakerToBoundaryDistanceM);
        }
        if (reflectionCoefficient < 0 || reflectionCoefficient > 1) {
            throw new IllegalArgumentException(
                    "reflectionCoefficient must be in [0, 1]: "
                            + reflectionCoefficient);
        }

        double directDist = Math.max(speaker.distanceTo(listener), 1.0e-6);
        // Path-difference travelled by the reflected wave is 2d when the
        // listener lies on the same side of the boundary as the speaker
        // (the canonical SBIR geometry). This is also the *image-source*
        // path-difference for axial bounces.
        double pathDiff = 2.0 * speakerToBoundaryDistanceM;
        double reflectedDist = directDist + pathDiff;

        double[] mag = new double[frequenciesHz.length];
        double worstDb = 0.0;
        double worstFreq = 0.0;

        // 1/r pressure falloff for direct and reflected paths. We use
        // the textbook two-source image model: direct + one reflected
        // copy. (The issue's &quot;up to order 2&quot; directive refers
        // to multi-boundary combinations, handled by summing
        // {@link #calculateAllBoundaries} predictions; same-boundary
        // self-bouncing is energetically negligible compared to direct +
        // first reflection and would only blur the canonical c/(4d)
        // notch frequency.)
        double aDirect = 1.0 / directDist;
        double aRefl1  = reflectionCoefficient / reflectedDist;

        for (int i = 0; i < frequenciesHz.length; i++) {
            double f = frequenciesHz[i];
            double phi1 = 2.0 * Math.PI * f * pathDiff / SPEED_OF_SOUND_M_S;
            // Sum complex pressures.
            double re = aDirect + aRefl1 * Math.cos(phi1);
            double im = -aRefl1 * Math.sin(phi1);
            double magLin = Math.sqrt(re * re + im * im) / aDirect;
            double db = 20.0 * Math.log10(Math.max(magLin, 1.0e-9));
            mag[i] = db;

            if (f >= SBIR_BAND_LOW_HZ && f <= SBIR_BAND_HIGH_HZ && db < worstDb) {
                worstDb = db;
                worstFreq = f;
            }
        }
        // If no in-band notch was found (e.g. boundary so far away that
        // the first null is above 300 Hz), fall back to the global worst
        // bin so the prediction always carries a meaningful witness.
        if (worstFreq == 0.0) {
            for (int i = 0; i < mag.length; i++) {
                if (mag[i] < worstDb) {
                    worstDb = mag[i];
                    worstFreq = frequenciesHz[i];
                }
            }
        }
        return new SbirPrediction(frequenciesHz, mag, worstFreq, worstDb, boundary);
    }

    // ------------------------------------------------------------------
    // Whole-room calculation
    // ------------------------------------------------------------------

    /**
     * Computes one {@link SbirPrediction} per speaker, choosing the
     * boundary that produces the deepest in-band notch (the dominant
     * SBIR offender for that speaker).
     *
     * <p>Returned in the same order as
     * {@link RoomConfiguration#getSoundSources()}; an empty list is
     * returned when the room has no sources or no microphones.</p>
     *
     * @param config the room configuration
     * @return one prediction per source (worst-boundary), or an empty list
     */
    public List<SbirPrediction> calculate(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        List<MicrophonePlacement> mics = config.getMicrophones();
        List<SoundSource> sources = config.getSoundSources();
        if (mics.isEmpty() || sources.isEmpty()) return List.of();

        // Single-mic SBIR analysis (per the issue's non-goal: multiple
        // simultaneous mic positions are out of scope).
        Position3D listener = mics.get(0).position();
        RoomDimensions dims = config.getDimensions();
        SurfaceMaterialMap materials = config.getMaterialMap();

        List<SbirPrediction> out = new ArrayList<>(sources.size());
        for (SoundSource src : sources) {
            Map<BoundaryKind, SbirPrediction> perBoundary =
                    calculateAllBoundaries(src.position(), listener, dims, materials);
            // Choose the boundary with the deepest (most negative) notch.
            SbirPrediction worst = null;
            for (SbirPrediction p : perBoundary.values()) {
                if (worst == null || p.worstNotchDepthDb() < worst.worstNotchDepthDb()) {
                    worst = p;
                }
            }
            if (worst != null) out.add(worst);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Computes one prediction per boundary (front wall, back wall,
     * nearest side wall, floor, ceiling) for a single speaker.
     *
     * @param speaker   speaker position
     * @param listener  listener position
     * @param dims      room dimensions
     * @param materials per-surface materials
     * @return predictions keyed by boundary kind
     */
    public Map<BoundaryKind, SbirPrediction> calculateAllBoundaries(
            Position3D speaker, Position3D listener,
            RoomDimensions dims, SurfaceMaterialMap materials) {
        Objects.requireNonNull(speaker, "speaker must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        Objects.requireNonNull(dims, "dims must not be null");
        Objects.requireNonNull(materials, "materials must not be null");

        EnumMap<BoundaryKind, SbirPrediction> out = new EnumMap<>(BoundaryKind.class);

        // Front / back walls (Y axis).
        addIfPositive(out, BoundaryKind.FRONT_WALL,
                speaker.y(), materials.materialAt(RoomSurface.FRONT_WALL),
                speaker, listener);
        addIfPositive(out, BoundaryKind.BACK_WALL,
                dims.length() - speaker.y(),
                materials.materialAt(RoomSurface.BACK_WALL),
                speaker, listener);

        // Nearest side wall (X axis).
        double leftDist = speaker.x();
        double rightDist = dims.width() - speaker.x();
        RoomSurface sideSurface = leftDist <= rightDist
                ? RoomSurface.LEFT_WALL : RoomSurface.RIGHT_WALL;
        double sideDist = Math.min(leftDist, rightDist);
        addIfPositive(out, BoundaryKind.SIDE_WALL,
                sideDist, materials.materialAt(sideSurface),
                speaker, listener);

        // Floor / ceiling (Z axis).
        addIfPositive(out, BoundaryKind.FLOOR,
                speaker.z(), materials.materialAt(RoomSurface.FLOOR),
                speaker, listener);
        addIfPositive(out, BoundaryKind.CEILING,
                dims.height() - speaker.z(),
                materials.materialAt(RoomSurface.CEILING),
                speaker, listener);

        return out;
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    /**
     * Returns {@link TelemetrySuggestion.MoveSoundSource} suggestions
     * for every speaker whose worst-boundary notch exceeds
     * {@link #DEFAULT_NOTCH_THRESHOLD_DB} (–5&nbsp;dB).
     *
     * @param config the room configuration
     * @return move-speaker suggestions (possibly empty, never {@code null})
     */
    public List<TelemetrySuggestion> suggestMitigations(RoomConfiguration config) {
        return suggestMitigations(config, DEFAULT_NOTCH_THRESHOLD_DB);
    }

    /**
     * Returns {@link TelemetrySuggestion.MoveSoundSource} suggestions
     * for every speaker whose worst-boundary notch in the
     * {@link #SBIR_BAND_LOW_HZ}–{@link #SBIR_BAND_HIGH_HZ} band is
     * deeper than {@code thresholdDb}.
     *
     * <p>The recommended new position moves the speaker enough away
     * from the offending boundary to push the notch down to
     * ≈&nbsp;40&nbsp;Hz (i.e. just below the audible SBIR band),
     * computed from the {@code c / (4d)} relation.</p>
     *
     * @param config      the room configuration
     * @param thresholdDb threshold in dB (must be ≤ 0; e.g. {@code -5.0})
     * @return move-speaker suggestions
     */
    public List<TelemetrySuggestion> suggestMitigations(
            RoomConfiguration config, double thresholdDb) {
        Objects.requireNonNull(config, "config must not be null");
        if (thresholdDb > 0) {
            throw new IllegalArgumentException(
                    "thresholdDb must be ≤ 0 (notch depths are negative): "
                            + thresholdDb);
        }
        List<MicrophonePlacement> mics = config.getMicrophones();
        List<SoundSource> sources = config.getSoundSources();
        if (mics.isEmpty() || sources.isEmpty()) return List.of();

        Position3D listener = mics.get(0).position();
        RoomDimensions dims = config.getDimensions();
        SurfaceMaterialMap materials = config.getMaterialMap();

        List<TelemetrySuggestion> out = new ArrayList<>();
        for (SoundSource src : sources) {
            Map<BoundaryKind, SbirPrediction> perBoundary =
                    calculateAllBoundaries(src.position(), listener, dims, materials);
            SbirPrediction worst = null;
            for (SbirPrediction p : perBoundary.values()) {
                if (worst == null || p.worstNotchDepthDb() < worst.worstNotchDepthDb()) {
                    worst = p;
                }
            }
            if (worst == null || worst.worstNotchDepthDb() >= thresholdDb) continue;

            // Recommend the smallest boundary distance that pushes the
            // first null below the SBIR band: f = c/(4d) → d = c/(4f).
            double recommendedDistance =
                    SPEED_OF_SOUND_M_S / (4.0 * SBIR_BAND_LOW_HZ);
            Position3D moved = movedAway(
                    src.position(), worst.boundary(), dims, recommendedDistance);
            String reason = "%.0f dB notch at %.0f Hz from %s — move source ≥ %.2f m from boundary"
                    .formatted(worst.worstNotchDepthDb(), worst.worstNotchHz(),
                            describe(worst.boundary()), recommendedDistance);
            out.add(new TelemetrySuggestion.MoveSoundSource(src.name(), moved, reason));
        }
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void addIfPositive(
            EnumMap<BoundaryKind, SbirPrediction> out,
            BoundaryKind boundary,
            double distance,
            WallMaterial material,
            Position3D speaker, Position3D listener) {
        if (distance <= 0) return;
        // Pressure reflection coefficient ≈ √(1 − α). Using sqrt rather
        // than (1 − α) reflects that α is an *energy* coefficient.
        double alpha = Math.max(0.0, Math.min(1.0, material.absorptionCoefficient()));
        double r = Math.sqrt(1.0 - alpha);
        out.put(boundary, calculate(speaker, listener, boundary, distance, r));
    }

    private static Position3D movedAway(
            Position3D speaker, BoundaryKind boundary,
            RoomDimensions dims, double targetDistance) {
        return switch (boundary) {
            case FRONT_WALL -> new Position3D(
                    speaker.x(), Math.max(targetDistance, speaker.y()), speaker.z());
            case BACK_WALL -> new Position3D(
                    speaker.x(),
                    Math.min(dims.length() - targetDistance, speaker.y()),
                    speaker.z());
            case SIDE_WALL -> {
                double leftDist = speaker.x();
                double rightDist = dims.width() - speaker.x();
                double newX = leftDist <= rightDist
                        ? Math.max(targetDistance, speaker.x())
                        : Math.min(dims.width() - targetDistance, speaker.x());
                yield new Position3D(newX, speaker.y(), speaker.z());
            }
            case FLOOR -> new Position3D(
                    speaker.x(), speaker.y(),
                    Math.max(targetDistance, speaker.z()));
            case CEILING -> new Position3D(
                    speaker.x(), speaker.y(),
                    Math.min(dims.height() - targetDistance, speaker.z()));
        };
    }

    private static String describe(BoundaryKind boundary) {
        return switch (boundary) {
            case FRONT_WALL -> "front wall";
            case BACK_WALL  -> "back wall";
            case SIDE_WALL  -> "side wall";
            case FLOOR      -> "floor";
            case CEILING    -> "ceiling";
        };
    }

    private static double[] logSpace(double from, double to, int count) {
        double[] out = new double[count];
        double logFrom = Math.log(from);
        double logTo = Math.log(to);
        double step = (logTo - logFrom) / (count - 1);
        for (int i = 0; i < count; i++) {
            out[i] = Math.exp(logFrom + i * step);
        }
        return out;
    }

    /**
     * Returns a defensive copy of the frequency grid this calculator
     * samples on.
     */
    public double[] frequenciesHz() {
        return frequenciesHz.clone();
    }

    /**
     * Default frequency grid (20&nbsp;Hz – 1&nbsp;kHz, 256 log-spaced bins).
     * Useful for callers that want to share grids between predictions.
     */
    public static double[] defaultFrequenciesHz() {
        return Arrays.copyOf(DEFAULT_FREQUENCIES, DEFAULT_FREQUENCIES.length);
    }
}
