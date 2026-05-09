package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueSend;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSerializerCueBusTest {

    @Test
    void shouldSerializeCueBusesAndSends() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        CueBus bus = project.getCueBusManager().createCueBus("Singer", 1);
        UUID vocals = UUID.randomUUID();
        project.getCueBusManager().replace(
                bus.withSend(new CueSend(vocals, 0.8, -0.2, true)));

        String xml = new ProjectSerializer().serialize(project);

        assertThat(xml).contains("<cue-buses>");
        assertThat(xml).contains("label=\"Singer\"");
        assertThat(xml).contains("hardware-output-index=\"1\"");
        assertThat(xml).contains("master-gain=\"1.0\"");
        assertThat(xml).contains("track-id=\"" + vocals + "\"");
        assertThat(xml).contains("gain=\"0.8\"");
        assertThat(xml).contains("pan=\"-0.2\"");
        assertThat(xml).contains("pre-fader=\"true\"");
    }

    @Test
    void shouldSerializeEmptyCueBusesElementWhenNoBuses() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);

        String xml = new ProjectSerializer().serialize(project);
        // Section is always emitted for forward compatibility, even when empty.
        assertThat(xml).contains("cue-buses");
    }

    @Test
    void shouldRoundTripCueBusesAndSends() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID vocals = UUID.randomUUID();
        UUID drums = UUID.randomUUID();
        CueBus singerBus = project.getCueBusManager().createCueBus("Singer", 1);
        project.getCueBusManager().replace(singerBus
                .withSend(new CueSend(vocals, 0.85, -0.25, true))
                .withSend(new CueSend(drums, 0.40, 0.10, false))
                .withMasterGain(0.9));
        CueBus drummerBus = project.getCueBusManager().createCueBus("Drummer", 2);

        String xml = new ProjectSerializer().serialize(project);
        DawProject restored = new ProjectDeserializer().deserialize(xml);

        assertThat(restored.getCueBusManager().getCueBuses()).hasSize(2);
        CueBus restoredSinger = restored.getCueBusManager().getById(singerBus.id());
        assertThat(restoredSinger).isNotNull();
        assertThat(restoredSinger.label()).isEqualTo("Singer");
        assertThat(restoredSinger.hardwareOutputIndex()).isEqualTo(1);
        assertThat(restoredSinger.masterGain()).isEqualTo(0.9);
        assertThat(restoredSinger.sends()).hasSize(2);
        CueSend restoredVocals = restoredSinger.findSend(vocals);
        assertThat(restoredVocals).isNotNull();
        assertThat(restoredVocals.gain()).isEqualTo(0.85);
        assertThat(restoredVocals.pan()).isEqualTo(-0.25);
        assertThat(restoredVocals.preFader()).isTrue();
        CueSend restoredDrums = restoredSinger.findSend(drums);
        assertThat(restoredDrums).isNotNull();
        assertThat(restoredDrums.preFader()).isFalse();

        CueBus restoredDrummer = restored.getCueBusManager().getById(drummerBus.id());
        assertThat(restoredDrummer).isNotNull();
        assertThat(restoredDrummer.hardwareOutputIndex()).isEqualTo(2);
        assertThat(restoredDrummer.sends()).isEmpty();
    }

    @Test
    void shouldLoadLegacyProjectWithoutCueBuses() throws IOException {
        // Legacy XML predating the <cue-buses> element. Loading must succeed
        // and the cue bus manager must be empty.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <daw-project version="1">
                    <metadata><name>Legacy</name></metadata>
                    <audio-format sample-rate="44100.0" channels="2" bit-depth="16"/>
                    <transport position="0.0" tempo="120.0" looping="false" loop-start="0.0" loop-end="0.0"/>
                    <tracks/>
                    <mixer>
                        <master name="Master" volume="1.0" pan="0.0" muted="false" solo="false" send-level="0.0" phase-inverted="false"/>
                        <channels/>
                        <return-buses/>
                    </mixer>
                </daw-project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(legacyXml);
        assertThat(restored.getCueBusManager().getCueBuses()).isEmpty();
    }
}
