package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.DawAction;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import javafx.scene.input.KeyCombination;

import java.time.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The complete index of every user-configurable setting — the §3.4
 * descriptor catalogue behind the existing {@code SettingsDialog}
 * (Settings View Design Book §3.4 / §7 Stage 1, story 305).
 *
 * <p>{@link #create()} registers one {@link SettingDescriptor} per
 * setting the application exposes today, grouped under the §3.3
 * category/group taxonomy (Audio, Appearance, Project, Recording,
 * Backups, Key Bindings, Plugins, General — the General category and
 * the Backups "Disk usage" / Plugins "Manager" slots stay empty; disk
 * usage is a §5.8 group visual, not a descriptor).
 * The catalogue is metadata only — it renders nothing and changes no
 * behaviour — and is the data source for the §6.1 search index
 * (story 306) and the §6.3 import/export profile (story 309).</p>
 *
 * <h2>Descriptor population ({@code 33 + DawAction.values().length})</h2>
 * <ul>
 *   <li>17 {@code SettingsModel}-backed descriptors — id = the
 *       {@code Preferences} key. Defaults are read from a scratch
 *       {@code SettingsModel} over an in-memory
 *       {@link TransientPreferences} node (an empty node makes every
 *       getter return the authority's canonical default), and each
 *       validator delegates to the real setter on that scratch model —
 *       the existing guard, not a parallel one.</li>
 *   <li>3 manager-backed descriptors — ids
 *       {@link ThemeManager#PREF_KEY}, {@link DensityManager#PREF_KEY}
 *       and {@link MotionManager#PREF_KEY} (referenced, never
 *       duplicated), the three {@code LIVE} appearance managers of
 *       stories 277/278/279.</li>
 *   <li>13 absorbed Recording/Backups descriptors (story 308, §7
 *       Stage 4) — {@code metronome.*} persist through the
 *       {@code MetronomeController} preferences node,
 *       {@code ~/.daw/metronome-settings.json} and the project XML;
 *       {@code backups.*} persist as {@link BackupRetentionPolicy} in
 *       {@code ~/.daw/backup-retention.json}. None of them is a
 *       {@code SettingsModel} key. Validators delegate to the real
 *       authorities ({@link ClickOutput} / {@link BackupRetentionPolicy}
 *       withers) with the absorbed dialogs' UI bounds layered on top.</li>
 *   <li>One {@code KEY_CAPTURE} descriptor per {@link DawAction} — id
 *       {@code "keybinding." + action.name()} (the
 *       {@code KeyBindingManager} persistence key format), grouped by
 *       {@link DawAction.Category} in enum order. Every combination —
 *       or {@code null}, which clears a binding — is per-value valid;
 *       conflict rejection ("already assigned to …") is a
 *       cross-setting concern that stays in
 *       {@code KeyBindingManager.setBinding}.</li>
 * </ul>
 *
 * <h2>Deliberate exclusions</h2>
 * <ul>
 *   <li>{@code appearance.themeId} — a dead legacy key (story 194's
 *       WCAG JSON registry): no production writer except
 *       {@code SettingsModel.resetToDefaults()}. The live theme
 *       authority is {@link ThemeManager#PREF_KEY}
 *       ({@code "appearance.tokenTheme"}), which <em>is</em>
 *       catalogued.</li>
 *   <li>Clock source — live-only engine state via
 *       {@code AudioEngineController}, never persisted; §3.4 defines
 *       the id as "the Preferences key or project field", and it has
 *       none.</li>
 *   <li>WASAPI exclusive mode — rides the {@code audio.backend} value
 *       rather than owning a key of its own.</li>
 * </ul>
 *
 * <h2>Localization</h2>
 *
 * <p>Labels, descriptions and category/group titles are resolved at
 * {@link #create()} time through the shared {@code Messages} bundle
 * (key-fallback {@code msg} idiom); the catalogue passes message keys
 * internally and hard-codes no display string. Two exceptions by
 * design: key-binding labels delegate to
 * {@link DawAction#displayName()} and Key Bindings group titles to
 * {@link DawAction.Category#displayName()} (the enum authority), and
 * {@link SettingDescriptor#synonyms() synonyms} are lowercase search
 * literals (search data, not display strings).</p>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Validators are <strong>not</strong> thread-safe: they share the
 * one scratch model created by {@link #create()} (each test writes it).
 * Defaults are captured into the descriptor records at build time
 * <em>before</em> any validator runs, so later validator mutations of
 * the scratch model are harmless — but concurrent validation is not
 * supported. FX-thread / test use only.</p>
 */
public final class SettingsCatalogue {

    private static final String BUNDLE_NAME =
            "com.benesquivelmusic.daw.app.i18n.Messages";

    // ── Story 308 — UI bounds carried over from the absorbed dialogs.
    // The model authorities only enforce the LOWER bounds (>= 0); the
    // upper bounds are the old spinner/slider ranges and stay UI policy.
    private static final int MAX_CLICK_CHANNEL_INDEX = 63;
    private static final int MAX_KEEP_RECENT = 100;
    private static final int MAX_KEEP_HOURLY = 168;  // one week of hours
    private static final int MAX_KEEP_DAILY = 60;    // ~2 months of days
    private static final int MAX_KEEP_WEEKLY = 52;   // one year of weeks
    private static final int MAX_AGE_DAYS = 365;
    private static final int MAX_DISK_GIB = 64;
    private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;

    private final List<Category> categories;
    private final List<SettingDescriptor<?>> descriptors;
    private final Map<String, SettingDescriptor<?>> descriptorsById;

    private SettingsCatalogue(List<Category> categories) {
        this.categories = List.copyOf(categories);
        List<SettingDescriptor<?>> flattened = new ArrayList<>();
        Map<String, SettingDescriptor<?>> index = new LinkedHashMap<>();
        for (Category category : this.categories) {
            for (Group group : category.groups()) {
                for (SettingDescriptor<?> setting : group.settings()) {
                    if (index.putIfAbsent(setting.id(), setting) != null) {
                        throw new IllegalStateException(
                                "Duplicate setting id: " + setting.id());
                    }
                    flattened.add(setting);
                }
            }
        }
        this.descriptors = List.copyOf(flattened);
        this.descriptorsById = Collections.unmodifiableMap(index);
    }

    /**
     * A §3.3 settings category — an ordered list of groups.
     *
     * @param id     the stable category id (e.g. {@code "audio"})
     * @param title  the resolved, localized category title
     * @param groups the category's groups in display order
     */
    public record Category(String id, String title, List<Group> groups) {}

    /**
     * A §3.3 settings group — an ordered list of descriptors. Empty
     * placeholder groups mark taxonomy slots that later stages populate.
     *
     * @param id       the stable group id (e.g. {@code "format"})
     * @param title    the resolved, localized group title
     * @param settings the group's descriptors in display order
     */
    public record Group(String id, String title,
                        List<SettingDescriptor<?>> settings) {}

    /**
     * Builds the full catalogue: taxonomy, defaults, validators, and
     * resolved labels/descriptions. Cheap and I/O-free — the scratch
     * model writes only to an in-memory {@link TransientPreferences}
     * node, and device enumeration / disk usage stay off this path.
     *
     * @return a fully populated, immutable catalogue
     */
    public static SettingsCatalogue create() {
        ResourceBundle messages =
                ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT);
        TransientPreferences scratchPrefs = new TransientPreferences(null, "");
        SettingsModel scratch = new SettingsModel(scratchPrefs);

        // Capture every canonical default from the scratch model FIRST —
        // over an empty node each getter returns the authority's default
        // (SettingsModel's DEFAULT_* constants are package-private and not
        // visible here) — so later validator invocations, which mutate the
        // scratch model, cannot disturb the captured values.
        double defaultSampleRate = scratch.getSampleRate();
        int defaultBitDepth = scratch.getBitDepth();
        int defaultBufferSize = scratch.getBufferSize();
        var defaultMixPrecision = scratch.getMixPrecision();
        String defaultBackend = scratch.getAudioBackend();
        String defaultInputDevice = scratch.getAudioInputDevice();
        String defaultOutputDevice = scratch.getAudioOutputDevice();
        boolean defaultLatencyCompensation = scratch.isApplyLatencyCompensation();
        var defaultSrcQuality = scratch.getSrcQuality();
        int defaultWorkerPoolSize = scratch.getWorkerPoolSize();
        double defaultMasterCpuFraction = scratch.getMasterCpuBudgetFraction();
        int defaultAutoSaveInterval = scratch.getAutoSaveIntervalSeconds();
        double defaultTempo = scratch.getDefaultTempo();
        boolean defaultJournaledPersistence = scratch.isUseJournaledPersistence();
        double defaultUiScale = scratch.getUiScale();
        String defaultScanPaths = scratch.getPluginScanPaths();

        // The policy persists as a String short-name, but the mapping
        // (SettingsModel.policyName / parsePolicy) is package-private in
        // ..app.ui and unreachable here. Derive the canonical name without
        // duplicating it: round-trip the default policy through the real
        // setter — which persists policyName(...) into the scratch node —
        // and read the persisted representation back.
        scratch.setMasterCpuBudgetPolicy(scratch.getMasterCpuBudgetPolicy());
        String defaultMasterCpuPolicy = Objects.requireNonNull(
                scratchPrefs.get("audio.masterCpuBudgetPolicy", null),
                "scratch setter did not persist the default policy name");

        // ── 1. Audio ─────────────────────────────────────────────────────
        Group audioFormat = new Group("format",
                msg(messages, "settings.group.audio.format"), List.of(
                modelDescriptor(messages, "audio.sampleRate",
                        List.of("hz", "frequency"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultSampleRate, scratch::setSampleRate),
                modelDescriptor(messages, "audio.bitDepth",
                        List.of("bits", "resolution"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultBitDepth, scratch::setBitDepth),
                modelDescriptor(messages, "audio.bufferSize",
                        List.of("latency", "frames", "block size"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultBufferSize, scratch::setBufferSize),
                modelDescriptor(messages, "audio.mixPrecision",
                        List.of("double", "float", "64-bit"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultMixPrecision, scratch::setMixPrecision)));

        Group audioDevice = new Group("device",
                msg(messages, "settings.group.audio.device"), List.of(
                // The only RESTART_REQUIRED descriptor in the catalogue
                // (§6.4): switching the driver stack needs an app restart;
                // every other audio change is an engine re-arm at most.
                modelDescriptor(messages, "audio.backend",
                        List.of("asio", "wasapi", "driver"),
                        ApplyClass.RESTART_REQUIRED, ControlKind.CHOICE,
                        defaultBackend, scratch::setAudioBackend),
                modelDescriptor(messages, "audio.inputDevice",
                        List.of("latency", "device", "interface"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultInputDevice, scratch::setAudioInputDevice),
                modelDescriptor(messages, "audio.outputDevice",
                        List.of("latency", "device", "interface"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultOutputDevice, scratch::setAudioOutputDevice),
                // LIVE: the flag is read per-take by the recording
                // pipeline — no engine re-arm involved.
                modelDescriptor(messages, "audio.applyLatencyCompensation",
                        List.of("latency", "compensation"),
                        ApplyClass.LIVE, ControlKind.TOGGLE,
                        defaultLatencyCompensation,
                        scratch::setApplyLatencyCompensation)));

        Group audioPerformance = new Group("performance",
                msg(messages, "settings.group.audio.performance"), List.of(
                modelDescriptor(messages, "audio.srcQuality",
                        List.of("resampling", "conversion"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultSrcQuality, scratch::setSrcQuality),
                modelDescriptor(messages, "audio.workerPoolSize",
                        List.of("threads", "cores", "multicore"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                        defaultWorkerPoolSize, scratch::setWorkerPoolSize),
                modelDescriptor(messages, "audio.masterCpuBudgetFraction",
                        List.of("cpu", "performance"),
                        ApplyClass.ENGINE_RECONFIGURE, ControlKind.SLIDER,
                        defaultMasterCpuFraction,
                        scratch::setMasterCpuBudgetFraction),
                new SettingDescriptor<>("audio.masterCpuBudgetPolicy",
                        msg(messages, "audio.masterCpuBudgetPolicy.label"),
                        msg(messages, "audio.masterCpuBudgetPolicy.description"),
                        List.of("cpu", "performance"),
                        Scope.AUDIO_DEVICE, ApplyClass.ENGINE_RECONFIGURE,
                        ControlKind.CHOICE, defaultMasterCpuPolicy,
                        // The value is the persisted short-name String.
                        // SettingsModel.parsePolicy is forward-compatible —
                        // every non-null name resolves to a policy object the
                        // setter accepts — and setMasterCpuBudgetPolicy's only
                        // guard is requireNonNull, so the delegated guard chain
                        // reduces exactly to a non-null check (parsePolicy
                        // itself is package-private and unreachable here).
                        Objects::nonNull)));

        // ── 2. Appearance ────────────────────────────────────────────────
        Group appearanceTheme = new Group("theme",
                msg(messages, "settings.group.appearance.theme"), List.of(
                new SettingDescriptor<>(ThemeManager.PREF_KEY,
                        msg(messages, "appearance.theme.label"),
                        msg(messages, "appearance.theme.description"),
                        List.of("dark", "color", "colour"),
                        Scope.APPLICATION, ApplyClass.LIVE, ControlKind.CHOICE,
                        ThemeManager.DEFAULT_THEME,
                        // Mirrors ThemeManager.setActiveTheme's requireNonNull.
                        Objects::nonNull)));

        Group appearanceDensity = new Group("density",
                msg(messages, "settings.group.appearance.density"), List.of(
                new SettingDescriptor<>(DensityManager.PREF_KEY,
                        msg(messages, "appearance.density.label"),
                        msg(messages, "appearance.density.description"),
                        List.of("spacing", "compact", "touch"),
                        Scope.APPLICATION, ApplyClass.LIVE, ControlKind.CHOICE,
                        DensityManager.DEFAULT_DENSITY,
                        // Mirrors DensityManager.setActiveDensity's requireNonNull.
                        Objects::nonNull)));

        Group appearanceScale = new Group("scale",
                msg(messages, "settings.group.appearance.scale"), List.of(
                modelDescriptor(messages, "appearance.uiScale",
                        List.of("zoom", "scaling", "dpi"),
                        Scope.APPLICATION, ApplyClass.LIVE, ControlKind.SLIDER,
                        defaultUiScale, scratch::setUiScale)));

        Group appearanceMotion = new Group("motion",
                msg(messages, "settings.group.appearance.motion"), List.of(
                new SettingDescriptor<>(MotionManager.PREF_KEY,
                        msg(messages, "appearance.reduceMotion.label"),
                        msg(messages, "appearance.reduceMotion.description"),
                        List.of("animation", "accessibility"),
                        Scope.APPLICATION, ApplyClass.LIVE, ControlKind.TOGGLE,
                        MotionManager.DEFAULT_REDUCE_MOTION,
                        // MotionManager.setReduceMotion takes a primitive
                        // boolean — null has no representation there; both
                        // true and false are always applied.
                        Objects::nonNull)));

        // ── 3. Project ───────────────────────────────────────────────────
        Group projectDefaults = new Group("defaults",
                msg(messages, "settings.group.project.defaults"), List.of(
                // PROJECT_DEFAULTS dual reach (§3.2): stored as a default
                // for future projects AND live-applied to the open project
                // by LiveSettingsApplier — recorded in the description.
                modelDescriptor(messages, "project.defaultTempo",
                        List.of("bpm"),
                        Scope.PROJECT_DEFAULTS, ApplyClass.LIVE, ControlKind.TEXT,
                        defaultTempo, scratch::setDefaultTempo)));

        Group projectAutosave = new Group("autosave",
                msg(messages, "settings.group.project.autosave"), List.of(
                modelDescriptor(messages, "project.autoSaveIntervalSeconds",
                        List.of("checkpoint"),
                        Scope.PROJECT_DEFAULTS, ApplyClass.LIVE, ControlKind.CHOICE,
                        defaultAutoSaveInterval,
                        scratch::setAutoSaveIntervalSeconds),
                // LIVE with a documented delay: the persistence coordinator
                // reads the flag when a project opens, so the change takes
                // effect on the next project open (no restart involved).
                modelDescriptor(messages, "project.useJournaledPersistence",
                        List.of("crash", "recovery", "journal"),
                        Scope.APPLICATION, ApplyClass.LIVE, ControlKind.TOGGLE,
                        defaultJournaledPersistence,
                        scratch::setUseJournaledPersistence)));

        // ── 4. Recording (story 308 — absorbs MetronomeSettingsDialog) ───
        // §3.2: enabled/count-in are prefs-authoritative at startup →
        // APPLICATION. Click routing round-trips through the project XML
        // (ProjectSerializer.buildMetronome) → THIS_PROJECT records that
        // reach; the global JSON store stays the cross-session default.
        Group recordingMetronome = new Group("metronome",
                msg(messages, "settings.group.recording.metronome"), List.of(
                absorbedDescriptor(messages, "metronome.enabled",
                        List.of("click", "metronome"),
                        Scope.APPLICATION, ControlKind.TOGGLE,
                        Boolean.TRUE, _ -> true),
                absorbedDescriptor(messages, "metronome.countIn",
                        List.of("count in", "click", "bars"),
                        Scope.APPLICATION, ControlKind.CHOICE,
                        CountInMode.OFF, Objects::nonNull)));

        Group recordingClickRouting = new Group("clickRouting",
                msg(messages, "settings.group.recording.clickRouting"), List.of(
                absorbedDescriptor(messages, "metronome.clickChannel",
                        List.of("click", "routing", "output"),
                        Scope.THIS_PROJECT, ControlKind.STEPPER,
                        ClickOutput.MAIN_MIX_ONLY.hardwareChannelIndex(),
                        (Integer value) -> {
                            if (value == null || value > MAX_CLICK_CHANNEL_INDEX) {
                                return false;
                            }
                            try {
                                ClickOutput.MAIN_MIX_ONLY.withHardwareChannelIndex(value);
                                return true;
                            } catch (IllegalArgumentException rejected) {
                                return false;
                            }
                        }),
                absorbedDescriptor(messages, "metronome.clickGain",
                        List.of("click", "gain", "level"),
                        Scope.THIS_PROJECT, ControlKind.SLIDER,
                        ClickOutput.MAIN_MIX_ONLY.gain(),
                        (Double value) -> {
                            if (value == null) {
                                return false;
                            }
                            try {
                                ClickOutput.MAIN_MIX_ONLY.withGain(value);
                                return true;
                            } catch (IllegalArgumentException rejected) {
                                return false;
                            }
                        }),
                absorbedDescriptor(messages, "metronome.clickSideOutput",
                        List.of("click", "routing", "hardware"),
                        Scope.THIS_PROJECT, ControlKind.TOGGLE,
                        ClickOutput.MAIN_MIX_ONLY.sideOutputEnabled(), _ -> true)));

        Group recordingCueMix = new Group("cueMix",
                msg(messages, "settings.group.recording.cueMix"), List.of(
                absorbedDescriptor(messages, "metronome.clickMainMix",
                        List.of("click", "cue", "mix"),
                        Scope.THIS_PROJECT, ControlKind.TOGGLE,
                        ClickOutput.MAIN_MIX_ONLY.mainMixEnabled(), _ -> true),
                // Value = the cue-bus UUID as a String; "" = "Main mix only".
                absorbedDescriptor(messages, "metronome.clickCueBus",
                        List.of("click", "cue", "headphone"),
                        Scope.THIS_PROJECT, ControlKind.CHOICE,
                        "", Objects::nonNull)));

        Category recording = new Category("recording",
                msg(messages, "settings.category.recording"), List.of(
                recordingMetronome, recordingClickRouting, recordingCueMix));

        // ── 5. Backups (story 308 — absorbs BackupSettingsDialog) ────────
        // Retention rows render the six REAL BackupRetentionPolicy fields
        // (§5.8's "min free space" mockup row has no code counterpart).
        // The diskUsage group stays descriptor-empty: it hosts the pie as
        // a §5.8 group visual, not a setting.
        Group backupsRetention = new Group("retention",
                msg(messages, "settings.group.backups.retention"), List.of(
                absorbedDescriptor(messages, "backups.keepRecent",
                        List.of("autosave", "retention", "backup"),
                        Scope.APPLICATION, ControlKind.STEPPER,
                        BackupRetentionPolicy.DEFAULT.keepRecent(),
                        retentionCount(BackupRetentionPolicy.DEFAULT::withKeepRecent,
                                MAX_KEEP_RECENT)),
                absorbedDescriptor(messages, "backups.keepHourly",
                        List.of("autosave", "retention", "backup"),
                        Scope.APPLICATION, ControlKind.STEPPER,
                        BackupRetentionPolicy.DEFAULT.keepHourly(),
                        retentionCount(BackupRetentionPolicy.DEFAULT::withKeepHourly,
                                MAX_KEEP_HOURLY)),
                absorbedDescriptor(messages, "backups.keepDaily",
                        List.of("autosave", "retention", "backup"),
                        Scope.APPLICATION, ControlKind.STEPPER,
                        BackupRetentionPolicy.DEFAULT.keepDaily(),
                        retentionCount(BackupRetentionPolicy.DEFAULT::withKeepDaily,
                                MAX_KEEP_DAILY)),
                absorbedDescriptor(messages, "backups.keepWeekly",
                        List.of("autosave", "retention", "backup"),
                        Scope.APPLICATION, ControlKind.STEPPER,
                        BackupRetentionPolicy.DEFAULT.keepWeekly(),
                        retentionCount(BackupRetentionPolicy.DEFAULT::withKeepWeekly,
                                MAX_KEEP_WEEKLY)),
                absorbedDescriptor(messages, "backups.maxAgeDays",
                        List.of("autosave", "retention", "days", "age"),
                        Scope.APPLICATION, ControlKind.SLIDER,
                        (double) BackupRetentionPolicy.DEFAULT.maxAge().toDays(),
                        (Double value) -> {
                            if (value == null || value < 0 || value > MAX_AGE_DAYS) {
                                return false;
                            }
                            try {
                                BackupRetentionPolicy.DEFAULT.withMaxAge(
                                        Duration.ofDays(Math.round(value)));
                                return true;
                            } catch (IllegalArgumentException rejected) {
                                return false;
                            }
                        }),
                absorbedDescriptor(messages, "backups.maxDiskGiB",
                        List.of("autosave", "retention", "disk", "pie"),
                        Scope.APPLICATION, ControlKind.SLIDER,
                        (double) (BackupRetentionPolicy.DEFAULT.maxBytes() / BYTES_PER_GIB),
                        (Double value) -> {
                            if (value == null || value < 0 || value > MAX_DISK_GIB) {
                                return false;
                            }
                            try {
                                BackupRetentionPolicy.DEFAULT.withMaxBytes(
                                        Math.round(value) * BYTES_PER_GIB);
                                return true;
                            } catch (IllegalArgumentException rejected) {
                                return false;
                            }
                        })));

        Category backups = new Category("backups",
                msg(messages, "settings.category.backups"), List.of(
                backupsRetention,
                placeholderGroup(messages, "backups", "diskUsage")));

        // ── 6. Key Bindings — one group per DawAction.Category ───────────
        String keyBindingDescription =
                msg(messages, "keyBindings.action.description");
        // Every combination — or null, which clears the binding — is
        // per-value valid (KeyBindingManager.setBinding accepts both);
        // conflict rejection is cross-setting, not per-value.
        Predicate<KeyCombination> anyBinding = _ -> true;
        List<Group> keyBindingGroups = new ArrayList<>();
        for (DawAction.Category actionCategory : DawAction.Category.values()) {
            List<SettingDescriptor<?>> bindings = new ArrayList<>();
            for (DawAction action : DawAction.values()) {
                if (action.category() == actionCategory) {
                    bindings.add(new SettingDescriptor<>(
                            "keybinding." + action.name(),
                            action.displayName(),
                            keyBindingDescription,
                            List.of("shortcut", "hotkey"),
                            Scope.APPLICATION, ApplyClass.LIVE,
                            ControlKind.KEY_CAPTURE,
                            action.defaultBinding(), anyBinding));
                }
            }
            keyBindingGroups.add(new Group(actionCategory.name(),
                    actionCategory.displayName(), List.copyOf(bindings)));
        }

        // ── 7. Plugins ───────────────────────────────────────────────────
        Group pluginsScanPaths = new Group("scanPaths",
                msg(messages, "settings.group.plugins.scanPaths"), List.of(
                modelDescriptor(messages, "plugins.scanPaths",
                        List.of("clap", "folder"),
                        Scope.APPLICATION, ApplyClass.LIVE, ControlKind.PATH,
                        defaultScanPaths, scratch::setPluginScanPaths)));

        // ── §3.3 category order ──────────────────────────────────────────
        return new SettingsCatalogue(List.of(
                new Category("audio",
                        msg(messages, "settings.category.audio"),
                        List.of(audioFormat, audioDevice, audioPerformance)),
                new Category("appearance",
                        msg(messages, "settings.category.appearance"),
                        List.of(appearanceTheme, appearanceDensity,
                                appearanceScale, appearanceMotion)),
                new Category("project",
                        msg(messages, "settings.category.project"),
                        List.of(projectDefaults, projectAutosave)),
                recording,
                backups,
                new Category("keyBindings",
                        msg(messages, "settings.category.keyBindings"),
                        List.copyOf(keyBindingGroups)),
                new Category("plugins",
                        msg(messages, "settings.category.plugins"),
                        List.of(pluginsScanPaths,
                                placeholderGroup(messages, "plugins", "manager"))),
                new Category("general",
                        msg(messages, "settings.category.general"),
                        List.of(placeholderGroup(messages, "general", "startup"),
                                placeholderGroup(messages, "general", "language"),
                                placeholderGroup(messages, "general",
                                        "resetProfiles")))));
    }

    /** @return the §3.3 categories in display order (unmodifiable) */
    public List<Category> categories() {
        return categories;
    }

    /** @return every descriptor, flattened in category order (unmodifiable) */
    public List<SettingDescriptor<?>> descriptors() {
        return descriptors;
    }

    /**
     * Looks a descriptor up by its id.
     *
     * @param id the setting id (must not be {@code null})
     * @return the descriptor, or empty for an unknown id
     */
    public Optional<SettingDescriptor<?>> byId(String id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(descriptorsById.get(id));
    }

    // ── Build helpers ────────────────────────────────────────────────────

    /**
     * An audio-scoped {@code SettingsModel}-backed descriptor whose
     * message keys equal its id and whose validator delegates to the
     * scratch setter.
     */
    private static <T> SettingDescriptor<T> modelDescriptor(
            ResourceBundle messages, String id, List<String> synonyms,
            ApplyClass applyClass, ControlKind controlKind,
            T defaultValue, Consumer<T> scratchSetter) {
        return modelDescriptor(messages, id, synonyms, Scope.AUDIO_DEVICE,
                applyClass, controlKind, defaultValue, scratchSetter);
    }

    /**
     * A {@code SettingsModel}-backed descriptor: message keys equal the
     * id ({@code <id>.label} / {@code <id>.description}) and the
     * validator delegates to the real setter guard on the scratch model
     * — accepted iff the setter does not throw.
     */
    private static <T> SettingDescriptor<T> modelDescriptor(
            ResourceBundle messages, String id, List<String> synonyms,
            Scope scope, ApplyClass applyClass, ControlKind controlKind,
            T defaultValue, Consumer<T> scratchSetter) {
        return new SettingDescriptor<>(id,
                msg(messages, id + ".label"),
                msg(messages, id + ".description"),
                synonyms, scope, applyClass, controlKind, defaultValue,
                delegating(scratchSetter));
    }

    /**
     * Wraps a scratch-model setter as a per-value validator: the value
     * is accepted iff the real setter guard does not reject it. Writes
     * land in the in-memory scratch node only. Not thread-safe (shared
     * scratch model — see the class Javadoc).
     */
    private static <T> Predicate<T> delegating(Consumer<T> scratchSetter) {
        return value -> {
            try {
                scratchSetter.accept(value);
                return true;
            } catch (IllegalArgumentException | NullPointerException rejected) {
                return false;
            }
        };
    }

    /**
     * A story-308 absorbed Recording/Backups descriptor: message keys
     * equal the id, always {@link ApplyClass#LIVE}, and the value
     * persists in its pre-existing external store (metronome prefs /
     * {@code metronome-settings.json} / {@code backup-retention.json}),
     * never in {@code SettingsModel}.
     */
    private static <T> SettingDescriptor<T> absorbedDescriptor(
            ResourceBundle messages, String id, List<String> synonyms,
            Scope scope, ControlKind controlKind,
            T defaultValue, Predicate<T> validator) {
        return new SettingDescriptor<>(id,
                msg(messages, id + ".label"),
                msg(messages, id + ".description"),
                synonyms, scope, ApplyClass.LIVE, controlKind, defaultValue,
                validator);
    }

    /**
     * A retention-bucket validator: delegates the lower bound to the
     * real {@link BackupRetentionPolicy} wither guard and layers the
     * absorbed dialog's UI upper bound on top.
     */
    private static Predicate<Integer> retentionCount(
            java.util.function.IntFunction<BackupRetentionPolicy> wither, int uiMax) {
        return value -> {
            if (value == null || value > uiMax) {
                return false;
            }
            try {
                wither.apply(value);
                return true;
            } catch (IllegalArgumentException rejected) {
                return false;
            }
        };
    }

    /**
     * An empty §3.3 taxonomy slot — General (story-309 territory), the
     * Plugins "Manager" slot, and the Backups "Disk usage" slot, which
     * story 308 fills with a group-level visual rather than descriptors.
     */
    private static Group placeholderGroup(
            ResourceBundle messages, String categoryId, String groupId) {
        return new Group(groupId,
                msg(messages, "settings.group." + categoryId + "." + groupId),
                List.of());
    }

    /**
     * Key-fallback bundle lookup (the {@code DawgDialog#msg} /
     * {@code ThemeManager#displayName} idiom — returns the key itself
     * for a missing resource).
     */
    private static String msg(ResourceBundle messages, String key) {
        try {
            return messages.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
    }
}
