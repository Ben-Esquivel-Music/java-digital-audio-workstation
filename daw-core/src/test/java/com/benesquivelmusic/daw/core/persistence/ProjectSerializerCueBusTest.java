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
}
