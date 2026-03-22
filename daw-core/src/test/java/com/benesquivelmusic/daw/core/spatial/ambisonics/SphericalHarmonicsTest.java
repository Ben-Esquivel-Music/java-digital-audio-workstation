package com.benesquivelmusic.daw.core.spatial.ambisonics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SphericalHarmonicsTest {

    private static final double TOLERANCE = 1e-6;

    // ---- FOA Encoding ----

    @Test
    void shouldEncodeFrontDirection() {
        // Front: azimuth=0, elevation=0
        double[] coeffs = SphericalHarmonics.encode(0, 0, 1);

        assertThat(coeffs).hasSize(4);
        assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE)); // W
        assertThat(coeffs[1]).isCloseTo(0.0, within(TOLERANCE)); // Y (sin(0)*cos(0) = 0)
        assertThat(coeffs[2]).isCloseTo(0.0, within(TOLERANCE)); // Z (sin(0) = 0)
        assertThat(coeffs[3]).isCloseTo(1.0, within(TOLERANCE)); // X (cos(0)*cos(0) = 1)
    }

    @Test
    void shouldEncodeLeftDirection() {
        // Left: azimuth=π/2, elevation=0
        double[] coeffs = SphericalHarmonics.encode(Math.PI / 2.0, 0, 1);

        assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE)); // W
        assertThat(coeffs[1]).isCloseTo(1.0, within(TOLERANCE)); // Y (sin(π/2)*cos(0) = 1)
        assertThat(coeffs[2]).isCloseTo(0.0, within(TOLERANCE)); // Z
        assertThat(coeffs[3]).isCloseTo(0.0, within(TOLERANCE)); // X (cos(π/2)*cos(0) ≈ 0)
    }

    @Test
    void shouldEncodeAboveDirection() {
        // Above: azimuth=0, elevation=π/2
        double[] coeffs = SphericalHarmonics.encode(0, Math.PI / 2.0, 1);

        assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE)); // W
        assertThat(coeffs[1]).isCloseTo(0.0, within(TOLERANCE)); // Y
        assertThat(coeffs[2]).isCloseTo(1.0, within(TOLERANCE)); // Z (sin(π/2) = 1)
        assertThat(coeffs[3]).isCloseTo(0.0, within(TOLERANCE)); // X (cos(0)*cos(π/2) ≈ 0)
    }

    @Test
    void shouldEncodeRearDirection() {
        // Rear: azimuth=π, elevation=0
        double[] coeffs = SphericalHarmonics.encode(Math.PI, 0, 1);

        assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE));  // W
        assertThat(coeffs[1]).isCloseTo(0.0, within(TOLERANCE));  // Y (sin(π) ≈ 0)
        assertThat(coeffs[2]).isCloseTo(0.0, within(TOLERANCE));  // Z
        assertThat(coeffs[3]).isCloseTo(-1.0, within(TOLERANCE)); // X (cos(π) = -1)
    }

    // ---- Omnidirectional W Channel ----

    @Test
    void wChannelShouldBeConstantForAllDirections() {
        for (double az = 0; az < 2 * Math.PI; az += Math.PI / 4) {
            for (double el = -Math.PI / 2; el <= Math.PI / 2; el += Math.PI / 4) {
                double[] coeffs = SphericalHarmonics.encode(az, el, 1);
                assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE));
            }
        }
    }

    // ---- Orthogonality of FOA directions ----

    @Test
    void foaDirectionalChannelsShouldBeOrthogonal() {
        // Encode two orthogonal directions: front and left
        double[] front = SphericalHarmonics.encode(0, 0, 1);
        double[] left = SphericalHarmonics.encode(Math.PI / 2.0, 0, 1);

        // Dot product of directional channels (1-3) should be near zero
        double dot = front[1] * left[1] + front[2] * left[2] + front[3] * left[3];
        assertThat(dot).isCloseTo(0.0, within(TOLERANCE));
    }

    // ---- HOA Encoding ----

    @Test
    void shouldEncodeSecondOrder() {
        double[] coeffs = SphericalHarmonics.encode(0, 0, 2);
        assertThat(coeffs).hasSize(9);
        // W channel still 1.0
        assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldEncodeThirdOrder() {
        double[] coeffs = SphericalHarmonics.encode(0, 0, 3);
        assertThat(coeffs).hasSize(16);
        assertThat(coeffs[0]).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void secondOrderShouldBeZeroForFrontAtCertainChannels() {
        // Front direction (azimuth=0, elevation=0)
        double[] coeffs = SphericalHarmonics.encode(0, 0, 2);

        // ACN4 = sin(2*0)*cos²(0) = 0 (Y_2^{-2})
        assertThat(coeffs[4]).isCloseTo(0.0, within(TOLERANCE));
        // ACN5 = sin(0)*sin(0) = 0 (Y_2^{-1})
        assertThat(coeffs[5]).isCloseTo(0.0, within(TOLERANCE));
        // ACN6 = 0.5*(3*0 - 1) = -0.5 (Y_2^0)
        assertThat(coeffs[6]).isCloseTo(-0.5, within(TOLERANCE));
    }

    // ---- Unit Energy on Sphere ----

    @Test
    void foaDirectionalChannelsShouldHaveUnitEnergyOnSphere() {
        // For a direction at (azimuth, elevation), the sum of squares of Y, Z, X should be cos²(el)
        double az = Math.PI / 6.0;
        double el = 0.0; // horizontal
        double[] coeffs = SphericalHarmonics.encode(az, el, 1);

        double dirEnergy = coeffs[1] * coeffs[1] + coeffs[2] * coeffs[2] + coeffs[3] * coeffs[3];
        assertThat(dirEnergy).isCloseTo(1.0, within(TOLERANCE));
    }

    // ---- ACN <-> Degree/Order Conversion ----

    @Test
    void shouldConvertAcnToDegreeOrder() {
        assertThat(SphericalHarmonics.acnToDegreeOrder(0)).containsExactly(0, 0);
        assertThat(SphericalHarmonics.acnToDegreeOrder(1)).containsExactly(1, -1);
        assertThat(SphericalHarmonics.acnToDegreeOrder(2)).containsExactly(1, 0);
        assertThat(SphericalHarmonics.acnToDegreeOrder(3)).containsExactly(1, 1);
        assertThat(SphericalHarmonics.acnToDegreeOrder(4)).containsExactly(2, -2);
        assertThat(SphericalHarmonics.acnToDegreeOrder(8)).containsExactly(2, 2);
        assertThat(SphericalHarmonics.acnToDegreeOrder(9)).containsExactly(3, -3);
        assertThat(SphericalHarmonics.acnToDegreeOrder(15)).containsExactly(3, 3);
    }

    @Test
    void shouldConvertDegreeOrderToAcn() {
        assertThat(SphericalHarmonics.degreeOrderToAcn(0, 0)).isEqualTo(0);
        assertThat(SphericalHarmonics.degreeOrderToAcn(1, -1)).isEqualTo(1);
        assertThat(SphericalHarmonics.degreeOrderToAcn(1, 0)).isEqualTo(2);
        assertThat(SphericalHarmonics.degreeOrderToAcn(1, 1)).isEqualTo(3);
        assertThat(SphericalHarmonics.degreeOrderToAcn(3, 3)).isEqualTo(15);
    }

    @Test
    void acnConversionShouldRoundTrip() {
        for (int acn = 0; acn < 16; acn++) {
            int[] dl = SphericalHarmonics.acnToDegreeOrder(acn);
            assertThat(SphericalHarmonics.degreeOrderToAcn(dl[0], dl[1])).isEqualTo(acn);
        }
    }

    // ---- SN3D Normalization ----

    @Test
    void sn3dForZerothOrderShouldBeOne() {
        assertThat(SphericalHarmonics.sn3dNormalization(0, 0)).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void sn3dForFirstOrderShouldBeOne() {
        assertThat(SphericalHarmonics.sn3dNormalization(1, 0)).isCloseTo(1.0, within(TOLERANCE));
        assertThat(SphericalHarmonics.sn3dNormalization(1, 1)).isCloseTo(1.0, within(TOLERANCE));
        assertThat(SphericalHarmonics.sn3dNormalization(1, -1)).isCloseTo(1.0, within(TOLERANCE));
    }

    // ---- Validation ----

    @Test
    void shouldRejectInvalidOrder() {
        assertThatThrownBy(() -> SphericalHarmonics.encode(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SphericalHarmonics.encode(0, 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeAcn() {
        assertThatThrownBy(() -> SphericalHarmonics.acnToDegreeOrder(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
