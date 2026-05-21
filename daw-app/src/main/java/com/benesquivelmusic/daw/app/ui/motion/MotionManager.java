package com.benesquivelmusic.daw.app.ui.motion;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Phase-3 global "Reduce Motion" accessibility flag (UI Design Book
 * §2.7 / §3.5, story 279).
 *
 * <h2>The cut/keep rule</h2>
 *
 * <p>Reduce Motion cuts <strong>transitional</strong> motion — panel
 * show/hide, tab and modal in/out, press/hover feedback, dismissal fades
 * — to {@code 0 ms}, while leaving <strong>real-time-information</strong>
 * motion completely untouched. Real-time motion is not "animation": it
 * <em>is</em> the information. The exempt surfaces are the level-meter
 * signal rendering, the spectrum analyser / correlation meter /
 * goniometer / oscilloscope scopes, the waveform display while playback
 * advances, the {@code ArrangementCanvas} playhead, and any future
 * analogue-style VU needle ballistics. A future code review can apply
 * the cut/keep decision from this one paragraph without re-reading the
 * design book: <em>if it conveys live data, keep it; if it is a
 * transition between two static states, cut it.</em></p>
 *
 * <h2>Two-mechanism design</h2>
 *
 * <p>Per-control animation has two independent gates (UI Design Book
 * §2.7). Each animatable control keeps its own {@code animatedProperty()}
 * (the per-control opt-out — e.g. a test or a specific layout disabling
 * one control's motion), <em>and</em> this manager provides the global
 * Reduce Motion toggle. The effective per-control animation is
 * {@code localFlag AND NOT reduceMotion}: either gate can suppress
 * motion, and the per-control {@code setAnimated(...)} API keeps working
 * unchanged. This manager is therefore a pure observable flag — controls
 * observe {@link #reduceMotionProperty()} directly and recompute their
 * combined animated state; nothing here applies CSS or stylesheets.</p>
 *
 * <h2>Scope notes</h2>
 *
 * <p>{@code TrackStrip} and {@code MixerChannelStrip} have no decorative
 * motion today (their hover background swap is already instantaneous per
 * §3.5), so — unlike the five controls that do own an
 * {@code animatedProperty()} ({@code LevelMeter}, {@code Knob},
 * {@code Fader}, {@code InspectorDrawer}, {@code NotificationBar}) — they
 * deliberately have <em>no</em> animated property to gate. Adding one
 * would be gold-plating against a non-existent transition.</p>
 *
 * <h2>Relationship to the theme / density / WCAG systems</h2>
 *
 * <p>Reduce Motion persists under its own key ({@link #PREF_KEY} =
 * {@code "appearance.reduceMotion"}), deliberately distinct from
 * {@code ThemeManager.PREF_KEY} ({@code "appearance.tokenTheme"}, story
 * 277), {@code DensityManager.PREF_KEY} ({@code "appearance.density"},
 * story 278) and {@code SettingsModel.KEY_THEME_ID}
 * ({@code "appearance.themeId"}, story 194's WCAG JSON registry). Those
 * are four separate systems and must not share a key (pinned by
 * {@code MotionManagerPersistenceTest}). It is also a {@code boolean}
 * key, not an enum-name {@code String} like the theme/density keys.</p>
 *
 * <h2>OS-hint seeding</h2>
 *
 * <p>On the very first launch — when no in-app preference has yet been
 * persisted — the initial flag is seeded from the OS-level Reduce Motion
 * preference via {@link OsMotionHint} (Windows {@code SystemParametersInfo},
 * Linux {@code gsettings}; macOS is not reliably detectable and seeds to
 * the {@code false} default). Once the user has saved a choice the stored
 * value always wins over the OS hint.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #reduceMotionProperty()} is a JavaFX property and should be
 * read/written on the FX application thread once a toolkit is running.
 * Persistence and construction are toolkit-free so {@code MotionManager}
 * can be unit-tested without a running toolkit (mirrors
 * {@code ThemeManager} / {@code DensityManager}).</p>
 */
public final class MotionManager {

    /**
     * Preferences key under which the Reduce Motion flag is persisted.
     *
     * <p>Deliberately distinct from {@code ThemeManager.PREF_KEY}
     * ({@code "appearance.tokenTheme"}), {@code DensityManager.PREF_KEY}
     * ({@code "appearance.density"}) and {@code SettingsModel.KEY_THEME_ID}
     * ({@code "appearance.themeId"}). Reduce Motion, the token theme, the
     * density profile and the WCAG JSON registry are four separate
     * systems and must not share a key (pinned by
     * {@code MotionManagerPersistenceTest}). Unlike the theme/density
     * keys (which store an enum name) this key stores a {@code boolean}.</p>
     */
    public static final String PREF_KEY = "appearance.reduceMotion";

    /**
     * The default Reduce Motion value for a missing OS hint — motion
     * allowed (transitions play). Reduce Motion is opt-in.
     */
    public static final boolean DEFAULT_REDUCE_MOTION = false;

    private static final Logger LOG =
            Logger.getLogger(MotionManager.class.getName());

    /**
     * Process-wide default instance, backed by the same per-package
     * {@link Preferences} node the rest of the UI uses. Lazy-initialized
     * via the initialization-on-demand holder idiom so merely loading
     * {@code MotionManager} (e.g. to read {@link #PREF_KEY} in a unit test
     * using its own isolated node) performs no I/O against the real user
     * preferences store and runs no OS probe (mirrors {@code ThemeManager}
     * / {@code DensityManager}).
     */
    private static final class DefaultHolder {
        static final MotionManager INSTANCE =
                new MotionManager(Preferences.userNodeForPackage(MotionManager.class));
    }

    /**
     * Test-only override for the process-wide default returned by
     * {@link #getDefault()}. When non-null, {@code getDefault()} returns
     * this instance instead of the real singleton — isolating tests that
     * exercise controls / {@code SettingsDialog} from the developer's / CI
     * agent's actual user preferences store.
     *
     * <p>Set via {@link #setDefaultForTest(MotionManager)} and cleared by
     * passing {@code null}. Not thread-safe — call from a single test
     * thread before/after the code under test (mirrors
     * {@code ThemeManager.setDefaultForTest} /
     * {@code DensityManager.setDefaultForTest}).</p>
     */
    private static volatile MotionManager defaultOverride;

    /**
     * Overrides the process-wide default instance for testing purposes.
     * Pass a {@code MotionManager} backed by an isolated
     * {@link Preferences} node to prevent test runs from polluting (or
     * inheriting choices from) the developer's real user preferences.
     *
     * @param override the test instance, or {@code null} to restore the
     *                 real singleton
     */
    public static void setDefaultForTest(MotionManager override) {
        defaultOverride = override;
    }

    private final Preferences prefs;
    private final BooleanProperty reduceMotion;

    /**
     * Creates a {@code MotionManager} backed by the given preferences
     * node using the real platform OS-hint detector
     * ({@link PlatformMotionHint}). Equivalent to
     * {@code MotionManager(prefs, new PlatformMotionHint())}.
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public MotionManager(Preferences prefs) {
        this(prefs, new PlatformMotionHint());
    }

    /**
     * Creates a {@code MotionManager} backed by the given preferences node
     * and OS-hint seam, restoring the persisted Reduce Motion flag (or, on
     * first launch, seeding it from {@code osHint}) and persisting any
     * subsequent change.
     *
     * <p>The {@code osHint} parameter is the mockable seam exercised by
     * {@code OsHintDetectionTest}; production code uses the
     * {@link #MotionManager(Preferences)} constructor, which supplies the
     * real {@link PlatformMotionHint}.</p>
     *
     * @param prefs   the backing preferences node (must not be {@code null})
     * @param osHint  the OS-hint detector (must not be {@code null})
     */
    public MotionManager(Preferences prefs, OsMotionHint osHint) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
        Objects.requireNonNull(osHint, "osHint must not be null");
        this.reduceMotion =
                new SimpleBooleanProperty(this, "reduceMotion", restore(osHint));
        this.reduceMotion.addListener((_, _, now) -> {
            // Persist the new value. If persistence fails (backing-store
            // I/O error) we log a WARNING and do NOT propagate — the
            // in-memory flag is already updated so the UI is consistent
            // with what the user just chose; the choice simply may not
            // survive restart (mirrors ThemeManager / DensityManager).
            try {
                persist(now);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING,
                        "Failed to persist reduce-motion preference; the "
                                + "setting will apply but may not survive restart",
                        ex);
            }
        });
    }

    /**
     * @return the process-wide default {@code MotionManager} — either the
     *         test override (if set via {@link #setDefaultForTest}) or the
     *         real singleton (persists to
     *         {@code Preferences.userNodeForPackage(MotionManager.class)})
     */
    public static MotionManager getDefault() {
        MotionManager override = defaultOverride;
        return override != null ? override : DefaultHolder.INSTANCE;
    }

    // ── Reduce-motion flag ───────────────────────────────────────────────────

    /**
     * The global Reduce Motion flag. {@code true} = transitions are cut
     * to {@code 0 ms}; real-time motion is unaffected (see the class
     * Javadoc cut/keep rule). Setting it persists the choice.
     *
     * <p>Animatable controls observe this property directly to recompute
     * their combined {@code animated} state — there is no scene re-apply,
     * because Reduce Motion is a pure flag, not a CSS class.</p>
     *
     * @return the observable Reduce Motion property
     */
    public BooleanProperty reduceMotionProperty() {
        return reduceMotion;
    }

    /** @return whether Reduce Motion is currently enabled. */
    public boolean isReduceMotion() {
        return reduceMotion.get();
    }

    /**
     * Sets the Reduce Motion flag. Equivalent to
     * {@code reduceMotionProperty().set(value)}.
     *
     * @param value {@code true} to cut transitions to {@code 0 ms}
     */
    public void setReduceMotion(boolean value) {
        reduceMotion.set(value);
    }

    /**
     * Convenience inverse of {@link #isReduceMotion()} for non-control
     * gating sites that wrap a transition in
     * {@code if (MotionManager.getDefault().isAnimationAllowed())}.
     *
     * @return {@code true} when transitional animation is allowed
     *         (Reduce Motion off), {@code false} when it should be cut
     */
    public boolean isAnimationAllowed() {
        return !reduceMotion.get();
    }

    // ── Persistence (toolkit-free) ───────────────────────────────────────────

    /**
     * Restores the persisted flag. If the key is <em>present</em> the
     * stored boolean wins. If it is <em>absent</em> (first launch) the
     * value is seeded from the OS hint, falling back to
     * {@link #DEFAULT_REDUCE_MOTION} when the OS preference cannot be
     * determined.
     */
    private boolean restore(OsMotionHint osHint) {
        // prefs.get(key, null) == null distinguishes "key absent" from a
        // persisted "false" — getBoolean cannot make that distinction.
        boolean keyPresent = prefs.get(PREF_KEY, null) != null;
        if (keyPresent) {
            return prefs.getBoolean(PREF_KEY, DEFAULT_REDUCE_MOTION);
        }
        Optional<Boolean> hint = osHint.reduceMotionPreferred();
        return hint.orElse(DEFAULT_REDUCE_MOTION);
    }

    private void persist(boolean value) {
        prefs.putBoolean(PREF_KEY, value);
    }
}
