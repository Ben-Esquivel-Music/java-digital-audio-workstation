package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.app.ui.dock.DockEntry;
import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockLayoutJson;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five canonical, read-only Mission Control layouts shipped with the
 * app per UI Design Book §4 Concept D (story 282).
 *
 * <p>The catalog order — Default, Tracking, Mixing, Mastering, Live —
 * is the order the View → Layout menu renders the radio items in, so
 * downstream tests pin both the order and the names ({@code
 * LayoutMenuTest}).</p>
 *
 * <p>Each layout is captured as opaque {@link DockLayout} JSON so the
 * dock-layer schema (story 195) can evolve without touching this class.
 * The layouts deliberately assume only the canonical panel ids used by
 * {@code MainController} ({@code arrangement}, {@code mixer},
 * {@code browser}, {@code inspector}, {@code spectrum}, …); panels that
 * are not yet registered when a built-in is applied are dropped by
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockManager#applyLayout(DockLayout)}
 * — i.e. built-ins are forward-compatible with smaller projects.</p>
 */
public final class BuiltInLayouts {

    /** The user-facing name of the default layout. */
    public static final String DEFAULT = "Default";
    /** The user-facing name of the tracking layout. */
    public static final String TRACKING = "Tracking";
    /** The user-facing name of the mixing layout. */
    public static final String MIXING = "Mixing";
    /** The user-facing name of the mastering layout. */
    public static final String MASTERING = "Mastering";
    /** The user-facing name of the live-performance layout. */
    public static final String LIVE = "Live";

    /**
     * Canonical, ordered list of built-in layout names. The View → Layout
     * menu renders radio items in this order (story 282 §"Add a View →
     * Layout menu").
     */
    public static final List<String> NAMES = List.of(
            DEFAULT, TRACKING, MIXING, MASTERING, LIVE);

    private BuiltInLayouts() { }

    /** Returns all five built-in layouts in canonical menu order. */
    public static List<NamedLayout> all() {
        return List.of(
                NamedLayout.builtIn(DEFAULT,   defaultLayoutJson()),
                NamedLayout.builtIn(TRACKING,  trackingLayoutJson()),
                NamedLayout.builtIn(MIXING,    mixingLayoutJson()),
                NamedLayout.builtIn(MASTERING, masteringLayoutJson()),
                NamedLayout.builtIn(LIVE,      liveLayoutJson()));
    }

    /** Returns the built-in with the given name, or {@code null} if none. */
    public static NamedLayout byName(String name) {
        for (NamedLayout l : all()) {
            if (l.name().equals(name)) return l;
        }
        return null;
    }

    /** Returns {@code true} if the given name is one of the built-in layouts. */
    public static boolean isBuiltIn(String name) {
        return NAMES.contains(name);
    }

    // ── Built-in layout JSON factories ──────────────────────────────────────
    //
    // Each layout is materialised as a DockLayout and serialised through
    // DockLayoutJson so the on-disk format is identical to user-saved
    // layouts. Panels that don't exist in a given project are silently
    // dropped at apply-time — see DockManager#applyLayout.

    private static String defaultLayoutJson() {
        // Arrangement centre, browser left, inspector right, mixer bottom,
        // visualisations on the right strip — the §4 baseline layout.
        Map<String, DockEntry> e = new LinkedHashMap<>();
        e.put("browser",      DockEntry.docked("browser",      DockZone.LEFT,   0, true));
        e.put("arrangement",  DockEntry.docked("arrangement",  DockZone.CENTER, 0, true));
        e.put("inspector",    DockEntry.docked("inspector",    DockZone.RIGHT,  0, true));
        e.put("mixer",        DockEntry.docked("mixer",        DockZone.BOTTOM, 0, true));
        e.put("spectrum",     DockEntry.docked("spectrum",     DockZone.RIGHT,  1, false));
        e.put("correlation",  DockEntry.docked("correlation",  DockZone.RIGHT,  2, false));
        e.put("loudness",     DockEntry.docked("loudness",     DockZone.RIGHT,  3, false));
        return DockLayoutJson.toJson(DockLayout.of(e));
    }

    private static String trackingLayoutJson() {
        // Tracking emphasises browser + arrangement + meters; mixer hidden.
        Map<String, DockEntry> e = new LinkedHashMap<>();
        e.put("browser",     DockEntry.docked("browser",     DockZone.LEFT,   0, true));
        e.put("arrangement", DockEntry.docked("arrangement", DockZone.CENTER, 0, true));
        e.put("loudness",    DockEntry.docked("loudness",    DockZone.RIGHT,  0, true));
        e.put("inspector",   DockEntry.docked("inspector",   DockZone.RIGHT,  1, true));
        e.put("mixer",       DockEntry.docked("mixer",       DockZone.BOTTOM, 0, false));
        return DockLayoutJson.toJson(DockLayout.of(e));
    }

    private static String mixingLayoutJson() {
        // Mixing pins the mixer in CENTER (it owns the screen) with
        // arrangement reduced to the BOTTOM strip; visualisations on the
        // right rail support the mix decisions.
        Map<String, DockEntry> e = new LinkedHashMap<>();
        e.put("mixer",        DockEntry.docked("mixer",        DockZone.CENTER, 0, true));
        e.put("arrangement",  DockEntry.docked("arrangement",  DockZone.BOTTOM, 0, true));
        e.put("inspector",    DockEntry.docked("inspector",    DockZone.RIGHT,  0, true));
        e.put("spectrum",     DockEntry.docked("spectrum",     DockZone.RIGHT,  1, true));
        e.put("correlation",  DockEntry.docked("correlation",  DockZone.RIGHT,  2, true));
        e.put("loudness",     DockEntry.docked("loudness",     DockZone.RIGHT,  3, true));
        e.put("browser",      DockEntry.docked("browser",      DockZone.LEFT,   0, false));
        return DockLayoutJson.toJson(DockLayout.of(e));
    }

    private static String masteringLayoutJson() {
        // Mastering is metering-heavy: large arrangement preview centre,
        // spectrum / correlation / loudness front-and-centre on the right.
        Map<String, DockEntry> e = new LinkedHashMap<>();
        e.put("arrangement", DockEntry.docked("arrangement", DockZone.CENTER, 0, true));
        e.put("spectrum",    DockEntry.docked("spectrum",    DockZone.RIGHT,  0, true));
        e.put("correlation", DockEntry.docked("correlation", DockZone.RIGHT,  1, true));
        e.put("loudness",    DockEntry.docked("loudness",    DockZone.RIGHT,  2, true));
        e.put("inspector",   DockEntry.docked("inspector",   DockZone.LEFT,   0, true));
        e.put("mixer",       DockEntry.docked("mixer",       DockZone.BOTTOM, 0, false));
        e.put("browser",     DockEntry.docked("browser",     DockZone.LEFT,   1, false));
        return DockLayoutJson.toJson(DockLayout.of(e));
    }

    private static String liveLayoutJson() {
        // Live performance: arrangement centre, mixer floating on a
        // second monitor, browser and inspector tucked away. Floating
        // bounds are a sensible default; the user's actual second-monitor
        // position is persisted by FloatingWindowStore once they move it.
        Map<String, DockEntry> e = new LinkedHashMap<>();
        e.put("arrangement", DockEntry.docked("arrangement", DockZone.CENTER, 0, true));
        e.put("mixer",       DockEntry.floating("mixer", new Rectangle2D(1300, 80, 900, 500)));
        e.put("browser",     DockEntry.docked("browser",     DockZone.LEFT,   0, false));
        e.put("inspector",   DockEntry.docked("inspector",   DockZone.RIGHT,  0, false));
        return DockLayoutJson.toJson(DockLayout.of(e));
    }
}
