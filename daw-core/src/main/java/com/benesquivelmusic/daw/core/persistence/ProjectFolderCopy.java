package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;
import com.benesquivelmusic.daw.core.recording.SegmentWriter;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.session.SessionManifestStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Save As copy of a project folder (story 333, ruling R5; Persistence
 * Integrity book §2.2, §3.3, §4.1): what goes into the copy, and the copy
 * itself, written so the folder the user named never holds half a project.
 * {@link ProjectManager} is its only user ({@link ProjectManager#planSaveAs},
 * {@link ProjectManager#publishCopy}). Every method blocks on storage: call
 * it on a worker, never on the JavaFX application thread.
 *
 * <h2>What the copy holds</h2>
 * <ul>
 *   <li>{@code project.daw}: the captured document, rendered at the current
 *       schema against the copy's folder and written last, through the
 *       {@link AtomicFileWriter}, never over a file another writer put
 *       there. A clip reference under the source folder (or under one of its
 *       former roots) is written project-relative only when the copy holds
 *       the file that folder had at that path, so it names the copy's file;
 *       a reference the copy could not take keeps its absolute path, so it
 *       still names the original's file and plays again once that file is
 *       back ({@link FormerRoots}). A
 *       SoundFont path, which the document writes as held, is re-pointed at
 *       the copy's file when the copy holds it.</li>
 *   <li>{@code audio/} and {@code assets/} (an archive restore's audio),
 *       whole, except — under {@code audio/} — a take directory whose
 *       {@code take.manifest} reads streaming, {@code .part} segments and a
 *       take manifest's own {@code take.manifest.tmp}; and except this
 *       writer's temps. A folder under them that is a link (a symbolic link,
 *       or a junction on Windows) — {@code audio/} itself included — is
 *       walked through its real path, once, and its files are copied at the
 *       link's relative path; a link that leads into the project folder, above
 *       it, into a folder already walked, or to a folder that holds one already
 *       walked is left out.</li>
 *   <li>Every other file under the source folder that the document
 *       references, at the same relative path.</li>
 *   <li>{@code snapshots/}, whole, except this writer's temps.</li>
 * </ul>
 * <p>Left out: {@code checkpoints/}, {@code journal/}, {@code sessions/},
 * {@code .project.lock} and its audit log {@code project.log}, a restored
 * archive's header {@code archive.properties}, {@code archives/} — the
 * folder the disk-usage view counts as the original's packaged archives —
 * the head's
 * {@code project.daw.<tag>.<stamp>.bak} backups (migration, degraded and
 * recovered), this writer's temps, and every other top-level entry — those
 * nothing knows are counted ({@link SaveAsPlan.Ready#unknownEntries()}) and
 * logged at {@code INFO}. External references stay absolute and are not
 * copied (book §3.3).</p>
 *
 * <h2>A source it cannot read</h2>
 * <p>A source folder that cannot be listed — its volume unplugged or offline
 * — or an entry under it that cannot be read is logged at {@code WARNING}
 * and left out; the plan goes on, so a Save As can still keep the session's
 * work when the project's own disk fails (story 333, the studio engineer's
 * ruling B1 on stage 3). Every file the document references that the copy
 * then does not hold counts as missing.</p>
 *
 * <h2>How it is written</h2>
 * <p>Plain byte copies, never hard links: each file goes through
 * {@link AtomicFileWriter#copy} — forced, published under a name nothing
 * holds — and its size is checked against its source's. A target that does
 * not exist is written into its staging sibling, hidden on POSIX
 * ({@code .<name>.saving-as.tmp}, deleted first if a crash left one), and
 * published by one rename ({@link AtomicFileWriter#publishDirectory}); an
 * existing empty folder is written into directly, {@code project.daw} last.
 * Every folder the copy created is forced before {@code project.daw} is
 * written, best effort — so before the copy is published, too. A cancel
 * before {@code project.daw} is written, or a failure before
 * the copy is published, deletes what this copy created — the staging folder,
 * or each file and folder it created in the existing one, and any parent
 * folder it created — and nothing else. Folders are created and files
 * deleted directly; every file write goes through the writer.</p>
 */
final class ProjectFolderCopy {

    private static final Logger LOG = Logger.getLogger(ProjectFolderCopy.class.getName());

    /** The purpose a Save As staging sibling is named for ({@link AtomicFileWriter#stagingSiblingOf}). */
    static final String STAGING_PURPOSE = "saving-as";

    /**
     * The smallest allocation unit the free-space check assumes. Windows
     * reports a volume's sector size, not its cluster size, as its block size
     * ({@code FileStore.getBlockSize}: 512 bytes on an NTFS volume, measured),
     * so a file is never counted as smaller than one 4 KiB cluster.
     */
    static final long MIN_ALLOCATION_UNIT = 4_096L;

    /**
     * What the copy keeps free beside its own files: room for what opening
     * the copy writes at once — its first checkpoints and journal segment,
     * its session manifest — on the same volume (story 333, the studio
     * engineer's ruling N1 on stage 3: a judgement, not a measurement).
     */
    static final long RESERVE_BYTES = 16L * 1024 * 1024;

    /** How much room a volume has; a test supplies its own to plan a full volume. */
    @FunctionalInterface
    interface UsableSpace {

        /**
         * @param existingFolder an existing folder on the volume
         * @return the bytes usable there
         * @throws IOException if the volume cannot be asked
         */
        long of(Path existingFolder) throws IOException;

        /**
         * The volume's allocation unit, which every file of the copy is
         * rounded up to: its store's block size, and never less than
         * {@link #MIN_ALLOCATION_UNIT} — which it is when the store cannot be
         * asked.
         *
         * @param existingFolder an existing folder on the volume
         * @return the bytes one allocation unit holds
         */
        default long allocationUnit(Path existingFolder) {
            try {
                return Math.max(MIN_ALLOCATION_UNIT, Files.getFileStore(existingFolder).getBlockSize());
            } catch (IOException | RuntimeException unknown) {
                return MIN_ALLOCATION_UNIT;
            }
        }
    }

    /** The production answer: the folder's file store's usable space. */
    static final UsableSpace FILE_STORE_SPACE = folder -> Files.getFileStore(folder).getUsableSpace();

    /**
     * The top-level entries the copy leaves out on purpose: the source folder's own timeline — its
     * checkpoints, journal and session manifests — and its lock, with the lock's audit log
     * ({@link ProjectLog}), whose events are the source folder's; a restored archive's header, which
     * describes the archive, not the copy; and {@code archives/}, the folder the disk-usage view counts as
     * the original's packaged archives (ruling 3 Q2) — the original's history, valid where it is. Known, so
     * none is counted among the unknown entries; a file under them the document references is still
     * copied, as any referenced file is.
     */
    private static final Set<String> LEFT_OUT = Set.of(ProjectContext.CHECKPOINT_DIR_NAME,
            ProjectContext.JOURNAL_DIR_NAME, SessionManifestStore.SESSIONS_DIR_NAME, ProjectLockManager.LOCK_FILE_NAME,
            ProjectLog.LOG_FILE_NAME, ProjectManager.ARCHIVE_HEADER_FILE_NAME, ProjectManager.ARCHIVES_DIR_NAME);

    /** The top-level folders the copy takes whole. */
    private static final Set<String> TAKEN_WHOLE = Set.of(ProjectManager.AUDIO_DIR_NAME,
            ProjectManager.ASSETS_DIR_NAME, SnapshotStore.SNAPSHOTS_DIR);

    /** A take manifest's own staging file ({@link TakeManifest#write}). */
    private static final String TAKE_MANIFEST_TEMP = TakeManifest.FILE_NAME + TakeManifest.TMP_SUFFIX;

    private final AtomicFileWriter fileWriter;
    private final UsableSpace usableSpace;

    ProjectFolderCopy(AtomicFileWriter fileWriter) {
        this(fileWriter, FILE_STORE_SPACE);
    }

    ProjectFolderCopy(AtomicFileWriter fileWriter, UsableSpace usableSpace) {
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace must not be null");
    }

    /**
     * Plans the copy of {@code capture} into {@code target} (see the class
     * note), or refuses it: a target that is the source folder, lies inside
     * it, or is another name of either — judged lexically, by the file
     * system's own identity of the target's nearest existing folder and each
     * of its parents ({@link Files#isSameFile}), and by their real paths — is
     * {@link SaveAsPlan.TargetInsideProject}; an existing target that is not
     * an empty folder is {@link SaveAsPlan.TargetTaken}; a volume whose usable
     * space is less than what the copy needs there — every file and the
     * rendered {@code project.daw}, each rounded up to the volume's
     * allocation unit, and {@link #RESERVE_BYTES} with two more heads beside
     * them — is {@link SaveAsPlan.NotEnoughSpace}. Renders the document once,
     * to measure it.
     *
     * @param cancelled asked before each folder and file the plan reads, and before the render
     * @throws IOException              if the target's volume cannot be asked
     * @throws CancellationException    if {@code cancelled} answered {@code true}; nothing was written
     * @throws IllegalArgumentException if a copy would be made and {@code target}'s name is not
     *                                  {@code capture.name()} (ruling R6: the copy is named after its folder)
     */
    SaveAsPlan plan(SaveAsCapture capture, Path target, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(capture, "capture must not be null");
        Objects.requireNonNull(cancelled, "cancelled must not be null");
        Path destination = Objects.requireNonNull(target, "target must not be null").toAbsolutePath().normalize();
        Path parent = destination.getParent();
        Path name = destination.getFileName();
        if (parent == null || name == null) {
            throw new IOException("Save As needs a folder inside another folder, not " + target);
        }
        Optional<Path> source = sourceOf(capture);
        if (source.isPresent() && isOrLiesInside(destination, source.get())) {
            return new SaveAsPlan.TargetInsideProject();
        }
        boolean intoExistingEmptyFolder = false;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!isEmptyFolder(destination)) {
                return new SaveAsPlan.TargetTaken(parent, name.toString());
            }
            intoExistingEmptyFolder = true;
        }
        if (!name.toString().equals(capture.name())) {
            throw new IllegalArgumentException("The copy is named after its folder: " + destination
                    + " is not named \"" + capture.name() + "\"; capture the project again with that name");
        }
        Selection selection = source.isPresent() ? select(source.get(), capture, cancelled) : Selection.NONE;
        checkCancelled(cancelled);
        long headBytes = render(capture, selection.files(), destination).getBytes(StandardCharsets.UTF_8).length;
        Path volume = intoExistingEmptyFolder ? destination : nearestExisting(parent);
        long needed = allocated(selection.files(), headBytes, usableSpace.allocationUnit(volume));
        long usable = usableSpace.of(volume);
        if (needed > usable) {
            Path volumeRoot = parent.getRoot() == null ? parent : parent.getRoot();
            return new SaveAsPlan.NotEnoughSpace(needed, usable, volumeRoot);
        }
        return new SaveAsPlan.Ready(capture, destination, intoExistingEmptyFolder, selection.files(),
                selection.bytes() + headBytes, selection.missingFiles(), selection.unknownEntries());
    }

    /**
     * What the copy needs on a volume whose allocation unit is {@code unit}:
     * each file and the head rounded up to whole units, then the head twice
     * more and {@link #RESERVE_BYTES} — what opening the copy writes at once.
     */
    static long allocated(List<SaveAsPlan.CopyItem> files, long headBytes, long unit) {
        long needed = 0L;
        for (SaveAsPlan.CopyItem file : files) {
            needed += roundUp(file.bytes(), unit);
        }
        long head = roundUp(headBytes, unit);
        return needed + 3 * head + RESERVE_BYTES;
    }

    private static long roundUp(long bytes, long unit) {
        return bytes == 0L ? 0L : ((bytes + unit - 1) / unit) * unit;
    }

    /** The source folder, absolute and normalised; empty for a project never saved. */
    private static Optional<Path> sourceOf(SaveAsCapture capture) {
        return capture.sourceFolder().map(folder -> folder.toAbsolutePath().normalize());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Save As was cancelled while it planned the copy");
        }
    }

    /**
     * Whether {@code destination} is {@code source}, lies inside it, or is
     * another name of either: lexically; or {@code destination}'s nearest
     * existing folder, or one of that folder's parents, is the same folder as
     * {@code source} by the file system's own identity
     * ({@link Files#isSameFile}: the volume's serial number and the file's
     * index on Windows, the device and inode on POSIX — a junction, a
     * symbolic link, a share name, another letter case); or that folder's
     * real path starts with the source's. A folder whose identity cannot be
     * read — a source on a volume that is gone — is not the same.
     */
    private static boolean isOrLiesInside(Path destination, Path source) {
        if (destination.startsWith(source)) {
            return true;
        }
        Path existing = nearestExisting(destination);
        for (Path folder = existing; folder != null; folder = folder.getParent()) {
            if (isSameFolder(folder, source)) {
                return true;
            }
        }
        return Path.of(FolderFingerprint.of(existing).realPath())
                .startsWith(Path.of(FolderFingerprint.of(source).realPath()));
    }

    private static boolean isSameFolder(Path folder, Path source) {
        try {
            return Files.isSameFile(folder, source);
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "Save As could not tell whether " + folder + " is " + source);
            return false;
        }
    }

    /** {@code folder} itself when it exists, else its nearest existing parent (the root at worst). */
    private static Path nearestExisting(Path folder) {
        Path existing = folder;
        while (existing.getParent() != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing;
    }

    /** Whether {@code folder} is a folder, read without following a link, with no entry. */
    private static boolean isEmptyFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            return !entries.iterator().hasNext();
        }
    }

    /** What the copy takes from a source folder. */
    private record Selection(List<SaveAsPlan.CopyItem> files, long bytes, int missingFiles, int unknownEntries) {

        static final Selection NONE = new Selection(List.of(), 0L, 0, 0);
    }

    /** Selects the copy's files from {@code source} (see the class note). */
    private static Selection select(Path source, SaveAsCapture capture, BooleanSupplier cancelled) {
        FolderWalk walk = new FolderWalk(source, cancelled);
        walk.walkWhole(ProjectManager.AUDIO_DIR_NAME, true);
        walk.walkWhole(ProjectManager.ASSETS_DIR_NAME, false);
        walk.walkWhole(SnapshotStore.SNAPSHOTS_DIR, false);
        Map<Path, SaveAsPlan.CopyItem> items = walk.items;
        Set<Path> wholeFolders = new HashSet<>();
        for (String whole : TAKEN_WHOLE) {
            wholeFolders.add(source.resolve(whole));
        }
        Set<Path> referencedTopLevel = new HashSet<>();
        Set<Path> missing = new HashSet<>();
        for (ProjectDocument.AssetReference reference : capture.references()) {
            checkCancelled(cancelled);
            Optional<Path> relative = relativeUnder(source, capture.formerRoots(), reference);
            if (relative.isEmpty()) {
                continue;
            }
            Path file = source.resolve(relative.get()).normalize();
            if (!file.startsWith(source) || file.equals(source)) {
                continue;
            }
            Path topLevel = source.relativize(file).getName(0);
            referencedTopLevel.add(source.resolve(topLevel));
            if (items.containsKey(file)) {
                continue;
            }
            if (!wholeFolders.contains(source.resolve(topLevel))) {
                OptionalLong size = sizeOfRegularFile(file);
                if (size.isPresent()) {
                    items.put(file, new SaveAsPlan.CopyItem(file, source.relativize(file), size.getAsLong()));
                    continue;
                }
            }
            if (reference.kind() != ProjectDocument.ReferenceKind.SOUND_FONT) {
                // Named, under the folder, and not in the copy: absent, unreadable, or part of a take streaming.
                missing.add(file);
            }
        }
        List<SaveAsPlan.CopyItem> files = new ArrayList<>(items.values());
        files.sort(Comparator.comparing(item -> item.relativePath().toString()));
        long bytes = 0L;
        for (SaveAsPlan.CopyItem file : files) {
            bytes += file.bytes();
        }
        return new Selection(files, bytes, missing.size(), unknownEntries(source, referencedTopLevel));
    }

    /** The size of {@code file} when it is a regular file that can be read; empty otherwise. */
    private static OptionalLong sizeOfRegularFile(Path file) {
        try {
            return Files.isRegularFile(file) ? OptionalLong.of(Files.size(file)) : OptionalLong.empty();
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.WARNING, unreadable, () -> "Save As cannot read " + file + "; the copy leaves it out");
            return OptionalLong.empty();
        }
    }

    /**
     * One walk of the folders the copy takes whole: the files found, keyed
     * by their absolute, normalised path as the project names them — through
     * a linked folder, the link's path — and the real paths of the linked
     * folders walked so far, so each is walked once.
     */
    private static final class FolderWalk {

        private final Path source;
        private final Path sourceReal;
        private final Path audioRoot;
        private final BooleanSupplier cancelled;
        private final Map<Path, SaveAsPlan.CopyItem> items = new LinkedHashMap<>();
        private final List<Path> walkedLinks = new ArrayList<>();

        FolderWalk(Path source, BooleanSupplier cancelled) {
            this.source = source;
            this.sourceReal = Path.of(FolderFingerprint.of(source).realPath());
            this.audioRoot = source.resolve(ProjectManager.AUDIO_DIR_NAME);
            this.cancelled = cancelled;
        }

        /** Walks the top-level folder {@code name} of the source, when it is a folder (or leads to one). */
        void walkWhole(String name, boolean audio) {
            Path folder = source.resolve(name);
            if (Files.isDirectory(folder)) {
                walk(folder, folder, audio);
            }
        }

        /**
         * Walks {@code physical}, which the project names as
         * {@code linkForm}: every file found is keyed, and copied, by its
         * path under {@code linkForm}. A linked folder met on the way is
         * walked afterwards, through its real path.
         */
        private void walk(Path linkForm, Path physical, boolean audio) {
            Deque<Path[]> links = new ArrayDeque<>();
            try {
                Files.walkFileTree(physical, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        checkCancelled(cancelled);
                        Path named = named(linkForm, physical, directory);
                        if (audio && !named.equals(audioRoot) && isStreamingTake(directory)) {
                            LOG.fine(() -> "Save As leaves out " + named + ": its take is still streaming");
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        checkCancelled(cancelled);
                        visit(linkForm, physical, file, attributes, audio, links);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failed) {
                        checkCancelled(cancelled);
                        Path named = named(linkForm, physical, file);
                        if (file.equals(physical)) {
                            LOG.log(Level.WARNING, failed, () -> "Save As cannot list " + named + "; the copy takes"
                                    + " none of its files");
                        } else {
                            LOG.log(Level.WARNING, failed, () -> "Save As cannot read " + named + "; the copy"
                                    + " leaves it out");
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failed) {
                        if (failed != null) {
                            Path named = named(linkForm, physical, directory);
                            LOG.log(Level.WARNING, failed, () -> "Save As could not list all of " + named
                                    + "; the copy leaves out what it could not read");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException unreadable) {
                LOG.log(Level.WARNING, unreadable, () -> "Save As cannot list " + linkForm + "; the copy leaves out"
                        + " what it could not read");
            }
            for (Path[] link : links) {
                walkLink(link[0], link[1], audio);
            }
        }

        /** One entry the walk met that is not a folder it enters: a file, a link to one, or a linked folder. */
        private void visit(Path linkForm, Path physical, Path file, BasicFileAttributes attributes, boolean audio,
                           Deque<Path[]> links) {
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            if (AtomicFileWriter.publishedNameOf(name).isPresent()
                    || (audio && (name.endsWith(SegmentWriter.PART_SUFFIX) || name.equals(TAKE_MANIFEST_TEMP)))) {
                return;
            }
            Path named = named(linkForm, physical, file);
            if (attributes.isRegularFile()) {
                add(named, attributes.size());
            } else if (!attributes.isDirectory() && Files.isDirectory(file)) {
                links.add(new Path[] {named, file});
            } else if (!attributes.isDirectory() && Files.isRegularFile(file)) {
                OptionalLong size = sizeOfRegularFile(file);
                if (size.isPresent()) {
                    add(named, size.getAsLong());
                }
            } else {
                LOG.fine(() -> "Save As leaves out " + named + ": not a regular file");
            }
        }

        /**
         * Walks the folder the link {@code named} leads to — {@code entry}
         * on disk — through its real path, unless that path is the project
         * folder, lies inside it or above it, or is, lies inside or holds a
         * folder already walked through a link: so no file is taken twice and
         * a link loop is walked once.
         */
        private void walkLink(Path named, Path entry, boolean audio) {
            Path real;
            try {
                real = entry.toRealPath();
            } catch (IOException | RuntimeException unresolved) {
                LOG.log(Level.WARNING, unresolved, () -> "Save As cannot follow the link " + named + "; the copy"
                        + " leaves out what it leads to");
                return;
            }
            if (real.startsWith(sourceReal) || sourceReal.startsWith(real)) {
                LOG.fine(() -> "Save As leaves out " + named + ": it leads to " + real + ", inside the project"
                        + " folder or above it");
                return;
            }
            for (Path walked : walkedLinks) {
                if (real.startsWith(walked) || walked.startsWith(real)) {
                    LOG.fine(() -> "Save As leaves out " + named + ": it leads to " + real + ", which the copy"
                            + " already takes");
                    return;
                }
            }
            walkedLinks.add(real);
            LOG.fine(() -> "Save As copies " + named + " from " + real + ", where the link leads");
            walk(named, real, audio);
        }

        private void add(Path named, long size) {
            Path key = named.toAbsolutePath().normalize();
            items.put(key, new SaveAsPlan.CopyItem(key, source.relativize(key), size));
        }

        /** {@code visited}, met while walking {@code physical}, as the project names it: under {@code linkForm}. */
        private static Path named(Path linkForm, Path physical, Path visited) {
            return physical.equals(linkForm) ? visited : linkForm.resolve(physical.relativize(visited).toString());
        }
    }

    /** Whether {@code directory} holds a {@code take.manifest} that reads streaming; an unreadable one does not. */
    private static boolean isStreamingTake(Path directory) {
        Path manifest = TakeManifest.manifestPath(directory);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return TakeManifest.read(manifest).sealStatus() == TakeManifest.SealStatus.STREAMING;
        } catch (IOException | RuntimeException unreadable) {
            LOG.log(Level.FINE, unreadable, () -> "Save As copies " + directory + ": its take manifest could not"
                    + " be read");
            return false;
        }
    }

    /**
     * The path, relative to {@code source}, of the file {@code reference}
     * names under it: a clip reference the capture writes project-relative
     * ({@link ProjectPaths#relativize(Path, FormerRoots, String)} — under the
     * source, or under a former root whose own held set holds its path); a
     * SoundFont path, which the document writes as held, when it is absolute
     * and strictly under the source. Empty for every other reference.
     */
    private static Optional<Path> relativeUnder(Path source, FormerRoots formerRoots,
                                                ProjectDocument.AssetReference reference) {
        String held = reference.reference();
        if (reference.kind() == ProjectDocument.ReferenceKind.SOUND_FONT) {
            return absoluteUnder(source, held).map(source::relativize);
        }
        String written = ProjectPaths.relativize(source, formerRoots, held);
        if (written == null || written.equals(held)) {
            return Optional.empty();
        }
        return Optional.of(Path.of(written));
    }

    /** {@code reference} as an absolute, normalised path strictly under {@code root}; empty otherwise. */
    private static Optional<Path> absoluteUnder(Path root, String reference) {
        if (reference == null || !ProjectPaths.isAbsoluteReference(reference)) {
            return Optional.empty();
        }
        Path file = Path.of(reference).normalize();
        return file.startsWith(root) && !file.equals(root) ? Optional.of(file) : Optional.empty();
    }

    /**
     * How many of {@code source}'s top-level entries the copy leaves out and
     * nothing knows; {@code 0} when the folder cannot be listed (logged at
     * {@code WARNING}).
     */
    private static int unknownEntries(Path source, Set<Path> referencedTopLevel) {
        int unknown = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(source)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean known = TAKEN_WHOLE.contains(name) || name.equals(ProjectManager.PROJECT_FILE_NAME)
                        || LEFT_OUT.contains(name) || isHeadBackup(name)
                        || AtomicFileWriter.publishedNameOf(name).isPresent()
                        || referencedTopLevel.contains(entry.toAbsolutePath().normalize());
                if (!known) {
                    unknown++;
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException unreadable) {
            LOG.log(Level.WARNING, unreadable, () -> "Save As cannot list " + source + "; the copy takes only what"
                    + " it could read of it, and its unknown entries are not counted");
            return 0;
        }
        return unknown;
    }

    /** {@code project.daw.<tag>.<stamp>.bak}: a migration, degraded or recovered backup of the head. */
    private static boolean isHeadBackup(String name) {
        return name.startsWith(ProjectManager.PROJECT_FILE_NAME + ".") && name.endsWith(".bak");
    }

    /**
     * The former roots of a copy holding {@code files} (each by its path
     * relative to the copy's folder, the same path it had in the source):
     * the capture's former roots, each keeping only the files of its own set
     * the copy took, then the source folder, whose set is the copy's files
     * ({@link FormerRoots#thenCopiedFrom}). For a project never saved there
     * is no source folder and so no new root; the capture's former roots are
     * carried the same way ({@link FormerRoots#carriedBy}).
     */
    private static FormerRoots rootsOfCopy(SaveAsCapture capture, List<SaveAsPlan.CopyItem> files) {
        Set<Path> held = new HashSet<>();
        for (SaveAsPlan.CopyItem file : files) {
            held.add(file.relativePath());
        }
        FormerRoots own = capture.formerRoots();
        return sourceOf(capture).map(source -> own.thenCopiedFrom(source, held))
                .orElseGet(() -> own.carriedBy(held));
    }

    /**
     * The copy's {@code project.daw}: the captured document rendered against
     * {@code destination} — a clip reference into the folders the copy was
     * taken from written project-relative only when the copy holds that
     * folder's file at the same relative path — with its SoundFont paths
     * under the source folder that the copy takes re-pointed at the copy's
     * files.
     */
    private static String render(SaveAsCapture capture, List<SaveAsPlan.CopyItem> files, Path destination)
            throws IOException {
        Map<String, String> soundFonts = new HashMap<>();
        Optional<Path> source = sourceOf(capture);
        if (source.isPresent()) {
            Set<Path> copied = new HashSet<>();
            for (SaveAsPlan.CopyItem file : files) {
                copied.add(file.source().toAbsolutePath().normalize());
            }
            for (ProjectDocument.AssetReference reference : capture.references()) {
                if (reference.kind() != ProjectDocument.ReferenceKind.SOUND_FONT) {
                    continue;
                }
                absoluteUnder(source.get(), reference.reference())
                        .filter(copied::contains)
                        .ifPresent(file -> soundFonts.put(reference.reference(),
                                destination.resolve(source.get().relativize(file).toString()).toString()));
            }
        }
        return capture.document().toXml(destination, rootsOfCopy(capture, files),
                new ProjectSerializer.AssetReferenceRemap(Map.of(), soundFonts));
    }

    /**
     * Makes the copy {@code plan} describes (see the class note), reporting
     * to {@code progress} and asking {@code cancelled} before each file, before
     * {@link SaveAsProgress#writingHead()} and once more right after it
     * returns. Never throws: a failure is {@link SaveAsOutcome.Failed}, a
     * cancel {@link SaveAsOutcome.Cancelled}, and both delete what this call
     * created, as far as they can. A cancel asked for after that last check is
     * not seen: the copy finishes and is published.
     */
    SaveAsOutcome copy(SaveAsPlan.Ready plan, SaveAsProgress progress, BooleanSupplier cancelled) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(progress, "progress must not be null");
        Objects.requireNonNull(cancelled, "cancelled must not be null");
        SaveAsCapture capture = plan.capture();
        Path target = plan.target();
        Path staging = plan.intoExistingEmptyFolder() ? null
                : AtomicFileWriter.stagingSiblingOf(target, STAGING_PURPOSE);
        Path root = staging == null ? target : staging;
        Created created = new Created();
        try {
            if (staging == null) {
                requireStillAnEmptyFolder(target);
            } else {
                created.directories(target.getParent());
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    LOG.info(() -> "Deleting " + staging + ", which an interrupted Save As left");
                    deleteTree(staging);
                }
                Files.createDirectory(staging);
                created.addDirectory(staging);
            }
            created.directories(root.resolve(ProjectManager.AUDIO_DIR_NAME));
            long toCopy = plan.fileBytes();
            long copiedBytes = 0L;
            report(() -> progress.copied(0L, toCopy));
            for (SaveAsPlan.CopyItem file : plan.files()) {
                if (cancelled.getAsBoolean()) {
                    return cancelledAfter(created, target);
                }
                Path destination = root.resolve(file.relativePath().toString());
                created.directories(destination.getParent());
                fileWriter.copy(file.source(), destination, AtomicFileWriter.Overwrite.NEVER);
                created.add(destination);
                long written = Files.size(destination);
                long expected = Files.size(file.source());
                if (written != expected) {
                    throw new IOException("The copy of " + file.source() + " holds " + written + " bytes, not "
                            + expected);
                }
                copiedBytes += file.bytes();
                long reported = copiedBytes;
                report(() -> progress.copied(reported, toCopy));
            }
            if (cancelled.getAsBoolean()) {
                return cancelledAfter(created, target);
            }
            report(progress::writingHead);
            // Asked once more right after the callback: a cancel the caller took before that call returned is
            // seen here, so the caller's own decision (a compare-and-set in writingHead) is the copy's.
            if (cancelled.getAsBoolean()) {
                return cancelledAfter(created, target);
            }
            String xml = render(capture, plan.files(), target);
            Path head = root.resolve(ProjectManager.PROJECT_FILE_NAME);
            // Every folder the copy created is forced before project.daw is written: in an existing empty
            // folder the head is the commit point, and in a staged copy the publish below comes later still.
            created.forceDirectories();
            fileWriter.writeNewString(head, xml);
            created.add(head);
            if (staging != null) {
                fileWriter.publishDirectory(staging, target);
            }
        } catch (Throwable failure) {
            created.deleteAll(failure);
            LOG.log(Level.WARNING, failure, () -> "Save As into " + target + " failed; what it created was deleted,"
                    + " as far as it could be");
            return new SaveAsOutcome.Failed(failure);
        }
        if (plan.unknownEntries() > 0) {
            LOG.info(() -> "Save As left out " + plan.unknownEntries() + " top-level entr"
                    + (plan.unknownEntries() == 1 ? "y" : "ies") + " of " + capture.sourceFolder().orElse(null)
                    + " that no project file uses");
        }
        return new SaveAsOutcome.Published(target, capture.metadata().withPath(target), capture.sourceFolder(),
                rootsOfCopy(capture, plan.files()), plan.missingFiles(), plan.unknownEntries());
    }

    /** The existing empty folder a plan chose is still one: neither gone nor filled since. */
    private static void requireStillAnEmptyFolder(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(target.toString(), null,
                    "the empty folder chosen for the copy no longer exists");
        }
        if (!isEmptyFolder(target)) {
            throw new FileAlreadyExistsException(target.toString(), null,
                    "it is no longer an empty folder; Save As never merges into one");
        }
    }

    private static SaveAsOutcome cancelledAfter(Created created, Path target) {
        created.deleteAll(null);
        LOG.info(() -> "Save As into " + target + " was cancelled; what it created was deleted, as far as it could"
                + " be");
        return new SaveAsOutcome.Cancelled();
    }

    /** Reports progress; a report that throws is logged and the copy goes on. */
    private static void report(Runnable report) {
        try {
            report.run();
        } catch (RuntimeException failed) {
            LOG.log(Level.WARNING, failed, () -> "A Save As progress report failed; the copy goes on");
        }
    }

    /** Deletes {@code folder} and everything under it, links not followed. */
    private static void deleteTree(Path folder) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failed) throws IOException {
                if (failed != null) {
                    throw failed;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Every path this copy created, in creation order — what a cancel or a
     * failure deletes, last first. Confined to the copying thread.
     */
    private static final class Created {

        private final List<Path> paths = new ArrayList<>();
        private final List<Path> directories = new ArrayList<>();

        void add(Path path) {
            paths.add(path);
        }

        /** Records a folder this copy created. */
        void addDirectory(Path folder) {
            paths.add(folder);
            directories.add(folder);
        }

        /** Creates {@code folder} and every missing parent, recording each one this call created. */
        void directories(Path folder) throws IOException {
            if (folder == null) {
                return;
            }
            Deque<Path> missing = new ArrayDeque<>();
            for (Path step = folder; step != null && !Files.isDirectory(step); step = step.getParent()) {
                missing.push(step);
            }
            while (!missing.isEmpty()) {
                Path step = missing.pop();
                try {
                    Files.createDirectory(step);
                    paths.add(step);
                    directories.add(step);
                } catch (FileAlreadyExistsException raced) {
                    if (!Files.isDirectory(step)) {
                        throw raced;
                    }
                }
            }
        }

        /**
         * Forces the entries of every folder this copy created, and of each
         * one's parent, best effort ({@link AtomicFileWriter#forceDirectory}):
         * on POSIX a new folder's entry in its parent then survives a crash
         * once the copy is published.
         */
        void forceDirectories() {
            Set<Path> forced = new HashSet<>();
            for (int index = directories.size() - 1; index >= 0; index--) {
                Path directory = directories.get(index);
                for (Path folder : new Path[] {directory, directory.getParent()}) {
                    if (folder != null && forced.add(folder)) {
                        AtomicFileWriter.forceDirectory(folder);
                    }
                }
            }
        }

        /**
         * Deletes every recorded path, last first. A delete that fails is
         * attached to {@code failure} as suppressed, or logged when there is
         * none (a cancel); the others go on.
         */
        void deleteAll(Throwable failure) {
            for (int index = paths.size() - 1; index >= 0; index--) {
                Path path = paths.get(index);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException | RuntimeException notDeleted) {
                    if (failure != null) {
                        failure.addSuppressed(notDeleted);
                    } else {
                        LOG.log(Level.WARNING, notDeleted, () -> "Save As could not delete " + path
                                + ", which it created");
                    }
                }
            }
            paths.clear();
            directories.clear();
        }
    }
}
