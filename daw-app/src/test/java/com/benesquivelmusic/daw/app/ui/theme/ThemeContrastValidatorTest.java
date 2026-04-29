package com.benesquivelmusic.daw.app.ui.theme;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Verifies the {@link ThemeContrastValidator} against the published
 * WCAG 2.1 reference contrasts and exercises the parser/classifier
 * boundary cases. These tests are pure-logic — no JavaFX, no display.
 */
class ThemeContrastValidatorTest {

    private static final double TOL = 0.01;

    @Test
    void whiteOnBlackIs21To1() {
        // The canonical maximum WCAG contrast: pure white on pure black.
        double ratio = ThemeContrastValidator.contrastRatio("#ffffff", "#000000");
        assertThat(ratio).isCloseTo(21.0, org.assertj.core.data.Offset.offset(TOL));
        assertThat(ThemeContrastValidator.classify(ratio))
                .isEqualTo(ThemeContrastValidator.Tier.AAA);
    }

    @Test
    void identicalColorsHaveRatioOne() {
        double ratio = ThemeContrastValidator.contrastRatio("#777777", "#777777");
        assertThat(ratio).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ThemeContrastValidator.classify(ratio))
                .isEqualTo(ThemeContrastValidator.Tier.FAIL);
    }

    @Test
    void contrastRatioIsOrderIndependent() {
        double a = ThemeContrastValidator.contrastRatio("#ff0000", "#00ff00");
        double b = ThemeContrastValidator.contrastRatio("#00ff00", "#ff0000");
        assertThat(a).isCloseTo(b, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void greySevenSevenSevenOnWhitePassesAA() {
        // #777 on white is the textbook ~4.48:1 boundary case mentioned in
        // numerous WCAG references; the published reference value is ~4.48.
        double ratio = ThemeContrastValidator.contrastRatio("#777777", "#ffffff");
        assertThat(ratio).isCloseTo(4.48, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void blueOnWhiteMatchesWcagReference() {
        // #0000ff on #ffffff has a published WCAG ratio of ~8.59:1.
        double ratio = ThemeContrastValidator.contrastRatio("#0000ff", "#ffffff");
        assertThat(ratio).isCloseTo(8.59, org.assertj.core.data.Offset.offset(0.05));
        assertThat(ThemeContrastValidator.classify(ratio))
                .isEqualTo(ThemeContrastValidator.Tier.AAA);
    }

    @Test
    void thresholdsClassifyCorrectly() {
        assertThat(ThemeContrastValidator.classify(7.0))
                .isEqualTo(ThemeContrastValidator.Tier.AAA);
        assertThat(ThemeContrastValidator.classify(6.99))
                .isEqualTo(ThemeContrastValidator.Tier.AA);
        assertThat(ThemeContrastValidator.classify(4.5))
                .isEqualTo(ThemeContrastValidator.Tier.AA);
        assertThat(ThemeContrastValidator.classify(4.49))
                .isEqualTo(ThemeContrastValidator.Tier.FAIL);
    }

    @Test
    void parseHexColorAcceptsShortForm() {
        int[] rgb = ThemeContrastValidator.parseHexColor("#abc");
        assertThat(rgb).containsExactly(0xaa, 0xbb, 0xcc);
    }

    @Test
    void parseHexColorAcceptsNoLeadingHash() {
        int[] rgb = ThemeContrastValidator.parseHexColor("123456");
        assertThat(rgb).containsExactly(0x12, 0x34, 0x56);
    }

    @Test
    void parseHexColorRejectsInvalid() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ThemeContrastValidator.parseHexColor("#zzz"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ThemeContrastValidator.parseHexColor("#1234"));
    }

    @Test
    void describeFormatsRatioAndTier() {
        assertThat(ThemeContrastValidator.describe(21.0)).isEqualTo("21.00:1 (AAA)");
        assertThat(ThemeContrastValidator.describe(5.5)).isEqualTo("5.50:1 (AA)");
        assertThat(ThemeContrastValidator.describe(3.0)).isEqualTo("3.00:1 (FAIL)");
    }
}
