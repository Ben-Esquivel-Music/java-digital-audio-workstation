package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Phase-3 global density manager (UI Design Book §3.7, story 278).
 *
 * <p>Density is a single user-selectable, <em>global</em> profile
 * ({@link DensityMode}) that controls nothing but padding and row height.
 * Applying a density means adding exactly one of
 * {@code .density-compact} / {@code .density-comfortable} /
 * {@code .density-touch} to the scene <em>root</em>; JavaFX's descendant
 * selectors then drive every pure-CSS {@code -fx-padding} rule for free.
 * The two controls whose size is computed in Java
 * ({@code TrackStripSkin}, {@code MixerChannelStripSkin}) bridge the root
 * class into their measured sizes via {@link DensityMode#resolveFor}.</p>
 *
 * <p>{@code DensityManager} is the structural sibling of
 * {@code com.benesquivelmusic.daw.app.ui.theme.ThemeManager} (story 277):
 * same singleton / test-override shape, same weak scene / dialog-pane
 * registries gated on the FX thread, same toolkit-free persistence so
 * {@code DensityPersistenceTest} is a pure unit test, same persist-failure
 * policy (log a {@code WARNING}, still re-apply, never propagate to the
 * Preferences dialog's apply handler).</p>
 *
 * <h2>Relationship to the theme / WCAG systems</h2>
 *
 * <p>Density is intentionally orthogonal to theming. It persists under
 * its own key ({@link #PREF_KEY} = {@code "appearance.density"}),
 * deliberately distinct from {@code ThemeManager.PREF_KEY}
 * ({@code "appearance.tokenTheme"}, story 277) and
 * {@code SettingsModel.KEY_THEME_ID} ({@code "appearance.themeId"}, story
 * 194's WCAG JSON registry). The three systems do not share state.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The active-density {@link ObjectProperty} and the scene /
 * dialog-pane registries are JavaFX-thread state and must be touched on
 * the FX application thread (re-application schedules itself via
 * {@link Platform#runLater} when called off-thread). Persistence and
 * construction are toolkit-free so {@code DensityManager} can be
 * unit-tested without a running toolkit.</p>
 */
public final class DensityManager {

    /**
     * The default active density for an unknown / missing / unreadable
     * persisted value — {@link DensityMode#COMFORTABLE}, the design-book
     * default (UI Design Book §3.7).
     */
    public static final DensityMode DEFAULT_DENSITY = DensityMode.COMFORTABLE;

    /**
     * Preferences key under which the active density is persisted.
     *
     * <p>Deliberately distinct from {@code ThemeManager.PREF_KEY}
     * ({@code "appearance.tokenTheme"}) and
     * {@code SettingsModel.KEY_THEME_ID} ({@code "appearance.themeId"}).
     * Density, the token theme, and the WCAG JSON registry are three
     * separate systems and must not share a key (pinned by
     * {@code DensityPersistenceTest}).</p>
     */
    public static final String PREF_KEY = "appearance.density";

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private static final Logger LOG =
            Logger.getLogger(DensityManager.class.getName());

    /**
     * Process-wide default instance, backed by the same per-package
     * {@link Preferences} node the rest of the UI uses. Lazy-initialized
     * via the initialization-on-demand holder idiom so merely loading
     * {@code DensityManager} (e.g. to read {@link #PREF_KEY} in a unit
     * test using its own isolated node) performs no I/O against the real
     * user preferences store (mirrors {@code ThemeManager}).
     */
    private static final class DefaultHolder {
        static final DensityManager INSTANCE =
                new DensityManager(Preferences.userNodeForPackage(DensityManager.class));
    }

    /**
     * Test-only override for the process-wide default returned by
     * {@link #getDefault()}. When non-null, {@code getDefault()} returns
     * this instance instead of the real singleton — isolating tests that
     * exercise {@code SettingsDialog} (or the skin bridge) from the
     * developer's / CI agent's actual user preferences store.
     *
     * <p>Set via {@link #setDefaultForTest(DensityManager)} and cleared
     * by passing {@code null}. Not thread-safe — call from a single test
     * thread before/after the code under test (mirrors
     * {@code ThemeManager.setDefaultForTest}).</p>
     */
    private static volatile DensityManager defaultOverride;

    /**
     * Overrides the process-wide default instance for testing purposes.
     * Pass a {@code DensityManager} backed by an isolated
     * {@link Preferences} node to prevent test runs from polluting (or
     * inheriting choices from) the developer's real user preferences.
     *
     * @param override the test instance, or {@code null} to restore the
     *                 real singleton
     */
    public static void setDefaultForTest(DensityManager override) {
        defaultOverride = override;
    }

    private final Preferences prefs;
    private final ObjectProperty<DensityMode> activeDensity;

    /**
     * Scenes / dialog-panes to re-apply the density class to when the
     * active density changes. Held <strong>weakly</strong>: over a long
     * editing session many transient dialogs come and go and
     * {@link #getDefault()} is a process-lifetime singleton. All access
     * is serialized onto the FX thread via {@link #runOnFx};
     * {@link #reapplyAll()} iterates a {@link List#copyOf} snapshot so a
     * concurrent GC sweep cannot disturb it (mirrors {@code ThemeManager}).
     */
    private final Set<Scene> scenes =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<DialogPane> dialogPanes =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Creates a {@code DensityManager} backed by the given preferences
     * node, restoring the persisted active density (defaulting to
     * {@link #DEFAULT_DENSITY} for a missing/unknown value) and
     * persisting any subsequent change to the active-density property.
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public DensityManager(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        this.activeDensity =
                new SimpleObjectProperty<>(this, "activeDensity", restore());
        this.activeDensity.addListener((_, _, newDensity) -> {
            // Persist the new density, then re-apply to all registered
            // scenes/panes. If persistence fails (backing-store I/O
            // error) we log a WARNING and still re-apply so the UI
            // remains consistent with what the user just chose — they
            // simply won't get the preference restored on next launch.
            // Propagating the exception would leave callers (e.g.
            // SettingsDialog's APPLY handler) with an unchecked failure
            // while the UI has already visually switched (mirrors
            // ThemeManager exactly).
            try {
                persist(newDensity);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING,
                        "Failed to persist density preference; the UI will "
                                + "switch but the choice may not survive restart",
                        ex);
            }
            reapplyAll();
        });
    }

    /**
     * @return the process-wide default {@code DensityManager} — either
     *         the test override (if set via {@link #setDefaultForTest})
     *         or the real singleton (persists to
     *         {@code Preferences.userNodeForPackage(DensityManager.class)})
     */
    public static DensityManager getDefault() {
        DensityManager override = defaultOverride;
        return override != null ? override : DefaultHolder.INSTANCE;
    }

    // ── Active density ───────────────────────────────────────────────────────

    /**
     * The active global density. Setting it persists the choice and
     * re-applies the density class to every registered scene/dialog-pane
     * (so the UI updates with no restart). The two Java-computed-size
     * skins observe this property to re-measure on a live switch.
     *
     * @return the observable active-density property
     */
    public ObjectProperty<DensityMode> activeDensityProperty() {
        return activeDensity;
    }

    /** @return the current active density (never {@code null}). */
    public DensityMode getActiveDensity() {
        return activeDensity.get();
    }

    /**
     * Sets the active density. Equivalent to
     * {@code activeDensityProperty().set(density)}.
     *
     * @param density the new active density (must not be {@code null})
     */
    public void setActiveDensity(DensityMode density) {
        activeDensity.set(
                Objects.requireNonNull(density, "density must not be null"));
    }

    // ── Application ──────────────────────────────────────────────────────────

    /**
     * Applies the active density class to {@code scene}'s root and
     * registers the scene for live re-application on subsequent density
     * changes. Idempotent: all other {@code density-*} classes are
     * removed first so the swap never accumulates.
     *
     * <p><strong>Threading:</strong> if called from the JavaFX
     * application thread the class is applied synchronously; otherwise
     * the work is scheduled via {@link Platform#runLater(Runnable)} and
     * returns immediately.</p>
     *
     * @param scene the scene to style (must not be {@code null})
     */
    public void applyTo(Scene scene) {
        Objects.requireNonNull(scene, "scene must not be null");
        runOnFx(() -> {
            scenes.add(scene); // Set.add is idempotent
            Parent root = scene.getRoot();
            if (root != null) {
                applyDensityClass(root.getStyleClass());
            }
        });
    }

    /**
     * Applies the active density class to {@code pane} (a dialog's own
     * pane — its Scene does not inherit the main scene's root class),
     * ensures the {@code root-pane} style class is present (mirrors
     * {@code ThemeManager.applyTo(DialogPane)}), and registers the pane
     * for live re-application. Idempotent.
     *
     * <p>Density is scoped to the main UI rows; dialogs default to
     * Comfortable per the story table. This method is provided for
     * symmetry with {@code ThemeManager} and for callers that explicitly
     * opt a dialog pane into density — it is not wired into the dialog
     * chrome by default (story 278 keeps its footprint minimal).</p>
     *
     * @param pane the dialog pane to style (must not be {@code null})
     */
    public void applyTo(DialogPane pane) {
        Objects.requireNonNull(pane, "pane must not be null");
        runOnFx(() -> {
            dialogPanes.add(pane); // Set.add is idempotent
            if (!pane.getStyleClass().contains("root-pane")) {
                pane.getStyleClass().add("root-pane");
            }
            applyDensityClass(pane.getStyleClass());
        });
    }

    /**
     * Removes every {@code density-*} class then adds the active one.
     * Idempotent — re-application never accumulates a stale class (the
     * no-restart contract relies on this).
     */
    private void applyDensityClass(ObservableList<String> styleClasses) {
        styleClasses.removeAll(DensityMode.ALL_STYLE_CLASSES);
        styleClasses.add(activeDensity.get().styleClass());
    }

    private void reapplyAll() {
        // Gate all access to the WeakHashMap-backed sets on the FX
        // thread — WeakHashMap is not thread-safe and a concurrent GC
        // sweep racing with isEmpty() could throw or return a wrong
        // answer. If the toolkit is not running (pure-unit context)
        // runOnFx silently drops the work — correct, because there can be
        // no live scenes/panes to update (mirrors ThemeManager).
        runOnFx(() -> {
            if (scenes.isEmpty() && dialogPanes.isEmpty()) {
                return;
            }
            for (Scene scene : List.copyOf(scenes)) {
                Parent root = scene.getRoot();
                if (root != null) {
                    applyDensityClass(root.getStyleClass());
                }
            }
            for (DialogPane pane : List.copyOf(dialogPanes)) {
                applyDensityClass(pane.getStyleClass());
            }
        });
    }

    // ── Persistence (toolkit-free) ───────────────────────────────────────────

    private DensityMode restore() {
        String stored = prefs.get(PREF_KEY, DEFAULT_DENSITY.name());
        try {
            return DensityMode.valueOf(stored);
        } catch (IllegalArgumentException unknownValue) {
            // Unknown / corrupt enum name persisted by a newer or broken
            // build. (prefs.get with a non-null default never returns
            // null, so valueOf only ever throws here for an unrecognised
            // name.) Fall back to the design-book default.
            return DEFAULT_DENSITY;
        }
    }

    private void persist(DensityMode density) {
        prefs.put(PREF_KEY, density.name());
    }

    // ── i18n ─────────────────────────────────────────────────────────────────

    /**
     * Resolves a density's localized display name from the shared
     * {@code Messages} bundle, falling back to the raw key if absent
     * (mirrors {@code ThemeManager#displayName} / {@code DawgDialog#msg}
     * — Skill §14).
     *
     * @param density the density (must not be {@code null})
     * @return the localized display name, or the key if not found
     */
    public static String displayName(DensityMode density) {
        Objects.requireNonNull(density, "density must not be null");
        try {
            return MESSAGES.getString(density.displayNameKey());
        } catch (MissingResourceException e) {
            return density.displayNameKey();
        }
    }

    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        try {
            FxDispatcher.runOnFx(r);
        } catch (IllegalStateException toolkitNotRunning) {
            // No JavaFX toolkit yet / toolkit already shut down (a
            // pure-unit context, or a density change racing application
            // exit). There can be no live scene/dialog-pane to update,
            // so dropping the re-apply is correct rather than fatal —
            // logged at FINE so a genuine post-shutdown failure is not
            // entirely invisible given the FX-fork-shutdown history
            // (mirrors ThemeManager).
            LOG.log(Level.FINE,
                    "Density re-apply skipped: no running JavaFX toolkit",
                    toolkitNotRunning);
        }
    }
}
