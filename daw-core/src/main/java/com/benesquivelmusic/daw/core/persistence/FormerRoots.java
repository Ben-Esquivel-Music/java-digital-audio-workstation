package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where a Save As copy came from (story 333, ENG-S10; Persistence Integrity
 * book §3.3; PR #989 review 5704604621): the folders this session saved the
 * open project from as a copy, oldest first, each with its own held set — the
 * files of the copy that came from that folder at the same relative path
 * ({@link SaveAsOutcome.Published#formerRoots()}).
 *
 * <p>A copy maps paths one to one: copy {@code k+1}'s file at {@code p} is
 * copy {@code k}'s file at {@code p}. So a root's set holds {@code p} exactly
 * when the current copy's file at {@code p} was taken, through an unbroken
 * chain of same-path copies, from that folder's {@code p} as it was when the
 * folder was copied: the newest root's set is the newest copy's files, and an
 * older root's set is its own copy's files intersected with every later
 * copy's ({@link #thenCopiedFrom}). A file one copy left out and a later folder
 * acquired at the same path is held for the later folder only.</p>
 *
 * <p>A capture of the copy writes a reference that lies strictly under one of
 * those folders project-relative only when that folder's set holds the file
 * at the same relative path ({@link ProjectPaths#relativize(Path, FormerRoots,
 * String)}): then it names the copy's own file. Every other reference under
 * them — a file the copy could not take, one a copy in the chain left out, one
 * that appeared there after the copy — stays absolute, so it still names the
 * original's file and plays again once that file is back. A folder appears
 * once, at its latest place in the chain with its latest set: a Save As target
 * is absent or empty, so nothing of an earlier incarnation of a folder the
 * chain revisits is on disk there. Pure path arithmetic: no I/O. Paths compare
 * as the platform compares them (on Windows, in any letter case).</p>
 *
 * @param roots the folders the project was saved from as a copy this session, oldest first, each with its held set
 */
public record FormerRoots(List<Root> roots) {

    /** No former roots: a project that was opened or created, never saved as a copy this session. */
    public static final FormerRoots NONE = new FormerRoots(List.of());

    /**
     * Keeps one root per folder, at its latest place in {@code roots}, with
     * that entry's set; an earlier entry for the same folder is dropped, not
     * merged.
     *
     * @throws NullPointerException if the list or a root is {@code null}
     */
    public FormerRoots {
        List<Root> latestFirst = new ArrayList<>();
        List<Path> seen = new ArrayList<>();
        for (Root root : Objects.requireNonNull(roots, "roots must not be null").reversed()) {
            Objects.requireNonNull(root, "a root must not be null");
            if (!seen.contains(root.folder())) {
                seen.add(root.folder());
                latestFirst.add(root);
            }
        }
        roots = List.copyOf(latestFirst.reversed());
    }

    /**
     * These former roots carried on by a new copy taken from {@code folder}
     * that holds {@code copyHolds}: every carried root keeps only the files
     * of its set the new copy took ({@link #carriedBy}), then {@code folder}
     * is the newest root, with {@code copyHolds} as its own set. A folder
     * already among the roots moves to the newest place with that set.
     *
     * @param folder    the folder the new copy was taken from
     * @param copyHolds the files the new copy holds, relative to its folder
     * @return the new copy's former roots
     * @throws NullPointerException     if an argument or a held file is {@code null}
     * @throws IllegalArgumentException if a held file is absolute, empty, or leaves its folder ({@code ..})
     */
    public FormerRoots thenCopiedFrom(Path folder, Set<Path> copyHolds) {
        List<Root> chain = new ArrayList<>(carriedBy(copyHolds).roots());
        chain.add(new Root(Objects.requireNonNull(folder, "folder must not be null"), copyHolds));
        return new FormerRoots(chain);
    }

    /**
     * These former roots carried on by a new copy that holds {@code copyHolds}
     * and was taken from no folder (a project never saved): no new root, and
     * every root keeps only the files of its set the new copy holds.
     *
     * @param copyHolds the files the new copy holds, relative to its folder
     * @return the new copy's former roots
     * @throws NullPointerException     if {@code copyHolds} or a held file is {@code null}
     * @throws IllegalArgumentException if a held file is absolute, empty, or leaves its folder ({@code ..})
     */
    public FormerRoots carriedBy(Set<Path> copyHolds) {
        Set<Path> copied = heldSet(Objects.requireNonNull(copyHolds, "copyHolds must not be null"));
        List<Root> carried = new ArrayList<>();
        for (Root root : roots) {
            Set<Path> kept = new HashSet<>(root.heldFiles());
            kept.retainAll(copied);
            carried.add(new Root(root.folder(), kept));
        }
        return new FormerRoots(carried);
    }

    /**
     * The files of the copy that came from {@code folder} at the same
     * relative path: that root's own set, or an empty set when
     * {@code folder} is not a former root — the copy took nothing from it.
     *
     * @param folder a folder
     * @return the files held from {@code folder}, each relative to the copy's folder
     */
    public Set<Path> heldFrom(Path folder) {
        Path absolute = Objects.requireNonNull(folder, "folder must not be null").toAbsolutePath().normalize();
        for (Root root : roots) {
            if (root.folder().equals(absolute)) {
                return root.heldFiles();
            }
        }
        return Set.of();
    }

    /**
     * One former root: a folder the project was saved from as a copy, and
     * its own held set — the files of the current copy that came from it,
     * through an unbroken chain of same-path copies, at the same relative
     * path.
     *
     * @param folder    the folder, absolute and normalised
     * @param heldFiles the files held from it, each relative to the copy's folder (and to {@code folder})
     */
    public record Root(Path folder, Set<Path> heldFiles) {

        /**
         * @throws NullPointerException     if the folder, the set or a held file is {@code null}
         * @throws IllegalArgumentException if a held file is absolute, empty, or leaves its folder ({@code ..})
         */
        public Root {
            folder = Objects.requireNonNull(folder, "folder must not be null").toAbsolutePath().normalize();
            heldFiles = heldSet(Objects.requireNonNull(heldFiles, "heldFiles must not be null"));
        }

        /**
         * Whether the copy's file at {@code relative} came from this folder's
         * file at {@code relative}.
         *
         * @param relative a path relative to this folder
         * @return whether this root's set holds {@code relative}
         */
        public boolean holds(Path relative) {
            return heldFiles.contains(Objects.requireNonNull(relative, "relative must not be null"));
        }
    }

    private static Set<Path> heldSet(Set<Path> files) {
        Set<Path> held = new HashSet<>();
        for (Path file : files) {
            held.add(requireInside(Objects.requireNonNull(file, "a held file must not be null")));
        }
        return Set.copyOf(held);
    }

    private static Path requireInside(Path relative) {
        Path normalised = relative.normalize();
        if (relative.getRoot() != null || normalised.toString().isEmpty() || normalised.startsWith("..")) {
            throw new IllegalArgumentException("a held file is a path inside the copy's folder: " + relative);
        }
        return normalised;
    }
}
