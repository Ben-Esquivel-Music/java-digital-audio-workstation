package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.concurrent.DawScope;

import javafx.scene.input.KeyCombination;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The §6.3 settings-profile format plus its off-thread file I/O — story
 * 309 (Settings View Design Book §5.9, §6.3, §6.6).
 *
 * <h2>Format (pure, static, toolkit-free)</h2>
 *
 * <p>A profile is a diffable, deterministic, line-based text file: one
 * comment header line ({@code # dawg-settings-profile v1} — no
 * timestamps, so identical settings always serialise identically), then
 * one {@code scopeTag|key=value} line per selected descriptor in
 * catalogue order. Value serialisation follows the descriptor's
 * {@link SettingDescriptor#defaultValue() default-value} type
 * (Boolean/Integer/Double/String via {@code toString}/parse, enums via
 * {@code name()}), except {@link ControlKind#KEY_CAPTURE} descriptors
 * (whose defaults may be {@code null}) which serialise the
 * {@link KeyCombination#getName() combination name} — or the explicit
 * {@value #UNBOUND_MARKER} marker for a cleared binding, so an unbound
 * action round-trips.</p>
 *
 * <p>The scope tag is written for the reader's benefit (a hand-editable,
 * §3.2-scoped file) but on import the DESCRIPTOR is the scope authority
 * — a stale or edited tag never overrides
 * {@link ProfileScope#of(SettingDescriptor)} (canonical value from the
 * authority, never a hint).</p>
 *
 * <h2>Validation (§6.3 "report, don't fail whole")</h2>
 *
 * <p>{@link #parseAndValidate} never aborts: an unknown key, an
 * unparseable value, or a value the descriptor's
 * {@link SettingDescriptor#accepts validator} rejects (the canonical
 * {@code SettingsModel} setter guard, story 305 — no parallel check)
 * each produce one {@link Reject}; every other entry is applied.
 * Non-finite doubles are rejected at parse (the NaN call-site-guard
 * convention: the range guards cannot express NaN rejection).</p>
 *
 * <h2>Threading (§2.7/§6.6 — the third canonical off-thread case)</h2>
 *
 * <p>File reads/writes run on a virtual thread inside a
 * shutdown-on-failure {@link DawScope} (the {@link DeviceEnumerationTask}
 * pattern: generation guard, {@link FxDispatcher#runOnFx} dispatch,
 * injectable dispatcher seam for tests, {@link #close()} tears down
 * in-flight I/O without joining on the FX thread). Import
 * <em>validation</em> deliberately runs inside the FX-dispatched
 * callback, not on the worker: catalogue validators share one scratch
 * model and are documented FX-thread-only — only the raw file text
 * crosses the thread boundary, and parsing ~a hundred lines is
 * microseconds, never I/O.</p>
 */
public final class SettingsProfileIO implements AutoCloseable {

    /** The versioned header the first profile line carries (as a comment). */
    public static final String HEADER = "dawg-settings-profile v1";

    /**
     * Explicit round-trippable marker for a cleared key binding. Only
     * ever read for {@link ControlKind#KEY_CAPTURE} descriptors, so a
     * String-typed setting whose value happens to equal it is unaffected.
     */
    static final String UNBOUND_MARKER = "<none>";

    private static final String KEYBINDING_PREFIX = "keybinding.";

    /**
     * The §5.9 sheet's five scope checkboxes: the four §3.2 scopes with
     * Key Bindings carved out of Application ({@code keybinding.*} is a
     * user-recognisable profile unit of its own — the §5.9 mockup's
     * checkbox list), leaving "Application" as the remaining
     * APPLICATION-scope descriptors.
     */
    public enum ProfileScope {
        APPLICATION("application"),
        PROJECT_DEFAULTS("projectDefaults"),
        THIS_PROJECT("thisProject"),
        AUDIO_DEVICE("audioDevice"),
        KEY_BINDINGS("keyBindings");

        private final String tag;

        ProfileScope(String tag) {
            this.tag = tag;
        }

        /** @return the stable lower-camel tag written before {@code |} */
        public String tag() {
            return tag;
        }

        /**
         * The profile scope a descriptor belongs to — the authority the
         * import filter trusts (never the file's tag).
         */
        public static ProfileScope of(SettingDescriptor<?> descriptor) {
            Objects.requireNonNull(descriptor, "descriptor must not be null");
            if (descriptor.id().startsWith(KEYBINDING_PREFIX)) {
                return KEY_BINDINGS;
            }
            return switch (descriptor.scope()) {
                case APPLICATION -> APPLICATION;
                case PROJECT_DEFAULTS -> PROJECT_DEFAULTS;
                case THIS_PROJECT -> THIS_PROJECT;
                case AUDIO_DEVICE -> AUDIO_DEVICE;
            };
        }
    }

    /** Why one profile entry was not applied (§6.3 reject report). */
    public enum RejectKind {
        /** The line is not {@code scopeTag|key=value}. */
        MALFORMED_LINE,
        /** No catalogue descriptor carries this key. */
        UNKNOWN_SETTING,
        /** The value text does not parse as the descriptor's value type. */
        UNPARSEABLE_VALUE,
        /**
         * The parsed value was rejected by the descriptor's validator —
         * the canonical {@code SettingsModel} setter guard (story 305).
         */
        REJECTED_BY_VALIDATOR
    }

    /**
     * One rejected profile entry.
     *
     * @param key  the entry's key (or the raw line for a malformed line)
     * @param kind why it was rejected
     * @param value the offending raw value text ({@code ""} when the
     *              line never yielded one)
     */
    public record Reject(String key, RejectKind kind, String value) {
        public Reject {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * An import's outcome: the applied id → value map (catalogue-order,
     * values in the descriptors' canonical types; a key-binding value may
     * be {@code null} for an explicit {@value #UNBOUND_MARKER}) plus the
     * §6.3 reject report.
     */
    public record ImportOutcome(Map<String, Object> applied, List<Reject> rejects) {
        public ImportOutcome {
            // LinkedHashMap copy preserves catalogue/file order; a plain
            // Map.copyOf would reject the null unbound-binding values.
            Map<String, Object> orderedCopy = new LinkedHashMap<>(applied);
            applied = java.util.Collections.unmodifiableMap(orderedCopy);
            rejects = List.copyOf(rejects);
        }
    }

    // ── Pure format (toolkit-free; unit-tested off-toolkit) ──────────────────

    /**
     * Serialises the selected scopes' descriptors to profile text —
     * deterministic (catalogue order, no timestamps), diffable, and
     * machine-shareable (§6.3 "studio standard … reproducible setups").
     *
     * @param catalogue the story-305 catalogue; not null
     * @param scopes    the selected profile scopes; not null
     * @param values    current-value access per descriptor id (the
     *                  dialog's persisted read path); not null
     * @return the profile text
     */
    public static String serialize(SettingsCatalogue catalogue,
                                   Set<ProfileScope> scopes,
                                   Function<String, Object> values) {
        Objects.requireNonNull(catalogue, "catalogue must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(values, "values must not be null");
        StringBuilder out = new StringBuilder("# ").append(HEADER).append('\n');
        for (SettingDescriptor<?> descriptor : catalogue.descriptors()) {
            ProfileScope scope = ProfileScope.of(descriptor);
            if (!scopes.contains(scope)) {
                continue;
            }
            out.append(scope.tag()).append('|').append(descriptor.id()).append('=')
                    .append(serializeValue(descriptor, values.apply(descriptor.id())))
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Parses profile text and validates every entry through the matching
     * descriptor's {@link SettingDescriptor#accepts validator}. Never
     * aborts; entries outside the selected scopes are silently skipped
     * (the §5.9 checkboxes select what to include, they are not errors).
     *
     * @param catalogue the story-305 catalogue; not null
     * @param scopes    the selected profile scopes; not null
     * @param text      the profile text; not null
     * @return applied map + reject report
     */
    public static ImportOutcome parseAndValidate(SettingsCatalogue catalogue,
                                                 Set<ProfileScope> scopes,
                                                 String text) {
        Objects.requireNonNull(catalogue, "catalogue must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Map<String, Object> applied = new LinkedHashMap<>();
        List<Reject> rejects = new ArrayList<>();
        for (String line : text.lines().toList()) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int bar = entry.indexOf('|');
            int eq = entry.indexOf('=', Math.max(bar + 1, 0));
            if (bar <= 0 || eq <= bar + 1) {
                rejects.add(new Reject(entry, RejectKind.MALFORMED_LINE, ""));
                continue;
            }
            String key = entry.substring(bar + 1, eq);
            String raw = entry.substring(eq + 1);
            Optional<SettingDescriptor<?>> descriptor = catalogue.byId(key);
            if (descriptor.isEmpty()) {
                rejects.add(new Reject(key, RejectKind.UNKNOWN_SETTING, raw));
                continue;
            }
            // The DESCRIPTOR decides the scope — the file's tag is a hint.
            if (!scopes.contains(ProfileScope.of(descriptor.get()))) {
                continue;
            }
            Object value;
            try {
                value = parseValue(descriptor.get(), raw);
            } catch (RuntimeException unparseable) {
                rejects.add(new Reject(key, RejectKind.UNPARSEABLE_VALUE, raw));
                continue;
            }
            if (!descriptor.get().accepts(value)) {
                rejects.add(new Reject(key, RejectKind.REJECTED_BY_VALIDATOR, raw));
                continue;
            }
            applied.put(key, value);
        }
        return new ImportOutcome(applied, rejects);
    }

    /** One value → profile text, by the descriptor's canonical type. */
    static String serializeValue(SettingDescriptor<?> descriptor, Object value) {
        if (descriptor.controlKind() == ControlKind.KEY_CAPTURE) {
            return value instanceof KeyCombination combination
                    ? combination.getName() : UNBOUND_MARKER;
        }
        // Enum.name(), never toString() — display overrides must not leak
        // into the persistent format.
        return value instanceof Enum<?> enumValue
                ? enumValue.name() : String.valueOf(value);
    }

    /**
     * One profile text value → the descriptor's canonical type. Throws
     * (→ {@link RejectKind#UNPARSEABLE_VALUE}) for text that does not
     * parse; boolean parsing is strict ({@code true}/{@code false} only)
     * and doubles must be finite (the NaN call-site-guard convention —
     * a range validator cannot reject NaN itself).
     */
    static Object parseValue(SettingDescriptor<?> descriptor, String raw) {
        if (descriptor.controlKind() == ControlKind.KEY_CAPTURE) {
            return UNBOUND_MARKER.equals(raw) ? null : KeyCombination.valueOf(raw);
        }
        return switch (descriptor.defaultValue()) {
            case Boolean _ -> parseStrictBoolean(raw);
            case Integer _ -> Integer.valueOf(raw);
            case Double _ -> parseFiniteDouble(raw);
            case Enum<?> enumDefault -> enumValue(enumDefault.getDeclaringClass(), raw);
            case null, default -> raw;
        };
    }

    private static Boolean parseStrictBoolean(String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("not a boolean: " + raw);
    }

    private static Double parseFiniteDouble(String raw) {
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("not a finite number: " + raw);
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<? extends Enum> enumClass, String raw) {
        return Enum.valueOf(enumClass, raw);
    }

    // ── Off-thread I/O (the DeviceEnumerationTask pattern) ───────────────────

    private final SettingsCatalogue catalogue;
    private final Consumer<ImportOutcome> onImported;
    private final Consumer<Path> onExported;
    private final Consumer<Boolean> onRunningChanged;
    private final Consumer<Throwable> onFailed;
    private final Consumer<Runnable> fxDispatcher;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<DawScope> activeScope = new AtomicReference<>();
    private final AtomicReference<Thread> coordinator = new AtomicReference<>();

    private volatile boolean closed;

    /** Creates a production task whose callbacks are always posted to JavaFX. */
    public SettingsProfileIO(SettingsCatalogue catalogue,
                             Consumer<ImportOutcome> onImported,
                             Consumer<Path> onExported,
                             Consumer<Boolean> onRunningChanged,
                             Consumer<Throwable> onFailed) {
        this(catalogue, onImported, onExported, onRunningChanged, onFailed,
                FxDispatcher::runOnFx);
    }

    /** Test seam for observing the dispatch boundary without starting JavaFX. */
    SettingsProfileIO(SettingsCatalogue catalogue,
                      Consumer<ImportOutcome> onImported,
                      Consumer<Path> onExported,
                      Consumer<Boolean> onRunningChanged,
                      Consumer<Throwable> onFailed,
                      Consumer<Runnable> fxDispatcher) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue must not be null");
        this.onImported = Objects.requireNonNull(onImported, "onImported must not be null");
        this.onExported = Objects.requireNonNull(onExported, "onExported must not be null");
        this.onRunningChanged = Objects.requireNonNull(
                onRunningChanged, "onRunningChanged must not be null");
        this.onFailed = Objects.requireNonNull(onFailed, "onFailed must not be null");
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher must not be null");
    }

    /**
     * Starts (or replaces) an export. {@code valuesSnapshot} must be
     * captured on the FX thread by the caller — the worker only
     * serialises the immutable snapshot and writes the file, so shell
     * state is never read off-thread. Returns before any I/O begins.
     *
     * @param file           the destination file
     * @param scopes         the selected profile scopes
     * @param valuesSnapshot id → current value for every selected descriptor
     */
    public void startExport(Path file,
                            Set<ProfileScope> scopes,
                            Map<String, Object> valuesSnapshot) {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(valuesSnapshot, "valuesSnapshot must not be null");
        Map<String, Object> snapshot = new LinkedHashMap<>(valuesSnapshot);
        Set<ProfileScope> selected = Set.copyOf(scopes);
        startWork("settings-profile-export", "write", () -> {
            String text = serialize(catalogue, selected, snapshot::get);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return (Runnable) () -> onExported.accept(file);
        });
    }

    /**
     * Starts (or replaces) an import from {@code file}. The file read
     * runs on a virtual thread; parse + validation run inside the
     * FX-dispatched callback (catalogue validators are FX-thread-only —
     * see the class Javadoc). Returns before any I/O begins.
     */
    public void startImport(Path file, Set<ProfileScope> scopes) {
        Objects.requireNonNull(file, "file must not be null");
        startImport(scopes, () -> Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Test seam: an import over an arbitrary (possibly slow) content
     * source — production routes {@link #startImport(Path, Set)} here.
     */
    void startImport(Set<ProfileScope> scopes, Callable<String> source) {
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Set<ProfileScope> selected = Set.copyOf(scopes);
        startWork("settings-profile-import", "read", () -> {
            String text = source.call();
            return (Runnable) () ->
                    onImported.accept(parseAndValidate(catalogue, selected, text));
        });
    }

    /**
     * Shared coordinator start: bumps the generation (invalidating any
     * queued callbacks of a prior run), cancels in-flight work, and forks
     * {@code work} inside a fresh scope on a coordinator virtual thread.
     * The work returns the success callback to dispatch.
     */
    private void startWork(String name, String subtaskName, Callable<Runnable> work) {
        if (closed) {
            return;
        }
        long requestGeneration = generation.incrementAndGet();
        cancelActive();
        dispatchIfCurrent(requestGeneration, () -> onRunningChanged.accept(true));

        Thread worker = Thread.ofVirtual().name(name)
                .unstarted(() -> runScoped(requestGeneration, name, subtaskName, work));
        coordinator.set(worker);
        worker.start();
    }

    private void runScoped(long requestGeneration,
                           String name,
                           String subtaskName,
                           Callable<Runnable> work) {
        DawScope scope = DawScope.openShutdownOnFailure(name);
        try (scope) {
            activeScope.set(scope);
            var io = scope.fork(subtaskName, work);
            scope.joinAll();
            Runnable onSucceeded = io.resultNow();
            dispatchIfCurrent(requestGeneration, () -> {
                onSucceeded.run();
                onRunningChanged.accept(false);
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dispatchIfCurrent(requestGeneration, () -> onRunningChanged.accept(false));
        } catch (ExecutionException | CancellationException failure) {
            dispatchIfCurrent(requestGeneration, () -> {
                onFailed.accept(rootCause(failure));
                onRunningChanged.accept(false);
            });
        } catch (RuntimeException failure) {
            dispatchIfCurrent(requestGeneration, () -> {
                onFailed.accept(failure);
                onRunningChanged.accept(false);
            });
        } finally {
            activeScope.compareAndSet(scope, null);
            coordinator.compareAndSet(Thread.currentThread(), null);
        }
    }

    private void dispatchIfCurrent(long requestGeneration, Runnable callback) {
        fxDispatcher.accept(() -> {
            if (!closed && generation.get() == requestGeneration) {
                callback.run();
            }
        });
    }

    private static Throwable rootCause(Throwable failure) {
        return failure.getCause() == null ? failure : failure.getCause();
    }

    /** Cancels in-flight work without joining on the caller (normally FX) thread. */
    private void cancelActive() {
        DawScope scope = activeScope.get();
        if (scope != null) {
            scope.cancel();
        }
        Thread worker = coordinator.getAndSet(null);
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * Invalidates queued callbacks and interrupts the owned scope —
     * closing the sheet tears down in-flight I/O (§2.7). Deliberately
     * non-blocking; the coordinator closes/joins its own scope.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        generation.incrementAndGet();
        cancelActive();
        fxDispatcher.accept(() -> onRunningChanged.accept(false));
    }
}
