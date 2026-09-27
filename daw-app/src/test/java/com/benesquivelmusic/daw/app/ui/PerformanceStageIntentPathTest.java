package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.views.PerformanceStageView;
import com.benesquivelmusic.daw.app.ui.vm.TrackChannelRegistry;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.LinkedTrackCommandDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleArmCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleSoloCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — the Performance Stage tiles (story 280) are the third control
 * surface, and they drive the ONE intent path (Audio Engine Wiring Design Book
 * §2.10, §5.6): a tile's M/S/R toggle raises a {@code TrackCommand} through the
 * live wiring's sink, only the handler writes the model — {@code Track} and
 * {@code MixerChannel} in one dual-write — and the tile is a subscriber of the
 * same {@code TrackVM} flags the arrangement lane and the mixer strip use. With
 * a recording sink that does <em>not</em> execute, the model must stay
 * untouched: that is the proof the stage no longer writes {@code Track}
 * directly (the story-280 Track-only listeners were a dead write the engine
 * never read).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PerformanceStageIntentPathTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    /** A stage over a real registry and a recording sink; {@link #execute} decides whether the sink runs the handler. */
    private static final class StageRig {
        final DawProject project;
        final FxDispatcher dispatcher = new FxDispatcher();
        final TrackChannelRegistry registry;
        final CoreTrackIntentHandler handler;
        final List<TrackCommand> raised = new ArrayList<>();
        final TrackControlWiring wiring;
        final PerformanceStageView view;
        volatile boolean execute;

        /** FX thread only. */
        StageRig(DawProject project, boolean execute) {
            this.project = project;
            this.execute = execute;
            this.registry = new TrackChannelRegistry(project, dispatcher);
            this.handler = new CoreTrackIntentHandler(project);
            // Production shape: the sink is a LinkedTrackCommandDispatcher over the handler.
            Consumer<TrackCommand> production = new LinkedTrackCommandDispatcher(project, handler);
            Consumer<TrackCommand> sink = command -> {
                raised.add(command);
                if (this.execute) {
                    production.accept(command);
                }
            };
            this.wiring = new TrackControlWiring(registry, sink);
            this.view = new PerformanceStageView(project, messages(), new InertHost(), () -> wiring);
        }

        void close() {
            view.dispose();
            wiring.dispose();
            dispatcher.dispose();
        }
    }

    @Test
    void tileTogglesRaiseCommandsThroughTheWiringAndNeverWriteTheModelDirectly() throws Exception {
        DawProject project = new DawProject("Stage", FORMAT);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);
        StageRig rig = ArrangementStripFixture.onFx(() -> new StageRig(project, false));
        try {
            ArrangementStripFixture.onFx(() -> {
                assertThat(rig.view.trackTiles()).hasSize(2);
                TrackStrip drumsTile = rig.view.trackTiles().get(0);
                assertThat(drumsTile.isDisabled()).as("a wired tile is live").isFalse();
                assertThat(drumsTile.getTrackName()).isEqualTo("Drums");

                drumsTile.setMuted(true);   // what the tile skin's M toggle does
                drumsTile.setSoloed(true);
                drumsTile.setArmed(true);

                assertThat(rig.raised).containsExactly(
                        new ToggleMuteCommand(drums, true),
                        new ToggleSoloCommand(drums, true),
                        new ToggleArmCommand(drums, true));
                // The recording sink did not execute: the stage wrote nothing itself.
                assertThat(drums.isMuted()).isFalse();
                assertThat(drums.isSolo()).isFalse();
                assertThat(drums.isArmed()).isFalse();
                assertThat(drumsChannel.isMuted()).isFalse();
                assertThat(drumsChannel.isSolo()).isFalse();
                assertThat(bass.isMuted()).isFalse();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void executedTileGesturesDualWriteTrackAndChannelAndTheOtherSurfaceMovesTheTile() throws Exception {
        DawProject project = new DawProject("Stage", FORMAT);
        Track drums = project.createAudioTrack("Drums");
        Track bass = project.createAudioTrack("Bass");
        MixerChannel drumsChannel = project.getMixerChannelForTrack(drums);
        MixerChannel bassChannel = project.getMixerChannelForTrack(bass);
        StageRig rig = ArrangementStripFixture.onFx(() -> new StageRig(project, true));
        try {
            ArrangementStripFixture.onFx(() -> {
                TrackStrip drumsTile = rig.view.trackTiles().get(0);
                TrackStrip bassTile = rig.view.trackTiles().get(1);

                drumsTile.setMuted(true);
                assertThat(drums.isMuted()).as("Track (arrangement authority)").isTrue();
                assertThat(drumsChannel.isMuted()).as("MixerChannel (what the engine reads)").isTrue();
                assertThat(bass.isMuted()).isFalse();
                assertThat(bassChannel.isMuted()).isFalse();
                assertThat(bassTile.isMuted()).isFalse();
                assertThat(rig.raised).hasSize(1);

                // The arrangement / mixer path unmutes through the same handler: the
                // tile follows the ONE TrackVM flag and raises nothing.
                rig.handler.toggleMute(drums, false);
                assertThat(drumsTile.isMuted()).isFalse();
                assertThat(rig.raised).hasSize(1);

                // A rename on the other surface moves the tile's name too.
                drums.setName("Kick");
                assertThat(drumsTile.getTrackName()).isEqualTo("Kick");

                bassTile.setSoloed(true);
                assertThat(bass.isSolo()).isTrue();
                assertThat(bassChannel.isSolo()).isTrue();
                assertThat(rig.raised).hasSize(2).last().isEqualTo(new ToggleSoloCommand(bass, true));

                // dispose() severs both directions.
                rig.view.dispose();
                bassTile.setSoloed(false);
                assertThat(bass.isSolo()).as("a disposed tile writes nothing").isTrue();
                assertThat(rig.raised).hasSize(2);
                rig.handler.toggleMute(drums, true);
                assertThat(drumsTile.isMuted()).as("a disposed tile no longer follows the VM").isFalse();
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }

    @Test
    void withoutAWiringTheTilesAreDisabledRatherThanDead() throws Exception {
        DawProject project = new DawProject("Stage", FORMAT);
        Track drums = project.createAudioTrack("Drums");
        ArrangementStripFixture.onFx(() -> {
            PerformanceStageView view = new PerformanceStageView(project, messages(), new InertHost());
            try {
                TrackStrip tile = view.trackTiles().get(0);
                assertThat(tile.isDisabled()).as("no wiring → an inert control, not a dead one").isTrue();
                tile.setMuted(true);
                assertThat(drums.isMuted()).as("nothing writes the Track behind the engine's back").isFalse();
                assertThat(project.getMixerChannelForTrack(drums).isMuted()).isFalse();
            } finally {
                view.dispose();
            }
        });
    }

    private static ResourceBundle messages() {
        return ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
    }

    private static final class InertHost implements PerformanceStageView.Host {
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onToggleLoop() { }
        @Override public void onExitPerformanceStage() { }
        @Override public void onOpenAudioSettings() { }
        @Override public void onNewProject() { }
        @Override public void onOpenProject() { }
        @Override public void onSaveProject() { }
        @Override public void onRecentProjects() { }
    }
}
