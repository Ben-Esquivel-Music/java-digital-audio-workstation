package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class ClipGainEnvelopePersistenceTest {

    @Test
    void roundTripEnvelope_preservesBreakpointsAndCurves() throws IOException {
        DawProject project = new DawProject("p", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Vocals");
        AudioClip clip = new AudioClip("Take", 0.0, 4.0, "/a/b.wav");
        clip.setGainEnvelope(new ClipGainEnvelope(List.of(
                new ClipGainEnvelope.BreakpointDb(0L, 0.0, CurveShape.LINEAR),
                new ClipGainEnvelope.BreakpointDb(1024L, -6.0, CurveShape.EXPONENTIAL),
                new ClipGainEnvelope.BreakpointDb(4096L, -12.0, CurveShape.S_CURVE))));
        track.addClip(clip);

        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).contains("<gain-envelope>");
        assertThat(xml).contains("frame-offset=\"1024\"");
        assertThat(xml).contains("db-gain=\"-6.0\"");
        assertThat(xml).contains("curve=\"EXPONENTIAL\"");

        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        AudioClip restored = loaded.getTracks().getFirst().getClips().getFirst();
        assertThat(restored.gainEnvelope()).isPresent();
        var bps = restored.gainEnvelope().orElseThrow().breakpoints();
        assertThat(bps).hasSize(3);
        assertThat(bps.get(0).frameOffsetInClip()).isZero();
        assertThat(bps.get(0).curve()).isEqualTo(CurveShape.LINEAR);
        assertThat(bps.get(1).frameOffsetInClip()).isEqualTo(1024L);
        assertThat(bps.get(1).dbGain()).isCloseTo(-6.0, offset(1e-12));
        assertThat(bps.get(1).curve()).isEqualTo(CurveShape.EXPONENTIAL);
        assertThat(bps.get(2).curve()).isEqualTo(CurveShape.S_CURVE);
    }

    @Test
    void legacyClipWithoutEnvelope_loadsWithAbsentEnvelope() throws IOException {
        DawProject project = new DawProject("p", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Vocals");
        AudioClip clip = new AudioClip("Take", 0.0, 4.0, null);
        clip.setGainDb(-3.0);
        track.addClip(clip);

        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).doesNotContain("<gain-envelope>");

        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        AudioClip restored = loaded.getTracks().getFirst().getClips().getFirst();
        // Legacy scalar preserved; envelope remains absent (lazy migration).
        assertThat(restored.getGainDb()).isCloseTo(-3.0, offset(1e-12));
        assertThat(restored.gainEnvelope()).isEmpty();
    }
}
