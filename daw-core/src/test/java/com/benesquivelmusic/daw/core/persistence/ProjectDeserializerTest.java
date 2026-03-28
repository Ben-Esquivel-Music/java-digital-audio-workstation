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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ProjectDeserializerTest {

    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void shouldDeserializeEmptyProject() throws IOException {
        DawProject original = new DawProject("Empty Project", AudioFormat.CD_QUALITY);
        String xml = serializer.serialize(original);

        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getName()).isEqualTo("Empty Project");
        assertThat(restored.getFormat().sampleRate()).isEqualTo(44100.0);
        assertThat(restored.getFormat().channels()).isEqualTo(2);
        assertThat(restored.getFormat().bitDepth()).isEqualTo(16);
        assertThat(restored.getFormat().bufferSize()).isEqualTo(512);
        assertThat(restored.getTracks()).isEmpty();
    }

    @Test
    void shouldDeserializeProjectMetadata() throws IOException {
        DawProject original = new DawProject("My Song", AudioFormat.STUDIO_QUALITY);
        String xml = serializer.serialize(original);

        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getName()).isEqualTo("My Song");
        assertThat(restored.getMetadata().name()).isEqualTo("My Song");
        assertThat(restored.getMetadata().createdAt()).isEqualTo(original.getMetadata().createdAt());
        assertThat(restored.getMetadata().lastModified()).isEqualTo(original.getMetadata().lastModified());
    }

    @Test
    void shouldDeserializeTransportSettings() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.getTransport().setTempo(140.0);
        original.getTransport().setTimeSignature(3, 4);
        original.getTransport().setLoopEnabled(true);
        original.getTransport().setLoopRegion(4.0, 32.0);
        original.getTransport().setPositionInBeats(8.0);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTransport().getTempo()).isEqualTo(140.0);
        assertThat(restored.getTransport().getTimeSignatureNumerator()).isEqualTo(3);
        assertThat(restored.getTransport().getTimeSignatureDenominator()).isEqualTo(4);
        assertThat(restored.getTransport().isLoopEnabled()).isTrue();
        assertThat(restored.getTransport().getLoopStartInBeats()).isEqualTo(4.0);
        assertThat(restored.getTransport().getLoopEndInBeats()).isEqualTo(32.0);
        assertThat(restored.getTransport().getPositionInBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldDeserializeTracks() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track audio = original.createAudioTrack("Vocals");
        audio.setVolume(0.8);
        audio.setPan(-0.3);
        audio.setMuted(true);
        audio.setArmed(true);
        audio.setPhaseInverted(true);

        Track midi = original.createMidiTrack("Synth");
        midi.setSolo(true);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks()).hasSize(2);

        Track restoredAudio = restored.getTracks().get(0);
        assertThat(restoredAudio.getName()).isEqualTo("Vocals");
        assertThat(restoredAudio.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(restoredAudio.getVolume()).isCloseTo(0.8, within(0.001));
        assertThat(restoredAudio.getPan()).isCloseTo(-0.3, within(0.001));
        assertThat(restoredAudio.isMuted()).isTrue();
        assertThat(restoredAudio.isArmed()).isTrue();
        assertThat(restoredAudio.isPhaseInverted()).isTrue();

        Track restoredMidi = restored.getTracks().get(1);
        assertThat(restoredMidi.getName()).isEqualTo("Synth");
        assertThat(restoredMidi.getType()).isEqualTo(TrackType.MIDI);
        assertThat(restoredMidi.isSolo()).isTrue();
    }

    @Test
    void shouldDeserializeAudioClips() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Vocals");

        AudioClip clip = new AudioClip("Take 1", 4.0, 8.0, "/audio/take1.wav");
        clip.setSourceOffsetBeats(1.0);
        clip.setGainDb(-3.0);
        clip.setReversed(true);
        clip.setFadeInBeats(0.5);
        clip.setFadeOutBeats(1.0);
        clip.setFadeInCurveType(FadeCurveType.EQUAL_POWER);
        clip.setFadeOutCurveType(FadeCurveType.S_CURVE);
        track.addClip(clip);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getClips()).hasSize(1);
        AudioClip restoredClip = restored.getTracks().get(0).getClips().get(0);

        assertThat(restoredClip.getName()).isEqualTo("Take 1");
        assertThat(restoredClip.getStartBeat()).isEqualTo(4.0);
        assertThat(restoredClip.getDurationBeats()).isEqualTo(8.0);
        assertThat(restoredClip.getSourceFilePath()).isEqualTo("/audio/take1.wav");
        assertThat(restoredClip.getSourceOffsetBeats()).isEqualTo(1.0);
        assertThat(restoredClip.getGainDb()).isEqualTo(-3.0);
        assertThat(restoredClip.isReversed()).isTrue();
        assertThat(restoredClip.getFadeInBeats()).isEqualTo(0.5);
        assertThat(restoredClip.getFadeOutBeats()).isEqualTo(1.0);
        assertThat(restoredClip.getFadeInCurveType()).isEqualTo(FadeCurveType.EQUAL_POWER);
        assertThat(restoredClip.getFadeOutCurveType()).isEqualTo(FadeCurveType.S_CURVE);
    }

    @Test
    void shouldDeserializeMixerSettings() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.getMixer().getMasterChannel().setVolume(0.9);
        original.getMixer().getMasterChannel().setPan(0.1);
        original.getMixer().getMasterChannel().setMuted(true);

        Track track = original.createAudioTrack("Bass");
        original.getMixerChannelForTrack(track).setVolume(0.7);
        original.getMixerChannelForTrack(track).setPan(-0.5);
        original.getMixerChannelForTrack(track).setSendLevel(0.3);
        original.getMixerChannelForTrack(track).setPhaseInverted(true);
        original.getMixerChannelForTrack(track).setMuted(true);
        original.getMixerChannelForTrack(track).setSolo(true);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getMixer().getMasterChannel().getVolume()).isCloseTo(0.9, within(0.001));
        assertThat(restored.getMixer().getMasterChannel().getPan()).isCloseTo(0.1, within(0.001));
        assertThat(restored.getMixer().getMasterChannel().isMuted()).isTrue();

        assertThat(restored.getMixer().getChannels()).hasSize(1);
        assertThat(restored.getMixer().getChannels().get(0).getVolume()).isCloseTo(0.7, within(0.001));
        assertThat(restored.getMixer().getChannels().get(0).getPan()).isCloseTo(-0.5, within(0.001));
        assertThat(restored.getMixer().getChannels().get(0).getSendLevel()).isCloseTo(0.3, within(0.001));
        assertThat(restored.getMixer().getChannels().get(0).isPhaseInverted()).isTrue();
        assertThat(restored.getMixer().getChannels().get(0).isMuted()).isTrue();
        assertThat(restored.getMixer().getChannels().get(0).isSolo()).isTrue();
    }

    @Test
    void shouldDeserializeReturnBuses() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.getMixer().getAuxBus().setVolume(0.6);
        original.getMixer().addReturnBus("Delay Return");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getMixer().getReturnBuses()).hasSize(2);
        assertThat(restored.getMixer().getReturnBuses().get(0).getVolume()).isCloseTo(0.6, within(0.001));
    }

    @Test
    void shouldDeserializeStudioQualityFormat() throws IOException {
        DawProject original = new DawProject("Studio", AudioFormat.STUDIO_QUALITY);
        String xml = serializer.serialize(original);

        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getFormat().sampleRate()).isEqualTo(96000.0);
        assertThat(restored.getFormat().channels()).isEqualTo(2);
        assertThat(restored.getFormat().bitDepth()).isEqualTo(24);
        assertThat(restored.getFormat().bufferSize()).isEqualTo(256);
    }

    @Test
    void shouldRoundTripComplexProject() throws IOException {
        DawProject original = new DawProject("Complex Session", AudioFormat.STUDIO_QUALITY);
        original.getTransport().setTempo(95.0);
        original.getTransport().setTimeSignature(6, 8);
        original.getTransport().setLoopEnabled(true);
        original.getTransport().setLoopRegion(8.0, 64.0);

        Track drums = original.createAudioTrack("Drums");
        drums.setVolume(0.9);
        drums.addClip(new AudioClip("Kick Pattern", 0.0, 16.0, "/audio/kick.wav"));
        drums.addClip(new AudioClip("Snare Pattern", 0.0, 16.0, "/audio/snare.wav"));

        Track bass = original.createAudioTrack("Bass");
        bass.setVolume(0.7);
        bass.setPan(-0.2);

        Track vocals = original.createMidiTrack("Lead Vocal");
        vocals.setMuted(true);

        original.getMixer().getMasterChannel().setVolume(0.85);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getName()).isEqualTo("Complex Session");
        assertThat(restored.getTransport().getTempo()).isEqualTo(95.0);
        assertThat(restored.getTransport().getTimeSignatureNumerator()).isEqualTo(6);
        assertThat(restored.getTransport().getTimeSignatureDenominator()).isEqualTo(8);
        assertThat(restored.getTransport().isLoopEnabled()).isTrue();
        assertThat(restored.getTransport().getLoopStartInBeats()).isEqualTo(8.0);
        assertThat(restored.getTransport().getLoopEndInBeats()).isEqualTo(64.0);
        assertThat(restored.getTracks()).hasSize(3);
        assertThat(restored.getTracks().get(0).getName()).isEqualTo("Drums");
        assertThat(restored.getTracks().get(0).getClips()).hasSize(2);
        assertThat(restored.getTracks().get(1).getName()).isEqualTo("Bass");
        assertThat(restored.getTracks().get(2).getName()).isEqualTo("Lead Vocal");
        assertThat(restored.getTracks().get(2).isMuted()).isTrue();
        assertThat(restored.getMixer().getMasterChannel().getVolume()).isCloseTo(0.85, within(0.001));
    }

    @Test
    void shouldDeserializeClipWithoutSourceFile() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Track 1");
        track.addClip(new AudioClip("Empty", 0.0, 4.0, null));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        AudioClip restoredClip = restored.getTracks().get(0).getClips().get(0);
        assertThat(restoredClip.getSourceFilePath()).isNull();
    }

    @Test
    void shouldRejectInvalidXml() {
        ProjectDeserializer deserializer = new ProjectDeserializer();

        assertThatThrownBy(() -> deserializer.deserialize("not valid xml"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldHandleMissingOptionalElements() throws IOException {
        String minimalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                  <metadata>
                    <name>Minimal</name>
                  </metadata>
                </daw-project>
                """;

        DawProject restored = deserializer.deserialize(minimalXml);

        assertThat(restored.getName()).isEqualTo("Minimal");
        assertThat(restored.getTracks()).isEmpty();
        assertThat(restored.getTransport().getTempo()).isEqualTo(120.0);
    }
}
