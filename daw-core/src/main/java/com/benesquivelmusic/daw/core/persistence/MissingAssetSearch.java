package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.recording.TakeDirectories;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Locate folder: one walk of a folder the user chose, looking for every
 * missing asset of the report at once (Persistence Integrity book §4.3;
 * decision D17). It returns candidates only — {@link AssetRelinker} then
 * decodes and checks each before anything is relinked.
 *
 * <p><strong>The walk.</strong> Recursive, of every depth, never following
 * a symbolic link (nor, on Windows, a junction — both are entries the walk
 * does not enter, and a link to a file is not a candidate). Only regular
 * files are candidates. An entry that cannot be read is skipped. The
 * caller's cancellation token, and the thread's interrupt, are checked at
 * every directory and every entry; a cancelled walk throws
 * {@link CancellationException}. Run it off the owner thread.</p>
 *
 * <p><strong>Matching.</strong> A file matches an asset reference when its
 * name equals the reference's last element ignoring case
 * ({@link Locale#ROOT}). The reference's elements are split at both
 * {@code '/'} and {@code '\'}, so a reference written on another platform
 * still names its file. The file at the asset's own reference is not a
 * candidate: the search looks for the asset somewhere else.</p>
 *
 * <p><strong>Ranking.</strong> Of several candidates, the one sharing the
 * longest common trailing path with the original reference wins (path
 * elements compared from the end, ignoring case): a moved
 * {@code Samples/Kicks/kick.wav} is found as {@code …/Kicks/kick.wav} rather
 * than {@code …/Snares/kick.wav}. When the best are still tied, nothing is
 * guessed: the asset is {@link Ambiguous}, and the user picks with Locate
 * file. A take is one asset found as a whole in one folder: a folder is a
 * candidate only when it holds every segment by name and, when the take's
 * references name its take directory ({@code <stamp>_take-NNNN}, the
 * capture layout), lies under a directory of that name — every take of a
 * track has a {@code <trackId>/segment-000.wav}, so a sibling take is never
 * taken for the missing one. Candidate folders are ranked by the trailing
 * paths of all their segments. A take not found whole in one folder is
 * {@link NotFound} — never a partial take.</p>
 *
 * <p>This is not the archive's
 * {@code archive.MissingAssetResolver.smartSiblingSearch}, which resolves
 * one reference at a time, three levels deep, case-sensitively, to the first
 * match: archive opening keeps that behaviour, and this search does a
 * different job — every row in one walk, ranked, with ties reported.</p>
 */
public final class MissingAssetSearch {

    private MissingAssetSearch() {
    }

    /**
     * Searches {@code folder} for every asset of {@code assets}.
     *
     * @param folder    the folder to walk; when it is itself a link to a
     *                  directory, its target is walked (the user chose it)
     * @param assets    the assets to look for; duplicates are looked for once
     * @param cancelled the cancellation token, consulted at every directory and entry
     * @return one match per distinct asset, in the order given; unmodifiable
     * @throws IOException           if {@code folder} cannot be read or is not a directory
     * @throws CancellationException if the token reported cancellation, or
     *                               the thread was interrupted, during the walk
     */
    public static Map<AssetReference, Match> search(Path folder, List<AssetReference> assets,
                                                    BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(folder, "folder must not be null");
        Objects.requireNonNull(assets, "assets must not be null");
        Objects.requireNonNull(cancelled, "cancelled must not be null");
        Set<AssetReference> distinct = new LinkedHashSet<>(assets);
        Set<String> wanted = new HashSet<>();
        for (AssetReference asset : distinct) {
            for (String reference : Objects.requireNonNull(asset, "assets must not contain null").references()) {
                String name = fileNameOf(reference);
                if (!name.isEmpty()) {
                    wanted.add(nameKey(name));
                }
            }
        }
        Path root = walkRoot(folder);
        NameIndex index = new NameIndex();
        if (stopped(cancelled)) {
            throw new CancellationException("the folder search was cancelled");
        }
        if (!wanted.isEmpty()) {
            walk(root, wanted, index, cancelled);
        }
        Map<AssetReference, Match> matches = new LinkedHashMap<>();
        for (AssetReference asset : distinct) {
            matches.put(asset, switch (asset) {
                case AssetReference.SingleFile file -> matchFile(file, index);
                case AssetReference.Take take -> matchTake(take, index);
            });
        }
        return Collections.unmodifiableMap(matches);
    }

    /** What one search found for one asset. */
    public sealed interface Match permits Resolved, Ambiguous, NotFound {
    }

    /**
     * One best candidate: the asset found elsewhere.
     *
     * @param replacement the found file, or for a take the found segments in
     *                    order; every reference absolute
     */
    public record Resolved(AssetReference replacement) implements Match {
        public Resolved {
            Objects.requireNonNull(replacement, "replacement must not be null");
        }
    }

    /**
     * Several candidates tied for best; nothing is guessed.
     *
     * @param candidates the tied files — for a take, the tied folders — in
     *                   path order; copied, at least two
     */
    public record Ambiguous(List<Path> candidates) implements Match {
        public Ambiguous {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            if (candidates.size() < 2) {
                throw new IllegalArgumentException("an ambiguous match has at least two candidates: " + candidates);
            }
        }

        /**
         * Returns how many candidates tied.
         *
         * @return the candidate count, at least two
         */
        public int candidateCount() {
            return candidates.size();
        }
    }

    /** No candidate: the folder does not hold the asset (for a take, all of it in one folder). */
    public record NotFound() implements Match {
    }

    private static Path walkRoot(Path folder) throws IOException {
        Path absolute = folder.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(absolute, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isDirectory()) {
            return absolute;
        }
        if (Files.isDirectory(absolute)) {
            // The chosen folder is itself a link: follow that one choice, nothing under it.
            return absolute.toRealPath();
        }
        throw new NotDirectoryException(absolute.toString());
    }

    private static void walk(Path root, Set<String> wanted, NameIndex index, BooleanSupplier cancelled)
            throws IOException {
        // No FOLLOW_LINKS: a link (or junction) is visited as an entry and never entered.
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                        return stopped(cancelled) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (stopped(cancelled)) {
                            return FileVisitResult.TERMINATE;
                        }
                        Path name = file.getFileName();
                        if (attributes.isRegularFile() && name != null && wanted.contains(nameKey(name.toString()))) {
                            index.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        // An unreadable entry is skipped; the walk goes on.
                        return stopped(cancelled) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        if (stopped(cancelled)) {
            throw new CancellationException("the folder search was cancelled");
        }
    }

    private static boolean stopped(BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    private static Match matchFile(AssetReference.SingleFile asset, NameIndex index) {
        String name = fileNameOf(asset.reference());
        if (name.isEmpty()) {
            return new NotFound();
        }
        Map<Path, Integer> scored = new LinkedHashMap<>();
        for (Path candidate : index.named(name)) {
            if (!isAt(candidate, asset.reference())) {
                scored.put(candidate, trailingMatch(candidate, asset.reference()));
            }
        }
        List<Path> best = best(scored);
        return switch (best.size()) {
            case 0 -> new NotFound();
            case 1 -> new Resolved(new AssetReference.SingleFile(best.getFirst().toString()));
            default -> new Ambiguous(best);
        };
    }

    private static Match matchTake(AssetReference.Take asset, NameIndex index) {
        List<String> names = new ArrayList<>(asset.segments().size());
        for (String segment : asset.segments()) {
            String name = fileNameOf(segment);
            if (name.isEmpty()) {
                return new NotFound();
            }
            names.add(name);
        }
        Set<Path> folders = new LinkedHashSet<>();
        for (Path head : index.named(names.getFirst())) {
            if (head.getParent() != null) {
                folders.add(head.getParent());
            }
        }
        Map<Path, List<Path>> complete = new LinkedHashMap<>();
        Map<Path, Integer> scored = new LinkedHashMap<>();
        for (Path folder : folders) {
            Optional<List<Path>> segments = index.allIn(folder, names);
            if (segments.isEmpty() || isAt(segments.get(), asset.segments())) {
                continue;
            }
            int score = 0;
            boolean ownTake = true;
            for (int i = 0; i < names.size(); i++) {
                ownTake &= liesInItsTakeFolder(segments.get().get(i), asset.segments().get(i));
                score += trailingMatch(segments.get().get(i), asset.segments().get(i));
            }
            if (ownTake) {
                complete.put(folder, segments.get());
                scored.put(folder, score);
            }
        }
        List<Path> best = best(scored);
        return switch (best.size()) {
            case 0 -> new NotFound();
            case 1 -> {
                List<String> found = new ArrayList<>(names.size());
                for (Path segment : complete.get(best.getFirst())) {
                    found.add(segment.toString());
                }
                yield new Resolved(new AssetReference.Take(found));
            }
            default -> new Ambiguous(best);
        };
    }

    /** The candidates with the highest score, in path order. */
    private static List<Path> best(Map<Path, Integer> scored) {
        int top = Integer.MIN_VALUE;
        for (int score : scored.values()) {
            top = Math.max(top, score);
        }
        List<Path> best = new ArrayList<>();
        for (Map.Entry<Path, Integer> entry : scored.entrySet()) {
            if (entry.getValue() == top) {
                best.add(entry.getKey());
            }
        }
        best.sort(Comparator.comparing(Path::toString));
        return best;
    }

    /** Whether {@code candidate} is the file {@code reference} itself names. */
    private static boolean isAt(Path candidate, String reference) {
        return ProjectPaths.isAbsoluteReference(reference) && candidate.equals(Path.of(reference).normalize());
    }

    /** Whether every one of {@code candidates} is the file its original reference names. */
    private static boolean isAt(List<Path> candidates, List<String> references) {
        for (int i = 0; i < candidates.size(); i++) {
            if (!isAt(candidates.get(i), references.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code candidate} can be the take segment
     * {@code segmentReference} names: it shares the reference's trailing
     * path through the take's own directory when the reference names one
     * ({@code <stamp>_take-NNNN/<trackId>/segment-NNN.wav},
     * {@link TakeDirectories#isTakeDirectoryName}) — segment names, and the
     * track folders beside them, repeat in every take of a track and every
     * track of a take — and otherwise just its file name. The one identity
     * rule Locate folder (a candidate folder must hold the take this way)
     * and a take's Locate file ({@link AssetRelinker}, which asks before it
     * uses another folder) apply.
     *
     * @param candidate        a file found by the segment's name
     * @param segmentReference the segment as the take's clips hold it
     * @return {@code true} if the candidate lies in the segment's own take folder
     */
    static boolean liesInItsTakeFolder(Path candidate, String segmentReference) {
        return trailingMatch(candidate, segmentReference) >= throughTakeDirectory(segmentReference);
    }

    /**
     * Returns the folder a take segment's reference names, from the take's
     * own directory down — {@code "<stamp>_take-NNNN/<trackId>"} in the
     * capture layout, elements joined with {@code '/'} — or empty when the
     * reference names no take directory.
     *
     * @param segmentReference the segment as the take's clips hold it
     * @return the take folder, or empty
     */
    static Optional<String> takeFolderOf(String segmentReference) {
        int depth = takeFolderDepth(segmentReference);
        if (depth == 0) {
            return Optional.empty();
        }
        List<String> elements = elementsOf(segmentReference);
        return Optional.of(String.join("/", elements.subList(elements.size() - 1 - depth, elements.size() - 1)));
    }

    /**
     * Returns how many folders the take folder of a segment's reference has
     * ({@link #takeFolderOf}): 2 in the capture layout,
     * {@code <stamp>_take-NNNN/<trackId>}; 0 when the reference names no take
     * directory.
     *
     * @param segmentReference the segment as the take's clips hold it
     * @return the take folder's element count, or 0
     */
    static int takeFolderDepth(String segmentReference) {
        int through = throughTakeDirectory(segmentReference);
        return through < 2 ? 0 : through - 1;
    }

    /**
     * How many trailing elements a candidate must share with a segment
     * reference to be that take's segment: through the take's own directory
     * when the reference names one, otherwise just the file name
     * ({@link #liesInItsTakeFolder}).
     */
    private static int throughTakeDirectory(String segmentReference) {
        List<String> elements = elementsOf(segmentReference);
        for (int i = elements.size() - 2; i >= 0; i--) {
            if (TakeDirectories.isTakeDirectoryName(elements.get(i))) {
                return elements.size() - i;
            }
        }
        return 1;
    }

    /**
     * How many trailing path elements {@code candidate} shares with
     * {@code reference}, compared from the end ignoring case; at least 1
     * for a candidate found by name.
     */
    static int trailingMatch(Path candidate, String reference) {
        List<String> elements = elementsOf(reference);
        int matched = 0;
        int c = candidate.getNameCount() - 1;
        int r = elements.size() - 1;
        while (c >= 0 && r >= 0 && nameKey(candidate.getName(c).toString()).equals(nameKey(elements.get(r)))) {
            matched++;
            c--;
            r--;
        }
        return matched;
    }

    /**
     * Returns the file name a reference names: its last element, split at
     * both {@code '/'} and {@code '\'}; empty when it has none.
     *
     * @param reference a clip reference as held in memory
     * @return the file name, or an empty string
     */
    static String fileNameOf(String reference) {
        List<String> elements = elementsOf(reference);
        return elements.isEmpty() ? "" : elements.getLast();
    }

    private static List<String> elementsOf(String reference) {
        List<String> elements = new ArrayList<>();
        for (String element : reference.split("[/\\\\]")) {
            if (!element.isEmpty()) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** The case-insensitive form names are compared in. */
    static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Regular files by case-insensitive name — what a walk found, or what one
     * folder holds — and the one name rule both Locate folder and a take's
     * Locate file use.
     */
    static final class NameIndex {

        private final Map<String, List<Path>> byName = new HashMap<>();

        /**
         * Indexes the regular files directly in {@code folder} — not below
         * it, and not a link.
         *
         * @param folder the folder
         * @return the index
         * @throws IOException if the folder cannot be listed
         */
        static NameIndex ofFolder(Path folder) throws IOException {
            NameIndex index = new NameIndex();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        index.add(entry);
                    }
                }
            }
            return index;
        }

        void add(Path file) {
            byName.computeIfAbsent(nameKey(file.getFileName().toString()), key -> new ArrayList<>()).add(file);
        }

        /** Every indexed file whose name equals {@code name} ignoring case. */
        List<Path> named(String name) {
            return byName.getOrDefault(nameKey(name), List.of());
        }

        /**
         * Returns the one file directly in {@code folder} named {@code name}
         * ignoring case — of several that differ only in case (a
         * case-sensitive file system), the one with exactly that name; empty
         * when there is none or no one to prefer.
         *
         * @param folder the folder
         * @param name   the file name
         * @return the file, or empty
         */
        Optional<Path> in(Path folder, String name) {
            List<Path> inFolder = new ArrayList<>();
            for (Path file : named(name)) {
                if (folder.equals(file.getParent())) {
                    inFolder.add(file);
                }
            }
            if (inFolder.size() == 1) {
                return Optional.of(inFolder.getFirst());
            }
            Path exact = null;
            for (Path file : inFolder) {
                if (file.getFileName().toString().equals(name)) {
                    if (exact != null) {
                        return Optional.empty();
                    }
                    exact = file;
                }
            }
            return Optional.ofNullable(exact);
        }

        /** Every one of {@code names} in {@code folder} ({@link #in}), in order; empty if one is not. */
        Optional<List<Path>> allIn(Path folder, List<String> names) {
            List<Path> files = new ArrayList<>(names.size());
            for (String name : names) {
                Optional<Path> file = in(folder, name);
                if (file.isEmpty()) {
                    return Optional.empty();
                }
                files.add(file.get());
            }
            return Optional.of(List.copyOf(files));
        }
    }
}
