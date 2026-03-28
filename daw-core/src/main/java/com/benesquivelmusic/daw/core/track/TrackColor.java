package com.benesquivelmusic.daw.core.track;

import java.util.Objects;

/**
 * Represents a color that can be assigned to a track for visual organization.
 *
 * <p>A palette of {@value #PALETTE_SIZE} predefined colors is provided for quick
 * selection. Custom colors can be created from any valid hex color string
 * (e.g., {@code "#FF5733"}).</p>
 *
 * <p>Predefined palette colors cycle automatically when new tracks are created
 * via {@link com.benesquivelmusic.daw.core.project.DawProject}.</p>
 */
public final class TrackColor {

    /** The number of predefined palette colors. */
    public static final int PALETTE_SIZE = 16;

    // ── Predefined palette ──────────────────────────────────────────────────

    public static final TrackColor RED = new TrackColor("#E74C3C", "Red", 0);
    public static final TrackColor ORANGE = new TrackColor("#E67E22", "Orange", 1);
    public static final TrackColor AMBER = new TrackColor("#F39C12", "Amber", 2);
    public static final TrackColor YELLOW = new TrackColor("#F1C40F", "Yellow", 3);
    public static final TrackColor LIME = new TrackColor("#2ECC71", "Lime", 4);
    public static final TrackColor GREEN = new TrackColor("#27AE60", "Green", 5);
    public static final TrackColor TEAL = new TrackColor("#1ABC9C", "Teal", 6);
    public static final TrackColor CYAN = new TrackColor("#00BCD4", "Cyan", 7);
    public static final TrackColor SKY_BLUE = new TrackColor("#3498DB", "Sky Blue", 8);
    public static final TrackColor BLUE = new TrackColor("#2980B9", "Blue", 9);
    public static final TrackColor INDIGO = new TrackColor("#3F51B5", "Indigo", 10);
    public static final TrackColor PURPLE = new TrackColor("#9B59B6", "Purple", 11);
    public static final TrackColor MAGENTA = new TrackColor("#E91E63", "Magenta", 12);
    public static final TrackColor PINK = new TrackColor("#FF69B4", "Pink", 13);
    public static final TrackColor BROWN = new TrackColor("#795548", "Brown", 14);
    public static final TrackColor SLATE = new TrackColor("#607D8B", "Slate", 15);

    private static final TrackColor[] PALETTE = {
            RED, ORANGE, AMBER, YELLOW, LIME, GREEN, TEAL, CYAN,
            SKY_BLUE, BLUE, INDIGO, PURPLE, MAGENTA, PINK, BROWN, SLATE
    };

    private final String hexColor;
    private final String displayName;
    private final int paletteIndex;

    private TrackColor(String hexColor, String displayName, int paletteIndex) {
        this.hexColor = hexColor;
        this.displayName = displayName;
        this.paletteIndex = paletteIndex;
    }

    /**
     * Creates a custom track color from a hex color string.
     *
     * @param hexColor    the hex color string (e.g., {@code "#FF5733"})
     * @param displayName the display name for this color
     * @return a new custom track color
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if hexColor is not a valid hex color
     */
    public static TrackColor custom(String hexColor, String displayName) {
        Objects.requireNonNull(hexColor, "hexColor must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (!isValidHexColor(hexColor)) {
            throw new IllegalArgumentException("invalid hex color: " + hexColor);
        }
        return new TrackColor(hexColor.toUpperCase(), displayName, -1);
    }

    /**
     * Returns the predefined palette color at the given index.
     *
     * @param index the palette index (0-based, wraps around)
     * @return the palette color
     */
    public static TrackColor fromPaletteIndex(int index) {
        return PALETTE[Math.floorMod(index, PALETTE_SIZE)];
    }

    /**
     * Returns the predefined palette as an array. The returned array is a
     * defensive copy.
     *
     * @return a copy of the palette array
     */
    public static TrackColor[] palette() {
        return PALETTE.clone();
    }

    /**
     * Finds a palette color matching the given hex color string, or creates a
     * custom color if no match is found.
     *
     * @param hexColor the hex color string
     * @return the matching palette color, or a custom color
     * @throws NullPointerException     if hexColor is {@code null}
     * @throws IllegalArgumentException if hexColor is not a valid hex color
     */
    public static TrackColor fromHex(String hexColor) {
        Objects.requireNonNull(hexColor, "hexColor must not be null");
        if (!isValidHexColor(hexColor)) {
            throw new IllegalArgumentException("invalid hex color: " + hexColor);
        }
        String normalized = hexColor.toUpperCase();
        for (TrackColor color : PALETTE) {
            if (color.hexColor.equals(normalized)) {
                return color;
            }
        }
        return new TrackColor(normalized, "Custom", -1);
    }

    /** Returns the hex color string (e.g., {@code "#E74C3C"}). */
    public String getHexColor() {
        return hexColor;
    }

    /** Returns the display name (e.g., {@code "Red"} or {@code "Custom"}). */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the palette index of this color, or {@code -1} if this is a
     * custom color.
     *
     * @return the palette index, or {@code -1}
     */
    public int getPaletteIndex() {
        return paletteIndex;
    }

    /** Returns {@code true} if this color is one of the predefined palette colors. */
    public boolean isPaletteColor() {
        return paletteIndex >= 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TrackColor other)) {
            return false;
        }
        return hexColor.equals(other.hexColor);
    }

    @Override
    public int hashCode() {
        return hexColor.hashCode();
    }

    @Override
    public String toString() {
        return displayName + " (" + hexColor + ")";
    }

    private static boolean isValidHexColor(String color) {
        if (color == null || color.length() != 7 || color.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < 7; i++) {
            char c = color.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')
                    || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
