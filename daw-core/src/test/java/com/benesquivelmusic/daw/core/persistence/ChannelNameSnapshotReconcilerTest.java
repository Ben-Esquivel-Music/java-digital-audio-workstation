package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChannelNameSnapshotReconciler} — story 199.
 *
 * <p>Verifies the user-story acceptance criterion: when the live driver
 * reports a different channel name at the same index than the snapshot
 * persisted with the project, the reconciler emits exactly <em>one</em>
 * warning per project, not one per affected track.</p>
 */
class ChannelNameSnapshotReconcilerTest {

    private static DawProject newProject() {
        return new DawProject("Test", new AudioFormat(48_000.0, 2, 16, 512));
    }

    @Test
    void reconcileShouldEmitNoWarningWhenLiveNamesMatchSnapshot() {
        DawProject project = newProject();
        Track track = new Track("Vox", TrackType.AUDIO);
        track.setInputRouting(new InputRouting(2, 1));
        track.setInputRoutingDisplayName("Mic 3");
        project.addTrack(track);

        var live = List.of(
                new AudioChannelInfo(0, "Mic 1"),
                new AudioChannelInfo(1, "Mic 2"),
                new AudioChannelInfo(2, "Mic 3"));

        var result = ChannelNameSnapshotReconciler.reconcile(project, live, List.of());

        assertThat(result.warning()).isEmpty();
        assertThat(result.renames()).isEmpty();
        // Snapshot stays put.
        assertThat(track.getInputRoutingDisplayName()).isEqualTo("Mic 3");
    }

    @Test
    void reconcileShouldEmitSingleWarningRegardlessOfHowManyTracksShareTheSnapshot() {
        // The user-story acceptance criterion: a project with N tracks
        // routed to the same renamed channel must produce a SINGLE
        // warning, not N.
        DawProject project = newProject();
        for (int i = 0; i < 5; i++) {
            Track t = new Track("Track " + i, TrackType.AUDIO);
            t.setInputRouting(new InputRouting(2, 1));
            t.setInputRoutingDisplayName("Mic 3");
            project.addTrack(t);
        }

        var live = List.of(
                new AudioChannelInfo(0, "Mic 1"),
                new AudioChannelInfo(1, "Mic 2"),
                new AudioChannelInfo(2, "Hi-Z Inst 3"));

        var result = ChannelNameSnapshotReconciler.reconcile(project, live, List.of());

        assertThat(result.warning()).isPresent();
        assertThat(result.warning().get())
                .isEqualTo("Channel names changed since last save: 'Mic 3' → 'Hi-Z Inst 3'");
        assertThat(result.renames()).hasSize(1);
        assertThat(result.renames().get(0).oldName()).isEqualTo("Mic 3");
        assertThat(result.renames().get(0).newName()).isEqualTo("Hi-Z Inst 3");

        // Every track's snapshot is rewritten to the live name so a
        // subsequent save persists the new name and reload does not warn
        // again.
        for (Track t : project.getTracks()) {
            assertThat(t.getInputRoutingDisplayName()).isEqualTo("Hi-Z Inst 3");
        }
    }

    @Test
    void reconcileShouldAggregateMultipleDistinctRenamesIntoOneWarning() {
        DawProject project = newProject();

        Track t1 = new Track("Vox", TrackType.AUDIO);
        t1.setInputRouting(new InputRouting(2, 1));
        t1.setInputRoutingDisplayName("Mic 3");
        project.addTrack(t1);

        Track t2 = new Track("DI", TrackType.AUDIO);
        t2.setInputRouting(new InputRouting(3, 1));
        t2.setInputRoutingDisplayName("Line 4");
        project.addTrack(t2);

        var live = List.of(
                new AudioChannelInfo(2, "Hi-Z Inst 3"),
                new AudioChannelInfo(3, "S/PDIF L"));

        var result = ChannelNameSnapshotReconciler.reconcile(project, live, List.of());

        assertThat(result.warning()).isPresent();
        // One warning, two renames inside.
        assertThat(result.warning().get())
                .startsWith("Channel names changed since last save:")
                .contains("'Mic 3' → 'Hi-Z Inst 3'")
                .contains("'Line 4' → 'S/PDIF L'");
        assertThat(result.renames()).hasSize(2);
    }

    @Test
    void reconcileShouldDetectOutputRoutingRenamesOnMixerChannels() {
        DawProject project = newProject();
        // The constructor adds a default master channel; first user-added
        // track also adds a channel. Add a channel and reroute it to
        // outputs 3-4 with a snapshot.
        Track track = new Track("Drums", TrackType.AUDIO);
        project.addTrack(track);
        MixerChannel ch = project.getMixerChannelForTrack(track);
        assertThat(ch).isNotNull();
        ch.setOutputRouting(new OutputRouting(2, 2));
        ch.setOutputRoutingDisplayName("Cue Out 3-4");

        var liveOutputs = List.of(
                new AudioChannelInfo(2, "Phones 1 L"),
                new AudioChannelInfo(3, "Phones 1 R"));

        var result = ChannelNameSnapshotReconciler.reconcile(project, List.of(), liveOutputs);

        assertThat(result.warning()).isPresent();
        assertThat(result.warning().get())
                .contains("'Cue Out 3-4' → 'Phones 1 L'");
        assertThat(ch.getOutputRoutingDisplayName()).isEqualTo("Phones 1 L");
    }

    @Test
    void reconcileShouldIgnoreTracksWithoutSnapshot() {
        DawProject project = newProject();
        Track track = new Track("Vox", TrackType.AUDIO);
        track.setInputRouting(new InputRouting(2, 1));
        // No snapshot recorded — older project that pre-dates story 199.
        project.addTrack(track);

        var live = List.of(new AudioChannelInfo(2, "Hi-Z Inst 3"));

        var result = ChannelNameSnapshotReconciler.reconcile(project, live, List.of());

        // No snapshot → nothing to compare → no warning.
        assertThat(result.warning()).isEmpty();
    }

    @Test
    void reconcileShouldIgnoreNoneAndMasterRoutings() {
        DawProject project = newProject();
        Track track = new Track("Vox", TrackType.AUDIO);
        track.setInputRouting(InputRouting.NONE);
        track.setInputRoutingDisplayName("Mic 3"); // stale
        project.addTrack(track);

        var live = List.of(new AudioChannelInfo(2, "Hi-Z Inst 3"));

        var result = ChannelNameSnapshotReconciler.reconcile(project, live, List.of());

        // Routing is NONE — there is nothing to reconcile against.
        assertThat(result.warning()).isEmpty();
    }
}
