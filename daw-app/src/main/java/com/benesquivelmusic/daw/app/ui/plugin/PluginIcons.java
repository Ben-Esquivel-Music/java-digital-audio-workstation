package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;

import javafx.scene.Node;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a plugin's {@link PluginCategory} + optional {@code iconHint} onto a
 * concrete {@link DawIcon} (Plugin View Design Book §4.4, §6.7). This replaces
 * the legacy ~60-branch keyword-sniffing {@code pluginDetailIcon} that guessed a
 * plugin's kind from its name: a plugin now declares its category (and, when it
 * wants a specific glyph, a stable {@code iconHint}) in its manifest, and the
 * host resolves it structurally here.
 *
 * <p>Resolution (never {@code null}):</p>
 * <ol>
 *   <li>a non-blank {@code iconHint} is normalised (trimmed, upper-cased in
 *       {@link Locale#ROOT}, {@code '-'} &rarr; {@code '_'}) and matched against
 *       {@link DawIcon#valueOf(String)} — so {@code "compressor"} &rarr;
 *       {@link DawIcon#COMPRESSOR}, {@code "low-pass"} &rarr;
 *       {@link DawIcon#LOW_PASS};</li>
 *   <li>on a miss (or a blank hint) it falls back to the category's default glyph
 *       from a fixed {@link EnumMap}.</li>
 * </ol>
 *
 * <p>There is deliberately no {@code switch} on a plugin id anywhere: the map is
 * keyed on the closed {@link PluginCategory} enum (pinned by
 * {@code NoSwitchOnPluginIdTest}).</p>
 */
public final class PluginIcons {

    /** The category &rarr; default {@link DawIcon} fallback map (§6.7). */
    private static final Map<PluginCategory, DawIcon> CATEGORY_ICONS = buildCategoryIcons();

    private PluginIcons() {
        // utility class
    }

    /**
     * Returns a ready-to-use icon {@link Node} for the given category / hint,
     * scaled to {@code size} pixels.
     *
     * @param category the plugin's browser category; must not be {@code null}
     * @param iconHint the plugin's icon hint, or {@code ""} / {@code null} for none
     * @param size     the icon size in pixels (both width and height)
     * @return a scaled icon node (never {@code null})
     */
    public static Node of(PluginCategory category, String iconHint, double size) {
        return IconNode.of(resolve(category, iconHint), size);
    }

    /**
     * Resolves the {@link DawIcon} for a category / hint without building a node.
     * Package-private so a unit test can assert the mapping (e.g. that
     * {@code REVERB_AND_DELAY} and {@code DYNAMICS} resolve to different glyphs)
     * without a JavaFX scene.
     *
     * @param category the plugin's browser category; must not be {@code null}
     * @param iconHint the plugin's icon hint, or {@code ""} / {@code null} for none
     * @return the resolved icon (never {@code null})
     */
    static DawIcon resolve(PluginCategory category, String iconHint) {
        Objects.requireNonNull(category, "category must not be null");
        if (iconHint != null && !iconHint.isBlank()) {
            String normalized = iconHint.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                return DawIcon.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                // Unknown hint — fall back to the category default below.
            }
        }
        return CATEGORY_ICONS.getOrDefault(category, DawIcon.KNOB);
    }

    private static Map<PluginCategory, DawIcon> buildCategoryIcons() {
        Map<PluginCategory, DawIcon> map = new EnumMap<>(PluginCategory.class);
        map.put(PluginCategory.DYNAMICS, DawIcon.COMPRESSOR);
        map.put(PluginCategory.EQ_AND_FILTER, DawIcon.EQ);
        map.put(PluginCategory.MODULATION, DawIcon.CHORUS);
        map.put(PluginCategory.REVERB_AND_DELAY, DawIcon.REVERB);
        map.put(PluginCategory.SPATIAL, DawIcon.CORRELATION);
        map.put(PluginCategory.UTILITY, DawIcon.KNOB);
        map.put(PluginCategory.ANALYZER, DawIcon.SPECTRUM);
        map.put(PluginCategory.INSTRUMENT, DawIcon.KEYBOARD);
        map.put(PluginCategory.MIDI_EFFECT, DawIcon.MIDI);
        return map;
    }
}
