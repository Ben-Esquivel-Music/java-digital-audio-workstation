package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixerViewTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized — will verify below
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        // Verify the FX Application Thread is actually processing events.
        // Platform.runLater() itself can block if the toolkit is wedged,
        // so we call it from a daemon thread with a timeout.
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
                // Platform.runLater failed — toolkit is not functional
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

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
    void shouldRejectNullProject() {
        assertThatThrownBy(() -> new MixerView(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRenderEmptyMixerWithMasterOnly() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerView view = createOnFxThread(project);

        assertThat(view).isNotNull();
        assertThat(view.getChannelStrips().getChildren()).isEmpty();
        assertThat(view.getMasterStrip()).isNotNull();
    }

    @Test
    void shouldRenderChannelStripsForTracks() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocals");
        project.createMidiTrack("Piano");

        MixerView view = createOnFxThread(project);

        assertThat(view.getChannelStrips().getChildren()).hasSize(2);
    }

    @Test
    void shouldRefreshWhenTracksChange() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerView view = createOnFxThread(project);

        assertThat(view.getChannelStrips().getChildren()).isEmpty();

        Track track = project.createAudioTrack("Guitar");
        runOnFxThread(view::refresh);

        assertThat(view.getChannelStrips().getChildren()).hasSize(1);

        project.removeTrack(track);
        runOnFxThread(view::refresh);

        assertThat(view.getChannelStrips().getChildren()).isEmpty();
    }

    @Test
    void shouldHaveMixerPanelStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerView view = createOnFxThread(project);

        assertThat(view.getStyleClass()).contains("mixer-panel");
    }

    @Test
    void shouldSyncVolumeWithMixerChannel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Bass");
        MixerChannel channel = project.getMixerChannelForTrack(track);

        MixerView view = createOnFxThread(project);

        assertThat(view.getChannelStrips().getChildren()).hasSize(1);
        assertThat(channel.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldHandleMultipleTracksAndRefresh() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Track 1");
        project.createAudioTrack("Track 2");
        project.createMidiTrack("Track 3");

        MixerView view = createOnFxThread(project);
        assertThat(view.getChannelStrips().getChildren()).hasSize(3);

        project.createAudioTrack("Track 4");
        runOnFxThread(view::refresh);
        assertThat(view.getChannelStrips().getChildren()).hasSize(4);
    }
}
