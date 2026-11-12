package com.benesquivelmusic.daw.app.ui.theme;

import java.util.Arrays;
import java.util.List;

/**
 * The advertised UI-scale steps (story 335, studio ruling R6): seven
 * discrete factors from 90&nbsp;% to 200&nbsp;%. This one list is both the
 * {@code SettingsModel.setUiScale} guard ({@link #ofFactor(double)}) and the
 * Settings row's choice options ({@link #factors()}).
 *
 * <p>The factor multiplies on top of the operating system's display scaling.
 * {@link ThemeManager} applies it as a root font size plus a rem-resolved copy
 * of the application stylesheets' lengths — never as a scale transform.</p>
 */
public enum UiScale {

    /** 90&nbsp;% — the floor: 9&nbsp;px text becomes 8.1&nbsp;px. */
    P90(0.90, "90%"),
    /** 100&nbsp;% — the default; no scale sheet is installed. */
    P100(1.00, "100%"),
    /** 110&nbsp;%. */
    P110(1.10, "110%"),
    /** 125&nbsp;%. */
    P125(1.25, "125%"),
    /** 150&nbsp;%. */
    P150(1.50, "150%"),
    /** 175&nbsp;%. */
    P175(1.75, "175%"),
    /** 200&nbsp;% — the cap: a 1280×720 window keeps 640×360 of 100&nbsp;% room. */
    P200(2.00, "200%");

    /** The default step for a missing or unreadable value. */
    public static final UiScale DEFAULT = P100;

    private static final List<Double> FACTORS = Arrays.stream(values())
            .map(UiScale::factor)
            .toList();

    private final double factor;
    private final String label;
    private final long perMille;

    UiScale(double factor, String label) {
        this.factor = factor;
        this.label = label;
        this.perMille = Math.round(factor * 1000);
    }

    /** @return the multiplier, e.g. {@code 1.5} for 150&nbsp;% */
    public double factor() {
        return factor;
    }

    /** @return the display label, e.g. {@code "150%"} */
    public String label() {
        return label;
    }

    /**
     * Multiplies a 100&nbsp;% length by this step's factor.
     *
     * @param px a length at 100&nbsp;%
     * @return the length at this step
     */
    public double scale(double px) {
        return px * factor;
    }

    /** @return every step's factor, in ascending order (the row's choice options) */
    public static List<Double> factors() {
        return FACTORS;
    }

    /**
     * The step whose factor is exactly {@code factor}.
     *
     * @param factor a candidate factor
     * @return the matching step
     * @throws IllegalArgumentException if {@code factor} is not one of the steps
     */
    public static UiScale ofFactor(double factor) {
        for (UiScale step : values()) {
            if (step.factor == factor) {
                return step;
            }
        }
        throw new IllegalArgumentException(
                "uiScale must be one of " + FACTORS + ": " + factor);
    }

    /**
     * The step nearest to {@code factor}, for snapping a persisted value
     * (studio ruling R6; amendment A29 and ruling 2). NaN gives 100&nbsp;%;
     * anything at or below 0.9 (including negative infinity) gives 90&nbsp;%;
     * anything at or above 2.0 (including positive infinity) gives
     * 200&nbsp;%. In between, the distance is compared in whole per-mille and
     * a tie goes to the step closer to 100&nbsp;%.
     *
     * @param factor a persisted factor, possibly off-step or non-finite
     * @return the nearest step, never {@code null}
     */
    public static UiScale nearest(double factor) {
        if (Double.isNaN(factor)) {
            return P100;
        }
        if (factor <= P90.factor) {
            return P90;
        }
        if (factor >= P200.factor) {
            return P200;
        }
        long pm = Math.round(factor * 1000);
        UiScale best = P100;
        long bestDistance = Long.MAX_VALUE;
        for (UiScale step : values()) {
            long distance = Math.abs(pm - step.perMille);
            boolean closer = distance < bestDistance;
            boolean tieTowardDefault = distance == bestDistance
                    && Math.abs(step.perMille - P100.perMille) < Math.abs(best.perMille - P100.perMille);
            if (closer || tieTowardDefault) {
                best = step;
                bestDistance = distance;
            }
        }
        return best;
    }
}
