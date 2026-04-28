package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalizedHrtfProfileTest {

    private static PersonalizedHrtfProfile sample() {
        int m = 2;
        int n = 4;
        float[][] left = new float[m][n];
        float[][] right = new float[m][n];
        double[][] positions = new double[m][3];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                left[i][j] = i + 0.1f * j;
                right[i][j] = i + 0.2f * j;
            }
            positions[i] = new double[]{i * 90.0, 0.0, 1.5};
        }
        return new PersonalizedHrtfProfile("test", m, 48000.0, left, right, positions);
    }

    @Test
    void shouldExposeImpulseLength() {
        assertThat(sample().impulseLength()).isEqualTo(4);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new PersonalizedHrtfProfile(
                null, 1, 48000.0, new float[1][1], new float[1][1], new double[1][3]))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectMismatchedMeasurementCount() {
        assertThatThrownBy(() -> new PersonalizedHrtfProfile(
                "x", 2, 48000.0, new float[1][4], new float[1][4],
                new double[][]{{0, 0, 1}, {0, 0, 1}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leftImpulses length");
    }

    @Test
    void shouldRejectShortPositionVector() {
        assertThatThrownBy(() -> new PersonalizedHrtfProfile(
                "x", 1, 48000.0, new float[][]{{0, 0}}, new float[][]{{0, 0}},
                new double[][]{{0, 0}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 components");
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new PersonalizedHrtfProfile(
                "x", 1, 0.0, new float[][]{{0, 0}}, new float[][]{{0, 0}},
                new double[][]{{0, 0, 1}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldConvertToHrtfData() {
        PersonalizedHrtfProfile profile = sample();
        HrtfData data = profile.toHrtfData();
        assertThat(data.profileName()).isEqualTo("test");
        assertThat(data.sampleRate()).isEqualTo(48000.0);
        assertThat(data.measurementCount()).isEqualTo(2);
        assertThat(data.receiverCount()).isEqualTo(2);
        assertThat(data.irLength()).isEqualTo(4);
        // Receiver 0 = left, receiver 1 = right
        assertThat(data.impulseResponses()[1][0][2]).isEqualTo(profile.leftImpulses()[1][2]);
        assertThat(data.impulseResponses()[1][1][2]).isEqualTo(profile.rightImpulses()[1][2]);
        assertThat(data.sourcePositions().get(1).azimuthDegrees()).isEqualTo(90.0);
    }
}
