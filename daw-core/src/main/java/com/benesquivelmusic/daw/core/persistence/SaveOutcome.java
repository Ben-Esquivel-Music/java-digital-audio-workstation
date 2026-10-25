package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * How a {@link ProjectManager#save} ended (story 331; Persistence Integrity
 * book §4.2, §5.2): {@code project.daw} holds the capture ({@link Saved}),
 * or it is as it was ({@link Failed}).
 */
public sealed interface SaveOutcome permits SaveOutcome.Saved, SaveOutcome.Failed {

    /**
     * {@code project.daw} now holds the capture.
     *
     * @param metadata   the project metadata the capture carried
     * @param revision   the edit revision the capture was taken at; the
     *                   project was marked clean if it had not moved since
     * @param checkpoint the checkpoint holding the same document — written
     *                   after the save, or one that already held this
     *                   revision — or empty when none could be written, when
     *                   the project was closed or replaced while its head was
     *                   written (no checkpoint is then requested: ruling F9),
     *                   or when it was still being written when the save
     *                   stopped waiting for it; a checkpoint that is written,
     *                   or fails, is reported to the checkpoint listeners
     *                   either way
     */
    record Saved(ProjectMetadata metadata, long revision, Optional<Path> checkpoint) implements SaveOutcome {

        /** @throws NullPointerException if an object component is {@code null} */
        public Saved {
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        }
    }

    /**
     * {@code project.daw} is as it was: the save could not be made.
     *
     * <p>{@code safetyCheckpoint} names the checkpoint that holds the
     * document this save rendered when its backup copy or its head write
     * failed (ruling G9): written as a safety copy, or one that already held
     * the revision. It is empty when nothing was rendered (the project was
     * read-only, could not be captured or rendered, its lock was lost, or it
     * was closed before the write), when the safety copy failed — its error
     * is then in {@code cause.getSuppressed()} — and when the safety copy was
     * still being written when the save stopped waiting for it; then
     * {@link #safetyCopyStillWriting()} is {@code true} (ruling J3), and the
     * checkpoint listeners hear of the copy when it ends.</p>
     *
     * @param cause            what ended the save, as thrown (the same instance)
     * @param safetyCheckpoint the checkpoint holding the rendered document, if any
     */
    record Failed(Throwable cause, Optional<Path> safetyCheckpoint) implements SaveOutcome {

        /** @throws NullPointerException if a component is {@code null} */
        public Failed {
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(safetyCheckpoint, "safetyCheckpoint must not be null");
        }

        /**
         * Whether the save stopped waiting for its safety copy while it was
         * still being written (ruling J3): {@code cause} then carries a
         * {@link TimeoutException} among its suppressed exceptions, which
         * nothing else in a save adds. The copy may still land; no safety
         * checkpoint is named.
         *
         * @return {@code true} if the safety copy was still being written
         */
        public boolean safetyCopyStillWriting() {
            if (safetyCheckpoint.isPresent()) {
                return false;
            }
            for (Throwable suppressed : cause.getSuppressed()) {
                if (suppressed instanceof TimeoutException) {
                    return true;
                }
            }
            return false;
        }
    }
}
