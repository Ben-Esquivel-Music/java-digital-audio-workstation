package com.benesquivelmusic.daw.core.spatial.panner;

import com.benesquivelmusic.daw.sdk.spatial.PanAutomationCurve;
import com.benesquivelmusic.daw.sdk.spatial.PanAutomationPoint;
import com.benesquivelmusic.daw.sdk.spatial.PositioningMode;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPannerData;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PanningTableSynthesizerTest {

    private static final double TOLERANCE = 1e-6;

    private List<SpatialPosition> stereoSpeakers;
    private List<SpatialPosition> quadSpeakers;

    @BeforeEach
    void setUp() {
        // Stereo: left at 30°, right at 330°
        stereoSpeakers = List.of(
                new SpatialPosition(30, 0, 1.0),
                new SpatialPosition(330, 0, 1.0)
        );

        // Quad: front-left, front-right, rear-left, rear-right
        quadSpeakers = List.of(
                new SpatialPosition(30, 0, 1.0),
                new SpatialPosition(330, 0, 1.0),
                new SpatialPosition(110, 0, 1.0),
                new SpatialPosition(250, 0, 1.0)
        );
    }

    // ---- Construction ----

    @Test
    void shouldCreateWithDefaultResolution() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThat(synth.getSpeakerPositions()).hasSize(2);
        assertThat(synth.getOutputChannelCount()).isEqualTo(2);
        assertThat(synth.getInputChannelCount()).isEqualTo(1);
        assertThat(synth.getAzimuthResolution()).isEqualTo(1.0);
        assertThat(synth.getElevationResolution()).isEqualTo(1.0);
    }

    @Test
    void shouldCreateWithCustomResolution() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers, 5.0, 5.0);
        assertThat(synth.getAzimuthSteps()).isEqualTo(72);  // 360 / 5
        assertThat(synth.getElevationSteps()).isEqualTo(37); // 180 / 5 + 1
    }

    @Test
    void shouldRejectLessThanTwoSpeakers() {
        assertThatThrownBy(() -> new PanningTableSynthesizer(
                List.of(new SpatialPosition(0, 0, 1.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullSpeakers() {
        assertThatThrownBy(() -> new PanningTableSynthesizer(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidAzimuthResolution() {
        assertThatThrownBy(() -> new PanningTableSynthesizer(stereoSpeakers, 0.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanningTableSynthesizer(stereoSpeakers, -1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanningTableSynthesizer(stereoSpeakers, 361.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidElevationResolution() {
        assertThatThrownBy(() -> new PanningTableSynthesizer(stereoSpeakers, 1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanningTableSynthesizer(stereoSpeakers, 1.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PanningTableSynthesizer(stereoSpeakers, 1.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- VBAP Gain Accuracy ----

    @Test
    void shouldPanToLeftSpeakerWhenSourceIsLeft() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPosition(new SpatialPosition(30, 0, 1.0));

        double[] gains = synth.computeSpeakerGains();
        assertThat(gains[0]).isGreaterThan(gains[1]);
    }

    @Test
    void shouldPanToRightSpeakerWhenSourceIsRight() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPosition(new SpatialPosition(330, 0, 1.0));

        double[] gains = synth.computeSpeakerGains();
        assertThat(gains[1]).isGreaterThan(gains[0]);
    }

    @Test
    void shouldPanCenterEquallyForStereo() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPosition(new SpatialPosition(0, 0, 1.0));

        double[] gains = synth.computeSpeakerGains();
        assertThat(gains[0]).isCloseTo(gains[1], within(TOLERANCE));
    }

    // ---- Energy Preservation ----

    @Test
    void shouldPreserveEnergyForStereo() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPosition(new SpatialPosition(15, 0, 1.0));

        double[] gains = synth.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldPreserveEnergyForQuad() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(quadSpeakers);
        synth.setPosition(new SpatialPosition(45, 0, 1.0));

        double[] gains = synth.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldPreserveEnergyWithSpread() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(quadSpeakers);
        synth.setPosition(new SpatialPosition(60, 0, 1.0));
        synth.setSpread(0.5);

        double[] gains = synth.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldPreserveEnergyAtSubDegreePosition() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(quadSpeakers);
        synth.setPosition(new SpatialPosition(45.3, 12.7, 1.0));

        double[] gains = synth.computeSpeakerGains();
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    // ---- Bilinear Interpolation ----

    @Test
    void shouldInterpolateSmoothlyBetweenGridPoints() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers, 10.0, 10.0);

        // At grid point
        synth.setPosition(new SpatialPosition(30, 0, 1.0));
        double[] gainsAtGrid = synth.computeSpeakerGains();

        // At half-step (bilinear interpolation)
        synth.setPosition(new SpatialPosition(35, 0, 1.0));
        double[] gainsHalfStep = synth.computeSpeakerGains();

        // At next grid point
        synth.setPosition(new SpatialPosition(40, 0, 1.0));
        double[] gainsNextGrid = synth.computeSpeakerGains();

        // The interpolated value should be between the two grid values for each speaker
        for (int s = 0; s < stereoSpeakers.size(); s++) {
            double min = Math.min(gainsAtGrid[s], gainsNextGrid[s]);
            double max = Math.max(gainsAtGrid[s], gainsNextGrid[s]);
            assertThat(gainsHalfStep[s]).isBetween(min - TOLERANCE, max + TOLERANCE);
        }
    }

    @Test
    void shouldMatchVbapAtGridPoints() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(quadSpeakers, 1.0, 1.0);
        VbapPanner vbap = new VbapPanner(quadSpeakers);

        double azimuth = 45.0;
        double elevation = 0.0;

        synth.setPosition(new SpatialPosition(azimuth, elevation, 1.0));
        vbap.setPosition(new SpatialPosition(azimuth, elevation, 1.0));

        double[] tableGains = synth.computeSpeakerGains();
        double[] vbapGains = vbap.computeSpeakerGains();

        for (int i = 0; i < quadSpeakers.size(); i++) {
            assertThat(tableGains[i]).isCloseTo(vbapGains[i], within(0.01));
        }
    }

    // ---- Spread ----

    @Test
    void shouldIncreaseSpreadUniformity() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(quadSpeakers);
        synth.setPosition(new SpatialPosition(30, 0, 1.0));

        double[] pointGains = synth.computeSpeakerGains();
        synth.setSpread(1.0);
        double[] diffuseGains = synth.computeSpeakerGains();

        double pointStdDev = stddev(pointGains);
        double diffuseStdDev = stddev(diffuseGains);
        assertThat(diffuseStdDev).isLessThan(pointStdDev);
    }

    @Test
    void shouldRejectSpreadOutOfRange() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThatThrownBy(() -> synth.setSpread(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> synth.setSpread(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Position / Mode ----

    @Test
    void shouldDefaultToFreeFormMode() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThat(synth.getPositioningMode()).isEqualTo(PositioningMode.FREE_FORM);
    }

    @Test
    void shouldSnapToNearestSpeaker() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPositioningMode(PositioningMode.SNAP_TO_SPEAKER);
        synth.setPosition(new SpatialPosition(20, 0, 2.0));

        SpatialPosition pos = synth.getPosition();
        assertThat(pos.azimuthDegrees()).isCloseTo(30.0, within(TOLERANCE));
        assertThat(pos.distanceMeters()).isCloseTo(2.0, within(TOLERANCE));
    }

    @Test
    void shouldRejectNullPosition() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThatThrownBy(() -> synth.setPosition(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullPositioningMode() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThatThrownBy(() -> synth.setPositioningMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Distance Attenuation ----

    @Test
    void shouldDefaultToInverseSquareModel() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThat(synth.getDistanceAttenuationModel())
                .isInstanceOf(InverseSquareAttenuation.class);
    }

    @Test
    void shouldRejectNullAttenuationModel() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThatThrownBy(() -> synth.setDistanceAttenuationModel(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Automation ----

    @Test
    void shouldSetAndGetAutomationCurve() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        PanAutomationCurve curve = new PanAutomationCurve(List.of(
                new PanAutomationPoint(0, 0, 0, 1),
                new PanAutomationPoint(4, 90, 0, 1)
        ));
        synth.setAutomationCurve(curve);
        assertThat(synth.getAutomationCurve()).isEqualTo(curve);
    }

    @Test
    void shouldClearAutomationCurve() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setAutomationCurve(null);
        assertThat(synth.getAutomationCurve()).isNull();
    }

    // ---- Speaker Layout Update ----

    @Test
    void shouldRebuildTableOnSpeakerChange() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers, 10.0, 10.0);
        synth.setPosition(new SpatialPosition(30, 0, 1.0));
        double[] gainsBefore = synth.computeSpeakerGains();

        synth.setSpeakerPositions(quadSpeakers);
        double[] gainsAfter = synth.computeSpeakerGains();

        // After changing to quad, we should have 4 gains instead of 2 behavior
        assertThat(gainsAfter).hasSize(4);
        assertThat(gainsBefore).hasSize(2);
    }

    @Test
    void shouldRejectSetSpeakersNull() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThatThrownBy(() -> synth.setSpeakerPositions(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectSetSpeakersLessThanTwo() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThatThrownBy(() -> synth.setSpeakerPositions(
                List.of(new SpatialPosition(0, 0, 1.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Audio Processing ----

    @Test
    void shouldProcessMonoInputToMultipleOutputs() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPosition(new SpatialPosition(0, 0, 1.0));

        float[][] input = {new float[]{1.0f, 0.5f, -0.5f}};
        float[][] output = new float[2][3];
        synth.process(input, output, 3);

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
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setDistanceAttenuationModel(new InverseSquareAttenuation(1.0, 100.0));

        synth.setPosition(new SpatialPosition(0, 0, 1.0));
        float[][] input1 = {new float[]{1.0f}};
        float[][] output1 = new float[2][1];
        synth.process(input1, output1, 1);
        double nearLevel = Math.abs(output1[0][0]) + Math.abs(output1[1][0]);

        synth.setPosition(new SpatialPosition(0, 0, 10.0));
        float[][] input2 = {new float[]{1.0f}};
        float[][] output2 = new float[2][1];
        synth.process(input2, output2, 1);
        double farLevel = Math.abs(output2[0][0]) + Math.abs(output2[1][0]);

        assertThat(farLevel).isLessThan(nearLevel);
    }

    @Test
    void shouldResetWithoutError() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.reset();
    }

    // ---- Visualization Data ----

    @Test
    void shouldReturnPannerData() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        synth.setPosition(new SpatialPosition(45, 10, 2.0));
        synth.setSpread(0.3);

        SpatialPannerData data = synth.getPannerData();
        assertThat(data.sourcePosition().azimuthDegrees()).isCloseTo(45.0, within(TOLERANCE));
        assertThat(data.speakerPositions()).hasSize(2);
        assertThat(data.speakerGains()).hasSize(2);
        assertThat(data.spread()).isEqualTo(0.3);
        assertThat(data.positioningMode()).isEqualTo(PositioningMode.FREE_FORM);
    }

    // ---- Irregular Layouts ----

    @Test
    void shouldHandleNonSymmetricLayout() {
        // Irregular: speakers not at standard angles
        List<SpatialPosition> irregular = List.of(
                new SpatialPosition(15, 0, 1.0),
                new SpatialPosition(120, 0, 1.0),
                new SpatialPosition(250, 10, 1.0)
        );
        PanningTableSynthesizer synth = new PanningTableSynthesizer(irregular, 5.0, 5.0);
        synth.setPosition(new SpatialPosition(60, 5, 1.0));

        double[] gains = synth.computeSpeakerGains();
        assertThat(gains).hasSize(3);
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldHandleHeightOnlyArray() {
        // Height-only: all speakers at elevation != 0
        List<SpatialPosition> heightOnly = List.of(
                new SpatialPosition(0, 45, 1.0),
                new SpatialPosition(120, 45, 1.0),
                new SpatialPosition(240, 45, 1.0)
        );
        PanningTableSynthesizer synth = new PanningTableSynthesizer(heightOnly, 5.0, 5.0);
        synth.setPosition(new SpatialPosition(60, 45, 1.0));

        double[] gains = synth.computeSpeakerGains();
        assertThat(gains).hasSize(3);
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldHandleElevationPanning() {
        // 3D layout with height speakers
        List<SpatialPosition> layout3d = List.of(
                new SpatialPosition(30, 0, 1.0),
                new SpatialPosition(330, 0, 1.0),
                new SpatialPosition(30, 45, 1.0),
                new SpatialPosition(330, 45, 1.0)
        );
        PanningTableSynthesizer synth = new PanningTableSynthesizer(layout3d, 5.0, 5.0);
        synth.setPosition(new SpatialPosition(0, 20, 1.0));

        double[] gains = synth.computeSpeakerGains();
        assertThat(gains).hasSize(4);
        double sumSq = 0;
        for (double g : gains) sumSq += g * g;
        assertThat(sumSq).isCloseTo(1.0, within(TOLERANCE));
    }

    @Test
    void shouldProduceGainsAtBoundaryElevations() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers, 5.0, 5.0);

        // At -90° elevation (nadir)
        synth.setPosition(new SpatialPosition(0, -90, 1.0));
        double[] gainsNadir = synth.computeSpeakerGains();
        double sumSqNadir = 0;
        for (double g : gainsNadir) sumSqNadir += g * g;
        assertThat(sumSqNadir).isCloseTo(1.0, within(TOLERANCE));

        // At +90° elevation (zenith)
        synth.setPosition(new SpatialPosition(0, 90, 1.0));
        double[] gainsZenith = synth.computeSpeakerGains();
        double sumSqZenith = 0;
        for (double g : gainsZenith) sumSqZenith += g * g;
        assertThat(sumSqZenith).isCloseTo(1.0, within(TOLERANCE));
    }

    // ---- Table Grid Dimensions ----

    @Test
    void shouldComputeCorrectGridDimensionsForDefaultResolution() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers);
        assertThat(synth.getAzimuthSteps()).isEqualTo(360);
        assertThat(synth.getElevationSteps()).isEqualTo(181);
    }

    @Test
    void shouldComputeCorrectGridDimensionsForCoarseResolution() {
        PanningTableSynthesizer synth = new PanningTableSynthesizer(stereoSpeakers, 10.0, 10.0);
        assertThat(synth.getAzimuthSteps()).isEqualTo(36);
        assertThat(synth.getElevationSteps()).isEqualTo(19);
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
