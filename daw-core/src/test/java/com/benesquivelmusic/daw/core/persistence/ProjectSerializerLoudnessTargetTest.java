package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSerializerLoudnessTargetTest {

    @Test
    void newProjectShouldDefaultToSpotifyTarget() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThat(project.getLoudnessTarget()).isEqualTo(LoudnessTarget.SPOTIFY);
    }

    @Test
    void serializeShouldEmitLoudnessTargetElement() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.setLoudnessTarget(LoudnessTarget.APPLE_MUSIC);

        String xml = new ProjectSerializer().serialize(project);

        assertThat(xml).contains("<loudness-target value=\"APPLE_MUSIC\"");
    }

    @Test
    void roundTripShouldPreserveLoudnessTarget() throws IOException {
        for (LoudnessTarget target : LoudnessTarget.values()) {
            DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
            original.setLoudnessTarget(target);

            String xml = new ProjectSerializer().serialize(original);
            DawProject restored = new ProjectDeserializer().deserialize(xml);

            assertThat(restored.getLoudnessTarget())
                    .as("round-trip for %s", target)
                    .isEqualTo(target);
        }
    }

    @Test
    void deserializeShouldDefaultToSpotifyWhenElementMissing() throws IOException {
        // XML produced before this element existed must still load and
        // default to the Spotify target.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                  <metadata><name>Legacy</name></metadata>
                  <audio-format sample-rate="44100.0" channels="2" bit-depth="16" buffer-size="512"/>
                </daw-project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(legacyXml);

        assertThat(restored.getLoudnessTarget()).isEqualTo(LoudnessTarget.SPOTIFY);
    }

    @Test
    void deserializeShouldFallBackToDefaultForUnknownValue() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                  <metadata><name>BadValue</name></metadata>
                  <audio-format sample-rate="44100.0" channels="2" bit-depth="16" buffer-size="512"/>
                  <loudness-target value="DOES_NOT_EXIST"/>
                </daw-project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(xml);

        assertThat(restored.getLoudnessTarget()).isEqualTo(LoudnessTarget.SPOTIFY);
    }
}
