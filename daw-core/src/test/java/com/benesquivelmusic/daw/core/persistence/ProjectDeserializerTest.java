package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

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
    void shouldDeserializeSoloSafeFlag() throws IOException {
        // Round-trip: track flagged as solo-safe stays solo-safe; reverb
        // return whose default solo-safe is overridden to false stays off.
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Vocal");
        original.getMixerChannelForTrack(track).setSoloSafe(true);
        original.getMixer().getAuxBus().setSoloSafe(false);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getMixer().getChannels().get(0).isSoloSafe()).isTrue();
        assertThat(restored.getMixer().getAuxBus().isSoloSafe()).isFalse();
    }

    @Test
    void legacyProjectsWithoutSoloSafeAttributeUseDefaults() throws IOException {
        // A pre-issue-XXX project XML has no solo-safe attribute. The
        // deserializer must keep the construction-time defaults (return bus
        // = solo-safe, track channel = not solo-safe).
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <project name="Legacy" sampleRate="44100.0" channels="2" bitDepth="16" bufferSize="512">
                  <metadata>
                    <name>Legacy</name>
                    <createdAt>2024-01-01T00:00:00Z</createdAt>
                    <lastModified>2024-01-01T00:00:00Z</lastModified>
                  </metadata>
                  <tracks>
                    <track name="Vocal" type="AUDIO" volume="1.0" pan="0.0" muted="false" solo="false"/>
                  </tracks>
                  <mixer>
                    <master name="Master" volume="1.0" pan="0.0" muted="false" solo="false" send-level="0.0" phase-inverted="false"/>
                    <return-buses>
                      <return-bus name="Reverb Return" volume="1.0" pan="0.0" muted="false" solo="false" send-level="0.0" phase-inverted="false"/>
                    </return-buses>
                    <channels>
                      <channel name="Vocal" volume="1.0" pan="0.0" muted="false" solo="false" send-level="0.0" phase-inverted="false"/>
                    </channels>
                  </mixer>
                </project>
                """;

        DawProject restored = deserializer.deserialize(legacyXml);

        assertThat(restored.getMixer().getAuxBus().isSoloSafe())
                .as("legacy reverb return defaults to solo-safe")
                .isTrue();
        assertThat(restored.getMixer().getChannels().get(0).isSoloSafe())
                .as("legacy track channel defaults to not solo-safe")
                .isFalse();
        assertThat(restored.getMixer().getMasterChannel().isSoloSafe())
                .as("legacy master defaults to not solo-safe")
                .isFalse();
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

    @Test
    void shouldDeserializeSoundFontAssignment() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track midi = original.createMidiTrack("Piano");
        midi.setSoundFontAssignment(new SoundFontAssignment(
                Path.of("/sounds/GeneralUser.sf2"), 0, 0, "Acoustic Grand Piano"));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        Track restoredMidi = restored.getTracks().get(0);
        assertThat(restoredMidi.getSoundFontAssignment()).isNotNull();
        assertThat(restoredMidi.getSoundFontAssignment().soundFontPath())
                .isEqualTo(Path.of("/sounds/GeneralUser.sf2"));
        assertThat(restoredMidi.getSoundFontAssignment().bank()).isZero();
        assertThat(restoredMidi.getSoundFontAssignment().program()).isZero();
        assertThat(restoredMidi.getSoundFontAssignment().presetName())
                .isEqualTo("Acoustic Grand Piano");
    }

    @Test
    void shouldDeserializeTrackWithoutSoundFontAssignment() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.createMidiTrack("Synth");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getSoundFontAssignment()).isNull();
    }

    @Test
    void shouldRoundTripSoundFontAssignment() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track midi = original.createMidiTrack("Strings");
        SoundFontAssignment assignment = new SoundFontAssignment(
                Path.of("/sf2/FluidR3_GM.sf2"), 0, 48, "String Ensemble 1");
        midi.setSoundFontAssignment(assignment);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getSoundFontAssignment())
                .isEqualTo(assignment);
    }

    @Test
    void shouldRoundTripRoomConfiguration() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(12, 9, 4), WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addSoundSource(new SoundSource("Vocals", new Position3D(5, 2, 1.5), 75));
        config.addMicrophone(new MicrophonePlacement("Overhead", new Position3D(4, 4, 2), 45, 10));
        config.addAudienceMember(new AudienceMember("Row 1", new Position3D(2, 7, 0)));
        original.setRoomConfiguration(config);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        RoomConfiguration restoredConfig = restored.getRoomConfiguration();
        assertThat(restoredConfig).isNotNull();
        assertThat(restoredConfig.getDimensions().width()).isEqualTo(12);
        assertThat(restoredConfig.getDimensions().length()).isEqualTo(9);
        assertThat(restoredConfig.getDimensions().height()).isEqualTo(4);
        assertThat(restoredConfig.getWallMaterial()).isEqualTo(WallMaterial.CONCRETE);
        assertThat(restoredConfig.getSoundSources()).hasSize(2);
        assertThat(restoredConfig.getSoundSources().get(0).name()).isEqualTo("Guitar");
        assertThat(restoredConfig.getSoundSources().get(0).position().x()).isEqualTo(3);
        assertThat(restoredConfig.getSoundSources().get(0).powerDb()).isEqualTo(85);
        assertThat(restoredConfig.getSoundSources().get(1).name()).isEqualTo("Vocals");
        assertThat(restoredConfig.getMicrophones()).hasSize(1);
        assertThat(restoredConfig.getMicrophones().get(0).name()).isEqualTo("Overhead");
        assertThat(restoredConfig.getMicrophones().get(0).azimuth()).isCloseTo(45.0, within(0.01));
        assertThat(restoredConfig.getMicrophones().get(0).elevation()).isCloseTo(10.0, within(0.01));
        assertThat(restoredConfig.getAudienceMembers()).hasSize(1);
        assertThat(restoredConfig.getAudienceMembers().get(0).name()).isEqualTo("Row 1");
    }

    @Test
    void shouldDeserializeProjectWithoutRoomConfiguration() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getRoomConfiguration()).isNull();
    }

    @Test
    void shouldRoundTripRoomConfigurationWithNoSourcesOrMics() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(5, 4, 2.5), WallMaterial.ACOUSTIC_FOAM);
        original.setRoomConfiguration(config);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        RoomConfiguration restoredConfig = restored.getRoomConfiguration();
        assertThat(restoredConfig).isNotNull();
        assertThat(restoredConfig.getDimensions().width()).isEqualTo(5);
        assertThat(restoredConfig.getWallMaterial()).isEqualTo(WallMaterial.ACOUSTIC_FOAM);
        assertThat(restoredConfig.getSoundSources()).isEmpty();
        assertThat(restoredConfig.getMicrophones()).isEmpty();
        assertThat(restoredConfig.getAudienceMembers()).isEmpty();
    }

    @Test
    void shouldRoundTripRoomConfigurationWithAllWallMaterials() throws IOException {
        for (WallMaterial material : WallMaterial.values()) {
            DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
            original.setRoomConfiguration(new RoomConfiguration(
                    new RoomDimensions(10, 8, 3), material));

            String xml = serializer.serialize(original);
            DawProject restored = deserializer.deserialize(xml);

            assertThat(restored.getRoomConfiguration().getWallMaterial())
                    .as("Wall material %s should round-trip", material)
                    .isEqualTo(material);
        }
    }

    @Test
    void shouldRoundTripPerSurfaceMaterialMap() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        SurfaceMaterialMap map = new SurfaceMaterialMap(
                WallMaterial.MARBLE,        // floor
                WallMaterial.WOOD,          // frontWall
                WallMaterial.CURTAINS,      // backWall
                WallMaterial.GLASS,         // leftWall
                WallMaterial.DRYWALL,       // rightWall
                WallMaterial.ACOUSTIC_TILE  // ceiling
        );
        original.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(10, 8, 3), map));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        SurfaceMaterialMap restoredMap = restored.getRoomConfiguration().getMaterialMap();
        assertThat(restoredMap).isEqualTo(map);
    }

    @Test
    void shouldDeserializeLegacyRoomConfigurationByBroadcastingWallMaterial() throws IOException {
        // Legacy XML: only the wall-material attribute, no <surface-materials> child.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <daw-project version="1">
                    <metadata><name>Legacy</name></metadata>
                    <audio-format sample-rate="44100" bit-depth="16" channels="2"/>
                    <transport tempo="120.0" time-signature-numerator="4" time-signature-denominator="4"/>
                    <room-configuration width="6.0" length="8.0" height="3.0" wall-material="ACOUSTIC_FOAM"/>
                </daw-project>
                """;

        DawProject restored = deserializer.deserialize(legacyXml);

        SurfaceMaterialMap restoredMap = restored.getRoomConfiguration().getMaterialMap();
        assertThat(restoredMap).isEqualTo(new SurfaceMaterialMap(WallMaterial.ACOUSTIC_FOAM));
        assertThat(restoredMap.isUniform()).isTrue();
    }

    @Test
    void shouldRoundTripDomedCeiling() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(10, 10, new CeilingShape.Domed(4.0, 9.0)),
                WallMaterial.WOOD));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        CeilingShape ceiling = restored.getRoomConfiguration().getDimensions().ceiling();
        assertThat(ceiling).isInstanceOf(CeilingShape.Domed.class);
        CeilingShape.Domed d = (CeilingShape.Domed) ceiling;
        assertThat(d.baseHeight()).isEqualTo(4.0);
        assertThat(d.apexHeight()).isEqualTo(9.0);
    }

    @Test
    void shouldRoundTripBarrelVaultCeiling() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(10, 20,
                        new CeilingShape.BarrelVault(4.0, 8.0, CeilingShape.Axis.Y)),
                WallMaterial.WOOD));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        CeilingShape ceiling = restored.getRoomConfiguration().getDimensions().ceiling();
        assertThat(ceiling).isInstanceOf(CeilingShape.BarrelVault.class);
        CeilingShape.BarrelVault v = (CeilingShape.BarrelVault) ceiling;
        assertThat(v.baseHeight()).isEqualTo(4.0);
        assertThat(v.apexHeight()).isEqualTo(8.0);
        assertThat(v.axis()).isEqualTo(CeilingShape.Axis.Y);
    }

    @Test
    void shouldRoundTripCathedralCeiling() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(20, 10,
                        new CeilingShape.Cathedral(3.0, 7.0, CeilingShape.Axis.X)),
                WallMaterial.WOOD));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        CeilingShape ceiling = restored.getRoomConfiguration().getDimensions().ceiling();
        assertThat(ceiling).isInstanceOf(CeilingShape.Cathedral.class);
        CeilingShape.Cathedral c = (CeilingShape.Cathedral) ceiling;
        assertThat(c.eaveHeight()).isEqualTo(3.0);
        assertThat(c.ridgeHeight()).isEqualTo(7.0);
        assertThat(c.ridgeAxis()).isEqualTo(CeilingShape.Axis.X);
    }

    @Test
    void shouldRoundTripAngledCeiling() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(10, 8,
                        new CeilingShape.Angled(2.5, 4.5, CeilingShape.Axis.Y)),
                WallMaterial.WOOD));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        CeilingShape ceiling = restored.getRoomConfiguration().getDimensions().ceiling();
        assertThat(ceiling).isInstanceOf(CeilingShape.Angled.class);
        CeilingShape.Angled a = (CeilingShape.Angled) ceiling;
        assertThat(a.lowHeight()).isEqualTo(2.5);
        assertThat(a.highHeight()).isEqualTo(4.5);
        assertThat(a.slopeAxis()).isEqualTo(CeilingShape.Axis.Y);
    }

    @Test
    void shouldLoadLegacyRoomConfigurationWithoutCeilingElement() throws IOException {
        // Project file written before CeilingShape support: only the
        // scalar height attribute on <room-configuration>. Must load
        // successfully as a flat ceiling for backward compatibility.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <daw-project version="1">
                    <name>Legacy</name>
                    <audio-format sample-rate="44100.0" channels="2" bit-depth="16" buffer-size="512"/>
                    <transport tempo="120.0" beats-per-measure="4" beat-unit="4" loop-enabled="false" loop-start="0.0" loop-end="0.0" playhead="0.0"/>
                    <tracks/>
                    <mixer/>
                    <markers/>
                    <track-groups/>
                    <room-configuration width="10.0" length="8.0" height="3.0" wall-material="DRYWALL"/>
                </daw-project>
                """;

        DawProject restored = deserializer.deserialize(legacyXml);

        RoomConfiguration rc = restored.getRoomConfiguration();
        assertThat(rc).isNotNull();
        CeilingShape ceiling = rc.getDimensions().ceiling();
        assertThat(ceiling).isInstanceOf(CeilingShape.Flat.class);
        assertThat(((CeilingShape.Flat) ceiling).height()).isEqualTo(3.0);
        assertThat(rc.getDimensions().height()).isEqualTo(3.0);
    }

    @Test
    void shouldRoundTripMidiInputDeviceName() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track midi = original.createMidiTrack("Keys");
        midi.setMidiInputDeviceName("USB MIDI Controller");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getMidiInputDeviceName())
                .isEqualTo("USB MIDI Controller");
    }

    @Test
    void shouldDeserializeTrackWithoutMidiInputDeviceName() throws IOException {
        DawProject original = new DawProject("Test", AudioFormat.CD_QUALITY);
        original.createMidiTrack("Synth");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getMidiInputDeviceName()).isNull();
    }
}
