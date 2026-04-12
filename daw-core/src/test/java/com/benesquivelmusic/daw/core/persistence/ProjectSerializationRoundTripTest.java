package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.core.marker.Marker;
import com.benesquivelmusic.daw.core.marker.MarkerRange;
import com.benesquivelmusic.daw.core.marker.MarkerType;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.core.reference.ReferenceTrackManager;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackGroup;
import com.benesquivelmusic.daw.core.track.TrackType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Round-trip tests verifying that all project state is preserved
 * through serialize → deserialize cycles.
 */
class ProjectSerializationRoundTripTest {

    private final ProjectSerializer serializer = new ProjectSerializer();
    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void shouldRoundTripAutomationBreakpoints() throws IOException {
        DawProject original = new DawProject("Automation Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Lead");

        AutomationLane volumeLane = track.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME);
        volumeLane.addPoint(new AutomationPoint(0.0, 0.0, InterpolationMode.LINEAR));
        volumeLane.addPoint(new AutomationPoint(4.0, 0.8, InterpolationMode.CURVED));
        volumeLane.addPoint(new AutomationPoint(8.0, 0.5, InterpolationMode.LINEAR));
        volumeLane.setVisible(true);

        AutomationLane panLane = track.getAutomationData().getOrCreateLane(AutomationParameter.PAN);
        panLane.addPoint(new AutomationPoint(0.0, -0.5, InterpolationMode.LINEAR));
        panLane.addPoint(new AutomationPoint(16.0, 0.5, InterpolationMode.CURVED));
        panLane.setVisible(false);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        Track restoredTrack = restored.getTracks().get(0);
        AutomationLane restoredVolume = restoredTrack.getAutomationData().getLane(AutomationParameter.VOLUME);
        assertThat(restoredVolume).isNotNull();
        assertThat(restoredVolume.isVisible()).isTrue();
        assertThat(restoredVolume.getPoints()).hasSize(3);
        assertThat(restoredVolume.getPoints().get(0).getTimeInBeats()).isEqualTo(0.0);
        assertThat(restoredVolume.getPoints().get(0).getValue()).isEqualTo(0.0);
        assertThat(restoredVolume.getPoints().get(0).getInterpolationMode()).isEqualTo(InterpolationMode.LINEAR);
        assertThat(restoredVolume.getPoints().get(1).getTimeInBeats()).isEqualTo(4.0);
        assertThat(restoredVolume.getPoints().get(1).getValue()).isCloseTo(0.8, within(0.001));
        assertThat(restoredVolume.getPoints().get(1).getInterpolationMode()).isEqualTo(InterpolationMode.CURVED);
        assertThat(restoredVolume.getPoints().get(2).getTimeInBeats()).isEqualTo(8.0);
        assertThat(restoredVolume.getPoints().get(2).getValue()).isCloseTo(0.5, within(0.001));

        AutomationLane restoredPan = restoredTrack.getAutomationData().getLane(AutomationParameter.PAN);
        assertThat(restoredPan).isNotNull();
        assertThat(restoredPan.isVisible()).isFalse();
        assertThat(restoredPan.getPoints()).hasSize(2);
        assertThat(restoredPan.getPoints().get(0).getValue()).isCloseTo(-0.5, within(0.001));
        assertThat(restoredPan.getPoints().get(1).getValue()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void shouldRoundTripMarkers() throws IOException {
        DawProject original = new DawProject("Marker Test", AudioFormat.CD_QUALITY);
        Marker intro = new Marker("Intro", 0.0, MarkerType.SECTION);
        intro.setColor("#FF0000");
        Marker verse = new Marker("Verse 1", 16.0, MarkerType.SECTION);
        Marker chorus = new Marker("Chorus", 32.0, MarkerType.ARRANGEMENT);

        original.getMarkerManager().addMarker(intro);
        original.getMarkerManager().addMarker(verse);
        original.getMarkerManager().addMarker(chorus);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        List<Marker> markers = restored.getMarkerManager().getMarkers();
        assertThat(markers).hasSize(3);
        assertThat(markers.get(0).getName()).isEqualTo("Intro");
        assertThat(markers.get(0).getPositionInBeats()).isEqualTo(0.0);
        assertThat(markers.get(0).getType()).isEqualTo(MarkerType.SECTION);
        assertThat(markers.get(0).getColor()).isEqualTo("#FF0000");
        assertThat(markers.get(1).getName()).isEqualTo("Verse 1");
        assertThat(markers.get(1).getPositionInBeats()).isEqualTo(16.0);
        assertThat(markers.get(2).getName()).isEqualTo("Chorus");
        assertThat(markers.get(2).getType()).isEqualTo(MarkerType.ARRANGEMENT);
    }

    @Test
    void shouldRoundTripMarkerRanges() throws IOException {
        DawProject original = new DawProject("Range Test", AudioFormat.CD_QUALITY);
        MarkerRange verse = new MarkerRange("Verse 1", 0.0, 16.0, MarkerType.SECTION);
        verse.setColor("#00FF00");
        MarkerRange chorus = new MarkerRange("Chorus", 16.0, 32.0, MarkerType.ARRANGEMENT);

        original.getMarkerManager().addMarkerRange(verse);
        original.getMarkerManager().addMarkerRange(chorus);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        List<MarkerRange> ranges = restored.getMarkerManager().getMarkerRanges();
        assertThat(ranges).hasSize(2);
        assertThat(ranges.get(0).getName()).isEqualTo("Verse 1");
        assertThat(ranges.get(0).getStartPositionInBeats()).isEqualTo(0.0);
        assertThat(ranges.get(0).getEndPositionInBeats()).isEqualTo(16.0);
        assertThat(ranges.get(0).getType()).isEqualTo(MarkerType.SECTION);
        assertThat(ranges.get(0).getColor()).isEqualTo("#00FF00");
        assertThat(ranges.get(1).getName()).isEqualTo("Chorus");
        assertThat(ranges.get(1).getStartPositionInBeats()).isEqualTo(16.0);
        assertThat(ranges.get(1).getEndPositionInBeats()).isEqualTo(32.0);
        assertThat(ranges.get(1).getType()).isEqualTo(MarkerType.ARRANGEMENT);
    }

    @Test
    void shouldRoundTripTrackGroups() throws IOException {
        DawProject original = new DawProject("Group Test", AudioFormat.CD_QUALITY);
        Track drums = original.createAudioTrack("Drums");
        Track bass = original.createAudioTrack("Bass");
        Track guitar = original.createAudioTrack("Guitar");

        original.createTrackGroup("Rhythm Section", List.of(drums, bass));
        original.createTrackGroup("Strings", List.of(guitar));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTrackGroups()).hasSize(2);
        TrackGroup restoredGroup1 = restored.getTrackGroups().get(0);
        assertThat(restoredGroup1.getName()).isEqualTo("Rhythm Section");
        assertThat(restoredGroup1.getTracks()).hasSize(2);
        assertThat(restoredGroup1.getTracks().get(0).getName()).isEqualTo("Drums");
        assertThat(restoredGroup1.getTracks().get(1).getName()).isEqualTo("Bass");

        TrackGroup restoredGroup2 = restored.getTrackGroups().get(1);
        assertThat(restoredGroup2.getName()).isEqualTo("Strings");
        assertThat(restoredGroup2.getTracks()).hasSize(1);
        assertThat(restoredGroup2.getTracks().get(0).getName()).isEqualTo("Guitar");
    }

    @Test
    void shouldRoundTripInsertEffects() throws IOException {
        DawProject original = new DawProject("Insert Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Vocals");
        MixerChannel channel = original.getMixerChannelForTrack(track);

        InsertSlot compressor = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(compressor);

        InsertSlot reverb = InsertEffectFactory.createSlot(
                InsertEffectType.REVERB, 2, 44100.0);
        reverb.setBypassed(true);
        channel.addInsert(reverb);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        assertThat(restoredChannel.getInsertSlots()).hasSize(2);
        assertThat(restoredChannel.getInsertSlots().get(0).getEffectType()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(restoredChannel.getInsertSlots().get(0).isBypassed()).isFalse();
        assertThat(restoredChannel.getInsertSlots().get(1).getEffectType()).isEqualTo(InsertEffectType.REVERB);
        assertThat(restoredChannel.getInsertSlots().get(1).isBypassed()).isTrue();
    }

    @Test
    void shouldRoundTripSendRouting() throws IOException {
        DawProject original = new DawProject("Send Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Vocals");
        MixerChannel channel = original.getMixerChannelForTrack(track);

        MixerChannel auxBus = original.getMixer().getAuxBus();
        channel.addSend(new Send(auxBus, 0.4, SendMode.POST_FADER));

        MixerChannel delayReturn = original.getMixer().addReturnBus("Delay Return");
        channel.addSend(new Send(delayReturn, 0.6, SendMode.PRE_FADER));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        assertThat(restoredChannel.getSends()).hasSize(2);

        Send send1 = restoredChannel.getSends().get(0);
        assertThat(send1.getLevel()).isCloseTo(0.4, within(0.001));
        assertThat(send1.getMode()).isEqualTo(SendMode.POST_FADER);
        assertThat(send1.getTarget()).isEqualTo(restored.getMixer().getReturnBuses().get(0));

        Send send2 = restoredChannel.getSends().get(1);
        assertThat(send2.getLevel()).isCloseTo(0.6, within(0.001));
        assertThat(send2.getMode()).isEqualTo(SendMode.PRE_FADER);
        assertThat(send2.getTarget()).isEqualTo(restored.getMixer().getReturnBuses().get(1));
    }

    @Test
    void shouldRoundTripTimeStretchAndPitchShift() throws IOException {
        DawProject original = new DawProject("Stretch Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Audio");

        AudioClip clip = new AudioClip("Stretched", 0.0, 8.0, "/audio/sample.wav");
        clip.setTimeStretchRatio(1.5);
        clip.setPitchShiftSemitones(-3.0);
        clip.setStretchQuality(StretchQuality.HIGH);
        track.addClip(clip);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        AudioClip restoredClip = restored.getTracks().get(0).getClips().get(0);
        assertThat(restoredClip.getTimeStretchRatio()).isCloseTo(1.5, within(0.001));
        assertThat(restoredClip.getPitchShiftSemitones()).isCloseTo(-3.0, within(0.001));
        assertThat(restoredClip.getStretchQuality()).isEqualTo(StretchQuality.HIGH);
    }

    @Test
    void shouldRoundTripTrackInputDevice() throws IOException {
        DawProject original = new DawProject("Input Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Mic Input");
        track.setInputDeviceIndex(2);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getInputDeviceIndex()).isEqualTo(2);
    }

    @Test
    void shouldRoundTripTrackCollapsedState() throws IOException {
        DawProject original = new DawProject("Collapse Test", AudioFormat.CD_QUALITY);
        Track folder = original.createFolderTrack("Folder");
        folder.setCollapsed(true);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).isCollapsed()).isTrue();
        assertThat(restored.getTracks().get(0).getType()).isEqualTo(TrackType.FOLDER);
    }

    @Test
    void shouldRoundTripCompleteProject() throws IOException {
        DawProject original = new DawProject("Full Project", AudioFormat.STUDIO_QUALITY);

        // Transport
        original.getTransport().setTempo(128.0);
        original.getTransport().setTimeSignature(6, 8);
        original.getTransport().setLoopEnabled(true);
        original.getTransport().setLoopRegion(8.0, 64.0);
        original.getTransport().setPositionInBeats(16.0);

        // Tracks with clips and automation
        Track drums = original.createAudioTrack("Drums");
        drums.setVolume(0.9);
        drums.setInputDeviceIndex(0);
        AudioClip kickClip = new AudioClip("Kick", 0.0, 16.0, "/audio/kick.wav");
        kickClip.setTimeStretchRatio(0.95);
        kickClip.setPitchShiftSemitones(1.0);
        kickClip.setStretchQuality(StretchQuality.HIGH);
        drums.addClip(kickClip);

        AutomationLane drumsVolume = drums.getAutomationData().getOrCreateLane(AutomationParameter.VOLUME);
        drumsVolume.addPoint(new AutomationPoint(0.0, 0.5));
        drumsVolume.addPoint(new AutomationPoint(16.0, 1.0));

        Track bass = original.createAudioTrack("Bass");
        bass.setVolume(0.7);
        bass.setPan(-0.2);

        Track vocals = original.createMidiTrack("Vocals");
        vocals.setMuted(true);

        // Track groups
        original.createTrackGroup("Rhythm", List.of(drums, bass));

        // Mixer inserts
        MixerChannel drumsChannel = original.getMixerChannelForTrack(drums);
        drumsChannel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 96000.0));

        // Sends
        MixerChannel auxBus = original.getMixer().getAuxBus();
        drumsChannel.addSend(new Send(auxBus, 0.3, SendMode.POST_FADER));

        // Markers
        original.getMarkerManager().addMarker(new Marker("Intro", 0.0, MarkerType.SECTION));
        original.getMarkerManager().addMarker(new Marker("Verse", 16.0, MarkerType.SECTION));
        original.getMarkerManager().addMarkerRange(new MarkerRange("Verse Section", 16.0, 32.0, MarkerType.SECTION));

        original.getMixer().getMasterChannel().setVolume(0.85);

        // Serialize and deserialize
        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        // Verify complete state
        assertThat(restored.getName()).isEqualTo("Full Project");
        assertThat(restored.getFormat().sampleRate()).isEqualTo(96000.0);

        // Transport
        assertThat(restored.getTransport().getTempo()).isEqualTo(128.0);
        assertThat(restored.getTransport().getTimeSignatureNumerator()).isEqualTo(6);
        assertThat(restored.getTransport().getTimeSignatureDenominator()).isEqualTo(8);
        assertThat(restored.getTransport().isLoopEnabled()).isTrue();
        assertThat(restored.getTransport().getLoopStartInBeats()).isEqualTo(8.0);
        assertThat(restored.getTransport().getLoopEndInBeats()).isEqualTo(64.0);

        // Tracks
        assertThat(restored.getTracks()).hasSize(3);
        Track restoredDrums = restored.getTracks().get(0);
        assertThat(restoredDrums.getName()).isEqualTo("Drums");
        assertThat(restoredDrums.getVolume()).isCloseTo(0.9, within(0.001));
        assertThat(restoredDrums.getInputDeviceIndex()).isEqualTo(0);
        assertThat(restoredDrums.getClips()).hasSize(1);

        AudioClip restoredKick = restoredDrums.getClips().get(0);
        assertThat(restoredKick.getTimeStretchRatio()).isCloseTo(0.95, within(0.001));
        assertThat(restoredKick.getPitchShiftSemitones()).isCloseTo(1.0, within(0.001));
        assertThat(restoredKick.getStretchQuality()).isEqualTo(StretchQuality.HIGH);

        // Automation
        AutomationLane restoredDrumsVolume = restoredDrums.getAutomationData().getLane(AutomationParameter.VOLUME);
        assertThat(restoredDrumsVolume).isNotNull();
        assertThat(restoredDrumsVolume.getPoints()).hasSize(2);

        // Track groups
        assertThat(restored.getTrackGroups()).hasSize(1);
        assertThat(restored.getTrackGroups().get(0).getName()).isEqualTo("Rhythm");
        assertThat(restored.getTrackGroups().get(0).getTracks()).hasSize(2);

        // Mixer inserts
        MixerChannel restoredDrumsChannel = restored.getMixer().getChannels().get(0);
        assertThat(restoredDrumsChannel.getInsertSlots()).hasSize(1);
        assertThat(restoredDrumsChannel.getInsertSlots().get(0).getEffectType()).isEqualTo(InsertEffectType.COMPRESSOR);

        // Sends
        assertThat(restoredDrumsChannel.getSends()).hasSize(1);
        assertThat(restoredDrumsChannel.getSends().get(0).getLevel()).isCloseTo(0.3, within(0.001));

        // Markers
        assertThat(restored.getMarkerManager().getMarkers()).hasSize(2);
        assertThat(restored.getMarkerManager().getMarkerRanges()).hasSize(1);

        // Master
        assertThat(restored.getMixer().getMasterChannel().getVolume()).isCloseTo(0.85, within(0.001));
    }

    @Test
    void shouldHandleProjectWithNoAutomation() throws IOException {
        DawProject original = new DawProject("No Automation", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Plain Track");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        Track track = restored.getTracks().get(0);
        assertThat(track.getAutomationData().getLaneCount()).isZero();
    }

    @Test
    void shouldHandleProjectWithNoMarkers() throws IOException {
        DawProject original = new DawProject("No Markers", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Track");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getMarkerManager().getMarkers()).isEmpty();
        assertThat(restored.getMarkerManager().getMarkerRanges()).isEmpty();
    }

    @Test
    void shouldHandleProjectWithNoTrackGroups() throws IOException {
        DawProject original = new DawProject("No Groups", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Track");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTrackGroups()).isEmpty();
    }

    @Test
    void shouldRoundTripAllAutomationParameters() throws IOException {
        DawProject original = new DawProject("All Params", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Track");

        for (AutomationParameter param : AutomationParameter.values()) {
            AutomationLane lane = track.getAutomationData().getOrCreateLane(param);
            lane.addPoint(new AutomationPoint(0.0, param.getDefaultValue()));
            lane.addPoint(new AutomationPoint(4.0, param.getMaxValue()));
        }

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        Track restoredTrack = restored.getTracks().get(0);
        for (AutomationParameter param : AutomationParameter.values()) {
            AutomationLane lane = restoredTrack.getAutomationData().getLane(param);
            assertThat(lane).as("Lane for " + param).isNotNull();
            assertThat(lane.getPoints()).as("Points for " + param).hasSize(2);
            assertThat(lane.getPoints().get(0).getValue())
                    .as("Default value for " + param)
                    .isCloseTo(param.getDefaultValue(), within(0.001));
            assertThat(lane.getPoints().get(1).getValue())
                    .as("Max value for " + param)
                    .isCloseTo(param.getMaxValue(), within(0.001));
        }
    }

    @Test
    void shouldRoundTripMasterChannelInserts() throws IOException {
        DawProject original = new DawProject("Master Inserts", AudioFormat.CD_QUALITY);
        InsertSlot limiter = InsertEffectFactory.createSlot(
                InsertEffectType.LIMITER, 2, 44100.0);
        original.getMixer().getMasterChannel().addInsert(limiter);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        MixerChannel master = restored.getMixer().getMasterChannel();
        assertThat(master.getInsertSlots()).hasSize(1);
        assertThat(master.getInsertSlots().get(0).getEffectType()).isEqualTo(InsertEffectType.LIMITER);
    }

    @Test
    void shouldRoundTripDefaultStretchSettings() throws IOException {
        DawProject original = new DawProject("Defaults", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Track");
        track.addClip(new AudioClip("Clip", 0.0, 4.0, null));

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        AudioClip clip = restored.getTracks().get(0).getClips().get(0);
        assertThat(clip.getTimeStretchRatio()).isEqualTo(1.0);
        assertThat(clip.getPitchShiftSemitones()).isEqualTo(0.0);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.MEDIUM);
    }

    @Test
    void shouldRoundTripDefaultInputDevice() throws IOException {
        DawProject original = new DawProject("Defaults", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Track");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getTracks().get(0).getInputDeviceIndex()).isEqualTo(Track.NO_INPUT_DEVICE);
    }

    @Test
    void shouldRoundTripInsertEffectParameterValues() throws IOException {
        DawProject original = new DawProject("Param Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Vocals");
        MixerChannel channel = original.getMixerChannelForTrack(track);

        InsertSlot compressor = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        CompressorProcessor compProc = (CompressorProcessor) compressor.getProcessor();
        compProc.setThresholdDb(-30.0);
        compProc.setRatio(8.0);
        compProc.setAttackMs(5.0);
        compProc.setReleaseMs(200.0);
        compProc.setKneeDb(12.0);
        compProc.setMakeupGainDb(6.0);
        channel.addInsert(compressor);

        InsertSlot reverb = InsertEffectFactory.createSlot(
                InsertEffectType.REVERB, 2, 44100.0);
        ReverbProcessor revProc = (ReverbProcessor) reverb.getProcessor();
        revProc.setRoomSize(0.8);
        revProc.setDecay(0.7);
        revProc.setDamping(0.5);
        revProc.setMix(0.4);
        channel.addInsert(reverb);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        assertThat(restoredChannel.getInsertSlots()).hasSize(2);

        // Verify compressor parameters were restored
        CompressorProcessor restoredComp =
                (CompressorProcessor) restoredChannel.getInsertSlots().get(0).getProcessor();
        assertThat(restoredComp.getThresholdDb()).isCloseTo(-30.0, within(0.001));
        assertThat(restoredComp.getRatio()).isCloseTo(8.0, within(0.001));
        assertThat(restoredComp.getAttackMs()).isCloseTo(5.0, within(0.001));
        assertThat(restoredComp.getReleaseMs()).isCloseTo(200.0, within(0.001));
        assertThat(restoredComp.getKneeDb()).isCloseTo(12.0, within(0.001));
        assertThat(restoredComp.getMakeupGainDb()).isCloseTo(6.0, within(0.001));

        // Verify reverb parameters were restored
        ReverbProcessor restoredRev =
                (ReverbProcessor) restoredChannel.getInsertSlots().get(1).getProcessor();
        assertThat(restoredRev.getRoomSize()).isCloseTo(0.8, within(0.001));
        assertThat(restoredRev.getDecay()).isCloseTo(0.7, within(0.001));
        assertThat(restoredRev.getDamping()).isCloseTo(0.5, within(0.001));
        assertThat(restoredRev.getMix()).isCloseTo(0.4, within(0.001));
    }

    @Test
    void shouldRoundTripMetronomeSettings() throws IOException {
        DawProject original = new DawProject("Metronome Test", AudioFormat.CD_QUALITY);
        Metronome metronome = original.getMetronome();
        metronome.setEnabled(false);
        metronome.setVolume(0.6f);
        metronome.setClickSound(ClickSound.COWBELL);
        metronome.setSubdivision(Subdivision.EIGHTH);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        Metronome restoredMetronome = restored.getMetronome();
        assertThat(restoredMetronome.isEnabled()).isFalse();
        assertThat(restoredMetronome.getVolume()).isCloseTo(0.6f, within(0.001f));
        assertThat(restoredMetronome.getClickSound()).isEqualTo(ClickSound.COWBELL);
        assertThat(restoredMetronome.getSubdivision()).isEqualTo(Subdivision.EIGHTH);
    }

    @Test
    void shouldRoundTripDefaultMetronomeSettings() throws IOException {
        DawProject original = new DawProject("Default Metronome", AudioFormat.CD_QUALITY);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        Metronome restoredMetronome = restored.getMetronome();
        assertThat(restoredMetronome.isEnabled()).isTrue();
        assertThat(restoredMetronome.getVolume()).isCloseTo(1.0f, within(0.001f));
        assertThat(restoredMetronome.getClickSound()).isEqualTo(ClickSound.WOODBLOCK);
        assertThat(restoredMetronome.getSubdivision()).isEqualTo(Subdivision.QUARTER);
    }

    @Test
    void shouldRoundTripReferenceTrackManager() throws IOException {
        DawProject original = new DawProject("Reference Test", AudioFormat.CD_QUALITY);
        ReferenceTrack ref1 = new ReferenceTrack("Commercial Mix", "/audio/reference1.wav");
        ref1.setGainOffsetDb(-3.5);
        ref1.setLoopEnabled(true);
        ref1.setLoopRegion(16.0, 48.0);
        ref1.setIntegratedLufs(-14.0);

        ReferenceTrack ref2 = new ReferenceTrack("Indie Mix", "/audio/reference2.wav");
        ref2.setGainOffsetDb(1.2);

        original.addReferenceTrack(ref1);
        original.addReferenceTrack(ref2);
        original.getReferenceTrackManager().setActiveIndex(1);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        ReferenceTrackManager restoredManager = restored.getReferenceTrackManager();
        assertThat(restoredManager.getReferenceTrackCount()).isEqualTo(2);
        assertThat(restoredManager.getActiveIndex()).isEqualTo(1);

        ReferenceTrack restoredRef1 = restoredManager.getReferenceTracks().get(0);
        assertThat(restoredRef1.getName()).isEqualTo("Commercial Mix");
        assertThat(restoredRef1.getSourceFilePath()).isEqualTo("/audio/reference1.wav");
        assertThat(restoredRef1.getGainOffsetDb()).isCloseTo(-3.5, within(0.001));
        assertThat(restoredRef1.isLoopEnabled()).isTrue();
        assertThat(restoredRef1.getLoopStartInBeats()).isCloseTo(16.0, within(0.001));
        assertThat(restoredRef1.getLoopEndInBeats()).isCloseTo(48.0, within(0.001));
        assertThat(restoredRef1.getIntegratedLufs()).isCloseTo(-14.0, within(0.001));

        ReferenceTrack restoredRef2 = restoredManager.getReferenceTracks().get(1);
        assertThat(restoredRef2.getName()).isEqualTo("Indie Mix");
        assertThat(restoredRef2.getGainOffsetDb()).isCloseTo(1.2, within(0.001));
    }

    @Test
    void shouldDetectMissingAudioFiles() throws IOException {
        DawProject original = new DawProject("Missing Files", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Audio");
        AudioClip clip = new AudioClip("Clip", 0.0, 4.0, "/nonexistent/path/to/audio.wav");
        track.addClip(clip);

        // Also add a reference track with missing file
        ReferenceTrack ref = new ReferenceTrack("Missing Ref", "/nonexistent/reference.wav");
        original.addReferenceTrack(ref);

        String xml = serializer.serialize(original);
        ProjectDeserializer freshDeserializer = new ProjectDeserializer();
        DawProject restored = freshDeserializer.deserialize(xml);

        // Project should still load
        assertThat(restored.getTracks().get(0).getClips()).hasSize(1);
        assertThat(restored.getReferenceTrackManager().getReferenceTrackCount()).isEqualTo(1);

        // Missing files should be tracked
        assertThat(freshDeserializer.getMissingFiles()).contains(
                "/nonexistent/path/to/audio.wav",
                "/nonexistent/reference.wav"
        );
    }

    @Test
    void shouldNotReportExistingFilesAsMissing() throws IOException {
        DawProject original = new DawProject("No Missing", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Audio");
        // Clip with no source file
        AudioClip clip = new AudioClip("Clip", 0.0, 4.0, null);
        track.addClip(clip);

        String xml = serializer.serialize(original);
        ProjectDeserializer freshDeserializer = new ProjectDeserializer();
        freshDeserializer.deserialize(xml);

        assertThat(freshDeserializer.getMissingFiles()).isEmpty();
    }

    @Test
    void shouldRoundTripNoReferenceTracksGracefully() throws IOException {
        DawProject original = new DawProject("No Refs", AudioFormat.CD_QUALITY);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        assertThat(restored.getReferenceTrackManager().getReferenceTrackCount()).isZero();
        assertThat(restored.getReferenceTrackManager().isReferenceActive()).isFalse();
    }

    @Test
    void shouldRoundTripAutomationMode() throws IOException {
        DawProject original = new DawProject("Automation Mode Test", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Track READ");
        Track trackOff = original.createAudioTrack("Track OFF");
        trackOff.setAutomationMode(AutomationMode.OFF);

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);

        List<Track> tracks = restored.getTracks();
        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).getAutomationMode()).isEqualTo(AutomationMode.READ);
        assertThat(tracks.get(1).getAutomationMode()).isEqualTo(AutomationMode.OFF);
    }

    @Test
    void shouldRoundTripSidechainSource() throws IOException {
        DawProject original = new DawProject("Sidechain Test", AudioFormat.CD_QUALITY);
        Track kick = original.createAudioTrack("Kick");
        Track bass = original.createAudioTrack("Bass");

        MixerChannel kickChannel = original.getMixerChannelForTrack(kick);
        MixerChannel bassChannel = original.getMixerChannelForTrack(bass);

        InsertSlot compressor = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        compressor.setSidechainSource(kickChannel);
        bassChannel.addInsert(compressor);

        String xml = serializer.serialize(original);
        assertThat(xml).contains("sidechain-source=\"channel:0\"");

        DawProject restored = deserializer.deserialize(xml);

        MixerChannel restoredBass = restored.getMixer().getChannels().get(1);
        assertThat(restoredBass.getInsertSlots()).hasSize(1);
        InsertSlot restoredSlot = restoredBass.getInsertSlots().get(0);
        assertThat(restoredSlot.getSidechainSource()).isNotNull();
        assertThat(restoredSlot.getSidechainSource().getName()).isEqualTo("Kick");
        // Verify it's the actual channel object from the restored mixer
        assertThat(restoredSlot.getSidechainSource())
                .isSameAs(restored.getMixer().getChannels().get(0));
    }

    @Test
    void shouldRoundTripSidechainSourceFromReturnBus() throws IOException {
        DawProject original = new DawProject("Sidechain Return Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Vocals");

        MixerChannel vocalsChannel = original.getMixerChannelForTrack(track);
        MixerChannel reverbReturn = original.getMixer().getAuxBus();

        InsertSlot gate = InsertEffectFactory.createSlot(
                InsertEffectType.NOISE_GATE, 2, 44100.0);
        gate.setSidechainSource(reverbReturn);
        vocalsChannel.addInsert(gate);

        String xml = serializer.serialize(original);
        assertThat(xml).contains("sidechain-source=\"return:0\"");

        DawProject restored = deserializer.deserialize(xml);

        MixerChannel restoredVocals = restored.getMixer().getChannels().get(0);
        InsertSlot restoredSlot = restoredVocals.getInsertSlots().get(0);
        assertThat(restoredSlot.getSidechainSource()).isNotNull();
        assertThat(restoredSlot.getSidechainSource())
                .isSameAs(restored.getMixer().getReturnBuses().get(0));
    }

    @Test
    void shouldHandleMissingSidechainSourceGracefully() throws IOException {
        // Manually craft XML with an invalid sidechain source index
        DawProject original = new DawProject("Invalid SC Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Track");
        MixerChannel channel = original.getMixerChannelForTrack(track);
        InsertSlot comp = InsertEffectFactory.createSlot(
                InsertEffectType.COMPRESSOR, 2, 44100.0);
        channel.addInsert(comp);

        String xml = serializer.serialize(original);
        // Inject an invalid sidechain source reference
        xml = xml.replace("effect-type=\"COMPRESSOR\"",
                "effect-type=\"COMPRESSOR\" sidechain-source=\"channel:99\"");

        DawProject restored = deserializer.deserialize(xml);

        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        InsertSlot restoredSlot = restoredChannel.getInsertSlots().get(0);
        // Invalid source index should result in null (no sidechain)
        assertThat(restoredSlot.getSidechainSource()).isNull();
    }

    @Test
    void shouldRoundTripInputRouting() throws IOException {
        DawProject original = new DawProject("Input Routing Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Guitar");
        track.setInputRouting(new InputRouting(2, 2)); // Input 3-4

        String xml = serializer.serialize(original);
        assertThat(xml).contains("input-routing-channel=\"2\"");
        assertThat(xml).contains("input-routing-count=\"2\"");

        DawProject restored = deserializer.deserialize(xml);
        Track restoredTrack = restored.getTracks().get(0);
        assertThat(restoredTrack.getInputRouting()).isEqualTo(new InputRouting(2, 2));
    }

    @Test
    void shouldRoundTripOutputRouting() throws IOException {
        DawProject original = new DawProject("Output Routing Test", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Drums");
        MixerChannel channel = original.getMixerChannelForTrack(track);
        channel.setOutputRouting(new OutputRouting(4, 2)); // Output 5-6

        String xml = serializer.serialize(original);
        assertThat(xml).contains("output-routing-channel=\"4\"");
        assertThat(xml).contains("output-routing-count=\"2\"");

        DawProject restored = deserializer.deserialize(xml);
        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        assertThat(restoredChannel.getOutputRouting()).isEqualTo(new OutputRouting(4, 2));
    }

    @Test
    void shouldDefaultToMasterOutputWhenNoRoutingInXml() throws IOException {
        DawProject original = new DawProject("Default Routing Test", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Vocals");

        String xml = serializer.serialize(original);
        // No output-routing attributes for master-routed channels
        assertThat(xml).doesNotContain("output-routing-channel");

        DawProject restored = deserializer.deserialize(xml);
        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        assertThat(restoredChannel.getOutputRouting()).isEqualTo(OutputRouting.MASTER);
    }

    @Test
    void shouldDefaultToStereoInputWhenNoRoutingInXml() throws IOException {
        DawProject original = new DawProject("Default Input Test", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Vocals");

        String xml = serializer.serialize(original);
        DawProject restored = deserializer.deserialize(xml);
        Track restoredTrack = restored.getTracks().get(0);
        // Default input routing attributes are present, should restore to DEFAULT_STEREO
        assertThat(restoredTrack.getInputRouting()).isEqualTo(InputRouting.DEFAULT_STEREO);
    }

    @Test
    void shouldKeepDefaultInputRoutingOnInvalidXmlValues() throws IOException {
        DawProject original = new DawProject("Bad Input Routing", AudioFormat.CD_QUALITY);
        original.createAudioTrack("Track");

        String xml = serializer.serialize(original);
        // Inject invalid input routing: firstChannel = -5 (below -1 minimum)
        xml = xml.replace("input-routing-channel=\"0\"", "input-routing-channel=\"-5\"");

        DawProject restored = deserializer.deserialize(xml);
        Track restoredTrack = restored.getTracks().get(0);
        // Invalid values should be ignored, keeping the default
        assertThat(restoredTrack.getInputRouting()).isEqualTo(InputRouting.DEFAULT_STEREO);
    }

    @Test
    void shouldKeepDefaultOutputRoutingOnInvalidXmlValues() throws IOException {
        DawProject original = new DawProject("Bad Output Routing", AudioFormat.CD_QUALITY);
        Track track = original.createAudioTrack("Track");
        MixerChannel channel = original.getMixerChannelForTrack(track);
        channel.setOutputRouting(new OutputRouting(2, 2));

        String xml = serializer.serialize(original);
        // Inject invalid output routing: negative channel count
        xml = xml.replace("output-routing-count=\"2\"", "output-routing-count=\"-3\"");

        DawProject restored = deserializer.deserialize(xml);
        MixerChannel restoredChannel = restored.getMixer().getChannels().get(0);
        // Invalid values should be ignored, keeping the default (MASTER)
        assertThat(restoredChannel.getOutputRouting()).isEqualTo(OutputRouting.MASTER);
    }
}
