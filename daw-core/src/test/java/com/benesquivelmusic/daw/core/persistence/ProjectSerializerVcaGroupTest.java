package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectSerializer} writes the &lt;vca-groups&gt; section
 * required by the issue. The section is always emitted (even when empty) so
 * that future readers can rely on its presence; legacy projects without the
 * element load with no VCAs because the manager starts empty.
 */
class ProjectSerializerVcaGroupTest {

    @Test
    void shouldSerializeVcaGroupsAndMembers() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID kick = UUID.randomUUID();
        UUID snare = UUID.randomUUID();
        VcaGroup drums = project.getVcaGroupManager()
                .createVcaGroup("Drums", List.of(kick, snare));
        project.getVcaGroupManager().setMasterGainDb(drums.id(), 6.0);

        String xml = new ProjectSerializer().serialize(project);

        assertThat(xml).contains("<vca-groups>");
        assertThat(xml).contains("label=\"Drums\"");
        assertThat(xml).contains("master-gain-db=\"6.0\"");
        assertThat(xml).contains("id=\"" + drums.id() + "\"");
        assertThat(xml).contains("channel-id=\"" + kick + "\"");
        assertThat(xml).contains("channel-id=\"" + snare + "\"");
    }

    @Test
    void shouldSerializeEmptyVcaGroupsElementWhenNoGroups() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        String xml = new ProjectSerializer().serialize(project);
        // Section is always emitted for forward compatibility, even when empty.
        assertThat(xml).contains("vca-groups");
    }

    @Test
    void shouldRoundTripVcaGroups() throws IOException {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UUID kick = UUID.randomUUID();
        UUID snare = UUID.randomUUID();
        VcaGroup drums = project.getVcaGroupManager()
                .createVcaGroup("Drums", List.of(kick, snare));
        project.getVcaGroupManager().setMasterGainDb(drums.id(), 6.0);

        String xml = new ProjectSerializer().serialize(project);
        DawProject restored = new ProjectDeserializer().deserialize(xml);

        assertThat(restored.getVcaGroupManager().getVcaGroups()).hasSize(1);
        VcaGroup roundTripped = restored.getVcaGroupManager().getById(drums.id());
        assertThat(roundTripped).isNotNull();
        assertThat(roundTripped.label()).isEqualTo("Drums");
        assertThat(roundTripped.masterGainDb()).isEqualTo(6.0);
        assertThat(roundTripped.memberChannelIds()).containsExactly(kick, snare);
    }

    @Test
    void shouldLoadLegacyProjectWithoutVcaGroups() throws IOException {
        // Legacy XML that predates the <vca-groups> element. Loading must
        // succeed and the VCA manager must be empty — preserving the
        // "legacy projects load with no VCAs" goal in the issue.
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
        assertThat(restored.getVcaGroupManager().getVcaGroups()).isEmpty();
    }
}
