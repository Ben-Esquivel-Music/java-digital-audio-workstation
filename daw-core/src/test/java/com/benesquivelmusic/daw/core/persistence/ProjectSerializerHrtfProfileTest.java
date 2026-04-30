package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests for the per-project active HRTF profile reference
 * persisted by {@link ProjectSerializer} (story 174).
 */
class ProjectSerializerHrtfProfileTest {

    @Test
    void newProjectHasNoActiveHrtfProfile() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        assertThat(project.getActiveHrtfProfileName()).isNull();
    }

    @Test
    void serializeOmitsElementWhenUnset() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).doesNotContain("active-hrtf-profile");
    }

    @Test
    void serializeEmitsElementWhenSet() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.setActiveHrtfProfileName("subject-A12");

        String xml = new ProjectSerializer().serialize(project);

        assertThat(xml).contains("<active-hrtf-profile name=\"subject-A12\"");
    }

    @Test
    void roundTripPreservesActiveHrtfProfile() throws IOException {
        for (String name : new String[]{"Medium", "subject-A12", "alice's profile"}) {
            DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
            original.setActiveHrtfProfileName(name);

            String xml = new ProjectSerializer().serialize(original);
            DawProject restored = new ProjectDeserializer().deserialize(xml);

            assertThat(restored.getActiveHrtfProfileName())
                    .as("round-trip for %s", name)
                    .isEqualTo(name);
        }
    }

    @Test
    void legacyProjectsWithoutElementLoadAsNull() throws IOException {
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                  <metadata><name>Legacy</name></metadata>
                  <audio-format sample-rate="44100.0" channels="2" bit-depth="16" buffer-size="512"/>
                </daw-project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(legacyXml);

        assertThat(restored.getActiveHrtfProfileName()).isNull();
    }

    @Test
    void blankNameIsTreatedAsCleared() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.setActiveHrtfProfileName("Medium");
        project.setActiveHrtfProfileName("   ");
        assertThat(project.getActiveHrtfProfileName()).isNull();
    }
}
