package com.benesquivelmusic.daw.sdk.editor;

import java.util.Objects;

import javafx.scene.paint.Color;

/**
 * Resolved role-token colour values for the active theme, handed to a
 * {@link PluginEditorFactory.Canvas} plugin so its custom drawing tracks the
 * host palette (Plugin View Design Book §2.5, §4.1).
 *
 * <p>The host resolves these values from CSS (the {@code .root-pane} role
 * tokens surfaced by {@code ThemeManager}, story 277) — they are <em>never</em>
 * a hard-coded hex mirror of a shipped theme. A canvas plugin must draw with
 * these colours rather than its own literals so that switching the DAW theme
 * re-tints the plugin without the plugin doing anything (§9 rejection #6:
 * plugins consume host tokens, they cannot override them).</p>
 *
 * @param background the surface background colour
 * @param foreground the primary text / line colour on {@code background}
 * @param accent     the single accent colour used for focus and active state
 * @param meterSafe  the "nominal" meter colour (green band)
 * @param meterWarn  the "warning" meter colour (amber band)
 * @param meterClip  the "critical" meter colour (red / clip band)
 */
public record Theme(
        Color background,
        Color foreground,
        Color accent,
        Color meterSafe,
        Color meterWarn,
        Color meterClip) {

    public Theme {
        Objects.requireNonNull(background, "background must not be null");
        Objects.requireNonNull(foreground, "foreground must not be null");
        Objects.requireNonNull(accent, "accent must not be null");
        Objects.requireNonNull(meterSafe, "meterSafe must not be null");
        Objects.requireNonNull(meterWarn, "meterWarn must not be null");
        Objects.requireNonNull(meterClip, "meterClip must not be null");
    }

    /**
     * A neutral greyscale fallback palette used <em>only</em> when the host has
     * not yet supplied resolved role tokens — for example inside SDK unit tests
     * or before the first theme resolution completes. This is deliberately
     * <strong>not</strong> a mirror of any shipped DAW theme: the host always
     * replaces it with values resolved from CSS. It exists so a canvas plugin
     * always has a coherent, non-null palette to draw with.
     *
     * @return a neutral fallback theme, never {@code null}
     */
    public static Theme neutral() {
        return new Theme(
                Color.gray(0.12),
                Color.gray(0.88),
                Color.web("#4a9eff"),
                Color.web("#3ecf5b"),
                Color.web("#e0b93a"),
                Color.web("#e05a4a"));
    }
}
