package com.benesquivelmusic.daw.app.ui.telemetry;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the static, non-JavaFX helpers of {@link TreatmentSuggestionPanel}.
 * The full UI is exercised by integration tests that require a display;
 * these unit tests keep the advisor-&gt;panel wiring covered without a
 * JavaFX toolkit.
 */
class TreatmentSuggestionPanelTest {

    @Test
    void explainMentionsFirstReflectionForBroadbandAbsorber() {
        AcousticTreatment t = new AcousticTreatment(
                TreatmentKind.ABSORBER_BROADBAND,
                new WallAttachment.OnSurface(RoomSurface.LEFT_WALL, 2.5, 1.2),
                new Rectangle2D.Double(-0.3, -0.6, 0.6, 1.2),
                1.5);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4, 5, 2.8), WallMaterial.DRYWALL);

        String why = TreatmentSuggestionPanel.explain(t, config);

        assertThat(why).contains("first-reflection");
        assertThat(why).contains("left wall");
    }

    @Test
    void explainMentionsCornerForLfTrap() {
        AcousticTreatment trap = new AcousticTreatment(
                TreatmentKind.ABSORBER_LF_TRAP,
                new WallAttachment.InCorner(RoomSurface.FRONT_WALL, RoomSurface.LEFT_WALL, 1.25),
                new Rectangle2D.Double(-0.3, -0.9, 0.6, 1.8),
                2.1);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(3, 4, 2.5), WallMaterial.CONCRETE);

        assertThat(TreatmentSuggestionPanel.explain(trap, config))
                .contains("corner");
    }

    @Test
    void explainMentionsFlutterForDiffuser() {
        AcousticTreatment d = new AcousticTreatment(
                TreatmentKind.DIFFUSER_SKYLINE,
                new WallAttachment.OnSurface(RoomSurface.BACK_WALL, 2, 1.4),
                new Rectangle2D.Double(-0.3, -0.3, 0.6, 0.6),
                1.8);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4, 5, 2.8), WallMaterial.DRYWALL);

        assertThat(TreatmentSuggestionPanel.explain(d, config))
                .containsAnyOf("flutter", "Flutter");
    }

    @Test
    void kindTitleCoversEveryKind() {
        for (TreatmentKind k : TreatmentKind.values()) {
            assertThat(TreatmentSuggestionPanel.kindTitle(k))
                    .as("title for %s", k)
                    .isNotBlank();
        }
    }
}
