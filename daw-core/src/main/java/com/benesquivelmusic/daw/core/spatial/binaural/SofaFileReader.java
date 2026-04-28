package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.core.export.SampleRateConverter;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * High-level reader that imports a SOFA file (AES69-2020) and converts it to
 * a {@link PersonalizedHrtfProfile} ready for binaural rendering.
 *
 * <p>This reader sits on top of the lower-level {@link SofaFileParser} and
 * adds three concerns required by the import workflow:</p>
 * <ol>
 *   <li><b>Schema validation</b> — confirms the parsed {@link HrtfData}
 *       contains a non-empty set of source positions, two receivers
 *       (left + right ear), and a positive impulse-response length. Files
 *       that violate these invariants are rejected with a descriptive
 *       {@link IOException}.</li>
 *   <li><b>Sample-rate matching</b> — when the SOFA file was measured at a
 *       different sample rate than the active session, every impulse response
 *       is resampled with {@link SampleRateConverter} (story-126) so the
 *       renderer can use the data without further conversion.</li>
 *   <li><b>Coverage reporting</b> — a short list of human-readable warnings
 *       is returned for sparse measurement sets (e.g. fewer than ~20 unique
 *       directions, or no upper / lower hemisphere measurements). The
 *       SOFA-import dialog uses these to alert the user before commit.</li>
 * </ol>
 *
 * <p>The reader does <em>not</em> persist the imported profile — that is the
 * responsibility of {@link HrtfProfileLibrary}, which uses this class as its
 * import primitive.</p>
 */
public final class SofaFileReader {

    /**
     * Below this number of unique measurement directions, externalization
     * accuracy degrades noticeably; the reader emits a sparseness warning.
     */
    private static final int SPARSE_MEASUREMENT_THRESHOLD = 20;

    private SofaFileReader() {
        // utility class
    }

    /**
     * Outcome of a successful SOFA import.
     *
     * @param profile          the imported, session-rate-aligned profile
     * @param originalSampleRate the SOFA file's native sample rate, in Hz
     * @param resampled        {@code true} when impulses were resampled to the
     *                         session rate (i.e. the rates differed)
     * @param warnings         non-fatal advisories (sparse coverage,
     *                         missing hemispheres, etc.) — never {@code null}
     */
    public record ImportResult(
            PersonalizedHrtfProfile profile,
            double originalSampleRate,
            boolean resampled,
            List<String> warnings) {

        public ImportResult {
            Objects.requireNonNull(profile, "profile must not be null");
            Objects.requireNonNull(warnings, "warnings must not be null");
            warnings = List.copyOf(warnings);
        }

        /** Returns {@code true} iff at least one non-fatal warning was raised. */
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    /**
     * Reads and validates a SOFA file, returning a profile already aligned to
     * the requested session sample rate.
     *
     * @param sofaFile          path to the SOFA file (must exist and be readable)
     * @param sessionSampleRate session sample rate in Hz; impulses are resampled
     *                          to this rate when the SOFA file's rate differs
     * @return the import result, including any non-fatal warnings
     * @throws IOException              if the file cannot be read or is not a
     *                                  conformant SOFA/HDF5 file
     * @throws IllegalArgumentException if {@code sessionSampleRate <= 0}
     */
    public static ImportResult read(Path sofaFile, double sessionSampleRate) throws IOException {
        Objects.requireNonNull(sofaFile, "sofaFile must not be null");
        if (sessionSampleRate <= 0) {
            throw new IllegalArgumentException(
                    "sessionSampleRate must be positive: " + sessionSampleRate);
        }
        if (!Files.isRegularFile(sofaFile)) {
            throw new IOException("SOFA file does not exist or is not a regular file: " + sofaFile);
        }
        if (!Files.isReadable(sofaFile)) {
            throw new IOException("SOFA file is not readable: " + sofaFile);
        }

        HrtfData raw = SofaFileParser.parse(sofaFile);
        return fromHrtfData(raw, deriveProfileName(sofaFile), sessionSampleRate);
    }

    /**
     * Builds an {@link ImportResult} from already-parsed {@link HrtfData}.
     *
     * <p>Exposed for callers (and tests) that obtain HRTF data through means
     * other than parsing a SOFA file directly — for example, synthetic data
     * or alternative formats decoded into the same in-memory shape.</p>
     *
     * @param data              parsed HRTF data
     * @param profileName       desired profile name (typically the SOFA file basename)
     * @param sessionSampleRate session sample rate in Hz
     * @return the import result
     * @throws IOException if {@code data} fails schema validation
     */
    public static ImportResult fromHrtfData(HrtfData data, String profileName,
                                            double sessionSampleRate) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(profileName, "profileName must not be null");
        if (sessionSampleRate <= 0) {
            throw new IllegalArgumentException(
                    "sessionSampleRate must be positive: " + sessionSampleRate);
        }

        validateSchema(data);

        double sourceRate = data.sampleRate();
        boolean needsResample = Double.compare(sourceRate, sessionSampleRate) != 0;

        int m = data.measurementCount();
        float[][] left = new float[m][];
        float[][] right = new float[m][];
        double[][] positions = new double[m][3];

        for (int i = 0; i < m; i++) {
            float[] l = data.impulseResponses()[i][0];
            float[] r = data.impulseResponses()[i][1];
            if (needsResample) {
                left[i] = SampleRateConverter.convert(l,
                        (int) Math.round(sourceRate),
                        (int) Math.round(sessionSampleRate));
                right[i] = SampleRateConverter.convert(r,
                        (int) Math.round(sourceRate),
                        (int) Math.round(sessionSampleRate));
            } else {
                left[i] = l.clone();
                right[i] = r.clone();
            }

            SphericalCoordinate pos = data.sourcePositions().get(i);
            positions[i][0] = pos.azimuthDegrees();
            positions[i][1] = pos.elevationDegrees();
            positions[i][2] = pos.distanceMeters();
        }

        // Resampling can yield slightly different per-row lengths if the input
        // happened to vary; pad/truncate to the shortest common length so the
        // record's per-measurement length invariant holds.
        int n = Math.min(left[0].length, right[0].length);
        for (int i = 1; i < m; i++) {
            n = Math.min(n, Math.min(left[i].length, right[i].length));
        }
        boolean needsTrim = false;
        for (int i = 0; i < m; i++) {
            if (left[i].length != n || right[i].length != n) {
                needsTrim = true;
                break;
            }
        }
        if (needsTrim) {
            for (int i = 0; i < m; i++) {
                if (left[i].length != n) {
                    float[] trimmed = new float[n];
                    System.arraycopy(left[i], 0, trimmed, 0, n);
                    left[i] = trimmed;
                }
                if (right[i].length != n) {
                    float[] trimmed = new float[n];
                    System.arraycopy(right[i], 0, trimmed, 0, n);
                    right[i] = trimmed;
                }
            }
        }

        PersonalizedHrtfProfile profile = new PersonalizedHrtfProfile(
                profileName, m, sessionSampleRate, left, right, positions);
        List<String> warnings = computeCoverageWarnings(positions, sourceRate, sessionSampleRate);
        return new ImportResult(profile, sourceRate, needsResample, warnings);
    }

    /**
     * Validates that a parsed SOFA dataset conforms to the schema this reader
     * supports: a non-empty list of measurements, exactly two receivers
     * (left + right ear), and positive impulse-response length.
     *
     * @throws IOException if any invariant is violated
     */
    private static void validateSchema(HrtfData data) throws IOException {
        if (data.measurementCount() <= 0) {
            throw new IOException("SOFA file contains no measurements");
        }
        if (data.receiverCount() != 2) {
            throw new IOException(
                    "SOFA file must have exactly 2 receivers (left + right ear), got: "
                            + data.receiverCount());
        }
        if (data.irLength() <= 0) {
            throw new IOException("SOFA file impulse responses are empty");
        }
        if (data.sampleRate() <= 0) {
            throw new IOException(
                    "SOFA file declares non-positive sample rate: " + data.sampleRate());
        }
    }

    /**
     * Computes coverage and sample-rate advisories for an imported profile.
     *
     * <p>The hemisphere-coverage signal — split between upper (elevation &gt; 0)
     * and lower (elevation &lt; 0) measurements — feeds the SOFA-import dialog's
     * hemisphere-coverage visualization.</p>
     */
    private static List<String> computeCoverageWarnings(double[][] positions,
                                                        double sourceRate,
                                                        double sessionRate) {
        List<String> warnings = new ArrayList<>();
        int m = positions.length;

        if (m < SPARSE_MEASUREMENT_THRESHOLD) {
            warnings.add(String.format(Locale.ROOT,
                    "Sparse measurement set (%d directions); externalization may be reduced.", m));
        }

        boolean upper = false;
        boolean lower = false;
        for (double[] pos : positions) {
            if (pos[1] > 0.5) upper = true;
            else if (pos[1] < -0.5) lower = true;
        }
        if (!upper) {
            warnings.add("No upper-hemisphere measurements (elevation > 0.5°); height cues may be inaccurate.");
        }
        if (!lower) {
            warnings.add("No lower-hemisphere measurements (elevation < -0.5°); below-ear cues may be inaccurate.");
        }

        if (Double.compare(sourceRate, sessionRate) != 0) {
            warnings.add(String.format(Locale.ROOT,
                    "Resampled impulses from %.0f Hz to %.0f Hz to match session rate.",
                    sourceRate, sessionRate));
        }

        return warnings;
    }

    /** Strips the {@code .sofa} extension to use the bare file name as profile name. */
    private static String deriveProfileName(Path sofaFile) {
        String fname = sofaFile.getFileName().toString();
        int dot = fname.lastIndexOf('.');
        return (dot > 0) ? fname.substring(0, dot) : fname;
    }
}
