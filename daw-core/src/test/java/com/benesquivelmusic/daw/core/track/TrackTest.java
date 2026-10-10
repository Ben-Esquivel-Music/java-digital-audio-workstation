package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.recording.InputMonitoringMode;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTest {

    @Test
    void shouldCreateTrackWithDefaults() {
        Track track = new Track("Vocals", TrackType.AUDIO);

        assertThat(track.getName()).isEqualTo("Vocals");
        assertThat(track.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(track.getId()).isNotBlank();
        assertThat(track.getVolume()).isEqualTo(1.0);
        assertThat(track.getPan()).isEqualTo(0.0);
        assertThat(track.isMuted()).isFalse();
        assertThat(track.isSolo()).isFalse();
        assertThat(track.isArmed()).isFalse();
        assertThat(track.isPhaseInverted()).isFalse();
        assertThat(track.isFrozen()).isFalse();
    }

    @Test
    void shouldSetVolume() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setVolume(0.5);
        assertThat(track.getVolume()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectInvalidVolume() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> track.setVolume(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSetPan() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setPan(-1.0);
        assertThat(track.getPan()).isEqualTo(-1.0);
        track.setPan(1.0);
        assertThat(track.getPan()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectInvalidPan() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setPan(-1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> track.setPan(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldToggleMuteAndSolo() {
        Track track = new Track("Track", TrackType.MIDI);
        track.setMuted(true);
        assertThat(track.isMuted()).isTrue();
        track.setSolo(true);
        assertThat(track.isSolo()).isTrue();
    }

    @Test
    void shouldToggleArmed() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.isArmed()).isFalse();
        track.setArmed(true);
        assertThat(track.isArmed()).isTrue();
        track.setArmed(false);
        assertThat(track.isArmed()).isFalse();
    }

    @Test
    void shouldTogglePhaseInverted() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.isPhaseInverted()).isFalse();
        track.setPhaseInverted(true);
        assertThat(track.isPhaseInverted()).isTrue();
        track.setPhaseInverted(false);
        assertThat(track.isPhaseInverted()).isFalse();
    }

    @Test
    void shouldGenerateUniqueIds() {
        Track a = new Track("A", TrackType.AUDIO);
        Track b = new Track("B", TrackType.AUDIO);
        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    void shouldStartWithEmptyClipsList() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldAddClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip 1", 0.0, 4.0, null);

        track.addClip(clip);

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip 1", 0.0, 4.0, null);
        track.addClip(clip);

        boolean removed = track.removeClip(clip);

        assertThat(removed).isTrue();
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        AudioClip clip = new AudioClip("Clip 1", 0.0, 4.0, null);

        assertThat(track.removeClip(clip)).isFalse();
    }

    @Test
    void shouldReturnUnmodifiableClipsList() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.addClip(new AudioClip("Clip 1", 0.0, 4.0, null));

        assertThatThrownBy(() -> track.getClips().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.addClip(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static final DeviceId INTERFACE = new DeviceId("ASIO", "Interface [ASIO]");

    @Test
    void shouldDefaultToNoInputDevice() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.getInputDevice()).isEmpty();
        assertThat(track.getLegacyInputDeviceIndexHint()).isEqualTo(Track.NO_INPUT_DEVICE);
    }

    @Test
    void shouldSetAndClearStableInputDeviceIdentity() {
        Track track = new Track("Track", TrackType.AUDIO);
        List<Track.ChangeKind> changes = new ArrayList<>();
        track.addChangeListener(changes::add);

        track.setInputDevice(Optional.of(INTERFACE));
        assertThat(track.getInputDevice()).contains(INTERFACE);
        track.setInputDevice(Optional.empty());
        assertThat(track.getInputDevice()).isEmpty();

        assertThat(changes).containsExactly(Track.ChangeKind.INPUT_ROUTING, Track.ChangeKind.INPUT_ROUTING);
    }

    @Test
    void stableIdentitySupersedesTheLegacyIndexHint() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setLegacyInputDeviceIndexHint(3);

        track.setInputDevice(Optional.of(INTERFACE));

        assertThat(track.getLegacyInputDeviceIndexHint()).isEqualTo(Track.NO_INPUT_DEVICE);
        assertThatThrownBy(() -> track.setLegacyInputDeviceIndexHint(1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Track");
    }

    @Test
    void explicitNoneAlsoDiscardsTheLegacyIndexHint() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setLegacyInputDeviceIndexHint(5);

        track.setInputDevice(Optional.empty());

        assertThat(track.getLegacyInputDeviceIndexHint()).isEqualTo(Track.NO_INPUT_DEVICE);
    }

    @Test
    void shouldRejectInvalidLegacyInputDeviceIndexHint() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setLegacyInputDeviceIndexHint(-2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptZeroLegacyInputDeviceIndexHint() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setLegacyInputDeviceIndexHint(0);
        assertThat(track.getLegacyInputDeviceIndexHint()).isEqualTo(0);
    }

    @Test
    void duplicateCarriesTheLegacyHintOfAnUnmigratedTrack() {
        Track original = new Track("Legacy", TrackType.AUDIO);
        original.setLegacyInputDeviceIndexHint(2);

        Track copy = original.duplicate("Legacy (copy)");

        assertThat(copy.getInputDevice()).isEmpty();
        assertThat(copy.getLegacyInputDeviceIndexHint()).isEqualTo(2);
    }

    // ── Recording indicator tests ───────────────────────────────────────────

    @Test
    void shouldDefaultToNotRecording() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.isRecording()).isFalse();
    }

    @Test
    void shouldToggleRecordingIndicator() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setRecording(true);
        assertThat(track.isRecording()).isTrue();
        track.setRecording(false);
        assertThat(track.isRecording()).isFalse();
    }

    // ── Input monitoring mode tests ─────────────────────────────────────────

    @Test
    void shouldDefaultToMonitoringOff() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.getInputMonitoringMode()).isEqualTo(InputMonitoringMode.OFF);
    }

    @Test
    void shouldSetInputMonitoringMode() {
        Track track = new Track("Track", TrackType.AUDIO);
        track.setInputMonitoringMode(InputMonitoringMode.AUTO);
        assertThat(track.getInputMonitoringMode()).isEqualTo(InputMonitoringMode.AUTO);

        track.setInputMonitoringMode(InputMonitoringMode.ALWAYS);
        assertThat(track.getInputMonitoringMode()).isEqualTo(InputMonitoringMode.ALWAYS);
    }

    @Test
    void shouldRejectNullInputMonitoringMode() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThatThrownBy(() -> track.setInputMonitoringMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Track freeze state tests ────────────────────────────────────────────

    @Test
    void shouldDefaultToNotFrozen() {
        Track track = new Track("Track", TrackType.AUDIO);
        assertThat(track.isFrozen()).isFalse();
        assertThat(track.getFrozenAudioData()).isNull();
    }

    // ── Duplicate tests ─────────────────────────────────────────────────────

    @Test
    void shouldDuplicateTrackProperties() {
        Track original = new Track("Vocals", TrackType.AUDIO);
        original.setVolume(0.8);
        original.setPan(-0.5);
        original.setMuted(true);
        original.setSolo(true);
        original.setArmed(true);
        original.setPhaseInverted(true);
        original.setInputDevice(Optional.of(INTERFACE));
        original.setInputMonitoringMode(InputMonitoringMode.AUTO);

        Track copy = original.duplicate("Vocals (copy)");

        assertThat(copy.getId()).isNotEqualTo(original.getId());
        assertThat(copy.getName()).isEqualTo("Vocals (copy)");
        assertThat(copy.getType()).isEqualTo(TrackType.AUDIO);
        assertThat(copy.getVolume()).isEqualTo(0.8);
        assertThat(copy.getPan()).isEqualTo(-0.5);
        assertThat(copy.isMuted()).isTrue();
        assertThat(copy.isSolo()).isTrue();
        assertThat(copy.isArmed()).isFalse(); // armed is never copied
        assertThat(copy.isPhaseInverted()).isTrue();
        assertThat(copy.getInputDevice()).contains(INTERFACE);
        assertThat(copy.getInputMonitoringMode()).isEqualTo(InputMonitoringMode.AUTO);
        assertThat(copy.isRecording()).isFalse(); // recording state is never copied
        assertThat(copy.isFrozen()).isFalse(); // frozen state is never copied
    }

    @Test
    void shouldDuplicateTrackWithClips() {
        Track original = new Track("Guitar", TrackType.AUDIO);
        original.addClip(new AudioClip("Riff A", 0.0, 4.0, "/riff_a.wav"));
        original.addClip(new AudioClip("Riff B", 4.0, 4.0, "/riff_b.wav"));

        Track copy = original.duplicate("Guitar (copy)");

        assertThat(copy.getClips()).hasSize(2);
        assertThat(copy.getClips().get(0).getName()).isEqualTo("Riff A");
        assertThat(copy.getClips().get(1).getName()).isEqualTo("Riff B");
        // Verify clips are copies, not the same objects
        assertThat(copy.getClips().get(0).getId())
                .isNotEqualTo(original.getClips().get(0).getId());
    }

    @Test
    void shouldRejectNullNameInDuplicate() {
        Track track = new Track("Test", TrackType.AUDIO);

        assertThatThrownBy(() -> track.duplicate(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── SoundFont assignment tests ──────────────────────────────────────────

    @Test
    void shouldDefaultToNoSoundFontAssignment() {
        Track track = new Track("MIDI Track", TrackType.MIDI);
        assertThat(track.getSoundFontAssignment()).isNull();
    }

    @Test
    void shouldSetSoundFontAssignment() {
        Track track = new Track("MIDI Track", TrackType.MIDI);
        SoundFontAssignment assignment = new SoundFontAssignment(
                Path.of("/sounds/GeneralUser.sf2"), 0, 0, "Acoustic Grand Piano");

        track.setSoundFontAssignment(assignment);

        assertThat(track.getSoundFontAssignment()).isEqualTo(assignment);
    }

    @Test
    void shouldClearSoundFontAssignment() {
        Track track = new Track("MIDI Track", TrackType.MIDI);
        track.setSoundFontAssignment(new SoundFontAssignment(
                Path.of("/sounds/GeneralUser.sf2"), 0, 0, "Piano"));

        track.setSoundFontAssignment(null);

        assertThat(track.getSoundFontAssignment()).isNull();
    }

    @Test
    void shouldDuplicateTrackWithSoundFontAssignment() {
        Track original = new Track("Synth", TrackType.MIDI);
        SoundFontAssignment assignment = new SoundFontAssignment(
                Path.of("/sounds/FluidR3.sf2"), 0, 48, "String Ensemble");
        original.setSoundFontAssignment(assignment);

        Track copy = original.duplicate("Synth (copy)");

        assertThat(copy.getSoundFontAssignment()).isEqualTo(assignment);
    }

    @Test
    void shouldDuplicateTrackWithoutSoundFontAssignment() {
        Track original = new Track("Piano", TrackType.MIDI);

        Track copy = original.duplicate("Piano (copy)");

        assertThat(copy.getSoundFontAssignment()).isNull();
    }

    // ── MIDI input device name tests ────────────────────────────────────────

    @Test
    void shouldDefaultToNoMidiInputDeviceName() {
        Track track = new Track("MIDI 1", TrackType.MIDI);
        assertThat(track.getMidiInputDeviceName()).isNull();
    }

    @Test
    void shouldSetMidiInputDeviceName() {
        Track track = new Track("MIDI 1", TrackType.MIDI);
        track.setMidiInputDeviceName("USB MIDI Controller");

        assertThat(track.getMidiInputDeviceName()).isEqualTo("USB MIDI Controller");
    }

    @Test
    void shouldClearMidiInputDeviceName() {
        Track track = new Track("MIDI 1", TrackType.MIDI);
        track.setMidiInputDeviceName("USB MIDI Controller");
        track.setMidiInputDeviceName(null);

        assertThat(track.getMidiInputDeviceName()).isNull();
    }

    @Test
    void shouldDuplicateTrackWithMidiInputDeviceName() {
        Track original = new Track("MIDI 1", TrackType.MIDI);
        original.setMidiInputDeviceName("USB MIDI Controller");

        Track copy = original.duplicate("MIDI 1 (copy)");

        assertThat(copy.getMidiInputDeviceName()).isEqualTo("USB MIDI Controller");
    }

    @Test
    void shouldDuplicateTrackWithoutMidiInputDeviceName() {
        Track original = new Track("MIDI 1", TrackType.MIDI);

        Track copy = original.duplicate("MIDI 1 (copy)");

        assertThat(copy.getMidiInputDeviceName()).isNull();
    }
}
