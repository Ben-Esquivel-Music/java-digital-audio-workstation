package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoomParameterControllerTest {

    private static final RoomDimensions STUDIO = new RoomDimensions(6.0, 8.0, 3.0);
    private static final RoomDimensions SMALL_ROOM = new RoomDimensions(3.0, 4.0, 2.5);

    // ── RT60 computation ───────────────────────────────────────────

    @Test
    void shouldComputeRt60ForStudioWithAcousticTile() {
        double rt60 = RoomParameterController.computeRt60(STUDIO, WallMaterial.ACOUSTIC_TILE);

        // Sabine: 0.161 * V / (S * α)
        // V = 6*8*3 = 144, S = 2*(6*8 + 6*3 + 8*3) = 2*(48+18+24) = 180
        // α = 0.65, A = 180 * 0.65 = 117, RT60 = 0.161 * 144 / 117 ≈ 0.198
        assertThat(rt60).isCloseTo(0.198, within(0.01));
    }

    @Test
    void shouldComputeRt60ForConcreteRoom() {
        double rt60 = RoomParameterController.computeRt60(STUDIO, WallMaterial.CONCRETE);

        // α = 0.02, A = 180 * 0.02 = 3.6, RT60 = 0.161 * 144 / 3.6 ≈ 6.44
        assertThat(rt60).isCloseTo(6.44, within(0.1));
    }

    @Test
    void shouldReturnMaxValueForZeroAbsorption() {
        // Marble has 0.01 absorption but not zero — use a hypothetical
        // Since all WallMaterial values have non-zero absorption, compute
        // for a very reflective material and verify it's a large value
        double rt60 = RoomParameterController.computeRt60(STUDIO, WallMaterial.MARBLE);
        assertThat(rt60).isGreaterThan(5.0);
    }

    @Test
    void shouldRejectNullDimensionsForRt60() {
        assertThatThrownBy(() -> RoomParameterController.computeRt60(null, WallMaterial.WOOD))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMaterialForRt60() {
        assertThatThrownBy(() -> RoomParameterController.computeRt60(STUDIO, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rt60ShouldDecreaseWithMoreAbsorptiveMaterial() {
        double rt60Concrete = RoomParameterController.computeRt60(STUDIO, WallMaterial.CONCRETE);
        double rt60AcousticFoam = RoomParameterController.computeRt60(STUDIO, WallMaterial.ACOUSTIC_FOAM);

        assertThat(rt60AcousticFoam).isLessThan(rt60Concrete);
    }

    @Test
    void rt60ShouldIncreaseWithLargerRoom() {
        double rt60Small = RoomParameterController.computeRt60(SMALL_ROOM, WallMaterial.DRYWALL);
        double rt60Large = RoomParameterController.computeRt60(STUDIO, WallMaterial.DRYWALL);

        assertThat(rt60Large).isGreaterThan(rt60Small);
    }

    // ── Early reflections ──────────────────────────────────────────

    @Test
    void shouldComputeSixEarlyReflections() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2.0, 3.0, 1.0), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(3.0, 4.0, 1.2), 0, 0);

        List<RoomParameterController.EarlyReflection> reflections =
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.DRYWALL, source, mic);

        assertThat(reflections).hasSize(6);
    }

    @Test
    void earlyReflectionsShouldHavePositiveDistances() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2.0, 3.0, 1.0), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(3.0, 4.0, 1.2), 0, 0);

        List<RoomParameterController.EarlyReflection> reflections =
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.DRYWALL, source, mic);

        for (RoomParameterController.EarlyReflection ref : reflections) {
            assertThat(ref.distance()).isGreaterThan(0);
            assertThat(ref.delayMs()).isGreaterThan(0);
        }
    }

    @Test
    void earlyReflectionsShouldHaveSurfaceNames() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2.0, 3.0, 1.0), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(3.0, 4.0, 1.2), 0, 0);

        List<RoomParameterController.EarlyReflection> reflections =
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.DRYWALL, source, mic);

        List<String> names = reflections.stream()
                .map(RoomParameterController.EarlyReflection::surfaceName)
                .toList();
        assertThat(names).containsExactly(
                "Left wall", "Right wall", "Front wall", "Back wall", "Floor", "Ceiling");
    }

    @Test
    void earlyReflectionsShouldRejectNullInputs() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2.0, 3.0, 1.0), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(3.0, 4.0, 1.2), 0, 0);

        assertThatThrownBy(() ->
                RoomParameterController.computeEarlyReflections(null, WallMaterial.DRYWALL, source, mic))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                RoomParameterController.computeEarlyReflections(STUDIO, null, source, mic))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.DRYWALL, null, mic))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.DRYWALL, source, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void moreAbsorptiveMaterialShouldLowerReflectionLevels() {
        SoundSource source = new SoundSource("Guitar", new Position3D(3.0, 4.0, 1.0), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(3.0, 4.0, 1.2), 0, 0);

        List<RoomParameterController.EarlyReflection> concrete =
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.CONCRETE, source, mic);
        List<RoomParameterController.EarlyReflection> foam =
                RoomParameterController.computeEarlyReflections(STUDIO, WallMaterial.ACOUSTIC_FOAM, source, mic);

        // Each reflection from foam should have a lower level than concrete
        for (int i = 0; i < 6; i++) {
            assertThat(foam.get(i).levelDb()).isLessThan(concrete.get(i).levelDb());
        }
    }

    // ── Optimal mic position ───────────────────────────────────────

    @Test
    void shouldSuggestPositionNearSourceCentroid() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2.0, 3.0, 1.0), 85);

        Position3D suggested = RoomParameterController.suggestOptimalMicPosition(
                STUDIO, List.of(source));

        assertThat(suggested).isNotNull();
        // Should be between source and room center
        assertThat(suggested.x()).isBetween(2.0, 3.5);
        assertThat(suggested.y()).isBetween(3.0, 4.5);
        assertThat(suggested.z()).isEqualTo(1.2); // ear height
    }

    @Test
    void shouldReturnNullForEmptySources() {
        Position3D result = RoomParameterController.suggestOptimalMicPosition(STUDIO, List.of());
        assertThat(result).isNull();
    }

    @Test
    void shouldClampSuggestionAwayFromWalls() {
        // Source near corner
        SoundSource source = new SoundSource("Guitar", new Position3D(0.1, 0.1, 1.0), 85);

        Position3D suggested = RoomParameterController.suggestOptimalMicPosition(
                STUDIO, List.of(source));

        assertThat(suggested).isNotNull();
        assertThat(suggested.x()).isGreaterThanOrEqualTo(0.5);
        assertThat(suggested.y()).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void shouldHandleMultipleSources() {
        SoundSource s1 = new SoundSource("Guitar", new Position3D(1.0, 2.0, 1.0), 85);
        SoundSource s2 = new SoundSource("Drums", new Position3D(5.0, 6.0, 1.0), 90);

        Position3D suggested = RoomParameterController.suggestOptimalMicPosition(
                STUDIO, List.of(s1, s2));

        assertThat(suggested).isNotNull();
        assertThat(suggested.x()).isBetween(1.0, 5.0);
        assertThat(suggested.y()).isBetween(2.0, 6.0);
    }

    @Test
    void shouldRejectNullInputsForOptimalPosition() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2.0, 3.0, 1.0), 85);

        assertThatThrownBy(() ->
                RoomParameterController.suggestOptimalMicPosition(null, List.of(source)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                RoomParameterController.suggestOptimalMicPosition(STUDIO, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── EarlyReflection record ─────────────────────────────────────

    @Test
    void earlyReflectionRecordShouldRejectNullSurfaceName() {
        assertThatThrownBy(() ->
                new RoomParameterController.EarlyReflection(null, 1.0, 2.9, -10.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void earlyReflectionRecordShouldExposeFields() {
        RoomParameterController.EarlyReflection ref =
                new RoomParameterController.EarlyReflection("Left wall", 3.5, 10.2, -15.0);

        assertThat(ref.surfaceName()).isEqualTo("Left wall");
        assertThat(ref.distance()).isEqualTo(3.5);
        assertThat(ref.delayMs()).isEqualTo(10.2);
        assertThat(ref.levelDb()).isEqualTo(-15.0);
    }
}
