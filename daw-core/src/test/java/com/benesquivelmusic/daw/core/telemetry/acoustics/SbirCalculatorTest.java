package com.benesquivelmusic.daw.core.telemetry.acoustics;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.BoundaryKind;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SbirPrediction;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbirCalculatorTest {

    private static final double C = SbirCalculator.SPEED_OF_SOUND_M_S;

    @Test
    void shouldProduceNotchAtCanonicalFrequencyForHalfMetreFromWall() {
        // Speaker 0.5 m from front wall (y=0), listener directly in
        // front of it. The first SBIR cancellation must occur at
        // c / (4 d) ≈ 343 / 2 ≈ 171.5 Hz.
        SbirCalculator calc = new SbirCalculator();
        Position3D speaker = new Position3D(2, 0.5, 1.2);
        Position3D listener = new Position3D(2, 2.5, 1.2);

        SbirPrediction prediction = calc.calculate(
                speaker, listener, BoundaryKind.FRONT_WALL,
                /* boundaryDistance */ 0.5,
                /* perfect reflector */ 1.0);

        double expected = C / (4.0 * 0.5); // 171.5 Hz
        assertThat(prediction.worstNotchHz())
                .as("first SBIR null for d=0.5 m must be at c/(4d) ≈ 171.5 Hz")
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(2.0));
        assertThat(prediction.worstNotchDepthDb())
                .as("perfect reflector should produce a deep (≤ −6 dB) SBIR notch")
                .isLessThan(-6.0);
        assertThat(prediction.boundary()).isEqualTo(BoundaryKind.FRONT_WALL);
    }

    @Test
    void shouldMatchIdealOneEightyDegreeInversionDepthAtAlignedFrequency() {
        // At the cancellation frequency f0 = c/(4d), the reflected wave
        // is phase-inverted relative to the direct wave, so the analytic
        // "ideal 180° inversion" magnitude is
        //   |H| = | 1 + r·(aRefl/aDirect)·e^{-jπ} |
        // i.e. direct − r·(aRefl/aDirect).
        // The calculator's bin at f0 must match that to within 0.5 dB.
        double d = 0.4;
        double r = 0.9;                    // pressure reflection coefficient
        double directDist = 1.5;
        Position3D speaker = new Position3D(2, d, 1.2);
        Position3D listener = new Position3D(2, d + directDist, 1.2);
        double f0 = C / (4.0 * d);

        // Sample exactly at f0 so the bin alignment is unambiguous.
        SbirCalculator calc = new SbirCalculator(new double[] { f0 });
        SbirPrediction p = calc.calculate(
                speaker, listener, BoundaryKind.FRONT_WALL, d, r);

        double aDirect = 1.0 / directDist;
        double aRefl1 = r / (directDist + 2 * d);
        // phi1 = π → cos = −1, sin = 0.
        double idealLin = (aDirect - aRefl1) / aDirect;
        double idealDb = 20 * Math.log10(Math.abs(idealLin));

        assertThat(p.magnitudeDb()[0])
                .as("180°-inversion notch depth at f0 must match analytic model")
                .isCloseTo(idealDb, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void absorptiveBoundaryShouldFlattenResponse() {
        // ACOUSTIC_FOAM (α=0.70) → r = √0.30 ≈ 0.55 → much shallower notch.
        SbirCalculator calc = new SbirCalculator();
        Position3D speaker = new Position3D(2, 0.5, 1.2);
        Position3D listener = new Position3D(2, 2.5, 1.2);

        SbirPrediction reflective = calc.calculate(
                speaker, listener, BoundaryKind.FRONT_WALL, 0.5, 1.0);
        SbirPrediction absorbed = calc.calculate(
                speaker, listener, BoundaryKind.FRONT_WALL, 0.5,
                Math.sqrt(1.0 - 0.70));

        assertThat(absorbed.worstNotchDepthDb())
                .as("absorptive boundary must produce a shallower notch")
                .isGreaterThan(reflective.worstNotchDepthDb());
        assertThat(absorbed.worstNotchDepthDb()).isGreaterThan(-10.0);
    }

    @Test
    void shouldComputeAllFiveBoundariesForRoomConfiguration() {
        RoomConfiguration config = sampleRoomWithSpeakerNearFrontWall();

        Map<BoundaryKind, SbirPrediction> all =
                new SbirCalculator().calculateAllBoundaries(
                        config.getSoundSources().get(0).position(),
                        config.getMicrophones().get(0).position(),
                        config.getDimensions(),
                        config.getMaterialMap());

        assertThat(all).containsOnlyKeys(
                BoundaryKind.FRONT_WALL, BoundaryKind.BACK_WALL,
                BoundaryKind.SIDE_WALL, BoundaryKind.FLOOR, BoundaryKind.CEILING);
        // Front wall should be the worst boundary (speaker is 0.5 m from it).
        BoundaryKind worst = all.values().stream()
                .min((a, b) -> Double.compare(a.worstNotchDepthDb(), b.worstNotchDepthDb()))
                .map(SbirPrediction::boundary).orElseThrow();
        assertThat(worst).isEqualTo(BoundaryKind.FRONT_WALL);
    }

    @Test
    void calculateRoomReturnsWorstBoundaryPerSource() {
        RoomConfiguration config = sampleRoomWithSpeakerNearFrontWall();

        List<SbirPrediction> predictions = new SbirCalculator().calculate(config);

        assertThat(predictions).hasSize(1);
        assertThat(predictions.get(0).boundary()).isEqualTo(BoundaryKind.FRONT_WALL);
        assertThat(predictions.get(0).worstNotchHz())
                .isCloseTo(C / (4.0 * 0.5), org.assertj.core.data.Offset.offset(5.0));
    }

    @Test
    void shouldEmitMoveSourceSuggestionWhenNotchExceedsThreshold() {
        RoomConfiguration config = sampleRoomWithSpeakerNearFrontWall();

        List<TelemetrySuggestion> suggestions =
                new SbirCalculator().suggestMitigations(config);

        assertThat(suggestions).hasSize(1);
        TelemetrySuggestion s = suggestions.get(0);
        assertThat(s).isInstanceOf(TelemetrySuggestion.MoveSoundSource.class);
        TelemetrySuggestion.MoveSoundSource move = (TelemetrySuggestion.MoveSoundSource) s;
        assertThat(move.sourceName()).isEqualTo("L-Monitor");
        // Recommended distance ≥ c/(4 · 40) = ~2.14 m from the front wall.
        double minDist = C / (4.0 * SbirCalculator.SBIR_BAND_LOW_HZ);
        assertThat(move.suggestedPosition().y()).isGreaterThanOrEqualTo(minDist - 0.01);
        assertThat(move.description()).containsIgnoringCase("front wall");
    }

    @Test
    void shouldSuppressSuggestionsWhenSpeakerIsAlreadyFarFromBoundaries() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(6, 8, 3), WallMaterial.DRYWALL);
        // Speaker ≥ 2.5 m from every wall — first null pushed below 35 Hz.
        config.addSoundSource(
                new SoundSource("Centred", new Position3D(3, 4, 1.5), 85));
        config.addMicrophone(
                new MicrophonePlacement("LP", new Position3D(3, 6, 1.5), 0, 0));

        List<TelemetrySuggestion> suggestions =
                new SbirCalculator().suggestMitigations(config);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void shouldReturnEmptyForRoomWithoutSourcesOrMics() {
        RoomConfiguration empty = new RoomConfiguration(
                new RoomDimensions(5, 4, 3), WallMaterial.DRYWALL);

        assertThat(new SbirCalculator().calculate(empty)).isEmpty();
        assertThat(new SbirCalculator().suggestMitigations(empty)).isEmpty();
    }

    @Test
    void constructorAndCalculateShouldRejectInvalidArguments() {
        assertThatThrownBy(() -> new SbirCalculator(new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SbirCalculator(new double[] { -1.0 }))
                .isInstanceOf(IllegalArgumentException.class);

        SbirCalculator calc = new SbirCalculator();
        Position3D s = new Position3D(2, 0.5, 1.2);
        Position3D m = new Position3D(2, 2.0, 1.2);

        assertThatThrownBy(() -> calc.calculate(s, m, BoundaryKind.FRONT_WALL, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.calculate(s, m, BoundaryKind.FRONT_WALL, 0.5, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calc.suggestMitigations(
                sampleRoomWithSpeakerNearFrontWall(), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void predictionRecordShouldDefensivelyCopyArrays() {
        double[] freqs = { 100.0, 200.0 };
        double[] mags = { -3.0, -6.0 };
        SbirPrediction p = new SbirPrediction(
                freqs, mags, 200.0, -6.0, BoundaryKind.FRONT_WALL);

        // Mutate caller arrays — must not affect the record.
        freqs[0] = 9999;
        mags[0] = 9999;
        assertThat(p.frequenciesHz()[0]).isEqualTo(100.0);
        assertThat(p.magnitudeDb()[0]).isEqualTo(-3.0);

        // Mutate accessor result — must not affect the record either.
        p.magnitudeDb()[0] = 12345;
        assertThat(p.magnitudeDb()[0]).isEqualTo(-3.0);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static RoomConfiguration sampleRoomWithSpeakerNearFrontWall() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(6, 5, 3), WallMaterial.CONCRETE);
        // Speaker 0.5 m from front wall (y=0).
        config.addSoundSource(
                new SoundSource("L-Monitor", new Position3D(2, 0.5, 1.2), 85));
        // Listener at standard mix position 1.5 m further into the room.
        config.addMicrophone(
                new MicrophonePlacement("LP", new Position3D(2, 2.0, 1.2), 0, 0));
        return config;
    }
}
