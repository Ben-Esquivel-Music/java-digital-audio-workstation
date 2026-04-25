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
}
