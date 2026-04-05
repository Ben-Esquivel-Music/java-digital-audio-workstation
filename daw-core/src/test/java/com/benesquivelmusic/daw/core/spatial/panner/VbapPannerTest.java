package com.benesquivelmusic.daw.core.spatial.panner;

import com.benesquivelmusic.daw.sdk.spatial.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class VbapPannerTest {

    private static final double TOLERANCE = 1e-6;

    private List<SpatialPosition> stereoSpeakers;
    private List<SpatialPosition> quadSpeakers;

    @BeforeEach
    void setUp() {
        // Stereo: left at 30°, right at 330°
        stereoSpeakers = List.of(
                new SpatialPosition(30, 0, 1.0),   // left
                new SpatialPosition(330, 0, 1.0)    // right
        );

        // Quad: front-left, front-right, rear-left, rear-right
        quadSpeakers = List.of(
                new SpatialPosition(30, 0, 1.0),    // FL
                new SpatialPosition(330, 0, 1.0),   // FR
                new SpatialPosition(110, 0, 1.0),   // RL
                new SpatialPosition(250, 0, 1.0)    // RR
        );
    }

    // ---- Construction ----

    @Test
    void shouldCreatePannerWithSpeakers() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThat(panner.getSpeakerPositions()).hasSize(2);
        assertThat(panner.getOutputChannelCount()).isEqualTo(2);
        assertThat(panner.getInputChannelCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectLessThanTwoSpeakers() {
        assertThatThrownBy(() -> new VbapPanner(List.of(new SpatialPosition(0, 0, 1.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullSpeakers() {
        assertThatThrownBy(() -> new VbapPanner(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- VBAP Gain Accuracy ----

    @Test
    void shouldPanToLeftSpeakerWhenSourceIsLeft() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPosition(new SpatialPosition(30, 0, 1.0)); // exactly at left speaker

        double[] gains = panner.computeSpeakerGains();
        // Left speaker gain should be dominant
        assertThat(gains[0]).isGreaterThan(gains[1]);
    }

    @Test
    void shouldPanToRightSpeakerWhenSourceIsRight() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPosition(new SpatialPosition(330, 0, 1.0)); // exactly at right speaker

        double[] gains = panner.computeSpeakerGains();
        // Right speaker gain should be dominant
        assertThat(gains[1]).isGreaterThan(gains[0]);
    }

    @Test
    void shouldPanCenterEquallyForStereo() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPosition(new SpatialPosition(0, 0, 1.0)); // center (front)

        double[] gains = panner.computeSpeakerGains();
        // Both speakers should have approximately equal gain
        assertThat(gains[0]).isCloseTo(gains[1], within(TOLERANCE));
    }

    // ---- Energy Preservation ----

    @Test
    void shouldPreserveEnergyForStereo() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPosition(new SpatialPosition(15, 0, 1.0));

        double[] gains = panner.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) {
            sumSq += g * g;
        }
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldPreserveEnergyForQuad() {
        VbapPanner panner = new VbapPanner(quadSpeakers);
        panner.setPosition(new SpatialPosition(45, 0, 1.0));

        double[] gains = panner.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) {
            sumSq += g * g;
        }
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldPreserveEnergyWithSpread() {
        VbapPanner panner = new VbapPanner(quadSpeakers);
        panner.setPosition(new SpatialPosition(60, 0, 1.0));
        panner.setSpread(0.5);

        double[] gains = panner.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) {
            sumSq += g * g;
        }
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    // ---- Spread ----

    @Test
    void shouldIncreaseSpreadUniformity() {
        VbapPanner panner = new VbapPanner(quadSpeakers);
        panner.setPosition(new SpatialPosition(30, 0, 1.0)); // at FL speaker

        double[] pointGains = panner.computeSpeakerGains();
        panner.setSpread(1.0);
        double[] diffuseGains = panner.computeSpeakerGains();

        // With full spread, all gains should be more uniform
        double pointStdDev = stddev(pointGains);
        double diffuseStdDev = stddev(diffuseGains);
        assertThat(diffuseStdDev).isLessThan(pointStdDev);
    }

    @Test
    void shouldRejectSpreadOutOfRange() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThatThrownBy(() -> panner.setSpread(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> panner.setSpread(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Position / Mode ----

    @Test
    void shouldDefaultToFreeFormMode() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThat(panner.getPositioningMode()).isEqualTo(PositioningMode.FREE_FORM);
    }

    @Test
    void shouldSnapToNearestSpeaker() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPositioningMode(PositioningMode.SNAP_TO_SPEAKER);
        panner.setPosition(new SpatialPosition(20, 0, 2.0));

        // Should snap to left speaker (30°) since it's closer than right (330°)
        SpatialPosition pos = panner.getPosition();
        assertThat(pos.azimuthDegrees()).isCloseTo(30.0, within(TOLERANCE));
        // Distance should be preserved
        assertThat(pos.distanceMeters()).isCloseTo(2.0, within(TOLERANCE));
    }

    @Test
    void shouldRejectNullPosition() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThatThrownBy(() -> panner.setPosition(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPositioningMode() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThatThrownBy(() -> panner.setPositioningMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Distance Attenuation ----

    @Test
    void shouldDefaultToInverseSquareModel() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThat(panner.getDistanceAttenuationModel())
                .isInstanceOf(InverseSquareAttenuation.class);
    }

    @Test
    void shouldRejectNullAttenuationModel() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        assertThatThrownBy(() -> panner.setDistanceAttenuationModel(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Automation ----

    @Test
    void shouldSetAndGetAutomationCurve() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        PanAutomationCurve curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0, 0, 0, 1),
                new PanAutomationPoint(4, 90, 0, 1)
        ));
        panner.setAutomationCurve(curve);
        assertThat(panner.getAutomationCurve()).isEqualTo(curve);
    }

    @Test
    void shouldClearAutomationCurve() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setAutomationCurve(null);
        assertThat(panner.getAutomationCurve()).isNull();
    }

    // ---- Audio Processing ----

    @Test
    void shouldProcessMonoInputToMultipleOutputs() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPosition(new SpatialPosition(0, 0, 1.0));

        float[][] input = {new float[]{1.0f, 0.5f, -0.5f}};
        float[][] output = new float[2][3];
        panner.process(input, output, 3);

        // Both output channels should have signal (center pan)
        boolean leftHasSignal = false;
        boolean rightHasSignal = false;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(output[0][i]) > 1e-6f) leftHasSignal = true;
            if (Math.abs(output[1][i]) > 1e-6f) rightHasSignal = true;
        }
        assertThat(leftHasSignal).isTrue();
        assertThat(rightHasSignal).isTrue();
    }

    @Test
    void shouldApplyDistanceAttenuation() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setDistanceAttenuationModel(new InverseSquareAttenuation(1.0, 100.0));

        // Process at reference distance
        panner.setPosition(new SpatialPosition(0, 0, 1.0));
        float[][] input1 = {new float[]{1.0f}};
        float[][] output1 = new float[2][1];
        panner.process(input1, output1, 1);
        double nearLevel = Math.abs(output1[0][0]) + Math.abs(output1[1][0]);

        // Process at 10x reference distance
        panner.setPosition(new SpatialPosition(0, 0, 10.0));
        float[][] input2 = {new float[]{1.0f}};
        float[][] output2 = new float[2][1];
        panner.process(input2, output2, 1);
        double farLevel = Math.abs(output2[0][0]) + Math.abs(output2[1][0]);

        assertThat(farLevel).isLessThan(nearLevel);
    }

    @Test
    void shouldResetWithoutError() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.reset(); // no-op, should not throw
    }

    // ---- Visualization Data ----

    @Test
    void shouldReturnPannerData() {
        VbapPanner panner = new VbapPanner(stereoSpeakers);
        panner.setPosition(new SpatialPosition(45, 10, 2.0));
        panner.setSpread(0.3);

        SpatialPannerData data = panner.getPannerData();
        assertThat(data.sourcePosition().azimuthDegrees()).isCloseTo(45.0, within(TOLERANCE));
        assertThat(data.speakerPositions()).hasSize(2);
        assertThat(data.speakerGains()).hasSize(2);
        assertThat(data.spread()).isEqualTo(0.3);
        assertThat(data.positioningMode()).isEqualTo(PositioningMode.FREE_FORM);
    }

    // ---- Helper ----

    private static double stddev(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double variance = 0;
        for (double v : values) variance += (v - mean) * (v - mean);
        return Math.sqrt(variance / values.length);
    }
}
