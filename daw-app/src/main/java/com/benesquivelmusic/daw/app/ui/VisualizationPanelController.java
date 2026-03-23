package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.VisualizationPreferences.DisplayTile;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages the visualization panel toggle button and its context menu.
 *
 * <p>Single-click on the toolbar button toggles the entire visualization row
 * visibility. Right-click opens a context menu for per-display show/hide
 * configuration, "Show All", "Hide All", and "Reset Layout" options.</p>
 */
public final class VisualizationPanelController {

    private static final Logger LOG = Logger.getLogger(VisualizationPanelController.class.getName());
    private static final Duration ANIMATION_DURATION = Duration.millis(250);
    private static final double DEFAULT_ROW_HEIGHT = 120.0;

    private final HBox vizTileRow;
    private final Button visualizationsButton;
    private final VisualizationPreferences preferences;
    private final Map<DisplayTile, Node> tileLookup;
    private ContextMenu contextMenu;

    /**
     * Creates a new controller wiring the given UI components with the given preferences.
     *
     * @param vizTileRow           the row container holding visualization tiles
     * @param visualizationsButton the toolbar button that toggles the row
     * @param preferences          the preferences backing persistence
     * @param tileLookup           maps each {@link DisplayTile} to its JavaFX node in the row
     */
    public VisualizationPanelController(HBox vizTileRow,
                                        Button visualizationsButton,
                                        VisualizationPreferences preferences,
                                        Map<DisplayTile, Node> tileLookup) {
        if (vizTileRow == null) {
            throw new NullPointerException("vizTileRow must not be null");
        }
        if (visualizationsButton == null) {
            throw new NullPointerException("visualizationsButton must not be null");
        }
        if (preferences == null) {
            throw new NullPointerException("preferences must not be null");
        }
        if (tileLookup == null) {
            throw new NullPointerException("tileLookup must not be null");
        }
        this.vizTileRow = vizTileRow;
        this.visualizationsButton = visualizationsButton;
        this.preferences = preferences;
        this.tileLookup = new EnumMap<>(tileLookup);
    }

    /**
     * Initializes button handlers and applies persisted visibility state.
     * Must be called after the UI is fully constructed.
     */
    public void initialize() {
        // Apply persisted row visibility
        applyRowVisibility(preferences.isRowVisible(), false);

        // Apply persisted per-tile visibility
        for (DisplayTile tile : DisplayTile.values()) {
            Node node = tileLookup.get(tile);
            if (node != null) {
                boolean visible = preferences.isTileVisible(tile);
                node.setVisible(visible);
                node.setManaged(visible);
            }
        }

        // Single click toggles row visibility
        visualizationsButton.setOnAction(event -> toggleRowVisibility());

        // Right-click opens context menu
        visualizationsButton.setOnContextMenuRequested(event -> {
            showContextMenu();
            event.consume();
        });

        updateButtonActiveState();
        LOG.fine("Visualization panel controller initialized");
    }

    /**
     * Toggles the visibility of the entire visualization row with animation.
     */
    void toggleRowVisibility() {
        boolean nowVisible = !preferences.isRowVisible();
        preferences.setRowVisible(nowVisible);
        applyRowVisibility(nowVisible, true);
        updateButtonActiveState();
        LOG.fine(() -> "Visualization row toggled: " + (nowVisible ? "visible" : "hidden"));
    }

    /**
     * Shows the context menu with per-tile checkboxes and utility actions.
     */
    void showContextMenu() {
        if (contextMenu != null) {
            contextMenu.hide();
        }
        contextMenu = buildContextMenu();
        contextMenu.show(visualizationsButton, Side.RIGHT, 0, 0);
    }

    /**
     * Returns the current context menu, or {@code null} if none has been shown yet.
     */
    ContextMenu getContextMenu() {
        return contextMenu;
    }

    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();

        // Per-tile checkboxes
        DisplayTile[] tiles = DisplayTile.values();
        String[] labels = {"Spectrum", "Levels", "Waveform", "Loudness", "Correlation"};
        DawIcon[] icons = {
                DawIcon.SPECTRUM, DawIcon.PEAK, DawIcon.OSCILLOSCOPE,
                DawIcon.LOUDNESS_METER, DawIcon.PHASE_METER
        };

        CheckMenuItem[] checkItems = new CheckMenuItem[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            DisplayTile tile = tiles[i];
            CheckMenuItem checkItem = new CheckMenuItem(labels[i]);
            checkItem.setGraphic(IconNode.of(icons[i], 14));
            checkItem.setSelected(preferences.isTileVisible(tile));
            checkItem.setOnAction(event -> {
                boolean selected = checkItem.isSelected();
                preferences.setTileVisible(tile, selected);
                Node node = tileLookup.get(tile);
                if (node != null) {
                    node.setVisible(selected);
                    node.setManaged(selected);
                }
            });
            checkItems[i] = checkItem;
            menu.getItems().add(checkItem);
        }

        menu.getItems().add(new SeparatorMenuItem());

        // Show All
        MenuItem showAllItem = new MenuItem("Show All");
        showAllItem.setGraphic(IconNode.of(DawIcon.EXPAND, 14));
        showAllItem.setOnAction(event -> {
            preferences.showAll();
            for (int i = 0; i < tiles.length; i++) {
                checkItems[i].setSelected(true);
                Node node = tileLookup.get(tiles[i]);
                if (node != null) {
                    node.setVisible(true);
                    node.setManaged(true);
                }
            }
            if (!preferences.isRowVisible()) {
                preferences.setRowVisible(true);
                applyRowVisibility(true, true);
                updateButtonActiveState();
            }
        });
        menu.getItems().add(showAllItem);

        // Hide All
        MenuItem hideAllItem = new MenuItem("Hide All");
        hideAllItem.setGraphic(IconNode.of(DawIcon.COLLAPSE, 14));
        hideAllItem.setOnAction(event -> {
            preferences.hideAll();
            for (int i = 0; i < tiles.length; i++) {
                checkItems[i].setSelected(false);
                Node node = tileLookup.get(tiles[i]);
                if (node != null) {
                    node.setVisible(false);
                    node.setManaged(false);
                }
            }
        });
        menu.getItems().add(hideAllItem);

        menu.getItems().add(new SeparatorMenuItem());

        // Reset Layout
        MenuItem resetItem = new MenuItem("Reset Layout");
        resetItem.setGraphic(IconNode.of(DawIcon.SYNC, 14));
        resetItem.setOnAction(event -> {
            preferences.resetToDefaults();
            for (int i = 0; i < tiles.length; i++) {
                checkItems[i].setSelected(true);
                Node node = tileLookup.get(tiles[i]);
                if (node != null) {
                    node.setVisible(true);
                    node.setManaged(true);
                }
            }
            applyRowVisibility(true, true);
            updateButtonActiveState();
        });
        menu.getItems().add(resetItem);

        return menu;
    }

    private void applyRowVisibility(boolean visible, boolean animate) {
        if (animate) {
            animateRowVisibility(visible);
        } else {
            vizTileRow.setVisible(visible);
            vizTileRow.setManaged(visible);
            if (visible) {
                vizTileRow.setPrefHeight(DEFAULT_ROW_HEIGHT);
                vizTileRow.setOpacity(1.0);
            } else {
                vizTileRow.setPrefHeight(0);
                vizTileRow.setOpacity(0.0);
            }
        }
    }

    private void animateRowVisibility(boolean visible) {
        if (visible) {
            vizTileRow.setVisible(true);
            vizTileRow.setManaged(true);
            vizTileRow.setPrefHeight(0);
            vizTileRow.setOpacity(0.0);

            Timeline timeline = new Timeline(
                    new KeyFrame(ANIMATION_DURATION,
                            new KeyValue(vizTileRow.prefHeightProperty(), DEFAULT_ROW_HEIGHT),
                            new KeyValue(vizTileRow.opacityProperty(), 1.0))
            );
            timeline.play();
        } else {
            Timeline timeline = new Timeline(
                    new KeyFrame(ANIMATION_DURATION,
                            new KeyValue(vizTileRow.prefHeightProperty(), 0),
                            new KeyValue(vizTileRow.opacityProperty(), 0.0))
            );
            timeline.setOnFinished(event -> {
                vizTileRow.setVisible(false);
                vizTileRow.setManaged(false);
            });
            timeline.play();
        }
    }

    private void updateButtonActiveState() {
        List<String> styles = visualizationsButton.getStyleClass();
        if (preferences.isRowVisible()) {
            if (!styles.contains("toolbar-button-active")) {
                styles.add("toolbar-button-active");
            }
        } else {
            styles.remove("toolbar-button-active");
        }
    }
}
