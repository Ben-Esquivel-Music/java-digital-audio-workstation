package com.benesquivelmusic.daw.app.ui.theme;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

import java.net.URL;
import java.util.ArrayList;
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
 * Phase-3 token-driven theming (UI Design Book §3.1 / §6, story 277).
 *
 * <p>The DAW's structural CSS ({@code styles.css}) references every
 * colour by a semantic {@code -token} lookup, never a literal hex.
 * Switching theme therefore means nothing more than layering a tiny
 * stylesheet that re-declares those tokens on top of {@code styles.css};
 * JavaFX's lookup-colour cascade does the rest and <em>every</em>
 * Phase-1/2 control re-themes for free with no structural change.</p>
 *
 * <p>{@code ThemeManager} is the single source of "the ordered
 * stylesheet URL list to apply" = base {@code styles.css} +
 * (optionally) the active theme overlay, overlay last so it wins by
 * author order. Both the main {@link Scene} and every dialog's own
 * {@link DialogPane} (dialogs have a separate Scene that the main
 * scene's sheets do not cascade into) ask {@code ThemeManager} to apply
 * the same ordered list, so they all re-theme together.</p>
 *
 * <h2>Relationship to the WCAG JSON theme system (story 194)</h2>
 *
 * <p>This is intentionally <strong>separate</strong> from
 * {@link ThemeRegistry} / {@link Theme} (story 194). Story 194 is a JSON
 * theme registry whose job is WCAG contrast validation of user-supplied
 * palettes; it persists under the {@code appearance.themeId} key. Story
 * 277 is the token-CSS palette switch — three curated design-book
 * palettes applied via the lookup-colour cascade — and persists under a
 * distinct key ({@link #PREF_KEY}) in the <em>same</em>
 * {@link Preferences} store. The two systems do not share state by
 * design: one validates arbitrary user colour for accessibility, the
 * other swaps the three canonical design-book palettes.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>The active-theme {@link ObjectProperty} and scene/dialog-pane
 * registration are JavaFX-thread state and must be touched on the FX
 * application thread (re-application schedules itself via
 * {@link Platform#runLater} when called off-thread). Persistence and
 * construction are toolkit-free so {@code ThemeManager} can be
 * unit-tested without a running toolkit.</p>
 */
public final class ThemeManager {

    /**
     * The token-CSS theme. Each constant carries the classpath resource
     * of the overlay stylesheet that re-declares the Palette-A tokens
     * ({@code null} for {@link #ONYX_REFINED}, which <em>is</em> the
     * Palette-A baseline already in {@code styles.css}) and the
     * {@code Messages.properties} key for its display name (Skill §14).
     */
    public enum Theme {

        /**
         * Palette A — "Onyx Refined" (default). The baseline palette is
         * declared directly in {@code styles.css}; no overlay is needed.
         */
        ONYX_REFINED(null, "appearance.theme.onyxRefined"),

        /** Palette B — "Studio Slate" (dark, monochrome + warm accent). */
        STUDIO_SLATE("themes/studio-slate.css", "appearance.theme.studioSlate"),

        /** Palette C — "Atelier" (light, navy accent). */
        ATELIER("themes/atelier.css", "appearance.theme.atelier");

        private final String overlayResource;
        private final String displayNameKey;

        Theme(String overlayResource, String displayNameKey) {
            this.overlayResource = overlayResource;
            this.displayNameKey = displayNameKey;
        }

        /**
         * @return the overlay stylesheet classpath resource (relative to
         *         the {@code ui} package), or {@code null} for the
         *         baseline {@link #ONYX_REFINED}
         */
        String overlayResource() {
            return overlayResource;
        }

        /** @return the {@code Messages.properties} display-name key */
        public String displayNameKey() {
            return displayNameKey;
        }
    }

    /**
     * The default active theme for an unknown / missing / unreadable
     * persisted value — Palette A, the design-book default.
     */
    public static final Theme DEFAULT_THEME = Theme.ONYX_REFINED;

    /**
     * Preferences key under which the active token theme is persisted.
     *
     * <p>Deliberately distinct from {@code SettingsModel.KEY_THEME_ID}
     * ({@code "appearance.themeId"}), which belongs to story 194's JSON
     * {@link ThemeRegistry}. The two theme systems are separate by
     * design (see the class Javadoc) and must not share a key.</p>
     */
    public static final String PREF_KEY = "appearance.tokenTheme";

    /** Base structural stylesheet — always first in the applied list. */
    private static final String BASE_STYLESHEET = "styles.css";

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private static final Logger LOG =
            Logger.getLogger(ThemeManager.class.getName());

    /**
     * Process-wide default instance, backed by the same per-package
     * {@link Preferences} node the rest of the UI uses
     * ({@code Preferences.userNodeForPackage}). There is no DI container;
     * {@code DarkThemeHelper} (the deprecated shim) and {@code
     * SettingsDialog} consult this so dialogs and the Appearance tab
     * re-theme without threading a {@code ThemeManager} through every
     * call site.
     *
     * <p>Lazy-initialized via the initialization-on-demand holder idiom
     * so that merely loading {@code ThemeManager} (e.g. to read
     * {@link #PREF_KEY} in a unit test that uses its own isolated
     * preferences node) does not perform I/O against the developer's /
     * CI agent's real user preferences store. The holder class loads —
     * and the default instance constructs — only on the first call to
     * {@link #getDefault()}.</p>
     */
    private static final class DefaultHolder {
        static final ThemeManager INSTANCE =
                new ThemeManager(Preferences.userNodeForPackage(ThemeManager.class));
    }

    /**
     * Test-only override for the process-wide default returned by
     * {@link #getDefault()}. When non-null, {@code getDefault()} returns
     * this instance instead of the real singleton — isolating tests that
     * exercise {@code DarkThemeHelper} or {@code SettingsDialog} from
     * the developer's / CI agent's actual user preferences store.
     *
     * <p>Set via {@link #setDefaultForTest(ThemeManager)} and cleared by
     * passing {@code null}. Not thread-safe — call from a single test
     * thread before/after the code under test.</p>
     */
    private static volatile ThemeManager defaultOverride;

    /**
     * Overrides the process-wide default instance for testing purposes.
     * Pass a {@code ThemeManager} backed by an isolated
     * {@link Preferences} node to prevent test runs from polluting (or
     * inheriting choices from) the developer's real user preferences.
     *
     * <p><strong>Usage:</strong></p>
     * <pre>{@code
     * Preferences testNode = Preferences.userRoot().node("myTest_" + nanoTime());
     * try {
     *     ThemeManager.setDefaultForTest(new ThemeManager(testNode));
     *     // … exercise DarkThemeHelper / SettingsDialog …
     * } finally {
     *     ThemeManager.setDefaultForTest(null);
     *     testNode.removeNode();
     * }
     * }</pre>
     *
     * @param override the test instance, or {@code null} to restore the
     *                 real singleton
     */
    public static void setDefaultForTest(ThemeManager override) {
        defaultOverride = override;
    }

    private final Preferences prefs;
    private final ObjectProperty<Theme> activeTheme;

    /**
     * Scenes / dialog-panes to re-style when the active theme changes.
     * Held <strong>weakly</strong>: a dismissed dialog's pane (or a
     * closed window's scene) must not be pinned for the JVM lifetime —
     * over a long editing session many transient dialogs come and go,
     * and {@link #getDefault()} is a process-lifetime singleton. GC'd entries
     * simply drop out of the re-theme set. All access is serialized
     * onto the FX thread via {@link #runOnFx}; {@link #reapplyAll()}
     * iterates a snapshot so a concurrent GC sweep cannot disturb it.
     */
    private final Set<Scene> scenes =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<DialogPane> dialogPanes =
            Collections.newSetFromMap(new WeakHashMap<>());

    private volatile String resolvedBaseUrl;

    /**
     * Creates a {@code ThemeManager} backed by the given preferences
     * node, restoring the persisted active theme (defaulting to
     * {@link #DEFAULT_THEME} for a missing/unknown value) and persisting
     * any subsequent change to the active-theme property.
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public ThemeManager(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        this.activeTheme = new SimpleObjectProperty<>(this, "activeTheme", restore());
        this.activeTheme.addListener((_, _, newTheme) -> {
            // Persist the new theme, then re-apply to all registered
            // scenes/panes. If persistence fails (backing-store I/O
            // error) we log a WARNING and still re-apply so the UI
            // remains consistent with what the user just chose — they
            // simply won't get the preference restored on next launch.
            // Propagating the exception would leave callers (e.g.
            // SettingsDialog's OK handler) with an unchecked failure
            // while the UI has already visually switched.
            try {
                persist(newTheme);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING,
                        "Failed to persist theme preference; the UI will "
                                + "switch but the choice may not survive restart",
                        ex);
            }
            reapplyAll();
        });
    }

    /**
     * @return the process-wide default {@code ThemeManager} — either the
     *         test override (if set via {@link #setDefaultForTest}) or
     *         the real singleton (persists to
     *         {@code Preferences.userNodeForPackage(ThemeManager.class)})
     */
    public static ThemeManager getDefault() {
        ThemeManager override = defaultOverride;
        return override != null ? override : DefaultHolder.INSTANCE;
    }

    // ── Active theme ─────────────────────────────────────────────────────────

    /**
     * The active token theme. Setting it persists the choice and
     * re-applies the new overlay to every registered scene/dialog-pane
     * (so the UI updates with no restart).
     *
     * @return the observable active-theme property
     */
    public ObjectProperty<Theme> activeThemeProperty() {
        return activeTheme;
    }

    /** @return the current active theme (never {@code null}). */
    public Theme getActiveTheme() {
        return activeTheme.get();
    }

    /**
     * Sets the active theme. Equivalent to
     * {@code activeThemeProperty().set(theme)}.
     *
     * @param theme the new active theme (must not be {@code null})
     */
    public void setActiveTheme(Theme theme) {
        activeTheme.set(Objects.requireNonNull(theme, "theme must not be null"));
    }

    // ── Application ──────────────────────────────────────────────────────────

    /**
     * Applies the ordered stylesheet list (base {@code styles.css} +
     * active theme overlay, overlay last) to {@code scene} and registers
     * it for live re-application on subsequent theme changes. Idempotent.
     *
     * <p><strong>Threading:</strong> if called from the JavaFX
     * application thread the sheets are applied synchronously; otherwise
     * the work is scheduled via {@link Platform#runLater(Runnable)} and
     * returns immediately. Callers reading
     * {@code scene.getStylesheets()} from a non-FX thread right after
     * this method returns may not yet see the updated list.</p>
     *
     * @param scene the scene to style (must not be {@code null})
     */
    public void applyTo(Scene scene) {
        Objects.requireNonNull(scene, "scene must not be null");
        runOnFx(() -> {
            scenes.add(scene); // Set.add is idempotent
            applyOrderedSheets(scene.getStylesheets());
        });
    }

    /**
     * Applies the ordered stylesheet list to {@code pane} (a dialog's
     * own pane — its Scene does not inherit the main scene's sheets),
     * ensures the {@code root-pane} style class is present so the
     * {@code .root-pane} token block resolves, and registers the pane
     * for live re-application. Idempotent.
     *
     * <p><strong>Threading:</strong> if called from the JavaFX
     * application thread the sheets are applied synchronously; otherwise
     * the work is scheduled via {@link Platform#runLater(Runnable)} and
     * returns immediately. Callers reading
     * {@code pane.getStylesheets()} from a non-FX thread right after
     * this method returns may not yet see the updated list.</p>
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
            applyOrderedSheets(pane.getStylesheets());
        });
    }

    /**
     * Replaces {@code target}'s contents with the canonical ordered
     * stylesheet list: base {@code styles.css} first, then the active
     * theme's overlay (if any). Any previously-applied base/overlay
     * entries are removed first so re-application is idempotent and a
     * stale overlay never lingers. The whole replacement is performed
     * via a single {@link ObservableList#setAll(java.util.Collection)
     * setAll} call so the JavaFX CSS subsystem sees one change event
     * (and one re-evaluation pass) rather than a remove-then-add pair —
     * avoiding a transient frame in which a registered scene is styled
     * by the base sheet alone before the overlay is re-installed.
     *
     * <p><strong>Ordering contract:</strong> ThemeManager owns the
     * <em>tail</em> of the stylesheet list. Unrelated sheets that a
     * caller appended (e.g. a plugin view or test harness) are preserved
     * but positioned <em>before</em> the base/overlay pair. This means
     * the theme overlay always wins the CSS author-order cascade over any
     * user-appended sheet. If a caller explicitly needs to override a
     * token after the overlay, it should append its sheet after calling
     * {@code applyTo} and accept that subsequent theme switches will
     * re-order it before the managed pair. In the current codebase no
     * caller layers extra sheets on top of {@code styles.css}, so this
     * ordering is latent.</p>
     */
    private void applyOrderedSheets(ObservableList<String> target) {
        List<String> ordered = orderedStylesheetUrls();
        Set<String> managed = Set.copyOf(allManagedUrls());
        // Build the final list = unrelated existing sheets (in caller's
        // original order) + our ordered managed list (overlay last).
        List<String> finalList = new ArrayList<>(target.size() + ordered.size());
        for (String sheet : target) {
            if (!managed.contains(sheet)) {
                finalList.add(sheet);
            }
        }
        finalList.addAll(ordered);
        target.setAll(finalList);
    }

    /**
     * @return the ordered stylesheet URL list for the active theme:
     *         {@code [styles.css]} for {@link Theme#ONYX_REFINED},
     *         {@code [styles.css, <overlay>]} otherwise
     */
    public List<String> orderedStylesheetUrls() {
        List<String> urls = new ArrayList<>(2);
        urls.add(baseStylesheetUrl());
        String overlay = activeTheme.get().overlayResource();
        if (overlay != null) {
            urls.add(resolve(overlay));
        }
        return List.copyOf(urls);
    }

    /** Every URL this manager may have installed (base + all overlays). */
    private List<String> allManagedUrls() {
        List<String> all = new ArrayList<>();
        all.add(baseStylesheetUrl());
        for (Theme t : Theme.values()) {
            if (t.overlayResource() != null) {
                all.add(resolve(t.overlayResource()));
            }
        }
        return all;
    }

    private void reapplyAll() {
        // Gate all access to the WeakHashMap-backed sets on the FX
        // thread — WeakHashMap is not thread-safe and a concurrent GC
        // sweep (expungeStaleEntries) racing with isEmpty() could throw
        // ConcurrentModificationException or return an incorrect answer.
        // If the toolkit is not running (pure-unit context) runOnFx will
        // silently drop the work — which is fine because there can be no
        // live scenes/panes to update in that case.
        runOnFx(() -> {
            if (scenes.isEmpty() && dialogPanes.isEmpty()) {
                return;
            }
            // Snapshot the weak sets: copying decouples iteration from a
            // concurrent GC sweep, and List.copyOf rejects nulls (the
            // sets never hold null — registration is null-checked).
            for (Scene scene : List.copyOf(scenes)) {
                applyOrderedSheets(scene.getStylesheets());
            }
            for (DialogPane pane : List.copyOf(dialogPanes)) {
                applyOrderedSheets(pane.getStylesheets());
            }
        });
    }

    // ── Resource resolution (mirrors DarkThemeHelper.getStylesheetUrl) ───────

    /**
     * @return the external-form URL of the base {@code styles.css},
     *         resolved lazily and cached (mirrors the
     *         {@code DarkThemeHelper} resolution it now backs)
     */
    public String baseStylesheetUrl() {
        String url = resolvedBaseUrl;
        if (url == null) {
            url = resolve(BASE_STYLESHEET);
            resolvedBaseUrl = url;
        }
        return url;
    }

    /** Absolute classpath root of the {@code ui} package's resources. */
    private static final String UI_RESOURCE_ROOT =
            "/com/benesquivelmusic/daw/app/ui/";

    private static String resolve(String resource) {
        // The stylesheets live in the `ui` package (the parent of this
        // `theme` package): `styles.css`, `themes/*.css`. Class.getResource
        // does NOT normalise a relative `../` segment, so resolve via the
        // absolute classpath path instead.
        URL u = ThemeManager.class.getResource(UI_RESOURCE_ROOT + resource);
        Objects.requireNonNull(u, "Stylesheet resource not found: " + resource);
        return u.toExternalForm();
    }

    // ── Persistence (toolkit-free) ───────────────────────────────────────────

    private Theme restore() {
        String stored = prefs.get(PREF_KEY, DEFAULT_THEME.name());
        try {
            return Theme.valueOf(stored);
        } catch (IllegalArgumentException unknownValue) {
            // Unknown / corrupt enum name persisted by a newer or broken
            // build. (prefs.get with a non-null default never returns
            // null, so Theme.valueOf only ever throws here for an
            // unrecognised name.) Fall back to the design-book default.
            return DEFAULT_THEME;
        }
    }

    private void persist(Theme theme) {
        prefs.put(PREF_KEY, theme.name());
    }

    // ── i18n ─────────────────────────────────────────────────────────────────

    /**
     * Resolves a theme's localized display name from the shared bundle,
     * falling back to the raw key if absent (mirrors {@code
     * DawgDialog#msg} / {@code BrowserPanel#msg}).
     *
     * @param theme the theme (must not be {@code null})
     * @return the localized display name, or the key if not found
     */
    public static String displayName(Theme theme) {
        Objects.requireNonNull(theme, "theme must not be null");
        try {
            return MESSAGES.getString(theme.displayNameKey());
        } catch (MissingResourceException e) {
            return theme.displayNameKey();
        }
    }

    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        try {
            Platform.runLater(r);
        } catch (IllegalStateException toolkitNotRunning) {
            // No JavaFX toolkit yet / toolkit already shut down (a
            // pure-unit context, or a theme change racing application
            // exit). There can be no live scene/dialog-pane to update,
            // so dropping the re-apply is correct rather than fatal —
            // but log at FINE so a genuine post-shutdown failure is not
            // entirely invisible given the FX-fork-shutdown history.
            LOG.log(Level.FINE,
                    "Theme re-apply skipped: no running JavaFX toolkit",
                    toolkitNotRunning);
        }
    }
}
