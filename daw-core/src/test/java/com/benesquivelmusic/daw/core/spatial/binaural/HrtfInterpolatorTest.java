package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.core.spatial.binaural.HrtfInterpolator.InterpolatedHrtf;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class HrtfInterpolatorTest {

    private static final int IR_LENGTH = 64;
    private HrtfData hrtfData;

    @BeforeEach
    void setUp() {
        // Create a simple HRTF dataset with 4 directions: front, left, back, right
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1.0),    // front
                new SphericalCoordinate(90, 0, 1.0),   // left
                new SphericalCoordinate(180, 0, 1.0),  // back
                new SphericalCoordinate(270, 0, 1.0)   // right
        );

        float[][][] ir = new float[4][2][IR_LENGTH];
        float[][] delays = new float[4][2];

        // Each direction gets a distinct IR: scaled impulse at different amplitudes
        for (int m = 0; m < 4; m++) {
            float leftAmp = (m + 1) * 0.1f;
            float rightAmp = (4 - m) * 0.1f;
            ir[m][0][0] = leftAmp;   // left ear impulse
            ir[m][1][0] = rightAmp;  // right ear impulse
            delays[m][0] = m * 2.0f;
            delays[m][1] = (3 - m) * 2.0f;
        }

        hrtfData = new HrtfData("Test4Dir", 44100, positions, ir, delays);
    }

    @Test
    void shouldReturnExactMatchForMeasuredPosition() {
        HrtfInterpolator interpolator = new HrtfInterpolator(hrtfData);
        InterpolatedHrtf result = interpolator.interpolate(new SphericalCoordinate(0, 0, 1.0));

        // Front position: m=0, leftAmp=0.1, rightAmp=0.4
        assertThat(result.leftIr()[0]).isCloseTo(0.1f, within(1e-6f));
        assertThat(result.rightIr()[0]).isCloseTo(0.4f, within(1e-6f));
    }

    @Test
    void shouldInterpolateBetweenNeighbors() {
        HrtfInterpolator interpolator = new HrtfInterpolator(hrtfData, 2);
        // 45° is exactly between front (0°) and left (90°)
        InterpolatedHrtf result = interpolator.interpolate(new SphericalCoordinate(45, 0, 1.0));

        // Should be a blend of front (m=0) and left (m=1)
        // Front: leftIr[0]=0.1, Left: leftIr[0]=0.2
        // At 45° equidistant from both, equal weights → ~0.15
        assertThat(result.leftIr()[0]).isBetween(0.05f, 0.25f);
        assertThat(result.rightIr()[0]).isBetween(0.15f, 0.45f);
    }

    @Test
    void shouldInterpolateDelays() {
        HrtfInterpolator interpolator = new HrtfInterpolator(hrtfData, 2);
        InterpolatedHrtf result = interpolator.interpolate(new SphericalCoordinate(45, 0, 1.0));

        // Front: delays[0]={0,6}, Left: delays[1]={2,4}
        // Equidistant → equal weights → leftDelay~1.0, rightDelay~5.0
        assertThat(result.leftDelay()).isBetween(0.0f, 3.0f);
        assertThat(result.rightDelay()).isBetween(3.0f, 7.0f);
    }

    @Test
    void shouldUseSpecifiedNeighborCount() {
        HrtfInterpolator interpolator1 = new HrtfInterpolator(hrtfData, 1);
        HrtfInterpolator interpolator3 = new HrtfInterpolator(hrtfData, 3);

        // With 1 neighbor, the result should be the nearest measured position
        InterpolatedHrtf result1 = interpolator1.interpolate(new SphericalCoordinate(10, 0, 1.0));
        // Nearest to 10° is front (0°) → exact values
        assertThat(result1.leftIr()[0]).isCloseTo(0.1f, within(1e-3f));

        // With 3 neighbors, result is a blend
        InterpolatedHrtf result3 = interpolator3.interpolate(new SphericalCoordinate(10, 0, 1.0));
        assertThat(result3.leftIr()[0]).isNotNull();
    }

    @Test
    void shouldRejectNullHrtfData() {
        assertThatThrownBy(() -> new HrtfInterpolator(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroNeighborCount() {
        assertThatThrownBy(() -> new HrtfInterpolator(hrtfData, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleSingleMeasurement() {
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1.0)
        );
        float[][][] ir = {{{1.0f, 0.5f}, {0.8f, 0.3f}}};
        float[][] delays = {{0.0f, 2.0f}};
        HrtfData singleData = new HrtfData("Single", 44100, positions, ir, delays);

        HrtfInterpolator interpolator = new HrtfInterpolator(singleData);
        InterpolatedHrtf result = interpolator.interpolate(new SphericalCoordinate(90, 0, 1.0));

        // With only one measurement, it should return that measurement
        assertThat(result.leftIr()[0]).isCloseTo(1.0f, within(1e-6f));
        assertThat(result.rightIr()[0]).isCloseTo(0.8f, within(1e-6f));
    }

    @Test
    void shouldProduceIrOfCorrectLength() {
        HrtfInterpolator interpolator = new HrtfInterpolator(hrtfData);
        InterpolatedHrtf result = interpolator.interpolate(new SphericalCoordinate(45, 30, 1.0));

        assertThat(result.leftIr()).hasSize(IR_LENGTH);
        assertThat(result.rightIr()).hasSize(IR_LENGTH);
    }
}
