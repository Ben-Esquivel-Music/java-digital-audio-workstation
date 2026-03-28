package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSerializerTest {

    @Test
    void shouldSerializeEmptyProject() throws IOException {
        DawProject project = new DawProject("Empty", AudioFormat.CD_QUALITY);
        ProjectSerializer serializer = new ProjectSerializer();

        String xml = serializer.serialize(project);

        assertThat(xml).contains("<daw-project version=\"1\">");
        assertThat(xml).contains("<name>Empty</name>");
        assertThat(xml).contains("sample-rate=\"44100.0\"");
        assertThat(xml).contains("channels=\"2\"");
        assertThat(xml).contains("bit-depth=\"16\"");
        assertThat(xml).contains("buffer-size=\"512\"");
    }

    @Test
    void shouldSerializeProjectMetadata() throws IOException {
        DawProject project = new DawProject("My Song", AudioFormat.STUDIO_QUALITY);
        ProjectSerializer serializer = new ProjectSerializer();

        String xml = serializer.serialize(project);

        assertThat(xml).contains("<name>My Song</name>");
        assertThat(xml).contains("<created-at>");
        assertThat(xml).contains("<last-modified>");
    }

    @Test
    void shouldSerializeTransportSettings() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.getTransport().setTempo(140.0);
        project.getTransport().setTimeSignature(3, 4);
        project.getTransport().setLoopEnabled(true);
        project.getTransport().setLoopRegion(4.0, 32.0);
        project.getTransport().setPositionInBeats(8.0);

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("tempo=\"140.0\"");
        assertThat(xml).contains("time-sig-numerator=\"3\"");
        assertThat(xml).contains("time-sig-denominator=\"4\"");
        assertThat(xml).contains("loop-enabled=\"true\"");
        assertThat(xml).contains("loop-start=\"4.0\"");
        assertThat(xml).contains("loop-end=\"32.0\"");
        assertThat(xml).contains("position=\"8.0\"");
    }

    @Test
    void shouldSerializeTracks() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track audio = project.createAudioTrack("Vocals");
        audio.setVolume(0.8);
        audio.setPan(-0.3);
        audio.setMuted(true);
        audio.setSolo(false);
        audio.setArmed(true);

        Track midi = project.createMidiTrack("Synth");

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("name=\"Vocals\"");
        assertThat(xml).contains("type=\"AUDIO\"");
        assertThat(xml).contains("volume=\"0.8\"");
        assertThat(xml).contains("pan=\"-0.3\"");
        assertThat(xml).contains("muted=\"true\"");
        assertThat(xml).contains("armed=\"true\"");
        assertThat(xml).contains("name=\"Synth\"");
        assertThat(xml).contains("type=\"MIDI\"");
    }

    @Test
    void shouldSerializeAudioClips() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Vocals");

        AudioClip clip = new AudioClip("Take 1", 4.0, 8.0, "/audio/take1.wav");
        clip.setSourceOffsetBeats(1.0);
        clip.setGainDb(-3.0);
        clip.setReversed(true);
        clip.setFadeInBeats(0.5);
        clip.setFadeOutBeats(1.0);
        clip.setFadeInCurveType(FadeCurveType.EQUAL_POWER);
        clip.setFadeOutCurveType(FadeCurveType.S_CURVE);
        track.addClip(clip);

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("name=\"Take 1\"");
        assertThat(xml).contains("start-beat=\"4.0\"");
        assertThat(xml).contains("duration-beats=\"8.0\"");
        assertThat(xml).contains("source-file=\"/audio/take1.wav\"");
        assertThat(xml).contains("source-offset=\"1.0\"");
        assertThat(xml).contains("gain-db=\"-3.0\"");
        assertThat(xml).contains("reversed=\"true\"");
        assertThat(xml).contains("fade-in-beats=\"0.5\"");
        assertThat(xml).contains("fade-out-beats=\"1.0\"");
        assertThat(xml).contains("fade-in-curve=\"EQUAL_POWER\"");
        assertThat(xml).contains("fade-out-curve=\"S_CURVE\"");
    }

    @Test
    void shouldSerializeMixerSettings() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.getMixer().getMasterChannel().setVolume(0.9);
        project.getMixer().getMasterChannel().setPan(0.1);

        Track track = project.createAudioTrack("Bass");
        project.getMixerChannelForTrack(track).setVolume(0.7);
        project.getMixerChannelForTrack(track).setPan(-0.5);
        project.getMixerChannelForTrack(track).setSendLevel(0.3);
        project.getMixerChannelForTrack(track).setPhaseInverted(true);

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("<master");
        assertThat(xml).contains("<channel");
        assertThat(xml).contains("<return-bus");
    }

    @Test
    void shouldSerializeClipWithoutSourceFile() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Track 1");
        AudioClip clip = new AudioClip("Empty Clip", 0.0, 4.0, null);
        track.addClip(clip);

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("name=\"Empty Clip\"");
        assertThat(xml).doesNotContain("source-file=");
    }

    @Test
    void shouldProduceValidXml() throws IOException {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Track 1");
        project.createMidiTrack("Track 2");

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("<?xml version=\"1.0\" encoding=\"UTF-8\"");
        assertThat(xml).contains("</daw-project>");
    }
}
