package com.benesquivelmusic.daw.app.ui.inspector.sections;

import com.benesquivelmusic.daw.app.ui.controls.Fader;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * "SENDS" inspector section (UI Design Book §5.6, story 272).
 *
 * <p>Per-send row: name · mini-{@link Fader} ({@code .size-inspector},
 * <em>no meter</em>) · dB readout · pre/post toggle.
 */
public final class SendsSection extends InspectorSection {

    public static final String DEFAULT_STYLE_CLASS = "inspector-sends-section";

    private final VBox rows = new VBox();

    public SendsSection(String title) {
        super(title == null ? "SENDS" : title);
        getStyleClass().add(DEFAULT_STYLE_CLASS);
        rows.getStyleClass().add("inspector-sends-rows");
        setBody(rows);
    }

    public void setSends(java.util.List<? extends Row> sends) {
        rows.getChildren().clear();
        if (sends == null) return;
        for (Row r : sends) {
            rows.getChildren().add(buildRow(r));
        }
    }

    private HBox buildRow(Row r) {
        Label name = new Label(r.name() == null ? "" : r.name());
        name.getStyleClass().add("inspector-row-name");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);

        Fader fader = Fader.create()
                .min(-96).max(12).defaultValue(0)
                .curve(Fader.TravelCurve.LOG_DB)
                .showMeter(false)
                .build();
        fader.getStyleClass().add("size-inspector");
        fader.setValue(r.gainDb());

        Label readout = new Label(String.format(Locale.ROOT, "%.1f dB", r.gainDb()));
        readout.getStyleClass().addAll("inspector-send-readout", "numeric-value");
        // Live readout from the fader.
        fader.valueProperty().addListener((o, ov, nv) ->
                readout.setText(String.format(Locale.ROOT, "%.1f dB", nv.doubleValue())));

        ToggleButton prePost = new ToggleButton(r.prePost() == PrePost.PRE ? "PRE" : "POST");
        prePost.setSelected(r.prePost() == PrePost.PRE);
        prePost.setFocusTraversable(true);
        prePost.getStyleClass().add("inspector-prepost-toggle");
        prePost.selectedProperty().addListener((o, ov, nv) ->
                prePost.setText(nv ? "PRE" : "POST"));

        HBox row = new HBox(8, name, fader, readout, prePost);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("inspector-send-row");
        return row;
    }

    public VBox getRowsContainer() { return rows; }

    public enum PrePost { PRE, POST }

    public record Row(String name, double gainDb, PrePost prePost) {}
}
