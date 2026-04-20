package com.benesquivelmusic.daw.core.telemetry.acoustics;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.SoundWaveTelemetryEngine;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.ModeKind;
import com.benesquivelmusic.daw.sdk.telemetry.ModeSpectrum;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomMode;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * First-principles room-mode calculator for rectangular ("shoebox")
 * rooms.
 *
 * <p>Below the Schroeder frequency the room's length, width, and
 * height produce discrete standing-wave resonances at predictable
 * frequencies. This calculator enumerates the axial, tangential, and
 * oblique modes up to a configurable order, evaluates each mode's
 * pressure magnitude at the listening position, and reports them as a
 * {@link ModeSpectrum} together with the Schroeder transition
 * frequency.</p>
 *
 * <p>The resonance frequency of a mode with indices {@code (nx, ny, nz)}
 * in a room of dimensions {@code Lx × Ly × Lz} is
 *
 * <pre>f(nx, ny, nz) = (c / 2) · √((nx/Lx)² + (ny/Ly)² + (nz/Lz)²)</pre>
 *
 * where {@code c} ≈ 343&nbsp;m/s is the speed of sound. The modal
 * pressure at position {@code (x, y, z)} in the room is
 *
 * <pre>p(x, y, z) = cos(nx·π·x/Lx) · cos(ny·π·y/Ly) · cos(nz·π·z/Lz)</pre>
 *
 * which we convert to dB as {@code 20·log10(|p|)} and clip to a sane
 * floor (−80&nbsp;dB) so consumers can render it directly as a bar
 * height.</p>
 *
 * <p>The Schroeder frequency is computed from the room's Sabine-style
 * RT60 as {@code f_s ≈ 2000 · √(T60 / V)}. Above this frequency modes
 * overlap densely and the diffuse-field statistical model (RT60, not
 * individual modes) is the right tool.</p>
 *
 * <p>Suggestions are produced for two well-known modal problems:
 * <ul>
 *   <li><b>Clusters</b> — pairs of modes closer than
 *       {@value #CLUSTER_THRESHOLD_HZ}&nbsp;Hz pile up into a strong
 *       peak that is audible as a boom.</li>
 *   <li><b>Poor proportions</b> — if any pair of room dimensions is an
 *       integer multiple of another (within
 *       {@value #PROPORTION_TOLERANCE}) the axial modes coincide, a
 *       classic Bolt-area / Bonello failure.</li>
 * </ul>
 * </p>
 *
 * <p>This calculator is stateless and thread-safe.</p>
 */
public final class RoomModeCalculator {

    /** Speed of sound in air at 20 °C, in metres per second. */
    public static final double SPEED_OF_SOUND_M_S = 343.0;

    /** Default maximum mode order (n in each axis) — 3 as per the issue spec. */
    public static final int DEFAULT_MAX_ORDER = 3;

    /**
     * Pairs of modes separated by less than this many Hz are flagged
     * as a problematic cluster (Toole's rule of thumb for small rooms).
     */
    public static final double CLUSTER_THRESHOLD_HZ = 20.0;

    /**
     * Tolerance for the integer-ratio check used by the Bolt /
     * Bonello proportion evaluation. Two dimensions are considered
     * near-multiples if their ratio is within this fraction of an
     * integer.
     */
    public static final double PROPORTION_TOLERANCE = 0.05;

    /** Magnitude floor — we never return values below this in dB. */
    private static final double MAGNITUDE_FLOOR_DB = -80.0;

    private final int maxOrder;

    /** Creates a calculator that enumerates modes up to {@link #DEFAULT_MAX_ORDER}. */
    public RoomModeCalculator() {
        this(DEFAULT_MAX_ORDER);
    }

    /**
     * Creates a calculator that enumerates modes up to {@code maxOrder}
     * on each axis (so the highest-order oblique mode evaluated is
     * {@code (maxOrder, maxOrder, maxOrder)}).
     *
     * @param maxOrder the maximum mode index on any axis (must be ≥ 1)
     */
    public RoomModeCalculator(int maxOrder) {
        if (maxOrder < 1) {
            throw new IllegalArgumentException(
                    "maxOrder must be ≥ 1: " + maxOrder);
        }
        this.maxOrder = maxOrder;
    }

    /** Returns the maximum mode order enumerated on each axis. */
    public int maxOrder() {
        return maxOrder;
    }

    // ------------------------------------------------------------------
    // Core calculation
    // ------------------------------------------------------------------

    /**
     * Computes the mode spectrum for the supplied room, assuming the
     * listener sits in the geometric centre (the default if no
     * microphone is placed). RT60 — used to compute the Schroeder
     * frequency — is estimated via Sabine from the supplied materials.
     *
     * @param dims      room dimensions
     * @param materials per-surface material map (drives RT60)
     * @return the mode spectrum
     */
    public ModeSpectrum calculate(RoomDimensions dims, SurfaceMaterialMap materials) {
        Objects.requireNonNull(dims, "dims must not be null");
        Objects.requireNonNull(materials, "materials must not be null");
        Position3D listener = new Position3D(
                dims.width() / 2.0, dims.length() / 2.0, dims.height() / 2.0);
        double rt60 = SoundWaveTelemetryEngine.estimateRt60(dims, materials);
        return calculate(dims, listener, rt60);
    }

    /**
     * Computes the mode spectrum for the supplied room with the
     * listener at the given position and a caller-supplied RT60 value.
     *
     * @param dims     room dimensions
     * @param listener listener (microphone) position
     * @param rt60     reverberation time in seconds (must be &gt; 0)
     * @return the mode spectrum
     */
    public ModeSpectrum calculate(RoomDimensions dims, Position3D listener, double rt60) {
        Objects.requireNonNull(dims, "dims must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (!(rt60 > 0) || Double.isNaN(rt60) || Double.isInfinite(rt60)) {
            throw new IllegalArgumentException(
                    "rt60 must be a finite positive number: " + rt60);
        }

        double lx = dims.width();
        double ly = dims.length();
        double lz = dims.height();

        List<RoomMode> modes = new ArrayList<>();
        for (int nx = 0; nx <= maxOrder; nx++) {
            for (int ny = 0; ny <= maxOrder; ny++) {
                for (int nz = 0; nz <= maxOrder; nz++) {
                    if (nx == 0 && ny == 0 && nz == 0) continue;
                    double fx = nx / lx;
                    double fy = ny / ly;
                    double fz = nz / lz;
                    double freq = 0.5 * SPEED_OF_SOUND_M_S
                            * Math.sqrt(fx * fx + fy * fy + fz * fz);
                    double magDb = magnitudeAtDb(nx, ny, nz, dims, listener);
                    modes.add(new RoomMode(
                            freq, ModeKind.classify(nx, ny, nz),
                            new int[] {nx, ny, nz}, magDb));
                }
            }
        }
        modes.sort(Comparator.comparingDouble(RoomMode::frequencyHz));

        double schroederHz = schroederFrequencyHz(rt60, dims.volume());
        return new ModeSpectrum(modes, schroederHz);
    }

    /**
     * Computes the mode spectrum for an entire {@link RoomConfiguration}
     * — listener defaults to the first microphone (or the room centre
     * if there are none), and RT60 is computed from the room's
     * material map.
     *
     * @param config the room configuration
     * @return the mode spectrum
     */
    public ModeSpectrum calculate(RoomConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");
        RoomDimensions dims = config.getDimensions();
        SurfaceMaterialMap materials = config.getMaterialMap();
        List<MicrophonePlacement> mics = config.getMicrophones();

        Position3D listener = mics.isEmpty()
                ? new Position3D(dims.width() / 2.0, dims.length() / 2.0, dims.height() / 2.0)
                : mics.get(0).position();

        double rt60 = SoundWaveTelemetryEngine.estimateRt60(dims, materials);
        return calculate(dims, listener, rt60);
    }

    /**
     * Computes the Schroeder transition frequency in Hz from an RT60
     * reverberation time (in seconds) and a room volume (in cubic
     * metres).
     *
     * <p>{@code f_s ≈ 2000 · √(T60 / V)}. Below this frequency the
     * modal response dominates; above it the room is in the diffuse
     * (statistical) regime.</p>
     *
     * @param rt60Seconds RT60 in seconds (must be &gt; 0)
     * @param volumeM3    volume in m³ (must be &gt; 0)
     * @return the Schroeder frequency in Hz
     */
    public static double schroederFrequencyHz(double rt60Seconds, double volumeM3) {
        if (!(rt60Seconds > 0) || Double.isNaN(rt60Seconds)
                || Double.isInfinite(rt60Seconds)) {
            throw new IllegalArgumentException(
                    "rt60Seconds must be a finite positive number: " + rt60Seconds);
        }
        if (!(volumeM3 > 0) || Double.isNaN(volumeM3) || Double.isInfinite(volumeM3)) {
            throw new IllegalArgumentException(
                    "volumeM3 must be a finite positive number: " + volumeM3);
        }
        return 2000.0 * Math.sqrt(rt60Seconds / volumeM3);
    }

    /**
     * Returns the modal-pressure magnitude at {@code listener} for
     * mode {@code (nx, ny, nz)}, in dB re. the pressure antinode
     * (0&nbsp;dB = antinode, negative = partial null). Clipped to a
     * {@value #MAGNITUDE_FLOOR_DB}&nbsp;dB floor.
     */
    private static double magnitudeAtDb(
            int nx, int ny, int nz, RoomDimensions dims, Position3D listener) {
        double px = Math.abs(Math.cos(nx * Math.PI * listener.x() / dims.width()));
        double py = Math.abs(Math.cos(ny * Math.PI * listener.y() / dims.length()));
        double pz = Math.abs(Math.cos(nz * Math.PI * listener.z() / dims.height()));
        double p = px * py * pz;
        if (p <= 0) return MAGNITUDE_FLOOR_DB;
        double db = 20.0 * Math.log10(p);
        return Math.max(db, MAGNITUDE_FLOOR_DB);
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    /**
     * Returns advisory suggestions for the supplied spectrum and room
     * geometry. Currently two checks are performed:
     * <ul>
     *   <li>Modal clusters — pairs of modes closer than
     *       {@value #CLUSTER_THRESHOLD_HZ}&nbsp;Hz below the Schroeder
     *       frequency (these pile up into strong peaks).</li>
     *   <li>Poor room proportions (Bolt / Bonello) — any pair of
     *       dimensions being close to an integer multiple of another
     *       causes axial modes to coincide.</li>
     * </ul>
     *
     * <p>Suggestions are emitted as
     * {@link TelemetrySuggestion.AddDampening} so they fit the
     * existing suggestion pipeline without a new variant. The {@code
     * surfaceDescription} field carries the boundary pair implicated
     * by the clustering, and {@code reason} narrates the finding.</p>
     *
     * @param dims     room dimensions
     * @param spectrum the computed mode spectrum
     * @return advisory suggestions (possibly empty, never {@code null})
     */
    public List<TelemetrySuggestion> suggestMitigations(
            RoomDimensions dims, ModeSpectrum spectrum) {
        Objects.requireNonNull(dims, "dims must not be null");
        Objects.requireNonNull(spectrum, "spectrum must not be null");

        List<TelemetrySuggestion> out = new ArrayList<>();

        // ── Cluster detection — sort by frequency and scan consecutive pairs.
        List<RoomMode> sorted = new ArrayList<>(spectrum.modes());
        sorted.sort(Comparator.comparingDouble(RoomMode::frequencyHz));
        for (int i = 1; i < sorted.size(); i++) {
            RoomMode a = sorted.get(i - 1);
            RoomMode b = sorted.get(i);
            if (b.frequencyHz() > spectrum.schroederHz()) break;
            double gap = b.frequencyHz() - a.frequencyHz();
            if (gap < CLUSTER_THRESHOLD_HZ && gap > 0) {
                out.add(new TelemetrySuggestion.AddDampening(
                        "low-frequency absorbers (bass traps)",
                        ("Modal cluster: %s and %s are only %.1f Hz apart "
                                + "(< %.0f Hz) — this produces a strong peak. "
                                + "Add bass traps or move the listening "
                                + "position to attenuate.")
                                .formatted(describe(a), describe(b), gap,
                                        CLUSTER_THRESHOLD_HZ)));
            }
        }

        // ── Bolt / Bonello proportion evaluation.
        String proportion = evaluateProportions(dims);
        if (proportion != null) {
            out.add(new TelemetrySuggestion.AddDampening(
                    "low-frequency absorbers (bass traps)",
                    proportion));
        }

        return Collections.unmodifiableList(out);
    }

    /**
     * Returns a non-{@code null} description of a proportion problem
     * (Bolt-area / Bonello failure) or {@code null} if the room
     * proportions are acceptable.
     */
    static String evaluateProportions(RoomDimensions dims) {
        double lx = dims.width();
        double ly = dims.length();
        double lz = dims.height();
        String hit = checkPair(ly, lz, "length", "height");
        if (hit != null) return hit;
        hit = checkPair(lx, lz, "width", "height");
        if (hit != null) return hit;
        hit = checkPair(ly, lx, "length", "width");
        return hit;
    }

    /**
     * Returns a human-readable Bolt-area warning if {@code a / b} is
     * within {@link #PROPORTION_TOLERANCE} of a non-unity integer —
     * for example a 4&nbsp;m × 2&nbsp;m floor plan, whose axial modes
     * would all coincide. Returns {@code null} otherwise.
     */
    private static String checkPair(double a, double b, String aName, String bName) {
        double ratio = a / b;
        double nearestInt = Math.round(ratio);
        if (nearestInt < 2) return null;
        if (Math.abs(ratio - nearestInt) <= PROPORTION_TOLERANCE) {
            return ("Poor room proportion: %s (%.2f m) is ≈ %.0f× %s (%.2f m); "
                    + "axial modes on these axes will coincide (Bolt / Bonello "
                    + "criterion). Consider changing dimensions or adding heavy "
                    + "bass trapping.").formatted(
                    aName, a, nearestInt, bName, b);
        }
        return null;
    }

    private static String describe(RoomMode m) {
        return "%.1f Hz %s (%d,%d,%d)".formatted(
                m.frequencyHz(),
                switch (m.kind()) {
                    case AXIAL      -> "axial";
                    case TANGENTIAL -> "tangential";
                    case OBLIQUE    -> "oblique";
                },
                m.nx(), m.ny(), m.nz());
    }

    // ------------------------------------------------------------------
    // Convenience — legacy single-material overload
    // ------------------------------------------------------------------

    /**
     * Convenience wrapper delegating to
     * {@link #calculate(RoomDimensions, SurfaceMaterialMap)} with a
     * uniform material map.
     *
     * @param dims     room dimensions
     * @param material the single wall material to broadcast
     * @return the mode spectrum
     */
    public ModeSpectrum calculate(RoomDimensions dims, WallMaterial material) {
        Objects.requireNonNull(material, "material must not be null");
        return calculate(dims, new SurfaceMaterialMap(material));
    }
}
