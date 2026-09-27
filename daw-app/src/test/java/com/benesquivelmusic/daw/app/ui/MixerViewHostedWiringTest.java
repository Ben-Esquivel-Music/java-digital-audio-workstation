package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round (N1) — a {@link MixerView} constructed with the host's
 * live {@link TrackControlWiring} supplier binds its strips through that
 * generation from its very first {@code refresh()} and never builds a
 * standalone wiring for itself (a full registry, its VMs and three continuous
 * channels per channel, plus a second strip build to replace it). The
 * shorter constructors keep the lazy standalone fallback: exactly one build,
 * replaced — not rebuilt — when a host wiring is injected later.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerViewHostedWiringTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    @Test
    void aViewConstructedWithTheHostsSupplierNeverBuildsAStandaloneWiring() throws Exception {
        DawProject project = new DawProject("Hosted", FORMAT);
        project.createAudioTrack("Drums");
        project.createAudioTrack("Bass");
        FxDispatcher hostDispatcher = new FxDispatcher();
        FxDispatcher viewDispatcher = new FxDispatcher();
        TrackControlWiring hosted = TrackControlWiring.standalone(project, hostDispatcher, null);
        MixerView view = ArrangementStripFixture.onFx(
                () -> new MixerView(project, new UndoManager(), viewDispatcher, () -> hosted));
        try {
            assertThat(view.standaloneTrackControlWiringBuilds())
                    .as("the constructor's own refresh() bound through the host's generation").isZero();
            assertThat(ArrangementStripFixture.onFx(view::getTrackControlWiring)).isSameAs(hosted);
            assertThat(viewDispatcher.openChannelCount())
                    .as("no VM was ever opened over the view's own dispatcher").isZero();
            assertThat(hostDispatcher.openChannelCount())
                    .as("the host's registry carries the VMs (2 track channels + return + master, "
                            + "3 channels each)").isEqualTo(12);

            ArrangementStripFixture.onFx(view::refresh);

            assertThat(view.standaloneTrackControlWiringBuilds()).as("still hosted after a refresh").isZero();
        } finally {
            ArrangementStripFixture.onFx(() -> {
                view.dispose();
                hosted.dispose();
            });
        }
    }

    @Test
    void aViewConstructedWithoutASupplierBuildsOneStandaloneWiringAndReplacesItOnInjection()
            throws Exception {
        DawProject project = new DawProject("Standalone", FORMAT);
        project.createAudioTrack("Drums");
        FxDispatcher hostDispatcher = new FxDispatcher();
        FxDispatcher viewDispatcher = new FxDispatcher();
        TrackControlWiring hosted = TrackControlWiring.standalone(project, hostDispatcher, null);
        MixerView view = ArrangementStripFixture.onFx(
                () -> new MixerView(project, new UndoManager(), viewDispatcher));
        try {
            assertThat(view.standaloneTrackControlWiringBuilds()).as("the pure-unit fallback").isEqualTo(1);
            assertThat(viewDispatcher.openChannelCount()).as("the standalone registry's VMs").isPositive();

            ArrangementStripFixture.onFx(() -> view.setTrackControlWiring(() -> hosted));

            assertThat(ArrangementStripFixture.onFx(view::getTrackControlWiring)).isSameAs(hosted);
            assertThat(view.standaloneTrackControlWiringBuilds())
                    .as("injection replaces the standalone wiring; it does not build another").isEqualTo(1);
            assertThat(viewDispatcher.openChannelCount())
                    .as("the standalone wiring was disposed with its channels").isZero();
        } finally {
            ArrangementStripFixture.onFx(() -> {
                view.dispose();
                hosted.dispose();
            });
        }
    }
}
