package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.browser.SamplePreviewPlayer;

import java.nio.file.Path;

/**
 * Production {@link SampleAuditioner} binding (story 275) — a thin
 * adapter over the engine's
 * {@link SamplePreviewPlayer}. The player is itself single-channel
 * ({@code play(Path)} stops any current preview first), so this adapter
 * is a straight delegation.
 *
 * <p>{@code SamplePreviewPlayer} runs playback on its own daemon thread
 * and fires the playback-finished callback off the JavaFX Application
 * Thread; callers (e.g. {@link BrowserPanel}) marshal that callback back
 * to the FX thread before touching the scene graph.</p>
 */
public final class SamplePreviewAuditioner implements SampleAuditioner {

    private final SamplePreviewPlayer player = new SamplePreviewPlayer();

    @Override
    public void play(Path file) {
        player.play(file);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public boolean isPlaying() {
        return player.isPlaying();
    }

    @Override
    public void setOnPlaybackFinished(Runnable callback) {
        player.setOnPlaybackFinished(callback);
    }
}
