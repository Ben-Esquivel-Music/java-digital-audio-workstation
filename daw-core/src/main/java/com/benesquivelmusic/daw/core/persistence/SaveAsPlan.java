package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a Save As into a chosen folder would do (story 333, rulings R5, R6;
 * {@link ProjectManager#planSaveAs}): the copy it is {@linkplain Ready ready}
 * to make, or why the folder is refused — {@linkplain TargetTaken taken},
 * {@linkplain TargetInsideProject inside the open project}, or on a volume
 * with {@linkplain NotEnoughSpace not enough free space}. Planned off the
 * JavaFX application thread; a refusal sends the user back to the chooser.
 */
public sealed interface SaveAsPlan permits SaveAsPlan.Ready, SaveAsPlan.TargetTaken,
        SaveAsPlan.TargetInsideProject, SaveAsPlan.NotEnoughSpace {

    /**
     * One file the copy takes from the source folder.
     *
     * @param source       the file in the source folder
     * @param relativePath where it goes, relative to the copy's folder: its path relative to the source folder
     * @param bytes        its size when the plan was made
     */
    record CopyItem(Path source, Path relativePath, long bytes) {

        /**
         * @throws NullPointerException     if a path is {@code null}
         * @throws IllegalArgumentException if {@code relativePath} has a root (an absolute path, and on Windows
         *                                  {@code \x.wav} or {@code C:x.wav}), is empty or leaves the copy's
         *                                  folder ({@code ..}), or {@code bytes} is negative
         */
        public CopyItem {
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(relativePath, "relativePath must not be null");
            Path normalised = relativePath.normalize();
            if (relativePath.getRoot() != null || normalised.toString().isEmpty() || normalised.startsWith("..")) {
                throw new IllegalArgumentException("relativePath must lie inside the copy's folder: " + relativePath);
            }
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes must not be negative: " + bytes);
            }
        }
    }

    /**
     * The copy, ready to be made ({@link ProjectManager#publishCopy}).
     *
     * @param capture                  what Save As captured
     * @param target                   the copy's folder, absolute
     * @param intoExistingEmptyFolder  {@code true} when {@code target} is an existing empty folder the copy writes
     *                                 into; {@code false} when the copy is staged beside it and renamed to it
     * @param files                    every file the copy takes, ordered by relative path
     * @param bytes                    their sizes plus the rendered {@code project.daw}'s (what the volume must
     *                                 hold is more: see {@link NotEnoughSpace#needed()})
     * @param missingFiles             the audio files the project uses under its folder that the copy will not
     *                                 hold: missing there (a file named but absent, or left out as part of a take
     *                                 still streaming), or unreadable (a source folder, or an entry of it, that
     *                                 could not be read: they keep naming the original's files)
     * @param unknownEntries           the source folder's top-level entries the copy leaves out and knows nothing
     *                                 of
     */
    record Ready(SaveAsCapture capture, Path target, boolean intoExistingEmptyFolder, List<CopyItem> files,
                 long bytes, int missingFiles, int unknownEntries) implements SaveAsPlan {

        /** @throws NullPointerException if a component is {@code null} */
        public Ready {
            Objects.requireNonNull(capture, "capture must not be null");
            Objects.requireNonNull(target, "target must not be null");
            files = List.copyOf(files);
        }

        /**
         * The bytes of the files alone, the rendered {@code project.daw} not included.
         *
         * @return the files' total
         */
        public long fileBytes() {
            long total = 0L;
            for (CopyItem file : files) {
                total += file.bytes();
            }
            return total;
        }
    }

    /**
     * The chosen folder exists and is not empty (or is not a folder): Save
     * As never merges into one.
     *
     * @param parent the folder it lies in
     * @param name   its name
     */
    record TargetTaken(Path parent, String name) implements SaveAsPlan {

        /** @throws NullPointerException if a component is {@code null} */
        public TargetTaken {
            Objects.requireNonNull(parent, "parent must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    /** The chosen folder is the open project's folder, lies inside it, or is another name of either. */
    record TargetInsideProject() implements SaveAsPlan {
    }

    /**
     * The chosen folder's volume has less usable space than the copy needs.
     *
     * @param needed     what the copy needs on that volume: each file and {@code project.daw} rounded up to whole
     *                   allocation units, two more heads' worth and a 16 MiB reserve for what opening the copy
     *                   writes at once
     * @param usable     the bytes the volume has usable
     * @param volumeRoot the root of the chosen folder's path ({@code D:\}, {@code \\server\share\}, {@code /})
     */
    record NotEnoughSpace(long needed, long usable, Path volumeRoot) implements SaveAsPlan {

        /** @throws NullPointerException if {@code volumeRoot} is {@code null} */
        public NotEnoughSpace {
            Objects.requireNonNull(volumeRoot, "volumeRoot must not be null");
        }
    }
}
