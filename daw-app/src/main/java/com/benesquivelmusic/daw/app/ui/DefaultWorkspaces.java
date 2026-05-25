package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable identifiers and factory methods for the six default workspaces
 * shipped with the DAW: <em>Tracking</em>, <em>Editing</em>,
 * <em>Mixing</em>, <em>Mastering</em>, <em>Spatial</em>, and
 * <em>Minimal</em>.
 *
 * <p>Each default workspace is a reasonable starting point — users can
 * still tweak it and "Save Current as…" to produce their own variant.</p>
 *
 * <p>Panel-id constants mirror the menu entries already exposed by
 * {@code DawMenuBarController}: {@code "browser"}, {@code "history"},
 * {@code "notifications"}, {@code "visualizations"}, plus the four
 * primary views {@code "arrangement"}, {@code "mixer"},
 * {@code "editor"}, {@code "mastering"}.</p>
 */
public final class DefaultWorkspaces {

    /** Browser side panel (sample/preset library). */
    public static final String PANEL_BROWSER = "browser";
    /** Undo history side panel. */
    public static final String PANEL_HISTORY = "history";
    /** Notification history panel. */
    public static final String PANEL_NOTIFICATIONS = "notifications";
    /** Visualization (spectrum/scope) panel. */
    public static final String PANEL_VISUALIZATIONS = "visualizations";
    /** Primary arrangement timeline view. */
    public static final String PANEL_ARRANGEMENT = "arrangement";
    /** Mixer (channel-strip) view. */
    public static final String PANEL_MIXER = "mixer";
    /** Clip editor (audio/MIDI) view. */
    public static final String PANEL_EDITOR = "editor";
    /** Mastering chain view. */
    public static final String PANEL_MASTERING = "mastering";
    /** Spatial / immersive panner view. */
    public static final String PANEL_SPATIAL = "spatial";
    /** Loudness / LUFS metering panel. */
    public static final String PANEL_LOUDNESS = "loudness";

    private DefaultWorkspaces() { }

    /** Returns the six default workspaces in their preferred display order. */
    public static List<Workspace> all() {
        return List.of(tracking(), editing(), mixing(), mastering(), spatial(), minimal());
    }

    /**
     * Tracking: arrangement and transport are dominant; mixer minimised.
     * Browser is open so users can grab samples while recording.
     */
    public static Workspace tracking() {
        Map<String, PanelState> panels = orderedMap();
        panels.put(PANEL_ARRANGEMENT, new PanelState(true, 1.0, 0.0, 0.0));
        panels.put(PANEL_MIXER, PanelState.HIDDEN);
        panels.put(PANEL_EDITOR, PanelState.HIDDEN);
        panels.put(PANEL_MASTERING, PanelState.HIDDEN);
        panels.put(PANEL_BROWSER, PanelState.DEFAULT);
        panels.put(PANEL_HISTORY, PanelState.HIDDEN);
        panels.put(PANEL_NOTIFICATIONS, PanelState.HIDDEN);
        panels.put(PANEL_VISUALIZATIONS, PanelState.HIDDEN);
        return new Workspace("Tracking", panels, List.of(), Map.of());
    }

    /** Editing: clip editor takes the center stage with arrangement still visible. */
    public static Workspace editing() {
        Map<String, PanelState> panels = orderedMap();
        panels.put(PANEL_ARRANGEMENT, new PanelState(true, 1.5, 0.0, 0.0));
        panels.put(PANEL_EDITOR, PanelState.DEFAULT);
        panels.put(PANEL_MIXER, PanelState.HIDDEN);
        panels.put(PANEL_MASTERING, PanelState.HIDDEN);
        panels.put(PANEL_BROWSER, PanelState.HIDDEN);
        panels.put(PANEL_HISTORY, PanelState.DEFAULT);
        panels.put(PANEL_NOTIFICATIONS, PanelState.HIDDEN);
        panels.put(PANEL_VISUALIZATIONS, PanelState.HIDDEN);
        return new Workspace("Editing", panels, List.of(), Map.of());
    }

    /** Mixing: mixer front and center, arrangement minimal. */
    public static Workspace mixing() {
        Map<String, PanelState> panels = orderedMap();
        panels.put(PANEL_MIXER, PanelState.DEFAULT);
        panels.put(PANEL_ARRANGEMENT, PanelState.HIDDEN);
        panels.put(PANEL_EDITOR, PanelState.HIDDEN);
        panels.put(PANEL_MASTERING, PanelState.HIDDEN);
        panels.put(PANEL_BROWSER, PanelState.HIDDEN);
        panels.put(PANEL_HISTORY, PanelState.HIDDEN);
        panels.put(PANEL_NOTIFICATIONS, PanelState.HIDDEN);
        panels.put(PANEL_VISUALIZATIONS, PanelState.DEFAULT);
        return new Workspace("Mixing", panels, List.of(), Map.of());
    }

    /** Mastering: mastering chain prominent with loudness/visualization meters. */
    public static Workspace mastering() {
        Map<String, PanelState> panels = orderedMap();
        panels.put(PANEL_MASTERING, PanelState.DEFAULT);
        panels.put(PANEL_LOUDNESS, PanelState.DEFAULT);
        panels.put(PANEL_VISUALIZATIONS, PanelState.DEFAULT);
        panels.put(PANEL_ARRANGEMENT, PanelState.HIDDEN);
        panels.put(PANEL_MIXER, PanelState.HIDDEN);
        panels.put(PANEL_EDITOR, PanelState.HIDDEN);
        panels.put(PANEL_BROWSER, PanelState.HIDDEN);
        panels.put(PANEL_HISTORY, PanelState.HIDDEN);
        panels.put(PANEL_NOTIFICATIONS, PanelState.HIDDEN);
        return new Workspace("Mastering", panels, List.of(), Map.of());
    }

    /** Spatial: immersive/Atmos panner with arrangement context for object automation. */
    public static Workspace spatial() {
        Map<String, PanelState> panels = orderedMap();
        panels.put(PANEL_SPATIAL, PanelState.DEFAULT);
        panels.put(PANEL_ARRANGEMENT, PanelState.DEFAULT);
        panels.put(PANEL_MIXER, PanelState.HIDDEN);
        panels.put(PANEL_EDITOR, PanelState.HIDDEN);
        panels.put(PANEL_MASTERING, PanelState.HIDDEN);
        panels.put(PANEL_BROWSER, PanelState.HIDDEN);
        panels.put(PANEL_HISTORY, PanelState.HIDDEN);
        panels.put(PANEL_NOTIFICATIONS, PanelState.HIDDEN);
        panels.put(PANEL_VISUALIZATIONS, PanelState.HIDDEN);
        return new Workspace("Spatial", panels, List.of(), Map.of());
    }

    /** Minimal: only the arrangement is visible. Useful for distraction-free work. */
    public static Workspace minimal() {
        Map<String, PanelState> panels = orderedMap();
        panels.put(PANEL_ARRANGEMENT, PanelState.DEFAULT);
        panels.put(PANEL_MIXER, PanelState.HIDDEN);
        panels.put(PANEL_EDITOR, PanelState.HIDDEN);
        panels.put(PANEL_MASTERING, PanelState.HIDDEN);
        panels.put(PANEL_BROWSER, PanelState.HIDDEN);
        panels.put(PANEL_HISTORY, PanelState.HIDDEN);
        panels.put(PANEL_NOTIFICATIONS, PanelState.HIDDEN);
        panels.put(PANEL_VISUALIZATIONS, PanelState.HIDDEN);
        return new Workspace("Minimal", panels, List.of(), Map.of());
    }

    /**
     * Returns the panel-id list that corresponds to currently implemented UI components.
     * Ids for panels not yet wired in {@code MainController} (spatial, loudness) are
     * excluded — they will be added once those UI components exist.
     */
    public static List<String> panelIds() {
        return List.of(
                PANEL_ARRANGEMENT, PANEL_MIXER, PANEL_EDITOR, PANEL_MASTERING,
                PANEL_BROWSER, PANEL_HISTORY, PANEL_NOTIFICATIONS, PANEL_VISUALIZATIONS);
    }

    /**
     * Convenience helper for callers that want a {@link Rectangle2D} of zero
     * size (used as a placeholder bounds entry in factory workspaces).
     */
    public static Rectangle2D zeroBounds() {
        return new Rectangle2D(0, 0, 0, 0);
    }

    private static Map<String, PanelState> orderedMap() {
        return new LinkedHashMap<>();
    }
}
