package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the installed-plugin registry persists (story 334, studio rulings E9
 * and E20; Persistence Integrity book §4.5): the list of installed JAR
 * plugins with the manifest each was installed from, in
 * {@code <app config>/plugin-registry.json}, and the copy of each plugin's
 * JAR, under {@code <app config>/plugins/<id>/<version>/<original file name>}.
 * The list is app-level: a project never stores an entry or a path.
 *
 * <p>The list is a small hand-rolled JSON document (no JSON library, as the
 * other app stores), UTF-8, written only through the {@link AtomicFileWriter}
 * (story 331): a failed write leaves the previous list as it was.</p>
 * <pre>{@code
 * {
 *   "schema": "1",
 *   "plugins": [
 *     {
 *       "kind": "jar",
 *       "id": "com.example.comp",
 *       "version": "1.2.0",
 *       "name": "Comp X",
 *       "vendor": "Example Audio",
 *       "type": "EFFECT",
 *       "category": "DYNAMICS",
 *       "iconHint": "",
 *       "pluginClass": "com.example.CompX",
 *       "jar": "plugins/com.example.comp/1.2.0/comp-x.jar",
 *       "originalFile": "comp-x.jar"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code jar} is relative to the list's folder, with {@code /} separators.
 * {@link #load()} reads a list another build cannot spoil: a document that is
 * not such a list leaves no plugin listed; an element that is not an object,
 * an entry with a missing or blank required value, a {@code kind} other than
 * {@code jar}, a type or category this build does not know, or a {@code jar}
 * value that is not a path is removed; a repeated id keeps its first entry.
 * Each logs one {@code WARNING}, and the list is copied once, when it is read,
 * to {@code plugin-registry.json.<yyyyMMdd-HHmmss>.bak} beside it, before
 * anything can replace it (G4); the {@link Listing} says whether that copy was
 * kept or is still owed (ruling-3 Q2). While such a copy is there, no startup
 * deletes a JAR copy ({@link #sweepUnreferenced}, ruling-4 Q7). File values
 * in those lines are quoted through {@link UnreadProjectItem#logText(String)}.</p>
 *
 * <p>{@link #saveLater} writes off the caller's thread, in the order asked,
 * on {@code daw-plugin-registry-write} virtual threads; the application's
 * exit waits for those writes ({@link PendingWrites}). A write that fails is
 * logged, and the first failure of a session reaches the
 * {@linkplain #setSaveFailureListener listener} (G5).</p>
 */
public final class InstalledPluginStore implements PendingWrites {

    /** The list's file name in the app configuration folder. */
    public static final String FILE_NAME = "plugin-registry.json";

    /** The folder, beside the list, that holds the copies of the installed JARs. */
    public static final String PLUGINS_FOLDER_NAME = "plugins";

    /** The list format this build reads and writes. */
    static final String SCHEMA = "1";

    private static final String KIND_JAR = "jar";

    /** The keys every entry must hold, non-blank, in the order an unreadable entry names the first missing one. */
    private static final List<String> REQUIRED_KEYS =
            List.of("id", "version", "name", "vendor", "type", "pluginClass", "jar", "originalFile");

    private static final Logger LOG = Logger.getLogger(InstalledPluginStore.class.getName());

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** A character a staged path segment cannot hold (critique J23). */
    private static final Pattern UNSAFE_SEGMENT_CHARACTER = Pattern.compile("[^A-Za-z0-9._-]");

    /** Starts each write on a virtual thread of its own; {@link #saveLater} chains them in order. */
    private static final Executor WRITE_THREAD =
            command -> Thread.ofVirtual().name("daw-plugin-registry-write").start(command);

    private final Path file;
    private final AtomicFileWriter fileWriter;

    /** Guards {@link #lastWrite}. */
    private final Object writes = new Object();
    /** The last write {@link #saveLater} handed out; never completes exceptionally. */
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);

    /** Guards {@link #stagedThisSession}, and makes a staging and a sweep exclusive. */
    private final Object staging = new Object();
    /** Every JAR copy {@link #stage} returned in this session: a sweep never deletes one (G22, critique J8). */
    private final Set<Path> stagedThisSession = new HashSet<>();

    /** Whether the copy of a list a load found problems in is still owed: every save makes it first. */
    private volatile boolean copyOwed;
    private final AtomicBoolean saveFailureReported = new AtomicBoolean();
    private volatile Consumer<String> saveFailureListener = failure -> { };

    /**
     * A store of {@code <user.home>/.daw/plugin-registry.json}, the JAR copies
     * under {@code <user.home>/.daw/plugins}.
     */
    public InstalledPluginStore() {
        this(defaultFile());
    }

    /**
     * A store of {@code file}, the JAR copies under the {@code plugins} folder
     * beside it — for tests, which never touch the real {@code ~/.daw}.
     *
     * @param file the list's file
     */
    public InstalledPluginStore(Path file) {
        this(file, new AtomicFileWriter());
    }

    /**
     * A store of {@code file} whose writes go through {@code fileWriter} — for
     * tests that fail or hold them (story 331).
     */
    InstalledPluginStore(Path file, AtomicFileWriter fileWriter) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
    }

    /** Returns {@code <user.home>/.daw/plugin-registry.json}. */
    private static Path defaultFile() {
        return Paths.get(System.getProperty("user.home", "."), ".daw", FILE_NAME);
    }

    /** The folder that holds the copies of the installed JARs. */
    public Path pluginsFolder() {
        return file.resolveSibling(PLUGINS_FOLDER_NAME);
    }

    /**
     * What {@link #load()} read.
     *
     * @param plugins        the readable entries, in list order, one per id
     * @param unreadable     whether the file is not a list this build can read
     * @param removedEntries how many unreadable entries were removed (a repeated id is not counted)
     * @param copy           whether the load found a problem with the list — the file unreadable, an
     *                       entry removed or an id repeated — and if so, whether its copy was kept; a
     *                       listing that is unreadable or removed entries never says no copy was needed
     */
    public record Listing(List<InstalledPlugin> plugins, boolean unreadable, int removedEntries, ListCopy copy) {

        public Listing {
            Objects.requireNonNull(plugins, "plugins must not be null");
            Objects.requireNonNull(copy, "copy must not be null");
            plugins = List.copyOf(plugins);
            if ((unreadable || removedEntries > 0) && copy == ListCopy.NOT_NEEDED) {
                throw new IllegalArgumentException("a load that found a problem copies the list");
            }
        }
    }

    /**
     * What a load did about copying the list (G4, ruling-3 Q2): a load that
     * finds a problem with the list copies it beside itself before anything
     * can replace it.
     */
    public enum ListCopy {

        /** The load found no problem with the list. */
        NOT_NEEDED,

        /** The load found a problem and kept a copy of the list. */
        KEPT,

        /**
         * The load found a problem but could not copy the list, which is left
         * as it is until a copy of it can be kept: every later save makes the
         * copy first and fails while it cannot.
         */
        OWED
    }

    /**
     * Reads the list: empty, with nothing logged, when there is no file; see
     * the class note for what an unreadable file or entry does. It never
     * throws for the file's content: content its own reading fails on is a
     * list this build cannot read.
     *
     * @return what the list holds
     */
    public Listing load() {
        if (!Files.exists(file)) {
            return new Listing(List.of(), false, 0, ListCopy.NOT_NEEDED);
        }
        Optional<Read> read;
        try {
            read = read(Files.readString(file));
        } catch (IOException | RuntimeException unreadable) {
            return unreadableList();
        }
        if (read.isEmpty()) {
            return unreadableList();
        }
        List<String> problems = read.get().problems();
        if (problems.isEmpty()) {
            return new Listing(read.get().plugins(), false, 0, ListCopy.NOT_NEEDED);
        }
        KeptCopy kept = keepCopy("the list");
        for (String problem : problems) {
            LOG.warning(problem + kept.lineEnding());
        }
        return new Listing(read.get().plugins(), false, read.get().removed(), kept.copy());
    }

    /**
     * What a list's text holds: its readable entries, one per id, the line
     * of each problem found, and how many entries were removed as unreadable.
     */
    private record Read(List<InstalledPlugin> plugins, List<String> problems, int removed) {

        private Read {
            plugins = List.copyOf(plugins);
            problems = List.copyOf(problems);
        }
    }

    /**
     * Reads {@code text} as the list, logging nothing; empty when it is not a
     * list this build can read.
     *
     * @throws IllegalArgumentException if {@code text} is not JSON
     */
    private Optional<Read> read(String text) {
        Object document = Json.parse(text);
        if (!(document instanceof Map<?, ?> list) || !SCHEMA.equals(list.get("schema"))
                || !(list.get("plugins") instanceof List<?> elements)) {
            return Optional.empty();
        }
        List<InstalledPlugin> plugins = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int removed = 0;
        for (int index = 0; index < elements.size(); index++) {
            int number = index + 1;
            switch (entry(elements.get(index))) {
                case Entry.Unreadable unreadable -> {
                    problems.add("Plugin registry: entry " + number + " cannot be read (" + unreadable.why()
                            + "); it is removed");
                    removed++;
                }
                case Entry.Readable readable -> {
                    String id = readable.plugin().id();
                    if (ids.add(id)) {
                        plugins.add(readable.plugin());
                    } else {
                        problems.add("Plugin registry: '" + UnreadProjectItem.logText(id) + "' is listed again as"
                                + " entry " + number + "; the first entry is used and this one is removed");
                    }
                }
            }
        }
        return Optional.of(new Read(plugins, problems, removed));
    }

    /** A list this build cannot read: nothing listed, one line, the copy kept. */
    private Listing unreadableList() {
        KeptCopy kept = keepCopy("the file");
        LOG.warning(() -> "Plugin registry: '" + UnreadProjectItem.logText(file.toString()) + "' is not a list of"
                + " installed plugins this build can read; no installed plugin is restored" + kept.lineEnding());
        return new Listing(List.of(), true, 0, kept.copy());
    }

    /** What became of a load's copy of the list, and the words its lines end with. */
    private record KeptCopy(ListCopy copy, String lineEnding) {
    }

    /**
     * Copies the list beside itself, once per load that found a problem, and
     * says so as the load's lines end. A copy that fails stays owed: every
     * later save makes it first, and fails while it cannot, so the list is
     * never replaced before a copy of it exists.
     */
    private KeptCopy keepCopy(String subject) {
        try {
            Path copy = copyList();
            copyOwed = false;
            return new KeptCopy(ListCopy.KEPT,
                    ", and " + subject + " is kept as '" + UnreadProjectItem.logText(copy.toString()) + "'");
        } catch (IOException | RuntimeException failure) {
            copyOwed = true;
            return new KeptCopy(ListCopy.OWED, ", and " + subject
                    + " is left as it is until a copy of it can be kept (" + simpleName(failure) + ")");
        }
    }

    /** Copies the list to {@code plugin-registry.json.<stamp>[-<n>].bak} beside it, never over another file. */
    private Path copyList() throws IOException {
        String stamp = LocalDateTime.now().format(BACKUP_STAMP);
        for (int attempt = 1; ; attempt++) {
            String suffix = attempt == 1 ? "" : "-" + attempt;
            Path copy = file.resolveSibling(file.getFileName() + "." + stamp + suffix + ".bak");
            try {
                return fileWriter.copy(file, copy, AtomicFileWriter.Overwrite.NEVER);
            } catch (FileAlreadyExistsException taken) {
                if (attempt >= 100) {
                    throw taken;
                }
            }
        }
    }

    /** One element of the {@code plugins} array, read. */
    private sealed interface Entry permits Entry.Readable, Entry.Unreadable {

        record Readable(InstalledPlugin plugin) implements Entry {
        }

        /** {@code why} is the parenthesised reason of the entry's line. */
        record Unreadable(String why) implements Entry {
        }
    }

    private Entry entry(Object element) {
        if (!(element instanceof Map<?, ?> fields)) {
            return new Entry.Unreadable("it is not a JSON object");
        }
        String kind = text(fields, "kind");
        if (kind == null) {
            return missing("kind");
        }
        if (!kind.equals(KIND_JAR)) {
            return new Entry.Unreadable("its kind '" + UnreadProjectItem.logText(kind) + "' is not jar");
        }
        for (String key : REQUIRED_KEYS) {
            if (text(fields, key) == null) {
                return missing(key);
            }
        }
        PluginType type = constant(PluginType.class, text(fields, "type"));
        if (type == null) {
            return unknown("type", text(fields, "type"));
        }
        String categoryName = text(fields, "category");
        PluginCategory category = categoryName == null
                ? PluginCategory.UTILITY : constant(PluginCategory.class, categoryName);
        if (category == null) {
            return unknown("category", categoryName);
        }
        Path jar;
        try {
            jar = file.resolveSibling(text(fields, "jar")).normalize();
        } catch (InvalidPathException notAPath) {
            return new Entry.Unreadable("its 'jar' value '" + UnreadProjectItem.logText(text(fields, "jar"))
                    + "' is not a path");
        }
        String iconHint = fields.get("iconHint") instanceof String hint ? hint : "";
        PluginDescriptor descriptor = new PluginDescriptor(text(fields, "id"), text(fields, "name"),
                text(fields, "version"), text(fields, "vendor"), type, category, iconHint);
        String pluginClass = text(fields, "pluginClass");
        return new Entry.Readable(new InstalledPlugin(new ExternalPluginEntry(jar, pluginClass),
                new PluginManifest(pluginClass, descriptor), text(fields, "originalFile")));
    }

    private static Entry missing(String key) {
        return new Entry.Unreadable("its '" + key + "' value is missing or blank");
    }

    private static Entry unknown(String key, String value) {
        return new Entry.Unreadable("its '" + key + "' value '" + UnreadProjectItem.logText(value)
                + "' is not one this build knows");
    }

    /** The non-blank string {@code fields} holds for {@code key}, else {@code null}. */
    private static String text(Map<?, ?> fields, String key) {
        return fields.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException notAConstant) {
            return null;
        }
    }

    /**
     * Writes {@code plugins} as the list, now, on the calling thread: creates
     * the folder first, makes a copy still owed (see {@link #load()}), then
     * replaces the file atomically.
     *
     * @param plugins the installed plugins, in list order
     * @throws IOException if the folder, the copy or the write fails; the previous list is then as it was
     */
    public void save(List<InstalledPlugin> plugins) throws IOException {
        String json = toJson(plugins);
        Files.createDirectories(file.getParent());
        if (copyOwed) {
            copyList();
            copyOwed = false;
        }
        fileWriter.writeString(file, json);
    }

    /**
     * Writes {@code plugins} as the list off the caller's thread, after every
     * write asked for before (each writes the list it was given). A failed
     * write logs a {@code WARNING}, and the session's first failure reaches
     * the {@linkplain #setSaveFailureListener listener}.
     *
     * @param plugins the installed plugins, in list order
     */
    public void saveLater(List<InstalledPlugin> plugins) {
        List<InstalledPlugin> snapshot = List.copyOf(plugins);
        synchronized (writes) {
            lastWrite = lastWrite.exceptionally(neverExceptional -> null)
                    .thenRunAsync(() -> writeNow(snapshot), WRITE_THREAD);
        }
    }

    private void writeNow(List<InstalledPlugin> plugins) {
        try {
            save(plugins);
        } catch (IOException | RuntimeException failure) {
            String failureName = simpleName(failure);
            LOG.warning(() -> "Plugin registry: the list of installed plugins could not be saved (" + failureName
                    + "); the next install or removal saves it again");
            if (saveFailureReported.compareAndSet(false, true)) {
                saveFailureListener.accept(failureName);
            }
        }
    }

    /**
     * Sets what hears of a failed {@link #saveLater} write: the simple class
     * name of what the write threw, at most once per session, on the writing
     * thread (G5: the application shows one notification).
     *
     * @param listener the listener
     */
    public void setSaveFailureListener(Consumer<String> listener) {
        saveFailureListener = Objects.requireNonNull(listener, "listener must not be null");
    }

    @Override
    public boolean pending() {
        synchronized (writes) {
            return !lastWrite.isDone();
        }
    }

    @Override
    public boolean awaitUntil(long deadlineNanos) throws InterruptedException {
        CompletableFuture<Void> awaited;
        synchronized (writes) {
            awaited = lastWrite;
        }
        try {
            awaited.get(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException stillWriting) {
            return false;
        } catch (ExecutionException neverExceptional) {
            // A write catches its own failure; were one to escape, that write would be over.
            return true;
        }
    }

    /**
     * Copies {@code jar} to {@code plugins/<id>/<version>/<file name>}, the
     * folder created as needed; returns the copy. A copy already there with
     * the same bytes is reused. One with other bytes — a rebuild of the same
     * version, perhaps still loaded and so locked on Windows — is left alone,
     * and the JAR goes to {@code <version>~<n>} for the first free {@code n}
     * from 2 (design §14 R6, accepted G23). The id and the version become
     * path segments only with every character outside
     * {@code [A-Za-z0-9._-]} replaced by {@code _}, and an empty, {@code .} or
     * {@code ..} segment as {@code _} (critique J23); a copy outside the
     * {@code plugins} folder is refused. A sweep never deletes a copy this
     * returned in this session.
     *
     * @param jar      the JAR the engineer installs
     * @param manifest the manifest of the plugin installed from it
     * @return the copy
     * @throws IOException if the copy fails or would leave the {@code plugins} folder
     */
    public Path stage(Path jar, PluginManifest manifest) throws IOException {
        Objects.requireNonNull(jar, "jar must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        Path root = pluginsFolder();
        Path idFolder = root.resolve(segment(manifest.descriptor().id()));
        String version = segment(manifest.descriptor().version());
        String fileName = jar.getFileName().toString();
        synchronized (staging) {
            for (int attempt = 1; ; attempt++) {
                Path target = idFolder.resolve(attempt == 1 ? version : version + "~" + attempt).resolve(fileName)
                        .normalize();
                if (!target.startsWith(root) || target.getNameCount() != root.getNameCount() + 3) {
                    throw new IOException("the copy of " + fileName + " would leave the plugins folder: " + target);
                }
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    fileWriter.copy(jar, target, AtomicFileWriter.Overwrite.NEVER);
                    stagedThisSession.add(target);
                    return target;
                }
                if (Files.mismatch(jar, target) == -1L) {
                    stagedThisSession.add(target);
                    return target;
                }
            }
        }
    }

    /** {@code value} as one safe path segment. */
    private static String segment(String value) {
        String safe = UNSAFE_SEGMENT_CHARACTER.matcher(value).replaceAll("_");
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? "_" : safe;
    }

    /**
     * Deletes, deepest first, every folder under the {@code plugins} folder that
     * holds none of the JARs {@code keptJars} names nor a copy {@link #stage}
     * returned in this session — the copies of removed and replaced plugins
     * (studio ruling E20). {@code keptJars} is asked once the sweep holds out
     * every staging, so a JAR staged meanwhile is never deleted (G22). A file
     * that cannot be deleted — a JAR a running class loader keeps locked on
     * Windows — is logged at {@code FINE} and left for the next startup. Only
     * a startup whose {@link #load()} found no problem with the list sweeps:
     * after any other load, the copy of the list names JARs no entry holds
     * (ruling-3 A1).
     *
     * <p>While a copy of the list is kept beside it — any entry of the list's
     * folder whose name, ignoring case, begins with the list's file name and
     * a dot and ends with {@code .bak}, as every copy {@link #load()} keeps
     * does — the sweep deletes nothing, and logs one {@code INFO} line naming
     * the first copy in name order: the copy is the engineer's way to recover
     * the list, and may name JAR copies no entry holds any more. A folder that
     * cannot be searched for copies counts as holding one. The hold is the
     * file on disk: it lasts until every copy is deleted, or one is renamed
     * back over the list (ruling-4 Q7).</p>
     *
     * @param keptJars the JARs the registry lists, read when the sweep starts
     */
    public void sweepUnreferenced(Supplier<? extends Collection<Path>> keptJars) {
        Objects.requireNonNull(keptJars, "keptJars must not be null");
        Path root = pluginsFolder();
        synchronized (staging) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (aCopyOfTheListHoldsTheSweep(root)) {
                return;
            }
            Set<Path> kept = new HashSet<>(stagedThisSession);
            for (Path jar : keptJars.get()) {
                kept.add(jar.toAbsolutePath().normalize());
            }
            List<Path> found;
            try (Stream<Path> walk = Files.walk(root)) {
                found = walk.filter(path -> !path.equals(root))
                        .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                        .toList();
            } catch (IOException | UncheckedIOException unreadable) {
                LOG.log(Level.FINE, unreadable, () -> "Plugin registry: the folder '" + root + "' could not be read;"
                        + " the next startup sweeps it again");
                return;
            }
            for (Path path : found) {
                Path folder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
                if (folder.equals(root) || kept.stream().anyMatch(jar -> jar.startsWith(folder))) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException locked) {
                    LOG.log(Level.FINE, locked, () -> "Plugin registry: '" + path + "' could not be deleted ("
                            + simpleName(locked) + "); the next startup deletes it");
                }
            }
        }
    }

    /**
     * Whether a copy of the list is kept beside it, which holds the sweep
     * (ruling-4 Q7); says why the sweep deletes nothing — at {@code INFO},
     * naming the first copy in name order, or at {@code FINE} when the folder
     * cannot be searched, which counts as a copy being there.
     */
    private boolean aCopyOfTheListHoldsTheSweep(Path pluginsFolder) {
        Path folder = file.getParent();
        String copyPrefix = file.getFileName().toString().toLowerCase(Locale.ROOT) + ".";
        Optional<String> firstCopy;
        try (Stream<Path> entries = Files.list(folder)) {
            firstCopy = entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> {
                        String lowerCase = name.toLowerCase(Locale.ROOT);
                        return lowerCase.startsWith(copyPrefix) && lowerCase.endsWith(".bak");
                    })
                    .min(Comparator.naturalOrder());
        } catch (IOException | UncheckedIOException unsearchable) {
            String failure = simpleName(unsearchable instanceof UncheckedIOException unchecked
                    ? unchecked.getCause() : unsearchable);
            LOG.fine(() -> "Plugin registry: the folder '" + UnreadProjectItem.logText(folder.toString())
                    + "' could not be searched for copies of the list (" + failure + "); no JAR copy is deleted"
                    + " at this startup");
            return true;
        }
        if (firstCopy.isEmpty()) {
            return false;
        }
        LOG.info(() -> "Plugin registry: no JAR copy under '" + UnreadProjectItem.logText(pluginsFolder.toString())
                + "' is deleted while a copy of the list is kept beside it ('"
                + UnreadProjectItem.logText(firstCopy.get()) + "'); once every copy of the list is deleted, a"
                + " startup deletes the JAR copies no entry holds");
        return true;
    }

    /** The list as the class note shows it. */
    String toJson(List<InstalledPlugin> plugins) {
        Path folder = file.getParent();
        StringBuilder json = new StringBuilder("{\n  \"schema\": \"").append(SCHEMA).append("\",\n  \"plugins\": [");
        for (int index = 0; index < plugins.size(); index++) {
            InstalledPlugin plugin = Objects.requireNonNull(plugins.get(index), "plugins must not hold null");
            PluginDescriptor descriptor = plugin.manifest().descriptor();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("kind", KIND_JAR);
            fields.put("id", descriptor.id());
            fields.put("version", descriptor.version());
            fields.put("name", descriptor.name());
            fields.put("vendor", descriptor.vendor());
            fields.put("type", descriptor.type().name());
            fields.put("category", descriptor.category().name());
            fields.put("iconHint", descriptor.iconHint());
            fields.put("pluginClass", plugin.manifest().pluginClass());
            fields.put("jar", relativeJar(folder, plugin.entry().jarPath()));
            fields.put("originalFile", plugin.originalFileName());
            json.append(index == 0 ? "\n" : ",\n").append("    {");
            int field = 0;
            for (Map.Entry<String, String> value : fields.entrySet()) {
                json.append(field++ == 0 ? "\n" : ",\n").append("      ");
                Json.appendString(json, value.getKey());
                json.append(": ");
                Json.appendString(json, value.getValue());
            }
            json.append("\n    }");
        }
        json.append(plugins.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        return json.toString();
    }

    /** {@code jar} relative to {@code folder} with {@code /} separators; absolute when it lies elsewhere. */
    private static String relativeJar(Path folder, Path jar) {
        Path absolute = jar.toAbsolutePath().normalize();
        if (!absolute.startsWith(folder)) {
            return absolute.toString();
        }
        List<String> names = new ArrayList<>();
        for (Path name : folder.relativize(absolute)) {
            names.add(name.toString());
        }
        return String.join("/", names);
    }

    private static String simpleName(Throwable failure) {
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.isEmpty() ? failure.getClass().getName() : simpleName;
    }

    /**
     * The JSON this store reads and writes: objects (as insertion-ordered
     * maps), arrays (as lists) and strings; a number, {@code true},
     * {@code false} or {@code null} is read as a {@link Literal}, which no
     * field accepts. Anything else is not JSON ({@link IllegalArgumentException}).
     */
    private static final class Json {

        private static final int MAX_DEPTH = 64;
        private static final Pattern LITERAL =
                Pattern.compile("true|false|null|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

        /** A JSON value that is neither a string, an object nor an array. */
        private record Literal(String token) {
        }

        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            Object value = json.value(0);
            json.skipSpace();
            if (json.at != text.length()) {
                throw json.notJson("text after the document");
            }
            return value;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw notJson("nested too deep");
            }
            skipSpace();
            if (at >= text.length()) {
                throw notJson("the document ends early");
            }
            return switch (text.charAt(at)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                default -> literal();
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> fields = new LinkedHashMap<>();
            at++;
            skipSpace();
            if (peek() == '}') {
                at++;
                return fields;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw notJson("a key is not a string");
                }
                String key = string();
                skipSpace();
                expect(':');
                fields.put(key, value(depth + 1));
                skipSpace();
                char next = peek();
                at++;
                if (next == '}') {
                    return fields;
                }
                if (next != ',') {
                    throw notJson("an object is not closed");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> elements = new ArrayList<>();
            at++;
            skipSpace();
            if (peek() == ']') {
                at++;
                return elements;
            }
            while (true) {
                elements.add(value(depth + 1));
                skipSpace();
                char next = peek();
                at++;
                if (next == ']') {
                    return elements;
                }
                if (next != ',') {
                    throw notJson("an array is not closed");
                }
            }
        }

        private String string() {
            StringBuilder value = new StringBuilder();
            at++;
            while (true) {
                if (at >= text.length()) {
                    throw notJson("a string is not closed");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return value.toString();
                }
                if (c < 0x20) {
                    throw notJson("a control character in a string");
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw notJson("a string is not closed");
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw notJson("a \\u escape is cut short");
                        }
                        try {
                            value.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException notHex) {
                            throw notJson("a \\u escape is not hexadecimal");
                        }
                        at += 4;
                    }
                    default -> throw notJson("an unknown escape");
                }
            }
        }

        private Literal literal() {
            int start = at;
            while (at < text.length() && "{}[]\",: \t\r\n".indexOf(text.charAt(at)) < 0) {
                at++;
            }
            String token = text.substring(start, at);
            if (!LITERAL.matcher(token).matches()) {
                throw notJson("an unknown value");
            }
            return new Literal(token);
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw notJson("'" + expected + "' expected");
            }
            at++;
        }

        private char peek() {
            if (at >= text.length()) {
                throw notJson("the document ends early");
            }
            return text.charAt(at);
        }

        private void skipSpace() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        private IllegalArgumentException notJson(String why) {
            return new IllegalArgumentException("not JSON at " + at + ": " + why);
        }

        static void appendString(StringBuilder json, String value) {
            json.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            json.append(c);
                        }
                    }
                }
            }
            json.append('"');
        }
    }
}
