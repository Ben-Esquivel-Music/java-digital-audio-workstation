package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.*;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Builds the five visualization tiles (spectrum, peak/rms, waveform, loudness,
 * correlation) for the main window's visualization row, and wires up a
 * {@link VisualizationPanelController} for toggle/context-menu control.
 *
 * <p>Extracted from {@code MainController} to keep the main coordinator
 * free of visualization UI assembly.</p>
 */
final class VisualizationTileBuilder {

    /** Result of building the visualization row, containing references the host may need later. */
    record Result(
            SpectrumDisplay spectrumDisplay,
            LevelMeterDisplay levelMeterDisplay,
            VisualizationPanelController panelController) {}

    private VisualizationTileBuilder() {}

    static Result build(HBox vizTileRow) {
        vizTileRow.setPrefHeight(120);
        vizTileRow.setMinHeight(100);

        SpectrumDisplay spectrumDisplay = new SpectrumDisplay();
        LevelMeterDisplay levelMeterDisplay = new LevelMeterDisplay();
        WaveformDisplay waveformDisplay = new WaveformDisplay();
        LoudnessDisplay loudnessDisplay = new LoudnessDisplay();
        CorrelationDisplay correlationDisplay = new CorrelationDisplay();

        VBox spectrumTile    = createTile("SPECTRUM",    DawIcon.SPECTRUM,       "tile-header-accent-green",  spectrumDisplay);
        VBox levelsTile      = createTile("PEAK / RMS",  DawIcon.PEAK,           "tile-header-accent-orange", levelMeterDisplay);
        VBox waveformTile    = createTile("OSCILLOSCOPE", DawIcon.OSCILLOSCOPE,  "tile-header-accent-cyan",   waveformDisplay);
        VBox loudnessTile    = createTile("LOUDNESS",    DawIcon.LOUDNESS_METER, "tile-header-accent-purple", loudnessDisplay);
        VBox correlationTile = createTile("PHASE",       DawIcon.PHASE_METER,    "tile-header-accent-red",    correlationDisplay);

        vizTileRow.getChildren().addAll(waveformTile, spectrumTile, levelsTile, loudnessTile, correlationTile);

        Map<VisualizationPreferences.DisplayTile, Node> tileLookup = new EnumMap<>(VisualizationPreferences.DisplayTile.class);
        tileLookup.put(VisualizationPreferences.DisplayTile.SPECTRUM,    spectrumTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.LEVELS,      levelsTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.WAVEFORM,    waveformTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.LOUDNESS,    loudnessTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.CORRELATION, correlationTile);

        Preferences vizPrefs = Preferences.userNodeForPackage(VisualizationPreferences.class);
        VisualizationPanelController controller = new VisualizationPanelController(
                vizTileRow, new VisualizationPreferences(vizPrefs), tileLookup);
        controller.initialize();

        return new Result(spectrumDisplay, levelMeterDisplay, controller);
    }

    private static VBox createTile(String title, DawIcon icon, String accentClass, Region displayComponent) {
        Label header = new Label(title);
        header.getStyleClass().addAll("viz-tile-label", accentClass);
        header.setGraphic(IconNode.of(icon, 12));
        displayComponent.setMinHeight(0);
        VBox.setVgrow(displayComponent, Priority.ALWAYS);
        VBox tile = new VBox(4, header, displayComponent);
        tile.getStyleClass().add("viz-tile");
        tile.setPadding(new Insets(8));
        HBox.setHgrow(tile, Priority.ALWAYS);
        return tile;
    }
}
