package com.benesquivelmusic.daw.app.longtests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared helpers for long-running render / export tests (story 209).
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>Deterministic synthetic audio generation (sine, silence, noise
 *       with a fixed seed) so tests are byte-reproducible across runs
 *       and machines.</li>
 *   <li>Golden-file lookup and bit-accuracy comparison via SHA-256.</li>
 *   <li>A rebaseline mode (system property
 *       {@code longtests.rebaseline=true}) that overwrites the on-disk
 *       golden file with the produced artefact instead of comparing.</li>
 * </ul>
 */
public final class LongTestSupport {

    /** System property that switches goldens to rebaseline (write) mode. */
    public static final String REBASELINE_PROP = "longtests.rebaseline";

    /** Repository-relative goldens directory. */
    private static final String GOLDEN_DIR = "golden/";

    private LongTestSupport() { }

    /** Returns true when goldens should be (re)written rather than asserted. */
    public static boolean isRebaselining() {
        return Boolean.parseBoolean(System.getProperty(REBASELINE_PROP, "false"));
    }

    // ─── deterministic audio synthesis ───────────────────────────────

    /**
     * Generates a deterministic sine wave at the given frequency and
     * amplitude. Pure function of inputs — no clocks, no RNG — so the
     * output is byte-identical across runs.
     */
    public static float[] sine(double freqHz, double amplitude,
                               int sampleRate, int numFrames) {
        float[] out = new float[numFrames];
        double twoPiF = 2.0 * Math.PI * freqHz / sampleRate;
        for (int n = 0; n < numFrames; n++) {
            out[n] = (float) (amplitude * Math.sin(twoPiF * n));
        }
        return out;
    }

    /**
     * Generates a deterministic pseudo-random noise buffer using a
     * SplitMix64 generator so the bytes are reproducible and portable
     * (unlike {@link java.util.Random}, which can drift across JDK
     * versions for secondary distributions).
     */
    public static float[] noise(long seed, double amplitude, int numFrames) {
        float[] out = new float[numFrames];
        long s = (seed == 0) ? 0x9E3779B97F4A7C15L : seed;
        for (int n = 0; n < numFrames; n++) {
            // SplitMix64 step → [-1, +1] mapping.
            s += 0x9E3779B97F4A7C15L;
            long z = s;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z =  z ^ (z >>> 31);
            double r = (z >>> 11) * (1.0 / (1L << 53));   // [0, 1)
            out[n] = (float) (amplitude * (2.0 * r - 1.0));
        }
        return out;
    }

    // ─── golden file management ──────────────────────────────────────

    /**
     * Asserts (or rebaselines) byte-equality of an output artefact
     * against its golden under {@code daw-app/src/test/long/resources/golden/}.
     *
     * @throws AssertionError if the files differ and we are not rebaselining
     */
    public static void assertMatchesGolden(Path produced, String goldenName) {
        String resourcePath = GOLDEN_DIR + goldenName;
        URL url = LongTestSupport.class.getClassLoader().getResource(resourcePath);

        if (isRebaselining()) {
            // Rebaseline writes back to the source tree so `git diff`
            // surfaces the new bytes for the developer to review.
            Path target = repoGoldenPath(goldenName);
            try {
                Files.createDirectories(target.getParent());
                Files.copy(produced, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to rebaseline golden " + goldenName, e);
            }
            return;
        }

        if (url == null) {
            throw new AssertionError(
                    "Missing golden " + resourcePath + " — first-time setup? "
                    + "Run with -Dlongtests.rebaseline=true to create it.");
        }
        String producedHash = sha256(produced);
        String goldenHash;
        try (var in = url.openStream()) {
            goldenHash = sha256(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read golden " + resourcePath, e);
        }
        if (!producedHash.equals(goldenHash)) {
            throw new AssertionError(
                    "Golden mismatch for " + goldenName
                    + ": produced sha256=" + producedHash
                    + ", golden sha256=" + goldenHash
                    + " — re-run with -Dlongtests.rebaseline=true if intended.");
        }
    }

    /**
     * Returns a fixed relative path into the daw-app source tree for a
     * golden file, resolved to an absolute path. Used by rebaseline mode
     * to overwrite the on-disk golden so {@code git diff} surfaces the
     * new bytes for the developer to review.
     */
    public static Path repoGoldenPath(String goldenName) {
        return Path.of("src", "test", "long", "resources", "golden", goldenName)
                .toAbsolutePath();
    }

    private static String sha256(Path p) {
        try {
            return sha256(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + p, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
