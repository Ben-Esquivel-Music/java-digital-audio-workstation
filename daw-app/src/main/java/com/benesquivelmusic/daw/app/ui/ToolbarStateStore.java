package com.benesquivelmusic.daw.app.ui;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Persists toolbar state across application sessions using the Java {@link Preferences} API.
 *
 * <p>The following state is persisted:</p>
 * <ul>
 *     <li>Active view ({@link DawView})</li>
 *     <li>Selected edit tool ({@link EditTool})</li>
 *     <li>Snap enabled/disabled</li>
 *     <li>Grid resolution ({@link GridResolution})</li>
 *     <li>Browser panel visibility</li>
 * </ul>
 *
 * <p>Missing or corrupted preference values fall back to sensible defaults:
 * Arrangement view, Pointer tool, snap enabled, Quarter grid, browser hidden.</p>
 *
 * <p>Toolbar collapsed/expanded state is handled by {@link ToolbarCollapseController},
 * visualization preferences by {@link VisualizationPreferences}, and recent projects
 * by {@link com.benesquivelmusic.daw.core.persistence.RecentProjectsStore}.</p>
 */
public final class ToolbarStateStore {

    private static final Logger LOG = Logger.getLogger(ToolbarStateStore.class.getName());

    static final String KEY_ACTIVE_VIEW = "toolbar.activeView";
    static final String KEY_EDIT_TOOL = "toolbar.editTool";
    static final String KEY_SNAP_ENABLED = "toolbar.snapEnabled";
    static final String KEY_GRID_RESOLUTION = "toolbar.gridResolution";
    static final String KEY_BROWSER_VISIBLE = "toolbar.browserVisible";
    static final String KEY_RIPPLE_ALL_TRACKS_PROMPT_SUPPRESSED =
            "toolbar.rippleAllTracksPromptSuppressed";

    static final DawView DEFAULT_ACTIVE_VIEW = DawView.ARRANGEMENT;
    static final EditTool DEFAULT_EDIT_TOOL = EditTool.POINTER;
    static final boolean DEFAULT_SNAP_ENABLED = true;
    static final GridResolution DEFAULT_GRID_RESOLUTION = GridResolution.QUARTER;
    static final boolean DEFAULT_BROWSER_VISIBLE = false;

    private final Preferences prefs;

    /**
     * Creates a new toolbar state store backed by the given {@link Preferences} node.
     *
     * @param prefs the backing preferences node (must not be {@code null})
     */
    public ToolbarStateStore(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs, "prefs must not be null");
    }

    /**
     * Loads the persisted active view, falling back to {@link DawView#ARRANGEMENT}
     * if the stored value is missing or invalid.
     *
     * @return the persisted active view
     */
    public DawView loadActiveView() {
        String value = prefs.get(KEY_ACTIVE_VIEW, DEFAULT_ACTIVE_VIEW.name());
        try {
            return DawView.valueOf(value);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Invalid persisted active view ''{0}'', defaulting to {1}",
                    new Object[]{value, DEFAULT_ACTIVE_VIEW});
            return DEFAULT_ACTIVE_VIEW;
        }
    }

    /**
     * Persists the active view.
     *
     * @param view the view to persist (must not be {@code null})
     */
    public void saveActiveView(DawView view) {
        Objects.requireNonNull(view, "view must not be null");
        prefs.put(KEY_ACTIVE_VIEW, view.name());
    }

    /**
     * Loads the persisted edit tool, falling back to {@link EditTool#POINTER}
     * if the stored value is missing or invalid.
     *
     * @return the persisted edit tool
     */
    public EditTool loadEditTool() {
        String value = prefs.get(KEY_EDIT_TOOL, DEFAULT_EDIT_TOOL.name());
        try {
            return EditTool.valueOf(value);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Invalid persisted edit tool ''{0}'', defaulting to {1}",
                    new Object[]{value, DEFAULT_EDIT_TOOL});
            return DEFAULT_EDIT_TOOL;
        }
    }

    /**
     * Persists the selected edit tool.
     *
     * @param tool the tool to persist (must not be {@code null})
     */
    public void saveEditTool(EditTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        prefs.put(KEY_EDIT_TOOL, tool.name());
    }

    /**
     * Loads the persisted snap-enabled state, defaulting to {@code true}.
     *
     * @return {@code true} if snap was enabled
     */
    public boolean loadSnapEnabled() {
        return prefs.getBoolean(KEY_SNAP_ENABLED, DEFAULT_SNAP_ENABLED);
    }

    /**
     * Persists the snap-enabled state.
     *
     * @param enabled {@code true} if snap is enabled
     */
    public void saveSnapEnabled(boolean enabled) {
        prefs.putBoolean(KEY_SNAP_ENABLED, enabled);
    }

    /**
     * Loads the persisted grid resolution, falling back to {@link GridResolution#QUARTER}
     * if the stored value is missing or invalid.
     *
     * @return the persisted grid resolution
     */
    public GridResolution loadGridResolution() {
        String value = prefs.get(KEY_GRID_RESOLUTION, DEFAULT_GRID_RESOLUTION.name());
        try {
            return GridResolution.valueOf(value);
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Invalid persisted grid resolution ''{0}'', defaulting to {1}",
                    new Object[]{value, DEFAULT_GRID_RESOLUTION});
            return DEFAULT_GRID_RESOLUTION;
        }
    }

    /**
     * Persists the grid resolution.
     *
     * @param resolution the resolution to persist (must not be {@code null})
     */
    public void saveGridResolution(GridResolution resolution) {
        Objects.requireNonNull(resolution, "resolution must not be null");
        prefs.put(KEY_GRID_RESOLUTION, resolution.name());
    }

    /**
     * Loads the persisted browser panel visibility, defaulting to {@code false}.
     *
     * @return {@code true} if the browser panel was visible
     */
    public boolean loadBrowserVisible() {
        return prefs.getBoolean(KEY_BROWSER_VISIBLE, DEFAULT_BROWSER_VISIBLE);
    }

    /**
     * Persists the browser panel visibility.
     *
     * @param visible {@code true} if the browser panel is visible
     */
    public void saveBrowserVisible(boolean visible) {
        prefs.putBoolean(KEY_BROWSER_VISIBLE, visible);
    }

    /**
     * Returns whether the user has suppressed the {@code ALL_TRACKS} ripple-mode
     * confirmation prompt ("don't ask again").
     *
     * @return {@code true} if the prompt should be skipped
     */
    public boolean loadRippleAllTracksPromptSuppressed() {
        return prefs.getBoolean(KEY_RIPPLE_ALL_TRACKS_PROMPT_SUPPRESSED, false);
    }

    /**
     * Persists the "don't ask again" choice for the {@code ALL_TRACKS} ripple
     * confirmation prompt.
     *
     * @param suppressed {@code true} to stop showing the prompt
     */
    public void saveRippleAllTracksPromptSuppressed(boolean suppressed) {
        prefs.putBoolean(KEY_RIPPLE_ALL_TRACKS_PROMPT_SUPPRESSED, suppressed);
    }
}
