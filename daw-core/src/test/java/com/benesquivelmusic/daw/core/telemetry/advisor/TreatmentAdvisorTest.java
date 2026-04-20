package com.benesquivelmusic.daw.core.telemetry.advisor;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreatmentAdvisorTest {

    /**
     * A perfectly symmetric room with a centered source and mic should produce
     * absorber suggestions on both side walls with near-equal predicted
     * improvement (the advisor is doing geometric first-reflection-point
     * ray tracing, not keyword matching).
     */
    @Test
    void symmetricRoomYieldsBothSideWallSuggestions() {
        RoomConfiguration config = symmetricRoom();
        TreatmentAdvisor advisor = new TreatmentAdvisor();

        List<AcousticTreatment> suggestions = advisor.analyze(config);

        AcousticTreatment left = findBroadbandOn(suggestions, RoomSurface.LEFT_WALL);
        AcousticTreatment right = findBroadbandOn(suggestions, RoomSurface.RIGHT_WALL);
        assertThat(left).as("left-wall absorber").isNotNull();
        assertThat(right).as("right-wall absorber").isNotNull();
        assertThat(left.predictedImprovementLufs())
                .isCloseTo(right.predictedImprovementLufs(), within(1e-6));
    }

    /**
     * Marking one side-wall suggestion as applied must cause the next
     * analysis to no longer rank that spot in the suggestion list — the
     * advisor's whole raison d'etre is to account for already-installed
     * treatment.
     */
    @Test
    void applyingTreatmentReRanksSubsequentAnalysis() {
        RoomConfiguration config = symmetricRoom();
        TreatmentAdvisor advisor = new TreatmentAdvisor();

        AcousticTreatment leftSuggestion =
                findBroadbandOn(advisor.analyze(config), RoomSurface.LEFT_WALL);
        assertThat(leftSuggestion).isNotNull();

        config.addAppliedTreatment(leftSuggestion);

        List<AcousticTreatment> reranked = advisor.analyze(config);
        assertThat(findBroadbandOn(reranked, RoomSurface.LEFT_WALL))
                .as("already-applied left-wall spot should not be re-suggested")
                .isNull();
        // Other spots (e.g. right wall) still appear.
        assertThat(findBroadbandOn(reranked, RoomSurface.RIGHT_WALL)).isNotNull();
    }

    @Test
    void suggestionsAreSortedByDescendingPredictedImprovement() {
        List<AcousticTreatment> suggestions =
                new TreatmentAdvisor().analyze(symmetricRoom());
        for (int i = 1; i < suggestions.size(); i++) {
            assertThat(suggestions.get(i - 1).predictedImprovementLufs())
                    .isGreaterThanOrEqualTo(suggestions.get(i).predictedImprovementLufs());
        }
    }

    @Test
    void rejectsNullConfiguration() {
        assertThatThrownBy(() -> new TreatmentAdvisor().analyze(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void lfTrapsAreSuggestedForSmallHardRooms() {
        RoomDimensions dims = new RoomDimensions(3.0, 3.5, 2.4);
        RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("S", new Position3D(1.5, 1.0, 1.2), 85));
        config.addMicrophone(new MicrophonePlacement("M", new Position3D(1.5, 2.5, 1.2), 0, 0));

        List<AcousticTreatment> suggestions = new TreatmentAdvisor().analyze(config);

        assertThat(suggestions.stream()
                        .anyMatch(s -> s.kind() == TreatmentKind.ABSORBER_LF_TRAP))
                .as("small hard-walled room should get an LF-trap suggestion")
                .isTrue();
    }

    @Test
    void rearWallDiffuserSuggestedForReflectiveBackWall() {
        RoomDimensions dims = new RoomDimensions(6, 8, 3);
        RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("S", new Position3D(3, 2, 1.2), 85));
        // Mic near the front — back wall is 5 m behind it.
        config.addMicrophone(new MicrophonePlacement("M", new Position3D(3, 3, 1.2), 0, 0));

        List<AcousticTreatment> suggestions = new TreatmentAdvisor().analyze(config);

        assertThat(suggestions.stream().anyMatch(s ->
                s.kind() == TreatmentKind.DIFFUSER_SKYLINE
                        && s.location() instanceof WallAttachment.OnSurface on
                        && on.surface() == RoomSurface.BACK_WALL))
                .as("reflective back wall should get a diffuser suggestion")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static RoomConfiguration symmetricRoom() {
        RoomDimensions dims = new RoomDimensions(4.0, 5.0, 2.8);
        RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.DRYWALL);
        // Source and mic both on the x = width/2 axis → perfectly symmetric
        // between LEFT_WALL (x=0) and RIGHT_WALL (x=width).
        config.addSoundSource(new SoundSource("Speaker",
                new Position3D(2.0, 1.0, 1.2), 85));
        config.addMicrophone(new MicrophonePlacement("Mic",
                new Position3D(2.0, 2.5, 1.2), 0, 0));
        return config;
    }

    private static AcousticTreatment findBroadbandOn(
            List<AcousticTreatment> list, RoomSurface surface) {
        return list.stream()
                .filter(t -> t.kind() == TreatmentKind.ABSORBER_BROADBAND)
                .filter(t -> t.location() instanceof WallAttachment.OnSurface on
                        && on.surface() == surface)
                .findFirst()
                .orElse(null);
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
