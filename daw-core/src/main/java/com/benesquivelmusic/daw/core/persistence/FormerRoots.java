package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where a Save As copy came from (story 333, ENG-S10; Persistence Integrity
 * book §3.3): the folders this session saved the open project from as a
 * copy, oldest first, and the files the copy holds, each by its path relative
 * to the copy's folder ({@link SaveAsOutcome.Published#formerRoots()}).
 *
 * <p>A capture of the copy writes a reference that lies strictly under one of
 * those folders project-relative only when the copy holds the file at the same
 * relative path ({@link ProjectPaths#relativize(Path, FormerRoots, String)}):
 * then it names the copy's own file. Every other reference under them — a file
 * the copy could not take, one that appeared there after the copy — stays
 * absolute, so it still names the original's file and plays again once that
 * file is back. Pure path arithmetic: no I/O. Paths compare as the platform
 * compares them (on Windows, in any letter case).</p>
 *
 * @param folders   the folders the project was saved from as a copy this session, absolute and normalised,
 *                  oldest first
 * @param heldFiles the files the copy holds, each relative to the copy's folder
 */
public record FormerRoots(List<Path> folders, Set<Path> heldFiles) {

    /** No former roots: a project that was opened or created, never saved as a copy this session. */
    public static final FormerRoots NONE = new FormerRoots(List.of(), Set.of());

    /**
     * @throws NullPointerException     if a list, a set or an element is {@code null}
     * @throws IllegalArgumentException if a held file is absolute, empty, or leaves its folder ({@code ..})
     */
    public FormerRoots {
        List<Path> normalised = new ArrayList<>();
        for (Path folder : Objects.requireNonNull(folders, "folders must not be null")) {
            Path absolute = Objects.requireNonNull(folder, "a folder must not be null").toAbsolutePath().normalize();
            if (!normalised.contains(absolute)) {
                normalised.add(absolute);
            }
        }
        folders = List.copyOf(normalised);
        Set<Path> held = new HashSet<>();
        for (Path file : Objects.requireNonNull(heldFiles, "heldFiles must not be null")) {
            held.add(requireInside(Objects.requireNonNull(file, "a held file must not be null")));
        }
        heldFiles = Set.copyOf(held);
    }

    /**
     * These former roots, then {@code folder} — the folder a new copy was
     * just taken from — with {@code copyHolds} as the new copy's files.
     *
     * @param folder    the folder the new copy was taken from
     * @param copyHolds the files the new copy holds, relative to its folder
     * @return the new copy's former roots
     */
    public FormerRoots thenCopiedFrom(Path folder, Set<Path> copyHolds) {
        List<Path> chain = new ArrayList<>(folders);
        chain.add(Objects.requireNonNull(folder, "folder must not be null"));
        return new FormerRoots(chain, copyHolds);
    }

    /**
     * Whether the copy holds the file at {@code relative}. For a chain of
     * copies (A to B to C) the last copy's files alone decide: C holds what
     * it took of B, which holds what it took of A, each at the same relative
     * path.
     *
     * @param relative a path relative to the copy's folder
     * @return whether the copy holds the file at {@code relative}
     */
    public boolean holds(Path relative) {
        return heldFiles.contains(Objects.requireNonNull(relative, "relative must not be null"));
    }

    private static Path requireInside(Path relative) {
        Path normalised = relative.normalize();
        if (relative.getRoot() != null || normalised.toString().isEmpty() || normalised.startsWith("..")) {
            throw new IllegalArgumentException("a held file is a path inside the copy's folder: " + relative);
        }
        return normalised;
    }
}
