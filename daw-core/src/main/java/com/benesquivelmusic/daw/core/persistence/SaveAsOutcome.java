package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * How a Save As copy ended (story 333, ruling R5;
 * {@link ProjectManager#publishCopy}): {@linkplain Published published},
 * {@linkplain Cancelled cancelled} before its {@code project.daw} was
 * written, or {@linkplain Failed failed}. A cancelled or failed copy deleted
 * what it created, as far as it could (a delete that failed is logged, or
 * attached to the failure); the open project is unchanged either way.
 */
public sealed interface SaveAsOutcome permits SaveAsOutcome.Published, SaveAsOutcome.Cancelled,
        SaveAsOutcome.Failed {

    /**
     * The copy's folder exists, whole, with its {@code project.daw} written
     * last. Nothing is open in it: no lock, journal or checkpoints
     * ({@link ProjectManager#adoptPublishedCopy} opens it).
     *
     * @param folder         the copy's folder
     * @param metadata       the copy's metadata, its folder set
     * @param sourceFolder   the folder the project was copied from; empty for a project never saved
     * @param formerRoots    the folders the copy was taken from, oldest first — the source folder's own former
     *                       roots, then the source folder — each with its own held set, by path relative to
     *                       {@code folder}: the source folder's set is every file the copy holds, and an older
     *                       root's set the files of its set the copy took at the same path. The copy's saves
     *                       write a reference that lies under those folders project-relative only when the held
     *                       set of a folder it lies under holds its path, as its path under the newest such
     *                       folder, and the live project is re-pointed at the copy
     *                       for the source folder's set alone
     *                       ({@link ProjectAssetReferences#rebase(com.benesquivelmusic.daw.core.project.DawProject,
     *                       Path, Path, java.util.Set)}, {@link FormerRoots#heldFrom})
     * @param missingFiles   the audio files the project uses that were missing and were not copied
     * @param unknownEntries the source folder's top-level entries left out because nothing knows them
     */
    record Published(Path folder, ProjectMetadata metadata, Optional<Path> sourceFolder, FormerRoots formerRoots,
                     int missingFiles, int unknownEntries) implements SaveAsOutcome {

        /** @throws NullPointerException if a component is {@code null} */
        public Published {
            Objects.requireNonNull(folder, "folder must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
            Objects.requireNonNull(sourceFolder, "sourceFolder must not be null");
            Objects.requireNonNull(formerRoots, "formerRoots must not be null");
        }
    }

    /** Cancelled before {@code project.daw} was written: what the copy created was deleted, as far as it could. */
    record Cancelled() implements SaveAsOutcome {
    }

    /**
     * The copy failed before it was published: everything it created was
     * deleted (a delete that failed is attached to {@code cause} as
     * suppressed).
     *
     * @param cause what ended the copy, as thrown
     */
    record Failed(Throwable cause) implements SaveAsOutcome {

        /** @throws NullPointerException if {@code cause} is {@code null} */
        public Failed {
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }
}
