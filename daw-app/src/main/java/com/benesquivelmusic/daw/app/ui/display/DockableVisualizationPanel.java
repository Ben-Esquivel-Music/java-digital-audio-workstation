package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Thin titled-tile adapter that wraps a single analyzer {@link Region}
 * (typically a {@link GpuCanvasView} subclass) so it can participate in
 * the {@code DockManager} system as a first-class {@link Dockable} panel
 * (story 287).
 *
 * <p>This is a one-off application layout node, so it is a plain
 * {@link VBox} subclass rather than a {@code Control}/{@code Skin} pair
 * (JavaFX design skill §3 / §16). It contributes only chrome — an accent
 * header label with an icon glyph above the wrapped display — and never
 * touches the display's DSP or pixel output. The header reuses the
 * existing {@code viz-tile} / {@code viz-tile-label} style classes plus
 * the per-tile accent class so the docked panel is visually identical to
 * the bottom-row tile it replaces.</p>
 *
 * <p>The wrapped display is set to grow to fill the available vertical
 * space; the panel as a whole grows to fill its dock slot. The four
 * {@link Dockable} methods are supplied verbatim from the constructor
 * arguments so the same id / display-name / icon / preferred-zone flow
 * through to the dock manifest and the View → Layout persistence.</p>
 */
public final class DockableVisualizationPanel extends VBox implements Dockable {

    private final String dockId;
    private final String displayName;
    private final String iconName;
    private final DockZone preferredZone;
    private final Region content;

    /**
     * Creates a dockable tile wrapping {@code content}.
     *
     * @param dockId        stable persistence id (see {@code DefaultWorkspaces})
     * @param displayName   human-readable name for tabs / menus / manifest
     * @param iconName      icon-registry key ({@link DawIcon} name)
     * @param preferredZone default dock zone on first show
     * @param icon          glyph rendered next to the header label
     * @param accentStyleClass per-tile header accent class
     *                         (e.g. {@code tile-header-accent-green})
     * @param content       the analyzer display to host (never {@code null})
     */
    public DockableVisualizationPanel(String dockId,
                                      String displayName,
                                      String iconName,
                                      DockZone preferredZone,
                                      DawIcon icon,
                                      String accentStyleClass,
                                      Region content) {
        this.dockId = Objects.requireNonNull(dockId, "dockId must not be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.iconName = iconName == null ? "" : iconName;
        this.preferredZone = Objects.requireNonNull(preferredZone, "preferredZone must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");

        getStyleClass().add("viz-tile");
        setSpacing(4);
        setPadding(new Insets(8));

        Label header = new Label(displayName.toUpperCase(java.util.Locale.ROOT));
        header.getStyleClass().add("viz-tile-label");
        if (accentStyleClass != null && !accentStyleClass.isBlank()) {
            header.getStyleClass().add(accentStyleClass);
        }
        if (icon != null) {
            header.setGraphic(IconNode.of(icon, 12));
        }

        content.setMinHeight(0);
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(header, content);
    }

    /** Returns the wrapped analyzer display node. */
    public Region content() {
        return content;
    }

    @Override public String dockId()          { return dockId; }
    @Override public String displayName()     { return displayName; }
    @Override public String iconName()        { return iconName; }
    @Override public DockZone preferredZone() { return preferredZone; }
}
