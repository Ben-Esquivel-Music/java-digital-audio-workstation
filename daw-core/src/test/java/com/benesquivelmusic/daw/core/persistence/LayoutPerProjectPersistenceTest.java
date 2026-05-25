package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 282 acceptance criterion ({@code LayoutPerProjectPersistenceTest}):
 * the opaque {@code LayoutManager} JSON blob is round-tripped through
 * {@link ProjectSerializer} / {@link ProjectDeserializer} byte-identical,
 * and missing / blank values on legacy projects yield {@code null} (so
 * the layout manager falls back to the "Default" built-in).
 *
 * <p>{@code DawProject} treats the layout JSON as opaque — the project
 * layer never parses it. This test pins both round-trip stability and
 * legacy-project tolerance so projects saved before story 282 continue
 * to load cleanly.</p>
 */
class LayoutPerProjectPersistenceTest {

    @Test
    void layoutJsonRoundTripsThroughProjectFile(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws IOException {
        DawProject p = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        String layoutJson = "{\"current\":\"Mixing\",\"layouts\":["
                + "{\"name\":\"MyMix\",\"dock\":\"{}\"}"
                + "]}";
        p.setLayoutJson(layoutJson);

        String xml = new ProjectSerializer().serialize(p);
        DawProject reloaded = new ProjectDeserializer().deserialize(xml);

        assertThat(reloaded.getLayoutJson()).isEqualTo(layoutJson);
    }

    @Test
    void missingLayoutElementYieldsNullForLegacyProjects() throws IOException {
        DawProject p = new DawProject("Legacy", AudioFormat.STUDIO_QUALITY);
        // Don't set any layout JSON — emulates a project saved before
        // story 282 introduced the <layout> element.
        String xml = new ProjectSerializer().serialize(p);
        // The serialiser must omit the <layout> element when none is
        // set, so legacy round-trips are byte-stable.
        assertThat(xml).doesNotContain("<layout>");

        DawProject reloaded = new ProjectDeserializer().deserialize(xml);
        assertThat(reloaded.getLayoutJson()).isNull();
    }

    @Test
    void blankLayoutJsonIsTreatedAsAbsent() {
        DawProject p = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        p.setLayoutJson("   ");
        assertThat(p.getLayoutJson()).isNull();
        p.setLayoutJson("");
        assertThat(p.getLayoutJson()).isNull();
        p.setLayoutJson(null);
        assertThat(p.getLayoutJson()).isNull();
    }

    @Test
    void layoutJsonSurvivesAcrossMultipleSaveLoadCycles() throws IOException {
        // Project A: "Mixing" layout.
        DawProject a = new DawProject("A", AudioFormat.STUDIO_QUALITY);
        a.setLayoutJson("{\"current\":\"Mixing\",\"layouts\":[]}");
        String xmlA = new ProjectSerializer().serialize(a);

        // Project B: "Tracking" layout — switching projects must not
        // contaminate A's persisted state.
        DawProject b = new DawProject("B", AudioFormat.STUDIO_QUALITY);
        b.setLayoutJson("{\"current\":\"Tracking\",\"layouts\":[]}");
        String xmlB = new ProjectSerializer().serialize(b);

        // Switch back to A.
        DawProject reloadedA = new ProjectDeserializer().deserialize(xmlA);
        assertThat(reloadedA.getLayoutJson()).contains("\"current\":\"Mixing\"");

        DawProject reloadedB = new ProjectDeserializer().deserialize(xmlB);
        assertThat(reloadedB.getLayoutJson()).contains("\"current\":\"Tracking\"");
    }
}
