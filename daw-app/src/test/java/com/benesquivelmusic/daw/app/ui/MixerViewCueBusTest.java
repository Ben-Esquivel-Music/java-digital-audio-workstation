package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.mixer.CueSend;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI tests for the Story 135 cue-bus mixer additions: the cue-bus strip
 * area, the per-channel cue-sends section, and the "Copy main mix" helper.
 *
 * <p>Verifies plumbing only — rendering and sample-level audio rendering are
 * covered by {@code CueBusManagerTest} in {@code daw-core}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerViewCueBusTest {

    private MixerView createOnFxThread(DawProject project) throws Exception {
        AtomicReference<MixerView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new MixerView(project));
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldAlwaysRenderAddCueBusButtonEvenWhenEmpty() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerView view = createOnFxThread(project);

        // Empty cue-bus area still contains the trailing "+" button so the
        // user can create the first cue bus without going through the menu.
        assertThat(view.getCueBusStrips().getChildren()).hasSize(1);
    }

    @Test
    void shouldRenderCueBusStripsForRegisteredBuses() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.getCueBusManager().createCueBus("Singer", 1);
        project.getCueBusManager().createCueBus("Drummer", 2);

        MixerView view = createOnFxThread(project);

        // Two cue-bus strips plus the trailing "+" button.
        assertThat(view.getCueBusStrips().getChildren()).hasSize(3);
    }

    @Test
    void shouldAddCueBusStripWhenManagerGainsBus() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerView view = createOnFxThread(project);
        assertThat(view.getCueBusStrips().getChildren()).hasSize(1);

        project.getCueBusManager().createCueBus("Singer", 1);
        runOnFxThread(view::refresh);

        assertThat(view.getCueBusStrips().getChildren()).hasSize(2);
    }

    @Test
    void copyMainMixToCueBusShouldSeedSendsFromMainFaders() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track vocals = project.createAudioTrack("Vocals");
        Track guitar = project.createAudioTrack("Guitar");
        MixerChannel vocalsChan = project.getMixerChannelForTrack(vocals);
        MixerChannel guitarChan = project.getMixerChannelForTrack(guitar);
        vocalsChan.setVolume(0.7);
        vocalsChan.setPan(-0.2);
        guitarChan.setVolume(0.5);
        CueBus singer = project.getCueBusManager().createCueBus("Singer", 1);

        MixerView view = createOnFxThread(project);
        runOnFxThread(() -> view.copyMainMixToCueBus(singer.id()));

        CueBus updated = project.getCueBusManager().getById(singer.id());
        assertThat(updated.sends()).hasSize(2);
        UUID vocalsId = UUID.fromString(vocals.getId());
        UUID guitarId = UUID.fromString(guitar.getId());
        CueSend vSend = updated.findSend(vocalsId);
        assertThat(vSend).isNotNull();
        assertThat(vSend.gain()).isEqualTo(0.7);
        assertThat(vSend.pan()).isEqualTo(-0.2);
        // Cue sends default to pre-fader during tracking — see the
        // CueBusManager.copyMainMix contract — so engineer fader moves don't
        // disturb the headphone mix.
        assertThat(vSend.preFader()).isTrue();
        CueSend gSend = updated.findSend(guitarId);
        assertThat(gSend).isNotNull();
        assertThat(gSend.gain()).isEqualTo(0.5);
    }
}
