package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.ReverbProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project file writes {@code <channels>} in mixer order, names a
 * sidechain source by its mixer index ({@code channel:<index>}), and on
 * reopen applies channel {@code i} to the channel of track {@code i}. That is
 * only right while mixer order equals track order, so a project with a
 * duplicated track must reopen with every strip on its own track — compared
 * by track id, never by position.
 */
class DuplicatedTrackMixerRoundTripTest {

    @Test
    void everyStripStaysOnItsOwnTrackAfterDuplicatingSaveAndReopen() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track a = project.createAudioTrack("A");
        Track b = project.createAudioTrack("B");
        Track c = project.createAudioTrack("C");
        MixerChannel channelB = project.getMixerChannelForTrack(b);
        MixerChannel channelC = project.getMixerChannelForTrack(c);
        setStrip(project.getMixerChannelForTrack(a), 0.9, -0.6);
        setStrip(channelB, 0.5, 0.3);
        setStrip(channelC, 0.7, 0.8);

        InsertSlot reverb = InsertEffectFactory.createSlot(InsertEffectType.REVERB, 2, 44100.0);
        ReverbProcessor reverbProcessor = (ReverbProcessor) reverb.getProcessor();
        reverbProcessor.setRoomSize(0.8);
        reverbProcessor.setMix(0.35);
        channelB.addInsert(reverb);

        InsertSlot compressor = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 44100.0);
        CompressorProcessor compressorProcessor = (CompressorProcessor) compressor.getProcessor();
        compressorProcessor.setThresholdDb(-24.0);
        compressorProcessor.setRatio(6.0);
        compressor.setSidechainSource(channelB);
        channelC.addInsert(compressor);

        VcaGroup group = project.getVcaGroupManager().createVcaGroup("Bass", List.of(channelB.getId()));
        project.getVcaGroupManager().setMasterGainDb(group.id(), -4.5);

        Track copy = project.duplicateTrack(a);
        // The copy's strip differs from every other one too, so a strip
        // applied to the wrong track always shows.
        setStrip(project.getMixerChannelForTrack(copy), 0.25, -0.2);
        double compositeVolumeOfBBefore = compositeVolume(project, b);

        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject reopened = deserializer.deserialize(new ProjectSerializer().serialize(project));

        for (Track original : project.getTracks()) {
            MixerChannel saved = project.getMixerChannelForTrack(original);
            MixerChannel restored = reopened.getMixerChannelForTrack(reopenedTrack(reopened, original));
            String track = "track '" + original.getName() + "'";
            assertThat(restored.getVolume()).as("volume of " + track).isEqualTo(saved.getVolume());
            assertThat(restored.getPan()).as("pan of " + track).isEqualTo(saved.getPan());
            assertThat(restored.getInsertSlots()).extracting(InsertSlot::getEffectType)
                    .as("insert types of " + track)
                    .containsExactlyElementsOf(saved.getInsertSlots().stream().map(InsertSlot::getEffectType).toList());
            for (int i = 0; i < saved.getInsertSlots().size(); i++) {
                assertThat(restored.getInsertSlots().get(i).snapshotParameterValues())
                        .as("parameters of insert " + i + " on " + track)
                        .isEqualTo(saved.getInsertSlots().get(i).snapshotParameterValues());
            }
        }

        MixerChannel reopenedB = reopened.getMixerChannelForTrack(reopenedTrack(reopened, b));
        MixerChannel reopenedC = reopened.getMixerChannelForTrack(reopenedTrack(reopened, c));
        assertThat(reopenedC.getInsertSlots().getFirst().getSidechainSource())
                .as("C's compressor is keyed from B's channel")
                .isSameAs(reopenedB);
        assertThat(compositeVolume(reopened, reopenedTrack(reopened, b)))
                .as("B's composite volume (fader times VCA)")
                .isEqualTo(compositeVolumeOfBBefore);
        assertThat(deserializer.getUnreadItems()).as("items the reader skipped").isEmpty();
    }

    private static void setStrip(MixerChannel channel, double volume, double pan) {
        channel.setVolume(volume);
        channel.setPan(pan);
    }

    /** The reopened track with the original's id — the identity the file keeps. */
    private static Track reopenedTrack(DawProject reopened, Track original) {
        return reopened.getTracks().stream()
                .filter(track -> track.getId().equals(original.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("track '" + original.getName() + "' is reopened"));
    }

    /**
     * What the render path plays the track's strip at. Faders and VCA gains
     * are written with {@code String.valueOf(double)}, which reads back to the
     * same double, so the composite compares exactly.
     */
    private static double compositeVolume(DawProject project, Track track) {
        MixerChannel channel = project.getMixerChannelForTrack(track);
        return channel.getVolume() * project.getVcaGroupManager().effectiveLinearMultiplier(channel.getId());
    }
}
