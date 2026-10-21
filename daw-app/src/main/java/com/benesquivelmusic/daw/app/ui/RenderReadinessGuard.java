package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.List;
import java.util.Objects;

/**
 * The check every entry point that renders clip audio into a file runs
 * before it renders anything — export, bounce, freeze (story 329; decisions
 * D19 and D28; the studio-engineer's risk 2: the bouncer silently skips a
 * clip with no audio, so a deliverable rendered while clips load, or with
 * assets missing, would be missing parts without a word).
 *
 * <p>It consults the one readiness query of the open project's audio,
 * {@link OpenProjectAudio#readinessOf}, for the tracks the render reads:</p>
 * <ul>
 *   <li>a clip of those tracks still loading — refused with the WARNING
 *       {@code Audio is still loading (N of M) — try again when loading
 *       completes}, its counts the whole project's; a track that has loaded
 *       renders while other tracks still load;</li>
 *   <li>clips of those tracks that would render silent (missing,
 *       unreadable, too large, skipped) — one explicit confirmation in the
 *       words of the {@linkplain RenderPath render path}: an export asks
 *       {@code K clip(s) have missing audio and will be silent. Export
 *       anyway?}; a freeze also says the frozen audio stays silent until it
 *       is unfrozen or frozen again, even after a relink;</li>
 *   <li>otherwise the render proceeds.</li>
 * </ul>
 * FX thread.
 */
final class RenderReadinessGuard {

    /** What the render makes: each asks in its own words (decision D28). */
    enum RenderPath {

        /** A file the user exports — Export as WAV. */
        EXPORT("projectAudio.render.silent", "projectAudio.render.silent.confirm"),

        /** A frozen track — both freeze entry points. */
        FREEZE("projectAudio.render.silent.freeze", "projectAudio.render.silent.freeze.confirm");

        private final String questionKey;
        private final String confirmKey;

        RenderPath(String questionKey, String confirmKey) {
            this.questionKey = questionKey;
            this.confirmKey = confirmKey;
        }
    }

    /** Asks the user whether to render with silent clips; tests answer without a dialog. */
    @FunctionalInterface
    interface Confirmation {

        /**
         * Asks {@code message}.
         *
         * @param title        the question's title
         * @param message      the question
         * @param confirmLabel the label of the button that renders anyway
         * @return {@code true} if the user wants the render anyway
         */
        boolean confirm(String title, String message, String confirmLabel);
    }

    private final OpenProjectAudio audio;
    private final NotificationBar notificationBar;
    private Confirmation confirmation;

    /**
     * Creates the guard.
     *
     * @param audio           the owner of the open project's audio
     * @param notificationBar where the refusal goes
     * @param confirmation    asks whether to render with silent clips
     */
    RenderReadinessGuard(OpenProjectAudio audio, NotificationBar notificationBar, Confirmation confirmation) {
        this.audio = Objects.requireNonNull(audio, "audio must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation must not be null");
    }

    /**
     * Whether a render of {@code tracks} through {@code path} may proceed:
     * refused (and said so) while a clip of {@code tracks} still loads;
     * asked, in the path's words, when clips of {@code tracks} would render
     * silent.
     *
     * @param path   what the render makes
     * @param tracks the tracks the render reads
     * @return {@code true} if the render may proceed
     */
    boolean mayRender(RenderPath path, List<Track> tracks) {
        Objects.requireNonNull(path, "path must not be null");
        OpenProjectAudio.Readiness readiness = audio.readinessOf(tracks);
        if (readiness.loading()) {
            notificationBar.show(NotificationLevel.WARNING, ProjectAudioText.text("projectAudio.render.loading",
                    Integer.toString(readiness.loadedSources()), Integer.toString(readiness.totalSources())));
            return false;
        }
        if (readiness.silentClips() > 0) {
            return confirmation.confirm(ProjectAudioText.text("projectAudio.render.silent.title"),
                    ProjectAudioText.count(path.questionKey, readiness.silentClips()),
                    ProjectAudioText.text(path.confirmKey));
        }
        return true;
    }

    /**
     * Replaces the confirmation (tests answer without a dialog).
     *
     * @param confirmation the confirmation
     */
    void setConfirmation(Confirmation confirmation) {
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation must not be null");
    }
}
