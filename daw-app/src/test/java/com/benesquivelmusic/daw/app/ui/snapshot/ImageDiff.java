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
 *   <li>for every pixel, each of the R/G/B/A channels differs by at most
 *       {@link #maxChannelDelta()} (default 4), and</li>
 *   <li>the fraction of pixels whose channels exceed the per-channel
 *       tolerance does not exceed {@link #maxDifferingFraction()}
 *       (default 0.005 — i.e. 0.5%).</li>
 * </ul>
 *
 * <p>The defaults absorb subpixel-rendering noise across machines while
 * still catching meaningful visual regressions (a 1px shift on a
 * 1000×600 view affects far more than 0.5% of pixels).</p>
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

    private final int maxChannelDelta;
    private final double maxDifferingFraction;

    public ImageDiff() {
        this(DEFAULT_MAX_CHANNEL_DELTA, DEFAULT_MAX_DIFFERING_FRACTION);
    }

    public ImageDiff(int maxChannelDelta, double maxDifferingFraction) {
        if (maxChannelDelta < 0) {
            throw new IllegalArgumentException(
                    "maxChannelDelta must be non-negative: " + maxChannelDelta);
        }
        if (maxDifferingFraction < 0.0 || maxDifferingFraction > 1.0) {
            throw new IllegalArgumentException(
                    "maxDifferingFraction must be in [0,1]: " + maxDifferingFraction);
        }
        this.maxChannelDelta = maxChannelDelta;
        this.maxDifferingFraction = maxDifferingFraction;
    }

    public int maxChannelDelta()        { return maxChannelDelta; }
    public double maxDifferingFraction() { return maxDifferingFraction; }

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
                int e = expected.getRGB(x, y);
                int a = actual.getRGB(x, y);
                if (e == a) {
                    continue;
                }
                int dA = Math.abs(((e >>> 24) & 0xff) - ((a >>> 24) & 0xff));
                int dR = Math.abs(((e >>> 16) & 0xff) - ((a >>> 16) & 0xff));
                int dG = Math.abs(((e >>>  8) & 0xff) - ((a >>>  8) & 0xff));
                int dB = Math.abs(((e       ) & 0xff) - ((a       ) & 0xff));
                int channelMax = Math.max(Math.max(dA, dR), Math.max(dG, dB));
                if (channelMax > maxObserved) {
                    maxObserved = channelMax;
                }
                if (channelMax > maxChannelDelta) {
                    differing++;
                }
            }
        }
        double fraction = total == 0 ? 0.0 : (double) differing / (double) total;
        boolean passed = fraction <= maxDifferingFraction;
        return new Result(false, w, h, w, h, differing, total, maxObserved, passed);
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
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inE = x < expected.getWidth() && y < expected.getHeight();
                boolean inA = x < actual.getWidth() && y < actual.getHeight();
                if (!inE || !inA) {
                    out.setRGB(x, y, 0xffff0000);
                    continue;
                }
                int e = expected.getRGB(x, y);
                int a = actual.getRGB(x, y);
                int dA = Math.abs(((e >>> 24) & 0xff) - ((a >>> 24) & 0xff));
                int dR = Math.abs(((e >>> 16) & 0xff) - ((a >>> 16) & 0xff));
                int dG = Math.abs(((e >>>  8) & 0xff) - ((a >>>  8) & 0xff));
                int dB = Math.abs(((e       ) & 0xff) - ((a       ) & 0xff));
                int channelMax = Math.max(Math.max(dA, dR), Math.max(dG, dB));
                if (channelMax > maxChannelDelta) {
                    out.setRGB(x, y, 0xffff0000);
                } else {
                    // Faint grayscale of expected so red diffs pop out.
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
