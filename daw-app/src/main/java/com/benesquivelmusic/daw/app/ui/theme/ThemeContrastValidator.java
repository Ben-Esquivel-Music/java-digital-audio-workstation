package com.benesquivelmusic.daw.app.ui.theme;

import java.util.Locale;
import java.util.Objects;

/**
 * Computes WCAG 2.1 contrast ratios between sRGB color pairs and classifies
 * the result against the WCAG conformance tiers.
 *
 * <p>This validator is the foundation for the accessibility audit performed
 * on every bundled and user theme. It is a pure-logic class — no JavaFX
 * dependencies — so it can be unit-tested without a screen.</p>
 *
 * <p>The math follows the
 * <a href="https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio">WCAG 2.1
 * contrast-ratio definition</a>:
 * <ol>
 *   <li>Convert each sRGB component to linear light by reversing the
 *       gamma encoding (the &le;0.03928 piecewise function).</li>
 *   <li>Compute relative luminance
 *       {@code L = 0.2126·R + 0.7152·G + 0.0722·B}.</li>
 *   <li>Contrast ratio = {@code (L_lighter + 0.05) / (L_darker + 0.05)}.</li>
 * </ol>
 *
 * <p>WCAG conformance tiers (normal-size body text):
 * <ul>
 *   <li>{@link Tier#FAIL}    — below 4.5:1</li>
 *   <li>{@link Tier#AA}      — 4.5:1 or higher</li>
 *   <li>{@link Tier#AAA}     — 7:1 or higher</li>
 * </ul>
 */
public final class ThemeContrastValidator {

    /** WCAG AA threshold for normal-size text. */
    public static final double AA_NORMAL = 4.5;

    /** WCAG AAA threshold for normal-size text. */
    public static final double AAA_NORMAL = 7.0;

    private ThemeContrastValidator() {
        // utility class
    }

    /**
     * WCAG conformance tier reached by a foreground/background pair.
     */
    public enum Tier {
        /** Contrast ratio is below 4.5:1 — fails AA for normal text. */
        FAIL,
        /** Contrast ratio is in {@code [4.5, 7.0)} — passes AA. */
        AA,
        /** Contrast ratio is &ge; 7.0 — passes AAA. */
        AAA
    }

    /**
     * Parses a 6- or 3-digit hex color string (with or without leading
     * {@code #}) into an int-array {@code [r, g, b]} in {@code 0..255}.
     *
     * @throws IllegalArgumentException if the string is not a valid hex color
     */
    public static int[] parseHexColor(String hex) {
        Objects.requireNonNull(hex, "hex must not be null");
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 3) {
            // expand "abc" → "aabbcc"
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                char c = s.charAt(i);
                sb.append(c).append(c);
            }
            s = sb.toString();
        }
        if (s.length() != 6) {
            throw new IllegalArgumentException("Invalid hex color: " + hex);
        }
        try {
            int r = Integer.parseInt(s.substring(0, 2), 16);
            int g = Integer.parseInt(s.substring(2, 4), 16);
            int b = Integer.parseInt(s.substring(4, 6), 16);
            return new int[] { r, g, b };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex color: " + hex, e);
        }
    }

    /**
     * Returns the relative luminance (0..1) of an sRGB color.
     *
     * @param r red   component (0..255)
     * @param g green component (0..255)
     * @param b blue  component (0..255)
     */
    public static double relativeLuminance(int r, int g, int b) {
        double rl = linearize(r / 255.0);
        double gl = linearize(g / 255.0);
        double bl = linearize(b / 255.0);
        return 0.2126 * rl + 0.7152 * gl + 0.0722 * bl;
    }

    /** Convenience overload taking a hex color. */
    public static double relativeLuminance(String hex) {
        int[] rgb = parseHexColor(hex);
        return relativeLuminance(rgb[0], rgb[1], rgb[2]);
    }

    private static double linearize(double channel) {
        return channel <= 0.03928
                ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /**
     * Returns the WCAG 2.1 contrast ratio between two hex colors. The
     * result is always &ge; 1.0 and order-independent.
     */
    public static double contrastRatio(String foregroundHex, String backgroundHex) {
        double l1 = relativeLuminance(foregroundHex);
        double l2 = relativeLuminance(backgroundHex);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /**
     * Classifies a numeric contrast ratio against the normal-text
     * AA / AAA thresholds.
     */
    public static Tier classify(double ratio) {
        if (ratio >= AAA_NORMAL) {
            return Tier.AAA;
        }
        if (ratio >= AA_NORMAL) {
            return Tier.AA;
        }
        return Tier.FAIL;
    }

    /**
     * Returns a short human-readable description of {@code ratio}, e.g.
     * {@code "8.21:1 (AAA)"}, suitable for showing inline in the
     * theme-edit UI.
     */
    public static String describe(double ratio) {
        return String.format(Locale.ROOT, "%.2f:1 (%s)", ratio, classify(ratio));
    }
}
