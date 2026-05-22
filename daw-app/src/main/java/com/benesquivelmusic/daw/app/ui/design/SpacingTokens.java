package com.benesquivelmusic.daw.app.ui.design;

/**
 * Numeric design tokens for the 4&nbsp;px grid that drives every spacing,
 * row-height and corner-radius value in the UI.
 *
 * <p>JavaFX CSS supports looked-up <em>colour</em> tokens but not looked-up
 * <em>numeric</em> tokens — there is no {@code calc(-spacing-sm * 2)}. To
 * keep CSS and FXML in lock-step, this class declares the canonical numeric
 * values documented in the spacing/row/radius scales at the top of
 * {@code styles.css}. FXML-driven code that needs to build
 * {@link javafx.geometry.Insets} (or any other numeric layout value)
 * should reference these constants rather than literal numbers.
 *
 * <p>The 4&nbsp;px grid contract is enforced by
 * {@code com.benesquivelmusic.daw.app.ui.TokenValidationTest} (CSS side —
 * validates that every {@code -fx-padding}, {@code -fx-spacing},
 * {@code -fx-background-radius} and {@code -fx-border-radius} value is
 * a multiple of 4, with per-property inline exceptions) and
 * {@code com.benesquivelmusic.daw.app.ui.MainViewFxmlSpacingTest}
 * (FXML side — validates that every {@code <Insets>} attribute and every
 * container {@code spacing} is a multiple of 4).
 *
 * <p>See UI Design Book §1.7 (problem), §2.3 and §3.3 (solution),
 * §7.4 (mixed-radius veto).
 */
public final class SpacingTokens {

    /** Tightest gap used for icon-to-text spacing inside a control. (-spacing-xxs) */
    public static final double SPACING_XXS = 2;
    /** One grid cell. Base unit for inter-control gaps. (-spacing-xs) */
    public static final double SPACING_XS = 4;
    /** Two grid cells. Standard padding inside compact rows. (-spacing-sm) */
    public static final double SPACING_SM = 8;
    /** Three grid cells. Standard horizontal padding for toolbars. (-spacing-md) */
    public static final double SPACING_MD = 12;
    /** Four grid cells. Section padding. (-spacing-lg) */
    public static final double SPACING_LG = 16;
    /** Six grid cells. Major group separation. (-spacing-xl) */
    public static final double SPACING_XL = 24;
    /** Eight grid cells. Dialog-level padding. (-spacing-xxl) */
    public static final double SPACING_XXL = 32;
    /**
     * Twelve grid cells. Performance Stage inter-band gaps (story 280 —
     * UI Design Book §4 Concept E). The Performance Stage's oversized
     * controls need wider separation than {@link #SPACING_XXL}; this is
     * the extension of §3.3's scale rather than a hardcoded inline value.
     * (-spacing-xxxl)
     */
    public static final double SPACING_XXXL = 48;

    /** Compact row height — dense table rows. (-row-compact) */
    public static final double ROW_COMPACT = 24;
    /** Default row height — toolbar buttons, list items. (-row-default) */
    public static final double ROW_DEFAULT = 28;
    /** Touch-friendly row height — primary actions. (-row-touch) */
    public static final double ROW_TOUCH = 32;
    /** Transport row height — playback controls. (-row-transport) */
    public static final double ROW_TRANSPORT = 36;

    /** Square corner. (-radius-0) */
    public static final double RADIUS_0 = 0;
    /** Buttons, inputs, badges. (-radius-1) */
    public static final double RADIUS_1 = 4;
    /** Cards, popovers. (-radius-2) */
    public static final double RADIUS_2 = 6;
    /** Large surfaces. (-radius-3) */
    public static final double RADIUS_3 = 8;

    private SpacingTokens() {
        // utility class
    }
}
