package com.benesquivelmusic.daw.core.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplePreviewPlayerTest {

    @Test
    void shouldStartNotPlaying() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        assertThat(player.isPlaying()).isFalse();
    }

    @Test
    void shouldHaveDefaultVolume() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        assertThat(player.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldSetVolume() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        player.setVolume(0.5);
        assertThat(player.getVolume()).isEqualTo(0.5);
    }

    @Test
    void shouldSetVolumeToZero() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        player.setVolume(0.0);
        assertThat(player.getVolume()).isEqualTo(0.0);
    }

    @Test
    void shouldSetVolumeToMax() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        player.setVolume(1.0);
        assertThat(player.getVolume()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectVolumeBelowZero() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        assertThatThrownBy(() -> player.setVolume(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volume");
    }

    @Test
    void shouldRejectVolumeAboveOne() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        assertThatThrownBy(() -> player.setVolume(1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("volume");
    }

    @Test
    void shouldRejectNullFilePath() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        assertThatThrownBy(() -> player.play(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldStopWithoutPlaying() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        // Should not throw
        player.stop();
        assertThat(player.isPlaying()).isFalse();
    }

    @Test
    void shouldAcceptCallbackSetting() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        boolean[] called = {false};
        player.setOnPlaybackFinished(() -> called[0] = true);
        // Callback should not be called just by setting it
        assertThat(called[0]).isFalse();
    }

    @Test
    void shouldAcceptNullCallback() {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        player.setOnPlaybackFinished(null);
        // Should not throw
        player.stop();
    }

    @Test
    void shouldNotPlayNonExistentFile() throws InterruptedException {
        SamplePreviewPlayer player = new SamplePreviewPlayer();
        boolean[] callbackInvoked = {false};
        player.setOnPlaybackFinished(() -> callbackInvoked[0] = true);

        player.play(Path.of("/nonexistent/file.wav"));

        // Give the daemon thread time to attempt and fail
        Thread.sleep(500);

        // Player should not remain in playing state after failure
        assertThat(player.isPlaying()).isFalse();
    }
}
