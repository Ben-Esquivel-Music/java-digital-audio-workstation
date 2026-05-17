package com.benesquivelmusic.daw.app.ui;

import java.nio.file.Path;

/**
 * Single-channel sample auditioner seam for the browser panel's per-row
 * audition button (story 275, UI Design Book §5.5).
 *
 * <p>{@link BrowserPanel} depends on this interface rather than the
 * concrete engine so headless tests can inject a fake that opens no
 * audio device. The production binding is
 * {@link SamplePreviewAuditioner}, which delegates to
 * {@code com.benesquivelmusic.daw.core.browser.SamplePreviewPlayer}.</p>
 *
 * <p>The contract is deliberately single-channel: {@link #play(Path)}
 * stops any preview already in progress before starting the new one, so
 * the user can never layer ten samples on top of each other.</p>
 */
public interface SampleAuditioner {

    /**
     * Starts auditioning {@code file}, stopping any preview already in
     * progress first (single-channel auditioner).
     *
     * @param file the audio file to audition
     */
    void play(Path file);

    /** Stops any currently playing preview. */
    void stop();

    /**
     * @return {@code true} while a preview is playing
     */
    boolean isPlaying();

    /**
     * Sets a callback invoked when playback finishes (naturally or
     * because it was stopped). The callback may run on a background
     * thread — consumers that touch the scene graph must marshal to the
     * JavaFX Application Thread themselves.
     *
     * @param callback the callback, or {@code null} to clear
     */
    void setOnPlaybackFinished(Runnable callback);
}
