package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.persistence.InstalledPluginStore;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginScanner;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.editor.PluginManifestReader;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The installed external plugins (story 334, studio rulings E9, E20 and
 * E21; Persistence Integrity book §4.5) — the only place a plugin id turns
 * into a plugin (Plugin View book rejection #5), and the production
 * {@link PluginResolver} of a project open.
 *
 * <p><b>Identity.</b> An entry is an installed JAR plugin, identified by its
 * manifest's plugin id, never its JAR path; one entry per id: an install of
 * an installed id replaces it. Each entry keeps the manifest it was
 * installed from ({@link InstalledPlugin}) and, once loaded, a catalogue
 * instance, which the plugin browser reads and no insert runs: an insert
 * gets a fresh instance of its own ({@link #resolve}).</p>
 *
 * <p><b>Persistence.</b> With an {@link InstalledPluginStore}, every install
 * and removal writes the whole list off the caller's thread
 * ({@link InstalledPluginStore#saveLater}); the application's exit waits for
 * those writes. {@link #restore()} — once per startup, on a
 * {@code daw-plugin-registry-restore} virtual thread — lists every entry the
 * file holds as pending at once, before any third-party code runs (critique
 * J8), then checks each again, in list order, and publishes it as it is
 * checked: loaded, or kept as Missing with its reason — never dropped (E9:
 * keep, never drop). A plugin whose own code throws while it is checked —
 * anything but the virtual machine's own error; a {@link StackOverflowError}
 * is the plugin's — fails to load that entry alone; the restore goes on
 * (ruling-3 C4, finding FX2-7). Then, when the store read the list with no
 * problem, the restore sweeps the JAR copies no entry holds — unless a copy
 * of the list is kept beside it, which holds the sweep for as long as it is
 * there ({@link InstalledPluginStore#sweepUnreferenced}, ruling-4 Q7); a
 * read that found the list unreadable, removed an entry or met a repeated id
 * sweeps nothing, since the copy of the list it kept names those copies
 * (ruling-3 A1). An entry installed while the restore runs is newer than the
 * file's entry of its id. A Missing entry never resolves an insert.</p>
 *
 * <p><b>Threads.</b> The registry is thread-safe: its state is guarded by
 * its monitor, and no third-party code — construction, {@code dispose()} —
 * and no disk work runs while it is held. The project open resolves on its
 * parse worker and waits for the restore ({@link #resolve}); a snapshot
 * restore on the JavaFX application thread asks {@link #withoutLoading()},
 * which never waits and never constructs (studio ruling R1). An install or
 * removal returns what it took out; its caller disposes that off the JavaFX
 * application thread ({@link RemovedPlugin}, critique J9). Installing or
 * removing changes no open project's insert (E2).</p>
 *
 * <p>The registry also holds native plugin hosts and scans for CLAP plugins
 * (story 034); it has no installed-CLAP concept (E9).</p>
 */
public final class PluginRegistry implements PluginResolver {

    /** How long a project open waits for the startup restore (E9: the first open waits for the registry). */
    static final Duration RESTORE_WAIT = Duration.ofSeconds(60);

    private static final Logger LOG = Logger.getLogger(PluginRegistry.class.getName());

    /** Where the list persists; {@code null} for a registry kept in memory only. */
    private final InstalledPluginStore store;
    private final Duration restoreWait;

    /** The entries, in list order; guarded by this registry's monitor, as every field below. */
    private final List<Listed> listed = new ArrayList<>();
    private final List<ExternalPluginHost> nativePlugins = new ArrayList<>();
    /** The one restore; {@code null} until {@link #restore()} first runs. */
    private CompletableFuture<RestoreReport> restoration;
    /** Whether the store's entries are listed (always, without a store). */
    private boolean storeListed;
    /** Whether an install or removal before the store's entries were listed still owes the list's write. */
    private boolean writeOwedOnceListed;
    /** Whether a project open already waited {@link #restoreWait} in vain (G6): later opens do not wait. */
    private boolean restoreWaitRanOut;

    /** An entry and what became of it; compared by identity. */
    private static final class Listed {
        private final InstalledPlugin plugin;
        private State state;

        private Listed(InstalledPlugin plugin, State state) {
            this.plugin = plugin;
            this.state = state;
        }
    }

    private sealed interface State permits Pending, Loaded, Missing {
    }

    /** Listed from the file, not checked yet. */
    private record Pending() implements State {
    }

    /** Loaded: the catalogue instance and the class loader that loaded it. */
    private record Loaded(DawPlugin plugin, URLClassLoader classLoader) implements State {
    }

    /** Kept as Missing. */
    private record Missing(MissingReason reason) implements State {
    }

    /** A registry kept in memory only, whose {@link #restore()} completes at once with nothing to report. */
    public PluginRegistry() {
        this.store = null;
        this.restoreWait = RESTORE_WAIT;
        this.storeListed = true;
    }

    /**
     * A registry persisted in {@code store}: the application's. Its entries
     * are listed by {@link #restore()}.
     *
     * @param store where the list persists
     */
    public PluginRegistry(InstalledPluginStore store) {
        this(store, RESTORE_WAIT);
    }

    /** As {@link #PluginRegistry(InstalledPluginStore)}, a project open waiting at most {@code restoreWait} (tests). */
    PluginRegistry(InstalledPluginStore store, Duration restoreWait) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.restoreWait = Objects.requireNonNull(restoreWait, "restoreWait must not be null");
    }

    /**
     * Restores the installed plugins from the store, once: the first call
     * starts the restore (see the class note); every call returns a future of
     * that one restore, completed — once the startup's sweep, if any, is over
     * — with what the startup tells the engineer once ({@link RestoreReport}).
     * Cancelling the returned future does not stop the restore. Without a
     * store it completes at once with nothing to report.
     *
     * @return the restore
     */
    public CompletableFuture<RestoreReport> restore() {
        return restoration().copy();
    }

    /** The one restore, started by the first call. */
    private CompletableFuture<RestoreReport> restoration() {
        CompletableFuture<RestoreReport> started;
        synchronized (this) {
            if (restoration != null) {
                return restoration;
            }
            restoration = new CompletableFuture<>();
            if (store == null) {
                restoration.complete(new RestoreReport(List.of(), false, 0, false));
                return restoration;
            }
            started = restoration;
        }
        InstalledPluginStore from = store;
        try {
            Thread.ofVirtual().name("daw-plugin-registry-restore").start(() -> restoreFrom(from, started));
        } catch (RuntimeException | Error notStarted) {
            started.completeExceptionally(notStarted);
            throw notStarted;
        }
        return started;
    }

    private void restoreFrom(InstalledPluginStore from, CompletableFuture<RestoreReport> restoring) {
        try {
            InstalledPluginStore.Listing listing = from.load();
            List<Listed> pending = listPending(listing.plugins());
            List<InstalledPlugin> missing = new ArrayList<>();
            for (Listed entry : pending) {
                State checked = check(entry.plugin);
                if (publish(entry, checked) && checked instanceof Missing) {
                    missing.add(entry.plugin);
                }
            }
            if (listing.copy() == InstalledPluginStore.ListCopy.NOT_NEEDED) {
                from.sweepUnreferenced(this::listedJars);
            }
            restoring.complete(new RestoreReport(missing, listing.unreadable(), listing.removedEntries(),
                    listing.copy() == InstalledPluginStore.ListCopy.OWED));
        } catch (RuntimeException | Error failure) {
            restoring.completeExceptionally(failure);
            throw failure;
        }
    }

    /**
     * Lists the store's entries as pending, ahead of any installed meanwhile,
     * skipping an id installed meanwhile (the install is newer); then makes
     * the list's write an install or removal meanwhile owes.
     */
    private synchronized List<Listed> listPending(List<InstalledPlugin> plugins) {
        List<Listed> pending = new ArrayList<>();
        for (InstalledPlugin plugin : plugins) {
            if (indexOfId(plugin.id()) < 0) {
                Listed entry = new Listed(plugin, new Pending());
                listed.add(pending.size(), entry);
                pending.add(entry);
            }
        }
        storeListed = true;
        if (writeOwedOnceListed) {
            writeOwedOnceListed = false;
            store.saveLater(snapshot());
        }
        return pending;
    }

    /**
     * Checks an entry again, as every startup does (E9), off the monitor: its
     * JAR, its manifest, the class it names, and the id and version the
     * loaded plugin reports. Anything the plugin's own code throws but the
     * virtual machine's own error — a {@link StackOverflowError} is the
     * plugin's ({@link PluginResolution.FailedToLoad#rethrowIfFatal}) — fails
     * to load the entry; the instance is released with its loader first,
     * whatever was thrown (ruling-3 C4). It never asks the plugin for its
     * latency: the catalogue instance is never initialized, so the answer
     * would mean nothing (ruling-4 Q8).
     */
    private static State check(InstalledPlugin plugin) {
        Path jar = plugin.entry().jarPath();
        if (!Files.isRegularFile(jar)) {
            return new Missing(new MissingReason.FileNotFound());
        }
        PluginManifestReader.BundleResult read = new PluginManifestReader().readAllFromJar(jar);
        if (!(read instanceof PluginManifestReader.BundleResult.Valid valid)) {
            return new Missing(new MissingReason.ManifestInvalid());
        }
        Optional<PluginManifest> declared = valid.values().stream()
                .filter(manifest -> manifest.pluginClass().equals(plugin.entry().className()))
                .findFirst();
        if (declared.isEmpty()) {
            return new Missing(new MissingReason.ManifestNoLongerDeclares());
        }
        if (!declares(declared.get().descriptor(), plugin)) {
            return new Missing(new MissingReason.IdOrVersionChanged());
        }
        ExternalPluginLoader.LoadResult loaded;
        try {
            loaded = ExternalPluginLoader.loadWithClassLoader(plugin.entry());
        } catch (Throwable failed) {
            // PluginLoadException, or what the plugin's class threw; the loader closed its class loader.
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return failedToLoad(failed);
        }
        Loaded made = new Loaded(loaded.plugin(), loaded.classLoader());
        boolean reportsItsEntry;
        try {
            reportsItsEntry = declares(loaded.plugin().getDescriptor(), plugin);
        } catch (Throwable failed) {
            releaseQuietly(made);
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return failedToLoad(failed);
        }
        if (reportsItsEntry) {
            return made;
        }
        releaseQuietly(made);
        return new Missing(new MissingReason.IdOrVersionChanged());
    }

    private static boolean declares(PluginDescriptor descriptor, InstalledPlugin plugin) {
        return descriptor.id().equals(plugin.id()) && descriptor.version().equals(plugin.version());
    }

    private static Missing failedToLoad(Throwable failed) {
        return new Missing(new MissingReason.FailedToLoad(PluginResolution.FailedToLoad.of(failed).failure()));
    }

    /**
     * Publishes a checked entry, if it is still listed; an entry replaced or
     * removed meanwhile gives up what its check loaded. A Missing entry logs
     * its one startup line.
     *
     * @return whether the entry was still listed
     */
    private boolean publish(Listed entry, State checked) {
        boolean stillListed;
        synchronized (this) {
            stillListed = indexOf(entry) >= 0;
            if (stillListed) {
                entry.state = checked;
            }
        }
        if (!stillListed) {
            if (checked instanceof Loaded loaded) {
                releaseQuietly(loaded);
            }
            return false;
        }
        if (checked instanceof Missing missing) {
            LOG.warning(() -> "Plugin registry: '" + UnreadProjectItem.logText(entry.plugin.id()) + "' version '"
                    + UnreadProjectItem.logText(entry.plugin.version()) + "' did not load — "
                    + missing.reason().describe() + "; it stays listed as Missing");
        }
        return true;
    }

    /**
     * Copies {@code userJar} into the registry's own folder for the plugin
     * {@code manifest} declares (E20): the file to install from, so the
     * engineer's original is never referenced again. Without a store it is
     * {@code userJar} itself. Runs on the caller's thread, off the monitor.
     *
     * @param userJar  the JAR the engineer picked
     * @param manifest the manifest of the plugin to install from it
     * @return the JAR to install from
     * @throws IOException if the copy fails
     */
    public Path stageJar(Path userJar, PluginManifest manifest) throws IOException {
        Objects.requireNonNull(userJar, "userJar must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        return store == null ? userJar : store.stage(userJar, manifest);
    }

    /**
     * Installs a loaded plugin: an entry of the same id — loaded, Missing or
     * still pending — is taken out, the new one listed last, and the list
     * written. The caller disposes what was taken out, off the JavaFX
     * application thread ({@link RemovedPlugin}). It changes no project (E9).
     *
     * @param plugin the plugin and the manifest it was installed from
     * @param loaded its catalogue instance and the class loader that loaded it
     * @return the entry it replaced, if any
     * @throws IllegalArgumentException if another id is installed from the same JAR and class
     */
    public Optional<RemovedPlugin> install(InstalledPlugin plugin, ExternalPluginLoader.LoadResult loaded) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(loaded, "loaded must not be null");
        synchronized (this) {
            int sameEntry = indexOfEntry(plugin.entry());
            if (sameEntry >= 0 && !listed.get(sameEntry).plugin.id().equals(plugin.id())) {
                throw new IllegalArgumentException(plugin.entry() + " is installed as "
                        + listed.get(sameEntry).plugin.id());
            }
            int same = indexOfId(plugin.id());
            Listed replaced = same >= 0 ? listed.remove(same) : null;
            listed.add(new Listed(plugin, new Loaded(loaded.plugin(), loaded.classLoader())));
            writeList();
            return Optional.ofNullable(replaced).map(PluginRegistry::removal);
        }
    }

    /**
     * Uninstalls {@code entry} and writes the list. The caller disposes what
     * was taken out, off the JavaFX application thread ({@link RemovedPlugin}).
     * It changes no project (E9).
     *
     * @param entry the entry to remove
     * @return what was removed; empty if {@code entry} is not listed
     */
    public Optional<RemovedPlugin> unregister(ExternalPluginEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        synchronized (this) {
            int at = indexOfEntry(entry);
            if (at < 0) {
                return Optional.empty();
            }
            Listed removed = listed.remove(at);
            writeList();
            return Optional.of(removal(removed));
        }
    }

    /** Writes the list, or — before the store's entries are listed — owes the write until they are. */
    private void writeList() {
        if (store == null) {
            return;
        }
        if (!storeListed) {
            writeOwedOnceListed = true;
            restoration();
            return;
        }
        store.saveLater(snapshot());
    }

    /** Every listed entry — pending, loaded and Missing — in list order (critique J8). */
    private List<InstalledPlugin> snapshot() {
        List<InstalledPlugin> plugins = new ArrayList<>(listed.size());
        for (Listed entry : listed) {
            plugins.add(entry.plugin);
        }
        return plugins;
    }

    private synchronized List<Path> listedJars() {
        List<Path> jars = new ArrayList<>(listed.size());
        for (Listed entry : listed) {
            jars.add(entry.plugin.entry().jarPath());
        }
        return jars;
    }

    private static RemovedPlugin removal(Listed removed) {
        Runnable disposal = removed.state instanceof Loaded loaded ? () -> release(loaded) : () -> { };
        return new RemovedPlugin(removed.plugin, disposal);
    }

    /** Disposes the catalogue instance, then closes its class loader, whatever the dispose threw. */
    private static void release(Loaded loaded) {
        try {
            loaded.plugin().dispose();
        } finally {
            ExternalPluginLoader.closeQuietly(loaded.classLoader());
        }
    }

    /**
     * As {@link #release}, logging at {@code FINE} what the plugin threw — all
     * but the virtual machine's own error ({@link PluginResolution.FailedToLoad#rethrowIfFatal}),
     * which propagates once the class loader is closed.
     */
    private static void releaseQuietly(Loaded loaded) {
        try {
            release(loaded);
        } catch (Throwable failed) {
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            LOG.log(Level.FINE, failed, () -> "Plugin registry: a plugin failed while it was disposed");
        }
    }

    /**
     * Resolves a saved JAR plugin identity to a fresh instance of the
     * installed plugin, initialized with {@code context} — the production
     * resolver of a project open, called on its parse worker (design §5.5).
     * It first waits for the startup restore (E9: an open at launch must not
     * turn every third-party insert offline), at most {@link #RESTORE_WAIT};
     * once one wait ran out no later call waits, and the first logs one
     * {@code WARNING} (G6). Then: a JAR id with no entry is not installed; a
     * Missing entry is {@linkplain PluginResolution.MissingAtStartup missing
     * at startup}; an entry the restore had not checked when the wait ran out
     * {@linkplain PluginResolution.FailedToLoad failed to load}
     * ({@code TimeoutException}), as does a JAR id while the restore had not
     * yet listed the store's entries; a CLAP identity is never installed — an
     * installed JAR of its id is {@linkplain PluginResolution.InstalledAsOtherKind
     * installed as the other kind} (E9). A loaded entry is instantiated off
     * the monitor, with a class loader of its own; what loading or
     * initializing it throws — the plugin's own code failing, a checked
     * exception it does not declare and a {@link StackOverflowError}
     * included — is a failure to load, the instance released (ruling-3 C3,
     * findings FX2-2 and FX2-7); the virtual machine's own error propagates,
     * the instance released first. The version does not take part (E21: the
     * reader logs a difference).
     *
     * @param identity the saved identity, of kind {@link PluginSourceKind#JAR} or {@link PluginSourceKind#CLAP}
     * @param context  the context to initialize the plugin with
     * @return the plugin, or why there is none
     * @throws IllegalArgumentException for a first-party identity, which the project reader resolves itself
     */
    @Override
    public PluginResolution resolve(PluginIdentity identity, PluginContext context) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(context, "context must not be null");
        requireExternal(identity);
        CompletableFuture<RestoreReport> restoring = restoration();
        try {
            awaitRestore(restoring);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new PluginResolution.FailedToLoad("InterruptedException");
        }
        ExternalPluginEntry entry;
        synchronized (this) {
            int at = indexOfId(identity.id());
            if (identity.kind() == PluginSourceKind.CLAP) {
                return at >= 0
                        ? new PluginResolution.InstalledAsOtherKind(PluginSourceKind.JAR)
                        : new PluginResolution.NotInstalled();
            }
            State state = at < 0 ? null : listed.get(at).state;
            switch (state) {
                case Loaded _ -> entry = listed.get(at).plugin.entry();
                case Missing _ -> {
                    return new PluginResolution.MissingAtStartup();
                }
                case Pending _ -> {
                    return notRestored(restoring);
                }
                case null -> {
                    return storeListed ? new PluginResolution.NotInstalled() : notRestored(restoring);
                }
            }
        }
        try {
            return new PluginResolution.Resolved(ExternalPluginLoader.instantiate(entry, context));
        } catch (Throwable failed) {
            // PluginLoadException, or what the plugin's own code threw, of any kind: instantiate released
            // what it built before it threw.
            PluginResolution.FailedToLoad.rethrowIfFatal(failed);
            return PluginResolution.FailedToLoad.of(failed);
        }
    }

    /** Waits for the restore, unless an earlier wait ran out (G6); the first wait that runs out logs. */
    private void awaitRestore(CompletableFuture<RestoreReport> restoring) throws InterruptedException {
        synchronized (this) {
            if (restoring.isDone() || restoreWaitRanOut) {
                return;
            }
        }
        try {
            restoring.get(restoreWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failed) {
            // The restore ended; what it published stands, and an entry it left unchecked failed to load.
        } catch (TimeoutException ranOut) {
            boolean first;
            synchronized (this) {
                first = !restoreWaitRanOut;
                restoreWaitRanOut = true;
            }
            if (first) {
                LOG.warning(() -> "Plugin registry: a project open waited " + durationText(restoreWait)
                        + " for the installed plugins to load; its plugin inserts open offline");
            }
        }
    }

    /** A JAR identity the restore has not published: the wait ran out (G6), or the restore itself failed. */
    private static PluginResolution notRestored(CompletableFuture<RestoreReport> restoring) {
        if (restoring.isCompletedExceptionally()) {
            return PluginResolution.FailedToLoad.of(restoring.exceptionNow());
        }
        return new PluginResolution.FailedToLoad("TimeoutException");
    }

    private static String durationText(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
    }

    /**
     * A resolver that answers from what this registry has published, never
     * waiting for the restore and never constructing a plugin — for the
     * snapshot restore, which reads on the JavaFX application thread (studio
     * ruling R1): a JAR id with no entry is not installed — once the restore
     * has listed the store's entries; before that nobody knows, and it is
     * {@linkplain PluginResolution.NotLoadedBySnapshotRestore not loaded by a
     * snapshot restore}, a text true either way (ruling-3 A3); a Missing entry
     * is missing at startup; an installed entry, loaded or still being
     * restored, is not loaded by a snapshot restore; a CLAP identity follows
     * {@link #resolve}'s rule.
     *
     * @return the resolver
     */
    public PluginResolver withoutLoading() {
        return (identity, context) -> classifyWithoutLoading(identity);
    }

    private synchronized PluginResolution classifyWithoutLoading(PluginIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        requireExternal(identity);
        int at = indexOfId(identity.id());
        if (identity.kind() == PluginSourceKind.CLAP) {
            return at >= 0
                    ? new PluginResolution.InstalledAsOtherKind(PluginSourceKind.JAR)
                    : new PluginResolution.NotInstalled();
        }
        if (at < 0) {
            return storeListed
                    ? new PluginResolution.NotInstalled()
                    : new PluginResolution.NotLoadedBySnapshotRestore();
        }
        return listed.get(at).state instanceof Missing
                ? new PluginResolution.MissingAtStartup()
                : new PluginResolution.NotLoadedBySnapshotRestore();
    }

    private static void requireExternal(PluginIdentity identity) {
        if (identity.kind() == PluginSourceKind.BUILT_IN) {
            throw new IllegalArgumentException("a first-party plugin is resolved by the project reader, not the"
                    + " registry: " + identity.id());
        }
    }

    /**
     * Why a bundle may not be installed (E20): one refusal per id its
     * manifest declares for more than one class, in manifest order.
     *
     * @param jarFileName the JAR's file name
     * @param bundle      the manifests the JAR declares
     * @return the refusals; empty when every id names one class
     */
    public static List<InstallRefusal> refusalsFor(String jarFileName, List<PluginManifest> bundle) {
        Objects.requireNonNull(jarFileName, "jarFileName must not be null");
        Map<String, List<String>> classesById = new LinkedHashMap<>();
        for (PluginManifest manifest : bundle) {
            classesById.computeIfAbsent(manifest.descriptor().id(), id -> new ArrayList<>())
                    .add(simpleName(manifest.pluginClass()));
        }
        List<InstallRefusal> refusals = new ArrayList<>();
        classesById.forEach((id, classNames) -> {
            if (classNames.size() > 1) {
                refusals.add(new InstallRefusal.DuplicateId(jarFileName, id, classNames));
            }
        });
        return List.copyOf(refusals);
    }

    /**
     * Why a loaded plugin may not be installed (E20): its descriptor reports
     * another id or version than its manifest declares.
     *
     * @param jarFileName the JAR's file name
     * @param manifest    the manifest the plugin was loaded for
     * @param loaded      the loaded plugin
     * @return the refusal; empty when they agree
     */
    public static Optional<InstallRefusal> refusalFor(String jarFileName, PluginManifest manifest, DawPlugin loaded) {
        Objects.requireNonNull(jarFileName, "jarFileName must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        PluginDescriptor reported = Objects.requireNonNull(loaded, "loaded must not be null").getDescriptor();
        PluginDescriptor declared = manifest.descriptor();
        if (reported.id().equals(declared.id()) && reported.version().equals(declared.version())) {
            return Optional.empty();
        }
        return Optional.of(new InstallRefusal.DescriptorMismatch(jarFileName, simpleName(manifest.pluginClass()),
                reported.id(), reported.version(), declared.id(), declared.version()));
    }

    /** The simple name of a binary class name: {@code com.example.CompX} → {@code CompX}. */
    private static String simpleName(String className) {
        return className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
    }

    /**
     * The installed JAR plugin of {@code id}, loaded, Missing or still being
     * restored.
     *
     * @param id the plugin id
     * @return the entry, if one is listed
     */
    public synchronized Optional<InstalledPlugin> installedJar(String id) {
        Objects.requireNonNull(id, "id must not be null");
        int at = indexOfId(id);
        return at < 0 ? Optional.empty() : Optional.of(listed.get(at).plugin);
    }

    /**
     * The installed plugin {@code entry} loads.
     *
     * @param entry the entry
     * @return the installed plugin, if {@code entry} is listed
     */
    public synchronized Optional<InstalledPlugin> installed(ExternalPluginEntry entry) {
        int at = indexOfEntry(Objects.requireNonNull(entry, "entry must not be null"));
        return at < 0 ? Optional.empty() : Optional.of(listed.get(at).plugin);
    }

    /**
     * Why {@code entry} is listed as Missing.
     *
     * @param entry the entry
     * @return the reason; empty unless the entry is listed and Missing
     */
    public synchronized Optional<MissingReason> missingReason(ExternalPluginEntry entry) {
        int at = indexOfEntry(Objects.requireNonNull(entry, "entry must not be null"));
        return at >= 0 && listed.get(at).state instanceof Missing missing
                ? Optional.of(missing.reason())
                : Optional.empty();
    }

    /**
     * Returns the listed entries — loaded, Missing and still being restored —
     * in list order, as an unmodifiable copy.
     *
     * @return the entries
     */
    public synchronized List<ExternalPluginEntry> getEntries() {
        List<ExternalPluginEntry> entries = new ArrayList<>(listed.size());
        for (Listed entry : listed) {
            entries.add(entry.plugin.entry());
        }
        return List.copyOf(entries);
    }

    /**
     * Returns the loaded entries and their catalogue instances, in list
     * order, as an unmodifiable copy.
     *
     * @return the loaded plugins
     */
    public synchronized Map<ExternalPluginEntry, DawPlugin> getLoadedPlugins() {
        Map<ExternalPluginEntry, DawPlugin> loaded = new LinkedHashMap<>();
        for (Listed entry : listed) {
            if (entry.state instanceof Loaded instance) {
                loaded.put(entry.plugin.entry(), instance.plugin());
            }
        }
        return Collections.unmodifiableMap(loaded);
    }

    /**
     * Returns the catalogue instance of {@code entry}, or {@code null} if it
     * is not loaded.
     *
     * @param entry the plugin entry
     * @return the loaded plugin, or {@code null}
     */
    public synchronized DawPlugin getPlugin(ExternalPluginEntry entry) {
        int at = indexOfEntry(entry);
        return at >= 0 && listed.get(at).state instanceof Loaded loaded ? loaded.plugin() : null;
    }

    private int indexOfId(String id) {
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).plugin.id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfEntry(ExternalPluginEntry entry) {
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).plugin.entry().equals(entry)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(Listed entry) {
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i) == entry) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Registers a native plugin host (e.g., CLAP, LV2) in the registry.
     *
     * @param host the native plugin host to register
     */
    public synchronized void registerNativePlugin(ExternalPluginHost host) {
        Objects.requireNonNull(host, "host must not be null");
        nativePlugins.add(host);
    }

    /**
     * Unregisters a native plugin host and disposes it, off the monitor.
     *
     * @param host the host to remove
     * @return {@code true} if the host was registered and removed
     */
    public boolean unregisterNativePlugin(ExternalPluginHost host) {
        Objects.requireNonNull(host, "host must not be null");
        synchronized (this) {
            if (!nativePlugins.remove(host)) {
                return false;
            }
        }
        host.dispose();
        return true;
    }

    /**
     * Returns the registered native plugin hosts, as an unmodifiable copy.
     *
     * @return the list of native plugin hosts
     */
    public synchronized List<ExternalPluginHost> getNativePlugins() {
        return List.copyOf(nativePlugins);
    }

    /**
     * Scans the default CLAP plugin directories and returns discovered plugin
     * paths. It reads no registry state and holds no lock (critique J24).
     *
     * @return a list of paths to discovered {@code .clap} files
     */
    public List<Path> scanClapPlugins() {
        return new ClapPluginScanner().scan();
    }

    /**
     * Scans the specified directories for CLAP plugins; it reads no registry
     * state and holds no lock.
     *
     * @param searchPaths the directories to scan
     * @return a list of paths to discovered {@code .clap} files
     */
    public List<Path> scanClapPlugins(List<Path> searchPaths) {
        return new ClapPluginScanner(searchPaths).scan();
    }

    /**
     * Creates a CLAP plugin host for the first plugin in the given library.
     *
     * <p>The returned host is <b>not</b> automatically registered or initialized.
     * Call {@link ClapPluginHost#initialize(com.benesquivelmusic.daw.sdk.plugin.PluginContext)}
     * and {@link #registerNativePlugin(ExternalPluginHost)} after creation.</p>
     *
     * @param libraryPath path to the {@code .clap} shared library
     * @return a new CLAP plugin host
     */
    public ClapPluginHost createClapHost(Path libraryPath) {
        Objects.requireNonNull(libraryPath, "libraryPath must not be null");
        return new ClapPluginHost(libraryPath);
    }

    /**
     * Disposes every catalogue instance and native host, off the monitor, and
     * clears the registry in memory; the persisted list is not touched.
     */
    public void disposeAll() {
        List<Loaded> loaded = new ArrayList<>();
        List<ExternalPluginHost> hosts;
        synchronized (this) {
            for (Listed entry : listed) {
                if (entry.state instanceof Loaded instance) {
                    loaded.add(instance);
                }
            }
            hosts = List.copyOf(nativePlugins);
            listed.clear();
            nativePlugins.clear();
        }
        for (Loaded instance : loaded) {
            release(instance);
        }
        for (ExternalPluginHost host : hosts) {
            host.dispose();
        }
    }
}
