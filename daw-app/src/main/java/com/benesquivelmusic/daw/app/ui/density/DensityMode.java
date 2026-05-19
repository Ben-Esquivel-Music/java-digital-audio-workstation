package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.design.SpacingTokens;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.Parent;

import java.util.List;
import java.util.Objects;

/**
 * Phase-3 global density profile (UI Design Book §3.7, story 278).
 *
 * <p>The DAW exposes three user-selectable, <em>global</em> density
 * profiles. Each profile maps to one of the row-height tokens already
 * declared in {@code styles.css} / {@link SpacingTokens} (story 261) and
 * to a single root-scope style class that the pure-CSS padding rules key
 * off:</p>
 *
 * <table>
 *   <caption>Density profiles</caption>
 *   <tr><th>Mode</th><th>Row height</th><th>Root style class</th></tr>
 *   <tr><td>{@link #COMPACT}</td><td>24&nbsp;px ({@code -row-compact})</td><td>{@code density-compact}</td></tr>
 *   <tr><td>{@link #COMFORTABLE}</td><td>28&nbsp;px ({@code -row-default}, default)</td><td>{@code density-comfortable}</td></tr>
 *   <tr><td>{@link #TOUCH}</td><td>32&nbsp;px ({@code -row-touch})</td><td>{@code density-touch}</td></tr>
 * </table>
 *
 * <p>§3.7 is explicit: <em>"Motion, type scale, and elevation are
 * unchanged across density. Only padding and row height change."</em>
 * {@code DensityMode} therefore carries <strong>no colour and no type
 * information</strong> — it is purely a padding / row-height selector.</p>
 *
 * <h2>The root → Java-skin bridge ({@link #resolveFor(Node)})</h2>
 *
 * <p>The single root-scope style class drives every pure-CSS
 * {@code -fx-padding} rule for free (JavaFX descendant selectors). It
 * cannot, however, drive the two controls whose size is computed in their
 * {@code Skin} ({@code TrackStripSkin}, {@code MixerChannelStripSkin}):
 * JavaFX style classes do <strong>not</strong> inherit to descendants, so
 * a {@code .density-*} class on {@code scene.getRoot()} is invisible to a
 * child control's {@code getStyleClass()}. {@link #resolveFor(Node)} is
 * the single shared resolver those skins consult to bridge the root
 * density class into their Java-computed sizes (story 278's load-bearing
 * mechanism — one resolver, reused, never duplicated per skin).</p>
 */
public enum DensityMode {

    /**
     * Dense rows for the mixer-with-many-channels and browser lists —
     * 24&nbsp;px ({@code -row-compact}, {@link SpacingTokens#ROW_COMPACT}).
     */
    COMPACT(SpacingTokens.ROW_COMPACT, "density-compact",
            "appearance.density.compact"),

    /**
     * The design-book default — 28&nbsp;px ({@code -row-default},
     * {@link SpacingTokens#ROW_DEFAULT}). At this density every density
     * rule is a deliberate no-op (the Phase-1/2 baseline).
     */
    COMFORTABLE(SpacingTokens.ROW_DEFAULT, "density-comfortable",
            "appearance.density.comfortable"),

    /**
     * Touch-friendly rows for tablets / hybrid laptops / live use —
     * 32&nbsp;px ({@code -row-touch}, {@link SpacingTokens#ROW_TOUCH}).
     */
    TOUCH(SpacingTokens.ROW_TOUCH, "density-touch",
            "appearance.density.touch");

    private final double rowHeightPx;
    private final String styleClass;
    private final String displayNameKey;

    DensityMode(double rowHeightPx, String styleClass, String displayNameKey) {
        this.rowHeightPx = rowHeightPx;
        this.styleClass = styleClass;
        this.displayNameKey = displayNameKey;
    }

    /**
     * @return this mode's row height in pixels — one of the
     *         {@link SpacingTokens} {@code ROW_*} constants (24 / 28 / 32)
     */
    public double rowHeightPx() {
        return rowHeightPx;
    }

    /**
     * @return the single root-scope style class for this mode
     *         ({@code density-compact} / {@code density-comfortable} /
     *         {@code density-touch}) — added by {@code DensityManager} to
     *         {@code scene.getRoot()} and keyed off by the pure-CSS
     *         padding rules in {@code styles.css}
     */
    public String styleClass() {
        return styleClass;
    }

    /** @return the {@code Messages.properties} display-name key (Skill §14). */
    public String displayNameKey() {
        return displayNameKey;
    }

    /** Every root-scope density style class (for idempotent swap removal). */
    static final List<String> ALL_STYLE_CLASSES = List.of(
            COMPACT.styleClass, COMFORTABLE.styleClass, TOUCH.styleClass);

    /**
     * The single shared root → Java-skin resolver (story 278's
     * load-bearing bridge). Resolves the effective {@link DensityMode}
     * for {@code node} by, in priority order:
     *
     * <ol>
     *   <li>walking to {@code node.getScene()} and reading
     *       {@code scene.getRoot().getStyleClass()} for one of the three
     *       {@code density-*} classes (the authoritative source — set by
     *       {@code DensityManager.applyTo(Scene)});</li>
     *   <li>falling back to the control's <em>own</em> legacy style class
     *       for back-compat with callers / tests that add a size class
     *       directly on the control: {@code size-compact} → COMPACT,
     *       {@code size-comfortable} / {@code size-performance} → TOUCH
     *       (the legacy {@code TrackStripSkin} 32&nbsp;px "comfortable"
     *       constant is the story's TOUCH — see that skin's Javadoc),
     *       {@code density-comfortable} on the control → COMFORTABLE;</li>
     *   <li>falling back to the live default
     *       ({@code DensityManager.getDefault().getActiveDensity()});</li>
     *   <li>finally {@link DensityManager#DEFAULT_DENSITY}.</li>
     * </ol>
     *
     * <p>Null-safe and toolkit-tolerant: a {@code null} node, a node not
     * yet in a scene, or a scene with no root all degrade gracefully
     * through the fallback chain — they never throw.</p>
     *
     * @param node the node whose effective density is being resolved —
     *             in practice the skinnable control consulting this
     *             resolver (may be {@code null})
     * @return the resolved density (never {@code null})
     */
    public static DensityMode resolveFor(Node node) {
        // 1 — authoritative: the root-scope density class set by
        //     DensityManager.applyTo(Scene).
        if (node != null) {
            Scene scene = node.getScene();
            if (scene != null) {
                Parent root = scene.getRoot();
                if (root != null) {
                    DensityMode fromRoot = fromStyleClasses(root.getStyleClass());
                    if (fromRoot != null) {
                        return fromRoot;
                    }
                }
            }
            // 2 — back-compat: the control's own style class. Existing
            //     tests/callers add `size-*` / `density-*` directly on the
            //     control; honour that so they keep working.
            var own = node.getStyleClass();
            if (own.contains("size-compact")) {
                return COMPACT;
            }
            if (own.contains("size-comfortable") || own.contains("size-performance")) {
                // Legacy TrackStripSkin "comfortable" == 32 px == story TOUCH.
                return TOUCH;
            }
            if (own.contains("density-compact")) {
                return COMPACT;
            }
            if (own.contains("density-touch")) {
                return TOUCH;
            }
            if (own.contains("density-comfortable")) {
                return COMFORTABLE;
            }
        }
        // 3 — the live default.
        DensityMode live = DensityManager.getDefault().getActiveDensity();
        // 4 — final hard fallback.
        return live != null ? live : DensityManager.DEFAULT_DENSITY;
    }

    /**
     * @param styleClasses a style-class list
     * @return the density matching one of the {@code density-*} classes,
     *         or {@code null} if none is present
     */
    static DensityMode fromStyleClasses(Iterable<String> styleClasses) {
        Objects.requireNonNull(styleClasses, "styleClasses must not be null");
        for (String c : styleClasses) {
            if (COMPACT.styleClass.equals(c)) {
                return COMPACT;
            }
            if (COMFORTABLE.styleClass.equals(c)) {
                return COMFORTABLE;
            }
            if (TOUCH.styleClass.equals(c)) {
                return TOUCH;
            }
        }
        return null;
    }
}
