package com.benesquivelmusic.daw.app.ui.inspector.sections;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * "INSERTS" inspector section (UI Design Book §5.6, story 272).
 *
 * <p>Per-insert row: name · active dot · edit pencil → opens the
 * <strong>InsertParameters</strong> sub-pane <em>inside</em> the
 * drawer (not a separate dialog). "+ Add" at the bottom opens a plugin
 * picker.
 *
 * <p>{@code PluginParameterEditorPanel}'s contents migrate into the
 * sub-pane revealed by the edit pencil; that migration is staged
 * across follow-up stories per the issue's "splitting along the
 * section boundary is preferred over deferring" guidance.
 */
public final class InsertsSection extends InspectorSection {

    public static final String DEFAULT_STYLE_CLASS = "inspector-inserts-section";

    private final VBox rows = new VBox();
    private final VBox subPaneHost = new VBox();
    private final Button addButton;
    private final IntegerProperty selectedIndex =
            new SimpleIntegerProperty(this, "selectedIndex", -1);

    public InsertsSection(String title, String addLabel) {
        super(title == null ? "INSERTS" : title);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        rows.getStyleClass().add("inspector-inserts-rows");
        subPaneHost.getStyleClass().add("inspector-inserts-subpane");
        subPaneHost.setVisible(false);
        subPaneHost.setManaged(false);

        addButton = new Button(addLabel == null ? "+ Add" : addLabel);
        addButton.getStyleClass().add("inspector-add-button");
        addButton.setFocusTraversable(true);
        addButton.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(4, rows, addButton, subPaneHost);
        body.setFillWidth(true);
        VBox.setVgrow(rows, Priority.SOMETIMES);
        setBody(body);
    }

    /** Reveal an insert's parameters in the in-drawer sub-pane. */
    public void showInsertParameters(int insertIndex, Region parametersPane) {
        selectedIndex.set(insertIndex);
        if (parametersPane == null) {
            subPaneHost.getChildren().clear();
            subPaneHost.setVisible(false);
            subPaneHost.setManaged(false);
        } else {
            subPaneHost.getChildren().setAll(parametersPane);
            subPaneHost.setVisible(true);
            subPaneHost.setManaged(true);
        }
    }

    /** Replaces the insert rows from a flat list. */
    public void setInserts(java.util.List<? extends Row> inserts) {
        rows.getChildren().clear();
        if (inserts == null) return;
        int i = 0;
        for (Row r : inserts) {
            rows.getChildren().add(buildRow(i++, r));
        }
    }

    private HBox buildRow(int index, Row r) {
        Label dot = new Label("\u25CF"); // ●
        dot.getStyleClass().add(r.active() ? "insert-dot-active" : "insert-dot-inactive");

        Label name = new Label(r.name() == null ? "" : r.name());
        name.getStyleClass().add("inspert-row-name");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);

        Button edit = new Button("\u270E"); // ✎
        edit.getStyleClass().add("inspector-row-edit");
        edit.setFocusTraversable(true);
        edit.setOnAction(e -> selectedIndex.set(index));

        HBox row = new HBox(4, dot, name, edit);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("inspector-insert-row");
        return row;
    }

    public Button getAddButton()         { return addButton; }
    public VBox getRowsContainer()       { return rows; }
    public Region getSubPaneHost()       { return subPaneHost; }
    public IntegerProperty selectedIndexProperty() { return selectedIndex; }

    /** Minimal row contract; the inspector drawer fills these from the domain. */
    public record Row(String name, boolean active) {}
}
