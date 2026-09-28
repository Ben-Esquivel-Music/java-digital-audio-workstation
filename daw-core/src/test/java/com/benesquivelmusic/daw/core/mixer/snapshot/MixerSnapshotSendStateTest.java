package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendTap;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — mixer snapshots carry per-send state keyed by target return
 * bus (level + tap) instead of the removed legacy scalar "send level", and
 * {@link MixerSnapshot#applyTo(Mixer)} restores sends <em>by target</em>
 * rather than by position in the channel's send list. Persistence writes
 * the new {@code <send target= target-index= level= tap=/>} shape, drops
 * the channel {@code send-level} attribute and tolerates it on read.
 */
class MixerSnapshotSendStateTest {

    @Test
    void captureRecordsEachSendKeyedByTargetBusWithLevelAndTap() {
        Mixer mixer = new Mixer();
        MixerChannel delay = mixer.addReturnBus("Delay Return");
        MixerChannel vocal = new MixerChannel("Vocal");
        vocal.addSend(new Send(delay, 0.2, SendTap.POST_FADER));
        vocal.addSend(new Send(mixer.getAuxBus(), 0.35, SendTap.PRE_INSERTS));
        mixer.addChannel(vocal);

        ChannelSnapshot state = MixerSnapshot.capture(mixer, "A").channels().getFirst();

        assertThat(state.sends()).hasSize(2);
        assertThat(state.sends().get(0).targetId()).isEqualTo(delay.getId());
        assertThat(state.sends().get(0).level()).isEqualTo(0.2);
        assertThat(state.sends().get(1).targetId()).isEqualTo(mixer.getAuxBus().getId());
        assertThat(state.sends().get(1).level()).isEqualTo(0.35);
        assertThat(state.sends().get(1).tap()).isEqualTo(SendTap.PRE_INSERTS);
        assertThat(ChannelSnapshot.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("sendLevel");
    }

    @Test
    void applyToRestoresBySendTargetEvenAfterTheSendListWasReordered() {
        Mixer mixer = new Mixer();
        MixerChannel delay = mixer.addReturnBus("Delay Return");
        MixerChannel vocal = new MixerChannel("Vocal");
        Send delaySend = new Send(delay, 0.2, SendTap.POST_FADER);
        Send reverbSend = new Send(mixer.getAuxBus(), 0.35, SendTap.PRE_INSERTS);
        vocal.addSend(delaySend);
        vocal.addSend(reverbSend);
        mixer.addChannel(vocal);
        MixerSnapshot snapshot = MixerSnapshot.capture(mixer, "A");

        // The delay send goes away: the reverb send is now index 0, where the
        // old index-aligned restore would have written the DELAY level.
        vocal.removeSend(delaySend);
        reverbSend.setLevel(0.9);
        reverbSend.setTap(SendTap.POST_FADER);

        snapshot.applyTo(mixer);

        assertThat(reverbSend.getLevel()).isEqualTo(0.35);
        assertThat(reverbSend.getTap()).isEqualTo(SendTap.PRE_INSERTS);
        assertThat(vocal.getSends()).as("a snapshot never creates routing").hasSize(1);
    }

    @Test
    void applyToSkipsSendsWhoseBusNoLongerExists() {
        Mixer mixer = new Mixer();
        MixerChannel delay = mixer.addReturnBus("Delay Return");
        MixerChannel vocal = new MixerChannel("Vocal");
        Send reverbSend = new Send(mixer.getAuxBus(), 0.35, SendTap.POST_FADER);
        vocal.addSend(new Send(delay, 0.2, SendTap.POST_FADER));
        vocal.addSend(reverbSend);
        mixer.addChannel(vocal);
        MixerSnapshot snapshot = MixerSnapshot.capture(mixer, "A");

        mixer.removeReturnBus(delay);
        reverbSend.setLevel(0.9);

        snapshot.applyTo(mixer);

        assertThat(reverbSend.getLevel()).isEqualTo(0.35);
        assertThat(vocal.getSends()).hasSize(1);
    }

    @Test
    void sendStateSurvivesSaveAndLoadAndRecallsOntoTheLoadedMixer() throws Exception {
        DawProject project = new DawProject("test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        Mixer mixer = project.getMixer();
        MixerChannel delay = mixer.addReturnBus("Delay Return");
        MixerChannel vocal = mixer.getChannels().getFirst();
        vocal.addSend(new Send(delay, 0.2, SendTap.POST_FADER));
        Send reverbSend = new Send(mixer.getAuxBus(), 0.35, SendTap.PRE_INSERTS);
        vocal.addSend(reverbSend);
        MixerSnapshot a = MixerSnapshot.capture(mixer, "A");
        project.getMixerSnapshotManager().setSlot(MixerSnapshotManager.Slot.A, a);
        reverbSend.setLevel(0.9);

        String xml = new ProjectSerializer().serialize(project);
        assertThat(xml).contains("tap=\"PRE_INSERTS\"");
        assertThat(xml).contains("target=\"" + mixer.getAuxBus().getId() + "\"");
        assertThat(xml).doesNotContain("send-level=");

        DawProject loaded = new ProjectDeserializer().deserialize(xml);
        Mixer loadedMixer = loaded.getMixer();
        MixerChannel loadedVocal = loadedMixer.getChannels().getFirst();
        Send loadedReverb = loadedVocal.getSendForTarget(loadedMixer.getAuxBus());
        assertThat(loadedReverb).isNotNull();
        assertThat(loadedReverb.getLevel()).as("live send state before recall").isEqualTo(0.9);

        MixerSnapshot loadedA = loaded.getMixerSnapshotManager().getSlotA();
        assertThat(loadedA.channels().getFirst().sends())
                .extracting(SendSnapshot::targetId)
                .as("targets resolve to the LOADED buses (ids are regenerated on load)")
                .containsExactly(loadedMixer.getReturnBuses().get(1).getId(),
                        loadedMixer.getAuxBus().getId());

        loadedA.applyTo(loadedMixer);

        assertThat(loadedReverb.getLevel()).isEqualTo(0.35);
        assertThat(loadedReverb.getTap()).isEqualTo(SendTap.PRE_INSERTS);
    }

    @Test
    void legacySendLevelAttributesAreToleratedOnRead() throws Exception {
        DawProject project = new DawProject("test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        MixerChannel vocal = project.getMixer().getChannels().getFirst();
        vocal.setVolume(0.55);
        project.getMixerSnapshotManager().setSlot(MixerSnapshotManager.Slot.A,
                MixerSnapshot.capture(project.getMixer(), "A"));
        String xml = new ProjectSerializer().serialize(project)
                .replace("volume=\"0.55\"", "volume=\"0.55\" send-level=\"0.4\"");
        assertThat(xml).contains("send-level=\"0.4\"");

        DawProject loaded = new ProjectDeserializer().deserialize(xml);

        assertThat(loaded.getMixer().getChannels().getFirst().getVolume()).isEqualTo(0.55);
        assertThat(loaded.getMixerSnapshotManager().getSlotA().channels().getFirst().volume())
                .isEqualTo(0.55);
    }
}
