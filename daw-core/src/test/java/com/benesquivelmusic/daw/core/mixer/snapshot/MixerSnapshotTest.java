package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerSnapshotTest {

    private static Mixer buildMixerWithTwoChannelsAndCompressor() {
        Mixer mixer = new Mixer();
        MixerChannel vocal = new MixerChannel("Vocal");
        MixerChannel drums = new MixerChannel("Drums");
        mixer.addChannel(vocal);
        mixer.addChannel(drums);

        vocal.setVolume(0.8);
        vocal.setPan(-0.25);
        drums.setVolume(0.6);
        drums.setMuted(true);

        InsertSlot slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 44100);
        ((CompressorProcessor) slot.getProcessor()).setThresholdDb(-18.0);
        ((CompressorProcessor) slot.getProcessor()).setRatio(4.0);
        vocal.addInsert(slot);

        MixerChannel reverb = mixer.getAuxBus();
        vocal.addSend(new Send(reverb, 0.5, SendMode.POST_FADER));
        return mixer;
    }

    @Test
    void captureRecordsAllChannelAndInsertAndSendState() {
        Mixer mixer = buildMixerWithTwoChannelsAndCompressor();

        MixerSnapshot snapshot = MixerSnapshot.capture(mixer, "Vocal-Forward");

        assertThat(snapshot.name()).isEqualTo("Vocal-Forward");
        assertThat(snapshot.channels()).hasSize(2);
        assertThat(snapshot.returnBuses()).hasSize(1);

        ChannelSnapshot vocalState = snapshot.channels().get(0);
        assertThat(vocalState.volume()).isEqualTo(0.8);
        assertThat(vocalState.pan()).isEqualTo(-0.25);
        assertThat(vocalState.inserts()).hasSize(1);
        assertThat(vocalState.inserts().getFirst().effectType()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(vocalState.inserts().getFirst().parameters()).containsEntry(0, -18.0);
        assertThat(vocalState.inserts().getFirst().parameters()).containsEntry(1, 4.0);
        assertThat(vocalState.sends()).hasSize(1);
        assertThat(vocalState.sends().getFirst().level()).isEqualTo(0.5);

        ChannelSnapshot drumsState = snapshot.channels().get(1);
        assertThat(drumsState.muted()).isTrue();
    }

    @Test
    void applyToRestoresExactValuesAfterIntermediateEdits() {
        Mixer mixer = buildMixerWithTwoChannelsAndCompressor();
        MixerSnapshot snapshot = MixerSnapshot.capture(mixer, "Before");

        // Mutate every captured value.
        MixerChannel vocal = mixer.getChannels().get(0);
        MixerChannel drums = mixer.getChannels().get(1);
        vocal.setVolume(0.1);
        vocal.setPan(0.9);
        drums.setMuted(false);
        drums.setVolume(1.0);
        InsertSlot slot = vocal.getInsertSlots().getFirst();
        ((CompressorProcessor) slot.getProcessor()).setThresholdDb(-6.0);
        ((CompressorProcessor) slot.getProcessor()).setRatio(2.0);
        vocal.setInsertBypassed(0, true);
        vocal.getSends().getFirst().setLevel(0.0);

        // Recall snapshot.
        snapshot.applyTo(mixer);

        assertThat(vocal.getVolume()).isEqualTo(0.8);
        assertThat(vocal.getPan()).isEqualTo(-0.25);
        assertThat(drums.getVolume()).isEqualTo(0.6);
        assertThat(drums.isMuted()).isTrue();
        assertThat(((CompressorProcessor) slot.getProcessor()).getThresholdDb()).isEqualTo(-18.0);
        assertThat(((CompressorProcessor) slot.getProcessor()).getRatio()).isEqualTo(4.0);
        assertThat(vocal.getInsertSlots().getFirst().isBypassed()).isFalse();
        assertThat(vocal.getSends().getFirst().getLevel()).isEqualTo(0.5);
    }

    @Test
    void snapshotManagerEnforces32SnapshotLimit() {
        MixerSnapshotManager manager = new MixerSnapshotManager();
        Mixer mixer = new Mixer();
        for (int i = 0; i < MixerSnapshotManager.MAX_SNAPSHOTS; i++) {
            manager.addSnapshot(MixerSnapshot.capture(mixer, "snap-" + i));
        }
        assertThat(manager.getSnapshotCount()).isEqualTo(32);
        assertThatThrownBy(() -> manager.addSnapshot(MixerSnapshot.capture(mixer, "too-many")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toggleABAppliesOppositeSlotAndFlipsActiveMarker() {
        Mixer mixer = buildMixerWithTwoChannelsAndCompressor();
        MixerChannel vocal = mixer.getChannels().get(0);

        // Capture slot A with volume 0.8.
        MixerSnapshot a = MixerSnapshot.capture(mixer, "A");

        // Change mixer and capture slot B with volume 0.2.
        vocal.setVolume(0.2);
        MixerSnapshot b = MixerSnapshot.capture(mixer, "B");

        MixerSnapshotManager manager = new MixerSnapshotManager();
        manager.setSlot(MixerSnapshotManager.Slot.A, a);
        manager.setSlot(MixerSnapshotManager.Slot.B, b);
        manager.setActiveSlot(MixerSnapshotManager.Slot.A);

        // Toggle to B — mixer should reflect B's values.
        vocal.setVolume(0.5);
        manager.toggleAB(mixer);
        assertThat(manager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.B);
        assertThat(vocal.getVolume()).isEqualTo(0.2);

        // Toggle back to A.
        manager.toggleAB(mixer);
        assertThat(manager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.A);
        assertThat(vocal.getVolume()).isEqualTo(0.8);
    }

    @Test
    void saveSnapshotActionIsUndoable() {
        Mixer mixer = buildMixerWithTwoChannelsAndCompressor();
        MixerSnapshotManager manager = new MixerSnapshotManager();

        SaveSnapshotAction save = new SaveSnapshotAction(manager, mixer, "Mix 1");
        save.execute();
        assertThat(manager.getSnapshotCount()).isEqualTo(1);
        assertThat(manager.getSnapshots().getFirst().name()).isEqualTo("Mix 1");

        save.undo();
        assertThat(manager.getSnapshotCount()).isZero();
    }

    @Test
    void recallSnapshotActionRestoresPreviousStateOnUndo() {
        Mixer mixer = buildMixerWithTwoChannelsAndCompressor();
        MixerChannel vocal = mixer.getChannels().get(0);

        MixerSnapshot target = MixerSnapshot.capture(mixer, "Target");
        vocal.setVolume(0.1);  // current state is different from target

        RecallSnapshotAction recall = new RecallSnapshotAction(mixer, target);
        recall.execute();
        assertThat(vocal.getVolume()).isEqualTo(0.8);

        recall.undo();
        assertThat(vocal.getVolume()).isEqualTo(0.1);
    }

    @Test
    void snapshotsSurviveProjectSaveAndLoadRoundTrip() throws Exception {
        DawProject project = new DawProject("test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        project.createAudioTrack("Drums");

        MixerChannel vocal = project.getMixer().getChannels().get(0);
        vocal.setVolume(0.75);
        vocal.setPan(0.33);

        MixerSnapshot a = MixerSnapshot.capture(project.getMixer(), "Vocal-Forward");
        vocal.setVolume(0.25);
        MixerSnapshot b = MixerSnapshot.capture(project.getMixer(), "Instrument-Forward");

        MixerSnapshotManager manager = project.getMixerSnapshotManager();
        manager.addSnapshot(a);
        manager.addSnapshot(b);
        manager.setSlot(MixerSnapshotManager.Slot.A, a);
        manager.setSlot(MixerSnapshotManager.Slot.B, b);
        manager.setActiveSlot(MixerSnapshotManager.Slot.B);

        String xml = new ProjectSerializer().serialize(project);
        DawProject loaded = new ProjectDeserializer().deserialize(xml);

        MixerSnapshotManager loadedManager = loaded.getMixerSnapshotManager();
        assertThat(loadedManager.getSnapshots()).hasSize(2);
        assertThat(loadedManager.getSnapshots().get(0).name()).isEqualTo("Vocal-Forward");
        assertThat(loadedManager.getSnapshots().get(0).channels().get(0).volume()).isEqualTo(0.75);
        assertThat(loadedManager.getSnapshots().get(1).channels().get(0).volume()).isEqualTo(0.25);
        assertThat(loadedManager.getSlotA()).isNotNull();
        assertThat(loadedManager.getSlotA().name()).isEqualTo("Vocal-Forward");
        assertThat(loadedManager.getSlotB()).isNotNull();
        assertThat(loadedManager.getSlotB().name()).isEqualTo("Instrument-Forward");
        assertThat(loadedManager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.B);

        // The loaded slot-B snapshot, when applied to the loaded mixer, restores
        // the Instrument-Forward volume.
        loadedManager.getSlotB().applyTo(loaded.getMixer());
        assertThat(loaded.getMixer().getChannels().get(0).getVolume()).isEqualTo(0.25);
    }
}
