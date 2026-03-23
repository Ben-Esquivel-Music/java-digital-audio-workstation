package com.benesquivelmusic.daw.app.ui;

import java.util.EnumMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Persists visualization panel visibility state across application restarts.
 *
 * <p>Each display tile can be shown or hidden independently, and the entire
 * visualization row can be toggled on or off. State is backed by the Java
 * {@link Preferences} API so changes survive process restarts.</p>
 */
public final class VisualizationPreferences {

    /** Identifiers for each visualization display tile. */
    public enum DisplayTile {
        SPECTRUM,
        LEVELS,
        WAVEFORM,
        LOUDNESS,
        CORRELATION
    }

    private static final String KEY_ROW_VISIBLE = "vizRowVisible";

    private final Preferences prefs;
    private boolean rowVisible;
    private final Map<DisplayTile, Boolean> tileVisibility;

    /**
     * Creates a new preferences instance backed by the given {@link Preferences} node.
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public VisualizationPreferences(Preferences prefs) {
        if (prefs == null) {
            throw new NullPointerException("prefs must not be null");
        }
        this.prefs = prefs;
        this.tileVisibility = new EnumMap<>(DisplayTile.class);
        load();
    }

    private void load() {
        rowVisible = prefs.getBoolean(KEY_ROW_VISIBLE, true);
        for (DisplayTile tile : DisplayTile.values()) {
            boolean visible = prefs.getBoolean(prefKeyFor(tile), true);
            tileVisibility.put(tile, visible);
        }
    }

    /**
     * Returns whether the entire visualization row is visible.
     */
    public boolean isRowVisible() {
        return rowVisible;
    }

    /**
     * Sets whether the entire visualization row is visible and persists the change.
     */
    public void setRowVisible(boolean visible) {
        this.rowVisible = visible;
        prefs.putBoolean(KEY_ROW_VISIBLE, visible);
    }

    /**
     * Returns whether the given display tile is visible.
     */
    public boolean isTileVisible(DisplayTile tile) {
        return tileVisibility.getOrDefault(tile, true);
    }

    /**
     * Sets whether the given display tile is visible and persists the change.
     */
    public void setTileVisible(DisplayTile tile, boolean visible) {
        tileVisibility.put(tile, visible);
        prefs.putBoolean(prefKeyFor(tile), visible);
    }

    /**
     * Shows all tiles and persists the change.
     */
    public void showAll() {
        for (DisplayTile tile : DisplayTile.values()) {
            setTileVisible(tile, true);
        }
    }

    /**
     * Hides all tiles and persists the change.
     */
    public void hideAll() {
        for (DisplayTile tile : DisplayTile.values()) {
            setTileVisible(tile, false);
        }
    }

    /**
     * Resets all preferences to defaults (row visible, all tiles visible) and persists.
     */
    public void resetToDefaults() {
        setRowVisible(true);
        showAll();
    }

    private static String prefKeyFor(DisplayTile tile) {
        return "vizTile_" + tile.name();
    }
}
