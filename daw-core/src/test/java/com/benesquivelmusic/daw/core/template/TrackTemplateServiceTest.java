package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackTemplateServiceTest {

    private static final AudioFormat FORMAT = new AudioFormat(48000.0, 2, 24, 512);

    @Test
    void shouldCreateTrackFromFactoryVocalTemplate() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        TrackTemplate vocal = TrackTemplateFactory.vocalTrack();

        Track track = TrackTemplateService.createTrackFromTemplate(vocal, project, "Lead Vocal");

        assertThat(track.getName()).isEqualTo("Lead Vocal");
        assertThat(track.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(track.getColor()).isEqualTo(TrackColor.PINK);

        MixerChannel channel = project.getMixerChannelForTrack(track);
        assertThat(channel).isNotNull();
        assertThat(channel.getInsertCount()).isEqualTo(2);
        assertThat(channel.getInsertSlot(0).getEffectType()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(channel.getInsertSlot(1).getEffectType()).isEqualTo(InsertEffectType.PARAMETRIC_EQ);

        assertThat(channel.getSends()).hasSize(1);
        Send reverbSend = channel.getSends().getFirst();
        assertThat(reverbSend.getTarget().getName()).isEqualTo(TrackTemplateFactory.REVERB_RETURN_NAME);
        assertThat(reverbSend.getLevel()).isEqualTo(0.25);
        assertThat(reverbSend.getMode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void shouldApplyParameterOverrides() {
        DawProject project = new DawProject("Test", FORMAT);
        TrackTemplate template = new TrackTemplate(
                "Test Template",
                TrackType.AUDIO,
                "Hat",
                java.util.List.of(
                        InsertEffectSpec.of(InsertEffectType.DELAY, Map.of(
                                0, 375.0, // delay ms
                                1, 0.45,  // feedback
                                2, 0.6    // mix
                        ))),
                java.util.List.of(),
                0.75,
                -0.25,
                TrackColor.CYAN,
                com.benesquivelmusic.daw.core.audio.InputRouting.DEFAULT_STEREO,
                com.benesquivelmusic.daw.core.mixer.OutputRouting.MASTER);

        Track track = TrackTemplateService.createTrackFromTemplate(template, project, null);
        MixerChannel channel = project.getMixerChannelForTrack(track);

        assertThat(channel.getVolume()).isEqualTo(0.75);
        assertThat(channel.getPan()).isEqualTo(-0.25);
        assertThat(track.getName()).isEqualTo("Hat"); // from nameHint

        InsertSlot slot = channel.getInsertSlot(0);
        Map<Integer, Double> values = com.benesquivelmusic.daw.core.mixer.InsertEffectFactory
                .getParameterValues(InsertEffectType.DELAY, slot.getProcessor());
        assertThat(values.get(0)).isEqualTo(375.0);
        assertThat(values.get(1)).isEqualTo(0.45);
        assertThat(values.get(2)).isEqualTo(0.6);
    }

    @Test
    void shouldApplyPresetReplacingExistingInsertsAndSends() {
        DawProject project = new DawProject("Test", FORMAT);
        Track track = project.createAudioTrack("Guitar");
        MixerChannel channel = project.getMixerChannelForTrack(track);

        // Preload some existing inserts and a send that should be replaced.
        channel.addInsert(com.benesquivelmusic.daw.core.mixer.InsertEffectFactory
                .createSlot(InsertEffectType.NOISE_GATE, 2, 48000.0));
        channel.addSend(new Send(project.getMixer().getReturnBuses().getFirst(), 0.5, SendMode.PRE_FADER));

        ChannelStripPreset preset = new ChannelStripPreset(
                "Vocal Channel",
                java.util.List.of(
                        InsertEffectSpec.ofDefaults(InsertEffectType.PARAMETRIC_EQ),
                        InsertEffectSpec.of(InsertEffectType.COMPRESSOR, Map.of(0, -22.0, 1, 2.5))),
                java.util.List.of(new SendSpec(TrackTemplateFactory.REVERB_RETURN_NAME, 0.3, SendMode.POST_FADER)),
                0.7,
                0.1);

        TrackTemplateService.applyPreset(preset, channel, project.getMixer(), FORMAT);

        assertThat(channel.getInsertCount()).isEqualTo(2);
        assertThat(channel.getInsertSlot(0).getEffectType()).isEqualTo(InsertEffectType.PARAMETRIC_EQ);
        assertThat(channel.getInsertSlot(1).getEffectType()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(channel.getSends()).hasSize(1);
        assertThat(channel.getSends().getFirst().getLevel()).isEqualTo(0.3);
        assertThat(channel.getVolume()).isEqualTo(0.7);
        assertThat(channel.getPan()).isEqualTo(0.1);
    }

    @Test
    void shouldSkipSendsForMissingReturnBuses() {
        DawProject project = new DawProject("Test", FORMAT);
        Track track = project.createAudioTrack("T");
        MixerChannel channel = project.getMixerChannelForTrack(track);

        ChannelStripPreset preset = new ChannelStripPreset(
                "X",
                java.util.List.of(),
                java.util.List.of(new SendSpec("NoSuchBus", 0.4, SendMode.POST_FADER)),
                1.0, 0.0);

        TrackTemplateService.applyPreset(preset, channel, project.getMixer(), FORMAT);

        assertThat(channel.getSends()).isEmpty();
    }

    @Test
    void shouldCaptureChannelStripAsPreset() {
        DawProject project = new DawProject("Test", FORMAT);
        Track track = project.createAudioTrack("Vox");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        channel.setVolume(0.6);
        channel.setPan(0.2);
        channel.addInsert(com.benesquivelmusic.daw.core.mixer.InsertEffectFactory
                .createSlot(InsertEffectType.LIMITER, 2, 48000.0));
        channel.addSend(new Send(project.getMixer().getReturnBuses().getFirst(), 0.33, SendMode.POST_FADER));

        ChannelStripPreset captured = TrackTemplateService.captureChannelStrip("Snapshot", channel);

        assertThat(captured.presetName()).isEqualTo("Snapshot");
        assertThat(captured.volume()).isEqualTo(0.6);
        assertThat(captured.pan()).isEqualTo(0.2);
        assertThat(captured.inserts()).hasSize(1);
        assertThat(captured.inserts().getFirst().type()).isEqualTo(InsertEffectType.LIMITER);
        assertThat(captured.sends()).hasSize(1);
        assertThat(captured.sends().getFirst().targetName()).isEqualTo(TrackTemplateFactory.REVERB_RETURN_NAME);
        assertThat(captured.sends().getFirst().level()).isEqualTo(0.33);
    }

    @Test
    void addTrackFromTemplateActionShouldBeUndoable() {
        DawProject project = new DawProject("Test", FORMAT);
        UndoManager undoManager = new UndoManager();
        AddTrackFromTemplateAction action = new AddTrackFromTemplateAction(
                project, TrackTemplateFactory.vocalTrack(), "V1");

        undoManager.execute(action);
        assertThat(project.getTracks()).hasSize(1);
        assertThat(project.getTracks().getFirst().getName()).isEqualTo("V1");

        undoManager.undo();
        assertThat(project.getTracks()).isEmpty();

        undoManager.redo();
        assertThat(project.getTracks()).hasSize(1);
        // Redo must reuse the same track (no duplicate channels).
        assertThat(project.getMixer().getChannels()).hasSize(1);
    }

    @Test
    void applyPresetActionShouldRestoreOriginalChannelStateOnUndo() {
        DawProject project = new DawProject("Test", FORMAT);
        Track track = project.createAudioTrack("Gtr");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        channel.setVolume(0.9);
        channel.setPan(-0.4);
        channel.addInsert(com.benesquivelmusic.daw.core.mixer.InsertEffectFactory
                .createSlot(InsertEffectType.NOISE_GATE, 2, 48000.0));

        ChannelStripPreset preset = new ChannelStripPreset(
                "New",
                java.util.List.of(InsertEffectSpec.ofDefaults(InsertEffectType.LIMITER)),
                java.util.List.of(),
                0.5, 0.0);
        UndoManager undo = new UndoManager();
        undo.execute(new ApplyChannelStripPresetAction(channel, preset, project.getMixer(), FORMAT));

        assertThat(channel.getVolume()).isEqualTo(0.5);
        assertThat(channel.getInsertSlot(0).getEffectType()).isEqualTo(InsertEffectType.LIMITER);

        undo.undo();
        assertThat(channel.getVolume()).isEqualTo(0.9);
        assertThat(channel.getPan()).isEqualTo(-0.4);
        assertThat(channel.getInsertCount()).isEqualTo(1);
        assertThat(channel.getInsertSlot(0).getEffectType()).isEqualTo(InsertEffectType.NOISE_GATE);
    }

    @Test
    void factoryTemplatesShouldBeDistinctAndNamed() {
        assertThat(TrackTemplateFactory.factoryTemplates())
                .extracting(TrackTemplate::templateName)
                .containsExactly("Vocal Track", "Drum Bus", "Guitar Track", "Synth Track");
    }
}
