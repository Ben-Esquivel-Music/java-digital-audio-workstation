package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that per-track input monitoring mode survives a serialize /
 * deserialize round-trip, and that projects saved before monitoring
 * was persisted default to {@link InputMonitoringMode#AUTO} on load.
 */
class InputMonitoringPersistenceTest {

    @Test
    void shouldRoundTripPerTrackMonitoringMode() throws IOException {
        DawProject project = new DawProject("Monitoring Modes", AudioFormat.CD_QUALITY);
        Track vocal = project.createAudioTrack("Vocal");
        vocal.setInputMonitoring(InputMonitoringMode.TAPE);

        Track synth = project.createAudioTrack("Synth");
        synth.setInputMonitoring(InputMonitoringMode.ALWAYS);

        Track kick = project.createAudioTrack("Kick");
        kick.setInputMonitoring(InputMonitoringMode.OFF);

        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).contains("input-monitoring=\"TAPE\"");
        assertThat(xml).contains("input-monitoring=\"ALWAYS\"");
        assertThat(xml).contains("input-monitoring=\"OFF\"");

        DawProject restored = new ProjectDeserializer().deserialize(xml);
        Track[] tracks = restored.getTracks().toArray(new Track[0]);
        assertThat(tracks[0].getInputMonitoring()).isEqualTo(InputMonitoringMode.TAPE);
        assertThat(tracks[1].getInputMonitoring()).isEqualTo(InputMonitoringMode.ALWAYS);
        assertThat(tracks[2].getInputMonitoring()).isEqualTo(InputMonitoringMode.OFF);
    }

    @Test
    void olderProjectsWithoutAttributeDefaultToAuto() throws IOException {
        // Simulate an older project file by serializing, then stripping
        // the input-monitoring attribute to mimic pre-feature XML.
        DawProject project = new DawProject("Legacy", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        String xml = new ProjectSerializer().serialize(project);
        String legacyXml = xml.replaceAll(" input-monitoring=\"[^\"]*\"", "");
        assertThat(legacyXml).doesNotContain("input-monitoring=");

        DawProject restored = new ProjectDeserializer().deserialize(legacyXml);
        assertThat(restored.getTracks().getFirst().getInputMonitoring())
                .isEqualTo(InputMonitoringMode.AUTO);
    }

    @Test
    void unknownMonitoringValueFallsBackToAuto() throws IOException {
        DawProject project = new DawProject("Bad Value", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        String xml = new ProjectSerializer().serialize(project);
        String broken = xml.replaceAll("input-monitoring=\"[^\"]*\"",
                "input-monitoring=\"NOT_A_MODE\"");

        DawProject restored = new ProjectDeserializer().deserialize(broken);
        assertThat(restored.getTracks().getFirst().getInputMonitoring())
                .isEqualTo(InputMonitoringMode.AUTO);
    }
}
