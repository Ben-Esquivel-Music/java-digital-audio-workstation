package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip persistence coverage for applied {@link AcousticTreatment}s.
 * Treatment placement hints that a user marks as &quot;applied&quot; must
 * survive save/load so subsequent advisor analyses honour the installed
 * panels.
 */
class AcousticTreatmentPersistenceTest {

    @Test
    void roundTripPreservesOnSurfaceTreatment() throws IOException {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4, 5, 2.8), WallMaterial.DRYWALL);
        AcousticTreatment onWall = new AcousticTreatment(
                TreatmentKind.ABSORBER_BROADBAND,
                new WallAttachment.OnSurface(RoomSurface.LEFT_WALL, 2.5, 1.2),
                new Rectangle2D.Double(-0.3, -0.6, 0.6, 1.2),
                1.5);
        config.addAppliedTreatment(onWall);

        DawProject project = new DawProject("P", AudioFormat.CD_QUALITY);
        project.setRoomConfiguration(config);

        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).contains("<applied-treatment");
        assertThat(xml).contains("kind=\"ABSORBER_BROADBAND\"");
        assertThat(xml).contains("surface=\"LEFT_WALL\"");

        DawProject loaded = new ProjectDeserializer()
                .deserialize(xml);
        RoomConfiguration loadedConfig = loaded.getRoomConfiguration();
        assertThat(loadedConfig.getAppliedTreatments()).hasSize(1);
        AcousticTreatment loadedTreatment = loadedConfig.getAppliedTreatments().get(0);
        assertThat(loadedTreatment.kind()).isEqualTo(TreatmentKind.ABSORBER_BROADBAND);
        assertThat(loadedTreatment.predictedImprovementLufs()).isEqualTo(1.5);
        assertThat(loadedTreatment.location())
                .isInstanceOf(WallAttachment.OnSurface.class);
        WallAttachment.OnSurface on = (WallAttachment.OnSurface) loadedTreatment.location();
        assertThat(on.surface()).isEqualTo(RoomSurface.LEFT_WALL);
        assertThat(on.u()).isEqualTo(2.5);
        assertThat(on.v()).isEqualTo(1.2);
        assertThat(loadedTreatment.sizeMeters().getWidth()).isEqualTo(0.6);
        assertThat(loadedTreatment.sizeMeters().getHeight()).isEqualTo(1.2);
    }

    @Test
    void roundTripPreservesInCornerTreatment() throws IOException {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(3, 4, 2.5), WallMaterial.CONCRETE);
        config.addAppliedTreatment(new AcousticTreatment(
                TreatmentKind.ABSORBER_LF_TRAP,
                new WallAttachment.InCorner(
                        RoomSurface.FRONT_WALL, RoomSurface.LEFT_WALL, 1.25),
                new Rectangle2D.Double(-0.3, -0.9, 0.6, 1.8),
                2.1));

        DawProject project = new DawProject("P", AudioFormat.CD_QUALITY);
        project.setRoomConfiguration(config);
        String xml = new ProjectSerializer().serialize(project);
        DawProject loaded = new ProjectDeserializer()
                .deserialize(xml);

        assertThat(loaded.getRoomConfiguration().getAppliedTreatments()).hasSize(1);
        AcousticTreatment t = loaded.getRoomConfiguration().getAppliedTreatments().get(0);
        assertThat(t.kind()).isEqualTo(TreatmentKind.ABSORBER_LF_TRAP);
        assertThat(t.location()).isInstanceOf(WallAttachment.InCorner.class);
        WallAttachment.InCorner c = (WallAttachment.InCorner) t.location();
        assertThat(c.surfaceA()).isEqualTo(RoomSurface.FRONT_WALL);
        assertThat(c.surfaceB()).isEqualTo(RoomSurface.LEFT_WALL);
        assertThat(c.z()).isEqualTo(1.25);
    }
}
