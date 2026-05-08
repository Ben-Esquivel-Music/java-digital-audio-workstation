package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TrackFreezeController} that don't require the
 * JavaFX toolkit. The freeze workflow itself instantiates a
 * {@link TaskProgressIndicator} (which creates a JavaFX
 * {@code Stage}) and is exercised indirectly by the daw-core
 * {@code BatchFreezeTracksActionTest} / {@code BatchUnfreezeTracksActionTest}
 * suites; the assertions below cover the pure-Java helpers used by
 * the on-track ❄ snowflake glyph (tooltip text and cache-state
 * lookup).
 */
class TrackFreezeControllerTest {

    @Test
    void tooltipForUnfrozenTrackIsEmpty() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        Track track = new Track("t", TrackType.AUDIO);
        TrackFreezeController c = new TrackFreezeController(
                project, new UndoManager(), null, t -> {}, () -> {}, (m, i) -> {});

        assertThat(c.tooltipFor(track)).isEmpty();
    }

    @Test
    void tooltipForFrozenTrackWithoutRecordIsGeneric() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        Track track = freezeFixtureTrack(project);
        TrackFreezeController c = new TrackFreezeController(
                project, new UndoManager(), null, t -> {}, () -> {}, (m, i) -> {});

        assertThat(c.tooltipFor(track))
                .isEqualTo("Frozen — pre-rendered audio is in use");
    }

    @Test
    void cacheStateIsEmptyBeforeAnyFreeze() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        Track track = new Track("t", TrackType.AUDIO);
        TrackFreezeController c = new TrackFreezeController(
                project, new UndoManager(), null, t -> {}, () -> {}, (m, i) -> {});

        assertThat(c.cacheState(track)).isEmpty();
        assertThat(c.cacheState(null)).isEmpty();
    }

    @Test
    void freezeIsNoOpForNullOrAlreadyFrozenTrack() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        Track track = freezeFixtureTrack(project);
        java.util.concurrent.atomic.AtomicReference<String> lastStatus = new java.util.concurrent.atomic.AtomicReference<>();
        TrackFreezeController c = new TrackFreezeController(
                project, new UndoManager(), null, t -> {}, () -> {},
                (m, i) -> lastStatus.set(m));

        c.freezeTrack(null);
        assertThat(lastStatus.get()).isEqualTo("No track selected to freeze");

        c.freezeTrack(track);
        assertThat(lastStatus.get()).startsWith("Track is already frozen");
    }

    /**
     * Builds a fully-set-up audio track and freezes it via the core
     * service so the {@link Track#isFrozen()} flag is genuine without
     * needing access to the package-private mutators.
     */
    private static Track freezeFixtureTrack(DawProject project) {
        Track track = new Track("t", TrackType.AUDIO);
        com.benesquivelmusic.daw.core.audio.AudioClip clip =
                new com.benesquivelmusic.daw.core.audio.AudioClip("c", 0.0, 1.0, null);
        int frames = (int) Math.round(60.0 / 120.0 * project.getFormat().sampleRate());
        clip.setAudioData(new float[project.getFormat().channels()][frames]);
        track.addClip(clip);
        project.addTrack(track);
        com.benesquivelmusic.daw.core.track.TrackFreezeService.freeze(
                track, project.getMixerChannelForTrack(track),
                (int) project.getFormat().sampleRate(), 120.0,
                project.getFormat().channels());
        return track;
    }

    @Test
    void unfreezeIsNoOpForNullOrUnfrozenTrack() {
        DawProject project = new DawProject("p", AudioFormat.STUDIO_QUALITY);
        Track track = new Track("t", TrackType.AUDIO);
        java.util.concurrent.atomic.AtomicReference<String> lastStatus = new java.util.concurrent.atomic.AtomicReference<>();
        TrackFreezeController c = new TrackFreezeController(
                project, new UndoManager(), null, t -> {}, () -> {},
                (m, i) -> lastStatus.set(m));

        c.unfreezeTrack(null);
        assertThat(lastStatus.get()).isEqualTo("No track selected to unfreeze");

        c.unfreezeTrack(track);
        assertThat(lastStatus.get()).startsWith("Track is not frozen");
    }
}
