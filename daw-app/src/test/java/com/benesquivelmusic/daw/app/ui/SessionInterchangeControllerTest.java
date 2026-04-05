package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests the {@link SessionInterchangeController} bridging logic between
 * {@link DawProject} and {@link SessionData}.
 */
class SessionInterchangeControllerTest {

    private SessionInterchangeController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionInterchangeController();
    }

    // ── buildSessionData ────────────────────────────────────────────────────

    @Test
    void shouldBuildSessionDataFromEmptyProject() {
        DawProject project = new DawProject("Test Project", AudioFormat.STUDIO_QUALITY);
        project.getTransport().setTempo(140.0);
        project.getTransport().setTimeSignature(3, 4);

        SessionData data = controller.buildSessionData(project);

        assertThat(data.projectName()).isEqualTo("Test Project");
        assertThat(data.tempo()).isEqualTo(140.0);
        assertThat(data.timeSignatureNumerator()).isEqualTo(3);
        assertThat(data.timeSignatureDenominator()).isEqualTo(4);
        assertThat(data.sampleRate()).isEqualTo(96_000.0);
        assertThat(data.tracks()).isEmpty();
    }

    @Test
    void shouldBuildSessionDataWithTracksAndClips() {
        DawProject project = new DawProject("Full Project", AudioFormat.CD_QUALITY);

        Track audioTrack = project.createAudioTrack("Drums");
        AudioClip clip = new AudioClip("Kick", 0.0, 4.0, "audio/kick.wav");
        clip.setGainDb(-3.0);
        clip.setSourceOffsetBeats(0.5);
        audioTrack.addClip(clip);

        Track midiTrack = project.createMidiTrack("Synth");

        SessionData data = controller.buildSessionData(project);

        assertThat(data.tracks()).hasSize(2);

        SessionTrack sessionDrums = data.tracks().get(0);
        assertThat(sessionDrums.name()).isEqualTo("Drums");
        assertThat(sessionDrums.type()).isEqualTo("AUDIO");
        assertThat(sessionDrums.clips()).hasSize(1);

        SessionClip sessionClip = sessionDrums.clips().get(0);
        assertThat(sessionClip.name()).isEqualTo("Kick");
        assertThat(sessionClip.startBeat()).isEqualTo(0.0);
        assertThat(sessionClip.durationBeats()).isEqualTo(4.0);
        assertThat(sessionClip.sourceFilePath()).isEqualTo("audio/kick.wav");
        assertThat(sessionClip.gainDb()).isEqualTo(-3.0);
        assertThat(sessionClip.sourceOffsetBeats()).isEqualTo(0.5);

        SessionTrack sessionSynth = data.tracks().get(1);
        assertThat(sessionSynth.name()).isEqualTo("Synth");
        assertThat(sessionSynth.type()).isEqualTo("MIDI");
        assertThat(sessionSynth.clips()).isEmpty();
    }

    @Test
    void shouldPreserveMixerChannelSettings() {
        DawProject project = new DawProject("Mixer Test", AudioFormat.STUDIO_QUALITY);
        Track track = project.createAudioTrack("Guitar");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        channel.setVolume(0.7);
        channel.setPan(-0.3);
        channel.setMuted(true);
        channel.setSolo(true);

        SessionData data = controller.buildSessionData(project);

        SessionTrack sessionTrack = data.tracks().get(0);
        assertThat(sessionTrack.volume()).isCloseTo(0.7, within(0.001));
        assertThat(sessionTrack.pan()).isCloseTo(-0.3, within(0.001));
        assertThat(sessionTrack.muted()).isTrue();
        assertThat(sessionTrack.solo()).isTrue();
    }

    @Test
    void shouldRejectNullProjectForBuild() {
        assertThatThrownBy(() -> controller.buildSessionData(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── applySessionData ────────────────────────────────────────────────────

    @Test
    void shouldApplySessionDataToProject() {
        SessionClip clip = new SessionClip("Snare", 4.0, 2.0, 0.0, "audio/snare.wav", -1.5);
        SessionTrack audioTrack = new SessionTrack("Drums", "AUDIO", 0.8, -0.5, true, false,
                List.of(clip));
        SessionTrack midiTrack = new SessionTrack("Synth", "MIDI", 0.6, 0.3, false, true,
                List.of());
        SessionData sessionData = new SessionData("Imported Song", 135.0, 7, 8, 48000.0,
                List.of(audioTrack, midiTrack));

        DawProject project = new DawProject("Empty", AudioFormat.STUDIO_QUALITY);
        controller.applySessionData(sessionData, project);

        assertThat(project.getName()).isEqualTo("Imported Song");
        assertThat(project.getTransport().getTempo()).isEqualTo(135.0);
        assertThat(project.getTransport().getTimeSignatureNumerator()).isEqualTo(7);
        assertThat(project.getTransport().getTimeSignatureDenominator()).isEqualTo(8);
        assertThat(project.getTracks()).hasSize(2);

        Track drums = project.getTracks().get(0);
        assertThat(drums.getName()).isEqualTo("Drums");
        assertThat(drums.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(drums.getClips()).hasSize(1);

        AudioClip importedClip = drums.getClips().get(0);
        assertThat(importedClip.getName()).isEqualTo("Snare");
        assertThat(importedClip.getStartBeat()).isEqualTo(4.0);
        assertThat(importedClip.getDurationBeats()).isEqualTo(2.0);
        assertThat(importedClip.getSourceFilePath()).isEqualTo("audio/snare.wav");
        assertThat(importedClip.getGainDb()).isEqualTo(-1.5);

        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);
        assertThat(drumsChannel.getVolume()).isCloseTo(0.8, within(0.001));
        assertThat(drumsChannel.getPan()).isCloseTo(-0.5, within(0.001));
        assertThat(drumsChannel.isMuted()).isTrue();
        assertThat(drumsChannel.isSolo()).isFalse();

        Track synth = project.getTracks().get(1);
        assertThat(synth.getName()).isEqualTo("Synth");
        assertThat(synth.getType()).isEqualTo(TrackType.MIDI);

        MixerChannel synthChannel = project.getMixerChannelForTrack(synth);
        assertThat(synthChannel.getVolume()).isCloseTo(0.6, within(0.001));
        assertThat(synthChannel.getPan()).isCloseTo(0.3, within(0.001));
        assertThat(synthChannel.isMuted()).isFalse();
        assertThat(synthChannel.isSolo()).isTrue();
    }

    @Test
    void shouldMapTrackTypesCorrectly() {
        SessionData sessionData = new SessionData("Types", 120.0, 4, 4, 44100.0, List.of(
                new SessionTrack("T1", "AUDIO", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T2", "MIDI", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T3", "AUX", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T4", "MASTER", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T5", "UNKNOWN", 1.0, 0.0, false, false, List.of())
        ));

        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        controller.applySessionData(sessionData, project);

        assertThat(project.getTracks().get(0).getType()).isEqualTo(TrackType.AUDIO);
        assertThat(project.getTracks().get(1).getType()).isEqualTo(TrackType.MIDI);
        assertThat(project.getTracks().get(2).getType()).isEqualTo(TrackType.AUX);
        assertThat(project.getTracks().get(3).getType()).isEqualTo(TrackType.MASTER);
        assertThat(project.getTracks().get(4).getType()).isEqualTo(TrackType.AUDIO);
    }

    @Test
    void shouldClampVolumeAndPan() {
        SessionData sessionData = new SessionData("Clamp", 120.0, 4, 4, 44100.0, List.of(
                new SessionTrack("Clamped", "AUDIO", 1.5, -2.0, false, false, List.of())
        ));

        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        controller.applySessionData(sessionData, project);

        MixerChannel channel = project.getMixerChannelForTrack(project.getTracks().get(0));
        assertThat(channel.getVolume()).isEqualTo(1.0);
        assertThat(channel.getPan()).isEqualTo(-1.0);
    }

    @Test
    void shouldRejectNullSessionDataForApply() {
        DawProject project = new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        assertThatThrownBy(() -> controller.applySessionData(null, project))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullProjectForApply() {
        SessionData sessionData = new SessionData("Test", 120.0, 4, 4, 44100.0, List.of());
        assertThatThrownBy(() -> controller.applySessionData(sessionData, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── buildImportSummary ──────────────────────────────────────────────────

    @Test
    void shouldBuildImportSummaryWithoutWarnings() {
        SessionTrack track = new SessionTrack("Drums", "AUDIO", 0.8, 0.0, false, false,
                List.of(new SessionClip("Kick", 0.0, 4.0, 0.0, "kick.wav", 0.0)));
        SessionData data = new SessionData("My Song", 128.0, 4, 4, 48000.0, List.of(track));
        SessionImportResult result = new SessionImportResult(data, List.of());

        String summary = controller.buildImportSummary(result);

        assertThat(summary).contains("Project: My Song");
        assertThat(summary).contains("Tempo: 128.0 BPM");
        assertThat(summary).contains("Time Signature: 4/4");
        assertThat(summary).contains("Sample Rate: 48000.0 Hz");
        assertThat(summary).contains("Tracks: 1");
        assertThat(summary).contains("Clips: 1");
        assertThat(summary).doesNotContain("Warnings:");
    }

    @Test
    void shouldBuildImportSummaryWithWarnings() {
        SessionData data = new SessionData("Song", 120.0, 4, 4, 44100.0, List.of());
        SessionImportResult result = new SessionImportResult(data,
                List.of("Automation unsupported", "Plugin chains skipped"));

        String summary = controller.buildImportSummary(result);

        assertThat(summary).contains("Warnings:");
        assertThat(summary).contains("Automation unsupported");
        assertThat(summary).contains("Plugin chains skipped");
    }

    // ── Full round-trip through export and import ───────────────────────────

    @Test
    void shouldRoundTripThroughExportAndImport(@TempDir Path tempDir) throws IOException {
        DawProject project = new DawProject("Round Trip Test", AudioFormat.CD_QUALITY);
        project.getTransport().setTempo(128.0);
        project.getTransport().setTimeSignature(6, 8);

        Track drums = project.createAudioTrack("Drums");
        AudioClip kick = new AudioClip("Kick", 0.0, 4.0, "audio/kick.wav");
        kick.setGainDb(-3.0);
        drums.addClip(kick);

        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);
        drumsChannel.setVolume(0.8);
        drumsChannel.setPan(-0.5);
        drumsChannel.setMuted(true);

        Track synth = project.createMidiTrack("Synth");
        MixerChannel synthChannel = project.getMixerChannelForTrack(synth);
        synthChannel.setVolume(0.6);
        synthChannel.setSolo(true);

        // Export
        SessionExportResult exportResult = controller.exportSession(project, tempDir, "roundtrip");
        assertThat(exportResult.outputPath()).exists();

        // Import
        SessionImportResult importResult = controller.importSession(exportResult.outputPath());
        SessionData importedData = importResult.sessionData();

        assertThat(importedData.projectName()).isEqualTo("Round Trip Test");
        assertThat(importedData.tempo()).isEqualTo(128.0);
        assertThat(importedData.timeSignatureNumerator()).isEqualTo(6);
        assertThat(importedData.timeSignatureDenominator()).isEqualTo(8);
        assertThat(importedData.tracks()).hasSize(2);

        // Apply to new project and verify
        DawProject newProject = new DawProject("Empty", AudioFormat.STUDIO_QUALITY);
        controller.applySessionData(importedData, newProject);

        assertThat(newProject.getName()).isEqualTo("Round Trip Test");
        assertThat(newProject.getTracks()).hasSize(2);

        Track importedDrums = newProject.getTracks().get(0);
        assertThat(importedDrums.getName()).isEqualTo("Drums");
        assertThat(importedDrums.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(importedDrums.getClips()).hasSize(1);

        MixerChannel importedDrumsChannel = newProject.getMixerChannelForTrack(importedDrums);
        assertThat(importedDrumsChannel.getVolume()).isCloseTo(0.8, within(0.001));
        assertThat(importedDrumsChannel.getPan()).isCloseTo(-0.5, within(0.001));
        assertThat(importedDrumsChannel.isMuted()).isTrue();
    }
}
