package com.benesquivelmusic.daw.app.ui.inspector.sections;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;

import java.util.UUID;

/**
 * "TRACK" inspector section (UI Design Book §5.6, story 272).
 *
 * <p>Exposes the basic per-track fields: Name (text field), Colour
 * (swatch picker — JavaFX {@link ColorPicker} stands in for the
 * design-book swatch grid for now), Type (display only), Input and
 * Output ({@link ChoiceBox} of routing options from story 215 / 092).
 *
 * <p>This class owns its own scaffolding only — the values are pushed
 * in by the {@code InspectorDrawer}'s selection listener which reads
 * the underlying domain track and binds the fields to it.
 */
public final class TrackSection extends InspectorSection {

    public static final String DEFAULT_STYLE_CLASS = "inspector-track-section";

    private final TextField nameField = new TextField();
    private final ColorPicker colorPicker = new ColorPicker();
    private final Label typeLabel = new Label();
    private final ChoiceBox<String> inputChoice = new ChoiceBox<>();
    private final ChoiceBox<String> outputChoice = new ChoiceBox<>();

    private final ObjectProperty<UUID> trackId =
            new SimpleObjectProperty<>(this, "trackId", null);

    public TrackSection(String title) {
        super(title == null ? "TRACK" : title);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("inspector-track-grid");
        grid.setHgap(8);
        grid.setVgap(4);

        int row = 0;
        addRow(grid, row++, "Name",   nameField);
        addRow(grid, row++, "Colour", colorPicker);
        addRow(grid, row++, "Type",   typeLabel);
        addRow(grid, row++, "Input",  inputChoice);
        addRow(grid, row++, "Output", outputChoice);

        for (var node : new javafx.scene.Node[]{
                nameField, colorPicker, inputChoice, outputChoice}) {
            node.setFocusTraversable(true);
        }

        setBody(grid);
    }

    private static void addRow(GridPane grid, int row, String key, javafx.scene.Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("inspector-field-label");
        k.setAlignment(Pos.CENTER_LEFT);
        grid.add(k, 0, row);
        grid.add(value, 1, row);
    }

    /** Fills the section from a domain track. */
    public void showTrack(UUID id, String name, Color color, String type,
                          String input, String output) {
        trackId.set(id);
        nameField.setText(name == null ? "" : name);
        if (color != null) {
            colorPicker.setValue(color);
        }
        typeLabel.setText(type == null ? "" : type);
        inputChoice.setValue(input);
        outputChoice.setValue(output);
    }

    public TextField getNameField()        { return nameField; }
    public ColorPicker getColorPicker()    { return colorPicker; }
    public Label getTypeLabel()            { return typeLabel; }
    public ChoiceBox<String> getInputChoice()  { return inputChoice; }
    public ChoiceBox<String> getOutputChoice() { return outputChoice; }
    public ObjectProperty<UUID> trackIdProperty() { return trackId; }
}
