package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelPanCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.SetChannelVolumeCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshotManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.Consumer;

import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.db;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.isActive;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.onFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 contract (probe-derived, kept as a permanent guard for the
 * snapshot / A-B recall row of the Audio Engine Wiring Design Book §5.6):
 * recalling snapshot B re-seeds EVERY bound control on both surfaces (mixer
 * strip fader / pan / M / S and arrangement lane slider / pan / M / S), heals
 * the {@code Track} mirrors through the intent path, adds no undo entries of
 * its own, a single nudge afterwards changes exactly one channel, and undo
 * restores A everywhere. Scenes use odd values only, applied through the
 * production wiring ({@link Story322ContractRig}).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322RecallReseedContractTest {

    private record Scene3(double[] vol, double[] pan, boolean[] muted, boolean[] soloed) { }

    private static final Scene3 A = new Scene3(
            new double[] {0.81, 0.55, 0.37}, new double[] {-0.62, 0.0, 0.81},
            new boolean[] {false, false, false}, new boolean[] {false, false, false});
    private static final Scene3 B = new Scene3(
            new double[] {0.37, 0.81, 0.55}, new double[] {0.81, -0.62, 0.0},
            new boolean[] {false, true, false}, new boolean[] {false, false, true});

    private static final class World implements AutoCloseable {
        final DawProject project = new DawProject("Story322", Story322ContractRig.FORMAT);
        final List<Track> tracks;
        final Story322ContractRig rig;

        World() {
            tracks = List.of(project.createAudioTrack("Kick"),
                    project.createAudioTrack("Bass"),
                    project.createAudioTrack("Keys"));
            rig = new Story322ContractRig(project);
            tracks.forEach(rig::lane);
        }

        MixerChannel channel(int i) {
            return project.getMixerChannelForTrack(tracks.get(i));
        }

        /** Applies a scene through the ONE intent path (so both models agree). */
        void apply(Scene3 scene) {
            Consumer<TrackCommand> sink = rig.wiring.commandSink();
            for (int i = 0; i < 3; i++) {
                sink.accept(new SetChannelVolumeCommand(channel(i), scene.vol()[i]));
                sink.accept(new SetChannelPanCommand(channel(i), scene.pan()[i]));
                sink.accept(new ToggleMuteCommand(tracks.get(i), scene.muted()[i]));
                sink.accept(new ToggleSoloCommand(tracks.get(i), scene.soloed()[i]));
            }
        }

        MixerSnapshot capture(String name) {
            return MixerSnapshot.capture(project.getMixer(), name);
        }

        void assertScene(Scene3 scene, String what) {
            for (int i = 0; i < 3; i++) {
                Track t = tracks.get(i);
                MixerChannel c = channel(i);
                MixerChannelStrip strip = rig.strip(i);
                var lane = rig.laneControls(t);
                assertThat(c.getVolume()).as("%s channel %d volume", what, i).isCloseTo(scene.vol()[i], within(1e-12));
                assertThat(t.getVolume()).as("%s Track %d volume", what, i).isCloseTo(scene.vol()[i], within(1e-12));
                assertThat(c.getPan()).as("%s channel %d pan", what, i).isEqualTo(scene.pan()[i]);
                assertThat(t.getPan()).as("%s Track %d pan", what, i).isEqualTo(scene.pan()[i]);
                assertThat(c.isMuted()).as("%s channel %d muted", what, i).isEqualTo(scene.muted()[i]);
                assertThat(t.isMuted()).as("%s Track %d muted", what, i).isEqualTo(scene.muted()[i]);
                assertThat(c.isSolo()).as("%s channel %d solo", what, i).isEqualTo(scene.soloed()[i]);
                assertThat(t.isSolo()).as("%s Track %d solo", what, i).isEqualTo(scene.soloed()[i]);
                assertThat(strip.getFaderDb()).as("%s strip %d fader", what, i).isCloseTo(db(scene.vol()[i]), within(1e-9));
                assertThat(strip.getPan()).as("%s strip %d pan", what, i).isEqualTo(scene.pan()[i]);
                assertThat(strip.isMuted()).as("%s strip %d muted", what, i).isEqualTo(scene.muted()[i]);
                assertThat(strip.isSoloed()).as("%s strip %d soloed", what, i).isEqualTo(scene.soloed()[i]);
                assertThat(lane.volumeSlider().getValue()).as("%s lane %d volume", what, i).isCloseTo(scene.vol()[i], within(1e-12));
                assertThat(lane.panSlider().getValue()).as("%s lane %d pan", what, i).isEqualTo(scene.pan()[i]);
                assertThat(isActive(lane.muteBtn())).as("%s lane %d mute :active", what, i).isEqualTo(scene.muted()[i]);
                assertThat(isActive(lane.soloBtn())).as("%s lane %d solo :active", what, i).isEqualTo(scene.soloed()[i]);
            }
        }

        @Override
        public void close() {
            rig.close();
        }
    }

    @Test
    void togglingToBReseedsBothSurfacesHealsTheTracksANudgeMovesOneChannelAndUndoRestoresA() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                MixerSnapshotManager manager = w.project.getMixerSnapshotManager();
                w.apply(A);
                manager.setSlot(MixerSnapshotManager.Slot.A, w.capture("A"));
                w.apply(B);
                manager.setSlot(MixerSnapshotManager.Slot.B, w.capture("B"));
                w.apply(A);
                manager.setActiveSlot(MixerSnapshotManager.Slot.A);
                w.assertScene(A, "start");
                int historyBefore = w.rig.undoManager.getHistory().size();

                w.rig.mixerView.toggleAB();

                assertThat(manager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.B);
                w.assertScene(B, "after recall of B");
                assertThat(w.rig.undoManager.getHistory().size())
                        .as("the heal added no undo entries of its own")
                        .isEqualTo(historyBefore + 1);

                w.rig.strip(0).setFaderDb(-7.3); // one nudge on the recalled scene
                double nudged = Math.pow(10.0, -7.3 / 20.0);

                assertThat(w.channel(0).getVolume()).isCloseTo(nudged, within(1e-12));
                assertThat(w.tracks.get(0).getVolume()).isCloseTo(nudged, within(1e-12));
                assertThat(w.rig.laneControls(w.tracks.get(0)).volumeSlider().getValue()).isCloseTo(nudged, within(1e-12));
                for (int i = 1; i < 3; i++) {
                    assertThat(w.channel(i).getVolume()).as("channel %d untouched by the nudge", i).isEqualTo(B.vol()[i]);
                    assertThat(w.tracks.get(i).getVolume()).isEqualTo(B.vol()[i]);
                    assertThat(w.rig.strip(i).getFaderDb()).isCloseTo(db(B.vol()[i]), within(1e-9));
                    assertThat(w.channel(i).getPan()).isEqualTo(B.pan()[i]);
                }
                assertThat(w.rig.undoManager.getHistory().size()).isEqualTo(historyBefore + 1);

                assertThat(w.rig.undoManager.undo()).isTrue();

                assertThat(manager.getActiveSlot()).isEqualTo(MixerSnapshotManager.Slot.A);
                w.assertScene(A, "after undo");
            }
        });
    }
}
