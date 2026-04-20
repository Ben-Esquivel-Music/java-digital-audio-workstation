package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SourceDirectivity;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip persistence coverage for per-source
 * {@link SourceDirectivity}. Legacy projects without a {@code directivity}
 * attribute must deserialize to the {@link SourceDirectivity#OMNIDIRECTIONAL}
 * default; explicit non-omni patterns must survive save/load.
 */
class SourceDirectivityPersistenceTest {

    @Test
    void roundTripPreservesExplicitDirectivity() throws IOException {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4, 5, 2.8), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource(
                "Vocal", new Position3D(2, 1.5, 1.2), 85.0));
        config.addSoundSource(new SoundSource(
                "Ambience", new Position3D(3, 4, 1.2), 75.0));
        config.setSourceDirectivity("Vocal", SourceDirectivity.CARDIOID);
        // Ambience left at default OMNIDIRECTIONAL.

        DawProject project = new DawProject("P", AudioFormat.CD_QUALITY);
        project.setRoomConfiguration(config);

        String xml = new ProjectSerializer().serialize(project);
        // Non-default directivity is serialized; default is omitted for
        // backwards compatibility.
        assertThat(xml).contains("directivity=\"CARDIOID\"");
        assertThat(xml).doesNotContain("directivity=\"OMNIDIRECTIONAL\"");

        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        RoomConfiguration loadedConfig = loaded.getRoomConfiguration();
        assertThat(loadedConfig.getSourceDirectivity("Vocal"))
                .isEqualTo(SourceDirectivity.CARDIOID);
        assertThat(loadedConfig.getSourceDirectivity("Ambience"))
                .isEqualTo(SourceDirectivity.OMNIDIRECTIONAL);
    }

    @Test
    void legacyXmlWithoutDirectivityDefaultsToOmnidirectional() throws IOException {
        // Simulate a project.daw file written before SourceDirectivity
        // was introduced: no "directivity" attribute on <sound-source>.
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <daw-project version="1">
                    <metadata name="Legacy"/>
                    <audio-format sample-rate="44100" bit-depth="16" channels="2"/>
                    <tracks/>
                    <room-configuration width="4.0" length="5.0" height="2.8"
                                        wall-material="DRYWALL">
                        <surface-materials floor="DRYWALL" front-wall="DRYWALL"
                                           back-wall="DRYWALL" left-wall="DRYWALL"
                                           right-wall="DRYWALL" ceiling="DRYWALL"/>
                        <sound-source name="Old" x="1.0" y="1.0" z="1.2" power-db="85.0"/>
                    </room-configuration>
                </daw-project>
                """;

        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        assertThat(loaded.getRoomConfiguration()
                .getSourceDirectivity("Old"))
                .isEqualTo(SourceDirectivity.OMNIDIRECTIONAL);
    }

    @Test
    void unknownDirectivityFallsBackToOmnidirectional() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <daw-project version="1">
                    <metadata name="Forward"/>
                    <audio-format sample-rate="44100" bit-depth="16" channels="2"/>
                    <tracks/>
                    <room-configuration width="4.0" length="5.0" height="2.8"
                                        wall-material="DRYWALL">
                        <surface-materials floor="DRYWALL" front-wall="DRYWALL"
                                           back-wall="DRYWALL" left-wall="DRYWALL"
                                           right-wall="DRYWALL" ceiling="DRYWALL"/>
                        <sound-source name="Future" x="1.0" y="1.0" z="1.2"
                                      power-db="85.0" directivity="FIG_8"/>
                    </room-configuration>
                </daw-project>
                """;

        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        assertThat(loaded.getRoomConfiguration()
                .getSourceDirectivity("Future"))
                .isEqualTo(SourceDirectivity.OMNIDIRECTIONAL);
    }
}
