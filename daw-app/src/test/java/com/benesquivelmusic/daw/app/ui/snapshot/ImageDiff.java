package com.benesquivelmusic.daw.app.ui.snapshot;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Pixel-level image comparison with tolerance, used by
 * {@link FxSnapshotTest} for visual-regression checks.
 *
 * <p>Two images are considered equal when:
 * <ul>
 *   <li>they have identical width and height, and</li>
 *   <li>at every position {@code (x,y)}, both directions have an
 *       in-window match: {@code actual[x,y]} matches some pixel in
 *       {@code expected[x±r, y±r]} <em>and</em> {@code expected[x,y]}
 *       matches some pixel in {@code actual[x±r, y±r]} within
 *       {@link #maxChannelDelta()} (default 4) on every R/G/B/A channel,
 *       and</li>
 *   <li>the fraction of positions where either direction fails to match
 *       does not exceed {@link #maxDifferingFraction()} (default 0.005 —
 *       i.e. 0.5%).</li>
 * </ul>
 *
 * <p>The {@code shiftRadius} (default 1) absorbs sub-pixel layout drift —
 * principally font-baseline differences across rendering environments —
 * by checking each pixel against a {@code (2r+1)×(2r+1)} neighbourhood
 * in the other image. The check is bidirectional so disappearing content
 * (e.g. a removed icon, where {@code actual} pixels happen to find bg
 * matches but {@code expected} pixels do not) still surfaces. A shift of
 * more than {@code r} pixels remains a real diff. Set
 * {@code shiftRadius=0} for strict pixel-pair comparison.</p>
 *
 * <p>Producing a visual diff image is also supported via
 * {@link #renderDiff(BufferedImage, BufferedImage)}; differing pixels are
 * highlighted in opaque red on a faint copy of the expected image.</p>
 */
public final class ImageDiff {

    /** Default per-channel tolerance (inclusive). */
    public static final int DEFAULT_MAX_CHANNEL_DELTA = 4;

    /** Default maximum fraction of differing pixels (0.005 = 0.5%). */
    public static final double DEFAULT_MAX_DIFFERING_FRACTION = 0.005;

    /**
     * Default neighbourhood radius for shift-tolerant comparison.
     * A value of {@code 3} absorbs the multi-pixel font-metric drift
     * seen across JavaFX rendering environments (Linux/Windows/macOS
     * pick different default fonts and line metrics) while still
     * surfacing layout regressions larger than ~3 pixels in any
     * direction. Set {@code shiftRadius=0} for strict pixel-pair
     * comparison.
     */
    public static final int DEFAULT_SHIFT_RADIUS = 3;

    private final int maxChannelDelta;
    private final double maxDifferingFraction;
    private final int shiftRadius;

    public ImageDiff() {
        this(DEFAULT_MAX_CHANNEL_DELTA, DEFAULT_MAX_DIFFERING_FRACTION, DEFAULT_SHIFT_RADIUS);
    }

    public ImageDiff(int maxChannelDelta, double maxDifferingFraction) {
        this(maxChannelDelta, maxDifferingFraction, DEFAULT_SHIFT_RADIUS);
    }

    public ImageDiff(int maxChannelDelta, double maxDifferingFraction, int shiftRadius) {
        if (maxChannelDelta < 0) {
            throw new IllegalArgumentException(
                    "maxChannelDelta must be non-negative: " + maxChannelDelta);
        }
        if (maxDifferingFraction < 0.0 || maxDifferingFraction > 1.0) {
            throw new IllegalArgumentException(
                    "maxDifferingFraction must be in [0,1]: " + maxDifferingFraction);
        }
        if (shiftRadius < 0) {
            throw new IllegalArgumentException(
                    "shiftRadius must be non-negative: " + shiftRadius);
        }
        this.maxChannelDelta = maxChannelDelta;
        this.maxDifferingFraction = maxDifferingFraction;
        this.shiftRadius = shiftRadius;
    }

    public int maxChannelDelta()        { return maxChannelDelta; }
    public double maxDifferingFraction() { return maxDifferingFraction; }
    public int shiftRadius()             { return shiftRadius; }

    /**
     * Compares two images and returns a {@link Result} carrying the
     * pixel statistics and a pass/fail decision.
     */
    public Result compare(BufferedImage expected, BufferedImage actual) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        if (expected.getWidth() != actual.getWidth()
                || expected.getHeight() != actual.getHeight()) {
            return new Result(
                    /*sizeMismatch*/ true,
                    expected.getWidth(), expected.getHeight(),
                    actual.getWidth(), actual.getHeight(),
                    /*differingPixels*/ -1,
                    /*totalPixels*/ -1,
                    /*maxObservedDelta*/ -1,
                    /*passed*/ false);
        }

        int w = expected.getWidth();
        int h = expected.getHeight();
        int total = w * h;
        int differing = 0;
        int maxObserved = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int forward = bestWindowedDelta(expected, actual, x, y);
                int reverse = bestWindowedDelta(actual, expected, x, y);
                int worst = Math.max(forward, reverse);
                if (worst > maxObserved) {
                    maxObserved = worst;
                }
                if (worst > maxChannelDelta) {
                    differing++;
                }
            }
        }
        double fraction = total == 0 ? 0.0 : (double) differing / (double) total;
        boolean passed = fraction <= maxDifferingFraction;
        return new Result(false, w, h, w, h, differing, total, maxObserved, passed);
    }

    /**
     * Returns the smallest channel-delta between {@code probe[x,y]} and
     * any pixel in {@code searchIn} within {@code shiftRadius} of the same
     * coordinate. Early-exits once a within-tolerance neighbour is found,
     * since any tighter match would not change the differing/non-differing
     * classification.
     */
    private int bestWindowedDelta(
            BufferedImage searchIn, BufferedImage probe, int x, int y) {
        int w = searchIn.getWidth();
        int h = searchIn.getHeight();
        int p = probe.getRGB(x, y);
        int yMin = Math.max(0, y - shiftRadius);
        int yMax = Math.min(h - 1, y + shiftRadius);
        int xMin = Math.max(0, x - shiftRadius);
        int xMax = Math.min(w - 1, x + shiftRadius);
        int best = Integer.MAX_VALUE;
        for (int yy = yMin; yy <= yMax; yy++) {
            for (int xx = xMin; xx <= xMax; xx++) {
                int s = searchIn.getRGB(xx, yy);
                if (s == p) {
                    return 0;
                }
                int dA = Math.abs(((s >>> 24) & 0xff) - ((p >>> 24) & 0xff));
                int dR = Math.abs(((s >>> 16) & 0xff) - ((p >>> 16) & 0xff));
                int dG = Math.abs(((s >>>  8) & 0xff) - ((p >>>  8) & 0xff));
                int dB = Math.abs(((s       ) & 0xff) - ((p       ) & 0xff));
                int channelMax = Math.max(Math.max(dA, dR), Math.max(dG, dB));
                if (channelMax < best) {
                    best = channelMax;
                    if (best <= maxChannelDelta) {
                        return best;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Renders a diff image where pixels that differ beyond tolerance are
     * highlighted in opaque red on a desaturated copy of {@code expected}.
     *
     * <p>If sizes differ, returns an image sized to the union and marks
     * out-of-bounds areas red.</p>
     */
    public BufferedImage renderDiff(BufferedImage expected, BufferedImage actual) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        int w = Math.max(expected.getWidth(), actual.getWidth());
        int h = Math.max(expected.getHeight(), actual.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean sameSize = expected.getWidth() == actual.getWidth()
                && expected.getHeight() == actual.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inE = x < expected.getWidth() && y < expected.getHeight();
                boolean inA = x < actual.getWidth() && y < actual.getHeight();
                if (!inE || !inA) {
                    out.setRGB(x, y, 0xffff0000);
                    continue;
                }
                // Use the same bidirectional, shift-tolerant comparison as
                // compare() so the visualisation reflects actual classifier
                // decisions (a pixel is red iff either direction's window
                // lookup fails — same predicate that drives the count).
                int delta;
                if (sameSize) {
                    int forward = bestWindowedDelta(expected, actual, x, y);
                    int reverse = bestWindowedDelta(actual, expected, x, y);
                    delta = Math.max(forward, reverse);
                } else {
                    delta = rawChannelDelta(expected.getRGB(x, y), actual.getRGB(x, y));
                }
                if (delta > maxChannelDelta) {
                    out.setRGB(x, y, 0xffff0000);
                } else {
                    // Faint grayscale of expected so red diffs pop out.
                    int e = expected.getRGB(x, y);
                    int r = (e >>> 16) & 0xff;
                    int g = (e >>>  8) & 0xff;
                    int b = (e       ) & 0xff;
                    int gray = (r * 30 + g * 59 + b * 11) / 100;
                    int faded = 0xff000000 | (gray << 16) | (gray << 8) | gray;
                    out.setRGB(x, y, faded);
                }
            }
        }
        return out;
    }

    private static int rawChannelDelta(int e, int a) {
        int dA = Math.abs(((e >>> 24) & 0xff) - ((a >>> 24) & 0xff));
        int dR = Math.abs(((e >>> 16) & 0xff) - ((a >>> 16) & 0xff));
        int dG = Math.abs(((e >>>  8) & 0xff) - ((a >>>  8) & 0xff));
        int dB = Math.abs(((e       ) & 0xff) - ((a       ) & 0xff));
        return Math.max(Math.max(dA, dR), Math.max(dG, dB));
    }

    /**
     * Outcome of a single image comparison.
     *
     * @param sizeMismatch     true if dimensions differ
     * @param expectedWidth    expected width in pixels
     * @param expectedHeight   expected height in pixels
     * @param actualWidth      actual width in pixels
     * @param actualHeight     actual height in pixels
     * @param differingPixels  count of pixels exceeding the per-channel tolerance
     * @param totalPixels      total pixels compared
     * @param maxObservedDelta maximum per-channel delta observed (any pixel)
     * @param passed           true iff differingPixels / totalPixels ≤ maxDifferingFraction
     */
    public record Result(
            boolean sizeMismatch,
            int expectedWidth, int expectedHeight,
            int actualWidth, int actualHeight,
            int differingPixels, int totalPixels,
            int maxObservedDelta,
            boolean passed) {

        public double differingFraction() {
            if (totalPixels <= 0) return 0.0;
            return (double) differingPixels / (double) totalPixels;
        }

        public String describe() {
            if (sizeMismatch) {
                return String.format(
                        "size mismatch — expected %dx%d, actual %dx%d",
                        expectedWidth, expectedHeight, actualWidth, actualHeight);
            }
            return String.format(
                    "%d / %d pixels differ (%.4f%%), max channel Δ %d",
                    differingPixels, totalPixels,
                    differingFraction() * 100.0, maxObservedDelta);
        }
    }
}
