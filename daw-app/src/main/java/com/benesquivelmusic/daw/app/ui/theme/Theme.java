package com.benesquivelmusic.daw.app.ui.theme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable in-memory representation of an accessible color theme.
 *
 * <p>A theme has a stable {@link #id() id}, a human-readable name and
 * description, a flag indicating whether it is a dark theme, an ordered
 * map of named colors (with role classification: foreground, background,
 * accent, warning, danger), and an ordered list of foreground/background
 * {@link Pair pairs} that the
 * {@link ThemeContrastValidator} should audit for WCAG conformance.</p>
 *
 * <p>Themes are loaded from JSON files by {@link ThemeRegistry} — bundled
 * themes ship under {@code daw-app/src/main/resources/themes/}, user
 * themes live under {@code ~/.daw/themes/}.</p>
 */
public record Theme(
        String id,
        String name,
        String description,
        boolean dark,
        Map<String, Color> colors,
        List<Pair> pairs) {

    public Theme {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(colors, "colors must not be null");
        Objects.requireNonNull(pairs, "pairs must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        // Defensive copies so the record stays effectively immutable.
        colors = Map.copyOf(new LinkedHashMap<>(colors));
        pairs = List.copyOf(pairs);
        // Validate all pair references resolve.
        for (Pair p : pairs) {
            if (!colors.containsKey(p.foreground())) {
                throw new IllegalArgumentException(
                        "Theme '" + id + "' pair references unknown color: " + p.foreground());
            }
            if (!colors.containsKey(p.background())) {
                throw new IllegalArgumentException(
                        "Theme '" + id + "' pair references unknown color: " + p.background());
            }
        }
    }

    /** Returns the hex value for the named color. */
    public String hex(String colorName) {
        Color c = colors.get(colorName);
        if (c == null) {
            throw new IllegalArgumentException(
                    "Theme '" + id + "' has no color named: " + colorName);
        }
        return c.value();
    }

    /**
     * A single named color in a theme.
     *
     * @param value 6-digit hex color (with or without leading {@code #})
     * @param role  semantic role — one of "background", "foreground",
     *              "accent", "warning", "danger" (free-form string;
     *              consumers may extend)
     */
    public record Color(String value, String role) {
        public Color {
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(role, "role must not be null");
            // Validate value is parseable so loading fails fast on bad data.
            ThemeContrastValidator.parseHexColor(value);
        }
    }

    /**
     * A foreground/background color-name pairing whose contrast must be
     * audited for WCAG conformance.
     */
    public record Pair(String foreground, String background) {
        public Pair {
            Objects.requireNonNull(foreground, "foreground must not be null");
            Objects.requireNonNull(background, "background must not be null");
        }
    }
}
