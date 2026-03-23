package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;

import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.awt.Desktop;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds and attaches right-click context menus to toolbar sections.
 *
 * <p>Each toolbar section (Views, Project, Tools) gets a context menu
 * providing quick access to related actions and configuration options.
 * Context menus follow the same pattern as the existing
 * {@link VisualizationPanelController} and track-item context menus
 * in {@link MainController}.</p>
 *
 * <p>The Visualizations button context menu is handled separately by
 * {@link VisualizationPanelController}.</p>
 */
public final class ToolbarContextMenuController {

    private static final Logger LOG = Logger.getLogger(ToolbarContextMenuController.class.getName());
    private static final double MENU_ICON_SIZE = 14;
    private static final DateTimeFormatter METADATA_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private final Button[] viewButtons;
    private final Button[] projectButtons;
    private final Button[] toolButtons;
    private final ProjectManager projectManager;
    private final Consumer<String> statusUpdater;
    private final Runnable resetViewLayoutAction;
    private final Consumer<Path> loadProjectAction;

    private ContextMenu viewContextMenu;
    private ContextMenu projectContextMenu;
    private ContextMenu toolsContextMenu;

    /**
     * Creates a new toolbar context menu controller.
     *
     * @param viewButtons          the sidebar view navigation buttons
     *                             (Arrangement, Mixer, Editor)
     * @param projectButtons       the sidebar project management buttons
     *                             (New, Open, Save, Recent)
     * @param toolButtons          the sidebar tool buttons
     *                             (Plugins, Visualizations, Settings)
     * @param projectManager       the project manager for recent project access
     * @param statusUpdater        callback to update the status bar text
     * @param resetViewLayoutAction action to reset the view layout to defaults
     * @param loadProjectAction    action to load a project from a given path
     */
    public ToolbarContextMenuController(Button[] viewButtons,
                                        Button[] projectButtons,
                                        Button[] toolButtons,
                                        ProjectManager projectManager,
                                        Consumer<String> statusUpdater,
                                        Runnable resetViewLayoutAction,
                                        Consumer<Path> loadProjectAction) {
        this.viewButtons = Objects.requireNonNull(viewButtons, "viewButtons must not be null");
        this.projectButtons = Objects.requireNonNull(projectButtons, "projectButtons must not be null");
        this.toolButtons = Objects.requireNonNull(toolButtons, "toolButtons must not be null");
        this.projectManager = Objects.requireNonNull(projectManager, "projectManager must not be null");
        this.statusUpdater = Objects.requireNonNull(statusUpdater, "statusUpdater must not be null");
        this.resetViewLayoutAction = Objects.requireNonNull(resetViewLayoutAction,
                "resetViewLayoutAction must not be null");
        this.loadProjectAction = Objects.requireNonNull(loadProjectAction,
                "loadProjectAction must not be null");
    }

    /**
     * Attaches context menus to all toolbar section buttons.
     * Must be called after the UI is fully constructed.
     */
    public void initialize() {
        for (Button button : viewButtons) {
            button.setOnContextMenuRequested(event -> {
                showViewContextMenu(button);
                event.consume();
            });
        }
        for (Button button : projectButtons) {
            button.setOnContextMenuRequested(event -> {
                showProjectContextMenu(button);
                event.consume();
            });
        }
        for (Button button : toolButtons) {
            button.setOnContextMenuRequested(event -> {
                showToolsContextMenu(button);
                event.consume();
            });
        }
        LOG.fine("Toolbar context menus initialized");
    }

    // ── Views section context menu ──────────────────────────────────────────

    /**
     * Shows the views section context menu anchored to the given button.
     *
     * @param anchor the button to anchor the menu to
     */
    void showViewContextMenu(Button anchor) {
        if (viewContextMenu != null) {
            viewContextMenu.hide();
        }
        viewContextMenu = buildViewContextMenu();
        viewContextMenu.show(anchor, Side.RIGHT, 0, 0);
    }

    /**
     * Returns the current views section context menu, or {@code null} if
     * none has been shown yet.
     *
     * @return the views context menu
     */
    ContextMenu getViewContextMenu() {
        return viewContextMenu;
    }

    ContextMenu buildViewContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem resetLayoutItem = new MenuItem("Reset View Layout");
        resetLayoutItem.setGraphic(IconNode.of(DawIcon.SYNC, MENU_ICON_SIZE));
        resetLayoutItem.setOnAction(event -> {
            resetViewLayoutAction.run();
            statusUpdater.accept("View layout reset to defaults");
        });

        MenuItem detachViewItem = new MenuItem("Detach View");
        detachViewItem.setGraphic(IconNode.of(DawIcon.PIP, MENU_ICON_SIZE));
        detachViewItem.setDisable(true);

        menu.getItems().addAll(resetLayoutItem, new SeparatorMenuItem(), detachViewItem);
        return menu;
    }

    // ── Project section context menu ────────────────────────────────────────

    /**
     * Shows the project section context menu anchored to the given button.
     *
     * @param anchor the button to anchor the menu to
     */
    void showProjectContextMenu(Button anchor) {
        if (projectContextMenu != null) {
            projectContextMenu.hide();
        }
        projectContextMenu = buildProjectContextMenu();
        projectContextMenu.show(anchor, Side.RIGHT, 0, 0);
    }

    /**
     * Returns the current project section context menu, or {@code null} if
     * none has been shown yet.
     *
     * @return the project context menu
     */
    ContextMenu getProjectContextMenu() {
        return projectContextMenu;
    }

    ContextMenu buildProjectContextMenu() {
        ContextMenu menu = new ContextMenu();

        // Recent projects list
        List<Path> recentPaths = projectManager.getRecentProjectPaths();
        if (recentPaths.isEmpty()) {
            MenuItem emptyItem = new MenuItem("No recent projects");
            emptyItem.setGraphic(IconNode.of(DawIcon.HISTORY, MENU_ICON_SIZE));
            emptyItem.setDisable(true);
            menu.getItems().add(emptyItem);
        } else {
            for (Path path : recentPaths) {
                MenuItem item = new MenuItem(path.getFileName().toString());
                item.setGraphic(IconNode.of(DawIcon.FOLDER, MENU_ICON_SIZE));
                item.setOnAction(event -> loadProjectAction.accept(path));
                menu.getItems().add(item);
            }
            MenuItem clearItem = new MenuItem("Clear Recent Projects");
            clearItem.setGraphic(IconNode.of(DawIcon.DELETE, MENU_ICON_SIZE));
            clearItem.setOnAction(event -> {
                RecentProjectsStore store = projectManager.getRecentProjectsStore();
                if (store != null) {
                    store.clear();
                }
                statusUpdater.accept("Recent projects cleared");
            });
            menu.getItems().addAll(new SeparatorMenuItem(), clearItem);
        }

        menu.getItems().add(new SeparatorMenuItem());

        // Reveal in File Manager
        MenuItem revealItem = new MenuItem("Reveal in File Manager");
        revealItem.setGraphic(IconNode.of(DawIcon.FOLDER, MENU_ICON_SIZE));
        ProjectMetadata currentProject = projectManager.getCurrentProject();
        if (currentProject == null || currentProject.projectPath() == null) {
            revealItem.setDisable(true);
        } else {
            revealItem.setOnAction(event -> revealInFileManager(currentProject.projectPath()));
        }

        // Project Properties
        MenuItem propertiesItem = new MenuItem("Project Properties");
        propertiesItem.setGraphic(IconNode.of(DawIcon.INFO, MENU_ICON_SIZE));
        if (currentProject == null) {
            propertiesItem.setDisable(true);
        } else {
            propertiesItem.setOnAction(event -> showProjectProperties(currentProject));
        }

        menu.getItems().addAll(revealItem, new SeparatorMenuItem(), propertiesItem);
        return menu;
    }

    // ── Tools section context menu ──────────────────────────────────────────

    /**
     * Shows the tools section context menu anchored to the given button.
     *
     * @param anchor the button to anchor the menu to
     */
    void showToolsContextMenu(Button anchor) {
        if (toolsContextMenu != null) {
            toolsContextMenu.hide();
        }
        toolsContextMenu = buildToolsContextMenu();
        toolsContextMenu.show(anchor, Side.RIGHT, 0, 0);
    }

    /**
     * Returns the current tools section context menu, or {@code null} if
     * none has been shown yet.
     *
     * @return the tools context menu
     */
    ContextMenu getToolsContextMenu() {
        return toolsContextMenu;
    }

    ContextMenu buildToolsContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem customizeItem = new MenuItem("Customize Tools");
        customizeItem.setGraphic(IconNode.of(DawIcon.SETTINGS, MENU_ICON_SIZE));
        customizeItem.setDisable(true);

        menu.getItems().add(customizeItem);
        return menu;
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private void revealInFileManager(Path projectPath) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(projectPath.toFile());
                statusUpdater.accept("Opened: " + projectPath.getFileName());
            } else {
                statusUpdater.accept("File manager not available on this platform");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to open file manager for " + projectPath, e);
            statusUpdater.accept("Failed to open file manager");
        }
    }

    private void showProjectProperties(ProjectMetadata metadata) {
        String info = metadata.name()
                + "  ·  Created: " + METADATA_FORMATTER.format(metadata.createdAt())
                + "  ·  Modified: " + METADATA_FORMATTER.format(metadata.lastModified());
        if (metadata.projectPath() != null) {
            info += "  ·  " + metadata.projectPath();
        }
        statusUpdater.accept(info);
    }
}
