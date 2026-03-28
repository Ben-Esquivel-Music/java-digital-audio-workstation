package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class FoldDownCoefficientsTest {

    // ---- Defaults -----------------------------------------------------------

    @Test
    void shouldProvideItuStandardCoefficients() {
        FoldDownCoefficients itu = FoldDownCoefficients.ITU_R_BS_775;
        double minus3dB = Math.sqrt(0.5);

        assertThat(itu.centerLevel()).isCloseTo(minus3dB, offset(0.001));
        assertThat(itu.surroundLevel()).isCloseTo(minus3dB, offset(0.001));
        assertThat(itu.lfeLevel()).isCloseTo(0.0, offset(0.001));
        assertThat(itu.heightLevel()).isCloseTo(minus3dB, offset(0.001));
    }

    // ---- Custom values ------------------------------------------------------

    @Test
    void shouldAcceptValidCustomCoefficients() {
        FoldDownCoefficients custom = new FoldDownCoefficients(0.5, 0.6, 0.1, 0.8);

        assertThat(custom.centerLevel()).isEqualTo(0.5);
        assertThat(custom.surroundLevel()).isEqualTo(0.6);
        assertThat(custom.lfeLevel()).isEqualTo(0.1);
        assertThat(custom.heightLevel()).isEqualTo(0.8);
    }

    @Test
    void shouldAcceptBoundaryValues() {
        FoldDownCoefficients zeros = new FoldDownCoefficients(0.0, 0.0, 0.0, 0.0);
        assertThat(zeros.centerLevel()).isEqualTo(0.0);

        FoldDownCoefficients ones = new FoldDownCoefficients(1.0, 1.0, 1.0, 1.0);
        assertThat(ones.centerLevel()).isEqualTo(1.0);
    }

    // ---- Validation ---------------------------------------------------------

    @Test
    void shouldRejectNegativeCenterLevel() {
        assertThatThrownBy(() -> new FoldDownCoefficients(-0.1, 0.5, 0.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("centerLevel");
    }

    @Test
    void shouldRejectCenterLevelAboveOne() {
        assertThatThrownBy(() -> new FoldDownCoefficients(1.1, 0.5, 0.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("centerLevel");
    }

    @Test
    void shouldRejectNegativeSurroundLevel() {
        assertThatThrownBy(() -> new FoldDownCoefficients(0.5, -0.1, 0.0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surroundLevel");
    }

    @Test
    void shouldRejectNegativeLfeLevel() {
        assertThatThrownBy(() -> new FoldDownCoefficients(0.5, 0.5, -0.1, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lfeLevel");
    }

    @Test
    void shouldRejectNegativeHeightLevel() {
        assertThatThrownBy(() -> new FoldDownCoefficients(0.5, 0.5, 0.0, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heightLevel");
    }

    // ---- Equality (record auto-generated) -----------------------------------

    @Test
    void shouldBeEqualForSameValues() {
        FoldDownCoefficients a = new FoldDownCoefficients(0.5, 0.6, 0.1, 0.8);
        FoldDownCoefficients b = new FoldDownCoefficients(0.5, 0.6, 0.1, 0.8);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void shouldNotBeEqualForDifferentValues() {
        FoldDownCoefficients a = new FoldDownCoefficients(0.5, 0.6, 0.1, 0.8);
        FoldDownCoefficients b = new FoldDownCoefficients(0.5, 0.6, 0.2, 0.8);

        assertThat(a).isNotEqualTo(b);
    }
}
