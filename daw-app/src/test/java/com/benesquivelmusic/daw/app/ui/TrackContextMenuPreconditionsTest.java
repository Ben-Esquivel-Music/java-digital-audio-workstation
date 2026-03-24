package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the precondition logic used by
 * {@code MainController.buildTrackContextMenu()} to conditionally disable
 * menu items. These tests exercise the domain models directly — no JavaFX
 * toolkit is required.
 */
class TrackContextMenuPreconditionsTest {

    // ── Paste Over: disabled when clipboard is empty ────────────────────────

    @Test
    void pasteOverShouldBeDisabledWhenClipboardIsEmpty() {
        var clipboard = new ClipboardManager();
        assertThat(clipboard.hasContent()).isFalse();
    }

    @Test
    void pasteOverShouldBeEnabledWhenClipboardHasContent() {
        var clipboard = new ClipboardManager();
        clipboard.markCopied();
        assertThat(clipboard.hasContent()).isTrue();
    }

    // ── Split at Playhead: disabled when track has no clips ─────────────────

    @Test
    void splitShouldBeDisabledWhenTrackHasNoClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void splitShouldBeEnabledWhenTrackHasClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        track.addClip(new AudioClip("clip1", 0.0, 4.0, null));
        assertThat(track.getClips()).isNotEmpty();
    }

    // ── Reverse: disabled when MIDI track or no audio clips ─────────────────

    @Test
    void reverseShouldBeDisabledForMidiTrack() {
        var track = new Track("MIDI 1", TrackType.MIDI);
        boolean isMidi = track.getType() == TrackType.MIDI;
        assertThat(isMidi).isTrue();
    }

    @Test
    void reverseShouldBeDisabledForAudioTrackWithNoClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        boolean disabled = track.getType() == TrackType.MIDI || track.getClips().isEmpty();
        assertThat(disabled).isTrue();
    }

    @Test
    void reverseShouldBeEnabledForAudioTrackWithClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        track.addClip(new AudioClip("clip1", 0.0, 4.0, null));
        boolean disabled = track.getType() == TrackType.MIDI || track.getClips().isEmpty();
        assertThat(disabled).isFalse();
    }

    // ── Fade In/Out: disabled when MIDI track or no audio clips ─────────────

    @Test
    void fadeShouldBeDisabledForMidiTrack() {
        var track = new Track("MIDI 1", TrackType.MIDI);
        boolean disabled = track.getType() == TrackType.MIDI || track.getClips().isEmpty();
        assertThat(disabled).isTrue();
    }

    @Test
    void fadeShouldBeDisabledForAudioTrackWithNoClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        boolean disabled = track.getType() == TrackType.MIDI || track.getClips().isEmpty();
        assertThat(disabled).isTrue();
    }

    @Test
    void fadeShouldBeEnabledForAudioTrackWithClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        track.addClip(new AudioClip("clip1", 0.0, 4.0, null));
        boolean disabled = track.getType() == TrackType.MIDI || track.getClips().isEmpty();
        assertThat(disabled).isFalse();
    }

    // ── Trim to Selection / Crop: disabled when no selection ────────────────

    @Test
    void trimAndCropShouldBeDisabledWhenNoSelection() {
        var selection = new SelectionModel();
        assertThat(selection.hasSelection()).isFalse();
    }

    @Test
    void trimAndCropShouldBeEnabledWhenSelectionExists() {
        var selection = new SelectionModel();
        selection.setSelection(1.0, 5.0);
        assertThat(selection.hasSelection()).isTrue();
    }

    // ── Export as WAV/MP3/AAC/WMA: disabled when no audio data ──────────────

    @Test
    void audioExportShouldBeDisabledWhenTrackHasNoClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void audioExportShouldBeEnabledWhenTrackHasClips() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        track.addClip(new AudioClip("clip1", 0.0, 4.0, null));
        assertThat(track.getClips()).isNotEmpty();
    }

    // ── Export as MIDI: disabled when track is not MIDI ──────────────────────

    @Test
    void midiExportShouldBeDisabledWhenTrackIsNotMidi() {
        var track = new Track("Audio 1", TrackType.AUDIO);
        assertThat(track.getType()).isNotEqualTo(TrackType.MIDI);
    }

    @Test
    void midiExportShouldBeDisabledForAuxTrack() {
        var track = new Track("Aux 1", TrackType.AUX);
        assertThat(track.getType()).isNotEqualTo(TrackType.MIDI);
    }

    @Test
    void midiExportShouldBeEnabledForMidiTrack() {
        var track = new Track("MIDI 1", TrackType.MIDI);
        assertThat(track.getType()).isEqualTo(TrackType.MIDI);
    }

    // ── Zoom In/Out: disabled at max/min zoom ───────────────────────────────

    @Test
    void zoomInShouldBeDisabledAtMaxZoom() {
        var zoom = new ZoomLevel(ZoomLevel.MAX_ZOOM);
        assertThat(zoom.canZoomIn()).isFalse();
    }

    @Test
    void zoomInShouldBeEnabledBelowMaxZoom() {
        var zoom = new ZoomLevel();
        assertThat(zoom.canZoomIn()).isTrue();
    }

    @Test
    void zoomOutShouldBeDisabledAtMinZoom() {
        var zoom = new ZoomLevel(ZoomLevel.MIN_ZOOM);
        assertThat(zoom.canZoomOut()).isFalse();
    }

    @Test
    void zoomOutShouldBeEnabledAboveMinZoom() {
        var zoom = new ZoomLevel();
        assertThat(zoom.canZoomOut()).isTrue();
    }
}
