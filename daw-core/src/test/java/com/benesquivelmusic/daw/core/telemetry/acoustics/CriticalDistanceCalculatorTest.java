package com.benesquivelmusic.daw.core.telemetry.acoustics;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.CriticalDistanceSnapshot;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SourceDirectivity;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriticalDistanceCalculatorTest {

    /**
     * Canonical textbook example from the issue: V = 30 m³, T60 = 0.3 s,
     * Q = 1.  The formula {@code d_c = 0.141·√(Q·V/(π·T60))} yields
     * 0.141·√(30/(π·0.3)) ≈ 0.80 m. The issue quotes ≈ 0.75 m as a
     * round-number approximation; we assert with a 0.1 m tolerance so
     * the literal formula is validated while still matching the issue's
     * stated expectation.
     */
    @Test
    void criticalDistanceMatchesTextbookFormulaForOmnidirectional() {
        double dc = CriticalDistanceCalculator.criticalDistanceMeters(
                /* Q */ 1.0, /* V */ 30.0, /* T60 */ 0.3);

        double expected = 0.141 * Math.sqrt(30.0 / (Math.PI * 0.3));
        assertThat(dc)
                .as("d_c must equal 0.141·√(V/(π·T60)) for Q=1")
                .isCloseTo(expected, Offset.offset(1.0e-9));
        // Sanity-check against the issue's round-number ~0.75 m.
        assertThat(dc).isCloseTo(0.80, Offset.offset(0.1));
    }

    @Test
    void criticalDistanceScalesAsSqrtOfQ() {
        // For fixed V and T60, d_c(Q) / d_c(1) = √Q.
        double v = 30.0;
        double t60 = 0.3;
        double dOmni = CriticalDistanceCalculator.criticalDistanceMeters(1.0, v, t60);
        double dCard = CriticalDistanceCalculator.criticalDistanceMeters(
                SourceDirectivity.CARDIOID.q(), v, t60);
        double dSuper = CriticalDistanceCalculator.criticalDistanceMeters(
                SourceDirectivity.SUPERCARDIOID.q(), v, t60);
        double dHyper = CriticalDistanceCalculator.criticalDistanceMeters(
                SourceDirectivity.HYPERCARDIOID.q(), v, t60);

        assertThat(dCard / dOmni).isCloseTo(
                Math.sqrt(SourceDirectivity.CARDIOID.q()),
                Offset.offset(1.0e-9));
        assertThat(dSuper / dOmni).isCloseTo(
                Math.sqrt(SourceDirectivity.SUPERCARDIOID.q()),
                Offset.offset(1.0e-9));
        assertThat(dHyper / dOmni).isCloseTo(
                Math.sqrt(SourceDirectivity.HYPERCARDIOID.q()),
                Offset.offset(1.0e-9));
    }

    @Test
    void directToReverberantRatioIsZeroAtCriticalDistanceAndSixDbAtHalf() {
        double dc = 1.0;
        // Right at d_c, direct energy equals reverberant energy → 0 dB.
        assertThat(CriticalDistanceCalculator
                .directToReverberantRatioDb(1.0, dc))
                .isCloseTo(0.0, Offset.offset(1.0e-9));
        // Half d_c → +6 dB (closer = more direct).
        assertThat(CriticalDistanceCalculator
                .directToReverberantRatioDb(0.5, dc))
                .isCloseTo(6.0206, Offset.offset(1.0e-3));
        // Twice d_c → −6 dB (farther = more reverberant).
        assertThat(CriticalDistanceCalculator
                .directToReverberantRatioDb(2.0, dc))
                .isCloseTo(-6.0206, Offset.offset(1.0e-3));
    }

    @Test
    void rejectsNonPositiveInputs() {
        assertThatThrownBy(() ->
                CriticalDistanceCalculator.criticalDistanceMeters(0, 30, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                CriticalDistanceCalculator.criticalDistanceMeters(1, -1, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                CriticalDistanceCalculator.criticalDistanceMeters(1, 30, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                CriticalDistanceCalculator.directToReverberantRatioDb(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceIdIsDeterministicAndStable() {
        SoundSource a = new SoundSource("Vocal", new Position3D(1, 1, 1), 85.0);
        SoundSource b = new SoundSource("Vocal", new Position3D(9, 9, 9), 70.0);
        SoundSource c = new SoundSource("Guitar", new Position3D(1, 1, 1), 85.0);

        assertThat(CriticalDistanceCalculator.sourceId(a))
                .isEqualTo(CriticalDistanceCalculator.sourceId(b));
        assertThat(CriticalDistanceCalculator.sourceId(a))
                .isNotEqualTo(CriticalDistanceCalculator.sourceId(c));
    }

    @Test
    void calculateReturnsOneSnapshotPerSourceUsingConfiguredDirectivity() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4, 5, 3), WallMaterial.ACOUSTIC_FOAM);
        SoundSource omni = new SoundSource(
                "Omni", new Position3D(1, 1, 1.2), 85.0);
        SoundSource cardioid = new SoundSource(
                "Vocal", new Position3D(2, 2, 1.2), 85.0);
        config.addSoundSource(omni);
        config.addSoundSource(cardioid);
        config.setSourceDirectivity("Vocal", SourceDirectivity.CARDIOID);

        List<CriticalDistanceSnapshot> snapshots =
                new CriticalDistanceCalculator().calculate(config);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).directivity())
                .isEqualTo(SourceDirectivity.OMNIDIRECTIONAL);
        assertThat(snapshots.get(1).directivity())
                .isEqualTo(SourceDirectivity.CARDIOID);
        assertThat(snapshots.get(0).sourceId())
                .isEqualTo(CriticalDistanceCalculator.sourceId(omni));
        // Cardioid radius should be √Q times the omni radius.
        assertThat(snapshots.get(1).distanceMeters()
                / snapshots.get(0).distanceMeters())
                .isCloseTo(Math.sqrt(SourceDirectivity.CARDIOID.q()),
                        Offset.offset(1.0e-9));
    }

    @Test
    void suggestsMovingMicThatSitsInReverberantField() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(8, 10, 3), WallMaterial.CONCRETE);
        SoundSource src = new SoundSource(
                "Speaker", new Position3D(2, 2, 1.2), 85.0);
        // Mic placed near the opposite wall → well outside d_c.
        MicrophonePlacement mic = new MicrophonePlacement(
                "FarMic", new Position3D(7, 9, 1.2), 0, 0);
        config.addSoundSource(src);
        config.addMicrophone(mic);

        List<TelemetrySuggestion> suggestions =
                new CriticalDistanceCalculator().suggestMitigations(config);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0))
                .isInstanceOfSatisfying(
                        TelemetrySuggestion.AdjustMicPosition.class,
                        s -> {
                            assertThat(s.microphoneName()).isEqualTo("FarMic");
                            assertThat(s.reason())
                                    .contains("reverberant field")
                                    .contains("Speaker");
                        });
    }

    @Test
    void doesNotSuggestMoveWhenMicInsideDirectField() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(8, 10, 3), WallMaterial.CONCRETE);
        SoundSource src = new SoundSource(
                "Speaker", new Position3D(2, 2, 1.2), 85.0);
        MicrophonePlacement mic = new MicrophonePlacement(
                "CloseMic", new Position3D(2.2, 2.2, 1.2), 0, 0);
        config.addSoundSource(src);
        config.addMicrophone(mic);

        assertThat(new CriticalDistanceCalculator()
                .suggestMitigations(config))
                .isEmpty();
    }

    @Test
    void defaultDirectivityIsOmnidirectional() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4, 5, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource(
                "X", new Position3D(1, 1, 1), 85.0));
        assertThat(config.getSourceDirectivity("X"))
                .isEqualTo(SourceDirectivity.OMNIDIRECTIONAL);
    }
}
