package com.benesquivelmusic.daw.app.ui.telemetry;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.advisor.TreatmentAdvisor;
import com.benesquivelmusic.daw.sdk.telemetry.AcousticTreatment;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind;
import com.benesquivelmusic.daw.sdk.telemetry.WallAttachment;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * UI panel that presents a ranked list of {@link AcousticTreatment}
 * suggestions from the {@link TreatmentAdvisor}.
 *
 * <p>Each row shows:
 * <ul>
 *     <li>A thumbnail of the room with the treatment highlighted on the
 *         relevant surface;</li>
 *     <li>The treatment kind, location summary, and predicted
 *         improvement in LUFS;</li>
 *     <li>A &quot;Why?&quot; expander with the acoustic reasoning;</li>
 *     <li>An &quot;Apply&quot; button that calls an application-supplied
 *         callback — typically the DAW records the treatment on the
 *         {@link RoomConfiguration} so subsequent analyses account for it.</li>
 * </ul></p>
 */
public final class TreatmentSuggestionPanel extends BorderPane {

    private final ObservableList<AcousticTreatment> suggestions =
            FXCollections.observableArrayList();
    private final ListView<AcousticTreatment> listView = new ListView<>(suggestions);
    private final Label emptyLabel =
            new Label("No treatment suggestions — the room is already well-balanced.");
    private RoomConfiguration configuration;
    private TreatmentAdvisor advisor = new TreatmentAdvisor();
    private Consumer<AcousticTreatment> applyListener = t -> { };

    public TreatmentSuggestionPanel() {
        setPadding(new Insets(8));
        setCenter(listView);
        listView.setCellFactory(v -> new SuggestionCell());
        listView.setPlaceholder(emptyLabel);
        VBox.setVgrow(listView, Priority.ALWAYS);
    }

    /**
     * Sets the room configuration the advisor should analyze. Calling this
     * immediately refreshes the suggestion list.
     *
     * @param configuration the current room (may be {@code null} to clear)
     */
    public void setRoomConfiguration(RoomConfiguration configuration) {
        this.configuration = configuration;
        refresh();
    }

    /** Replaces the advisor implementation (useful for testing). */
    public void setAdvisor(TreatmentAdvisor advisor) {
        this.advisor = Objects.requireNonNull(advisor, "advisor must not be null");
        refresh();
    }

    /**
     * Callback invoked when the user clicks the Apply button on a
     * suggestion. Typical implementations record the treatment on the
     * {@link RoomConfiguration} and then call {@link #refresh()}.
     */
    public void setOnApply(Consumer<AcousticTreatment> listener) {
        this.applyListener = Objects.requireNonNull(listener, "listener must not be null");
    }

    /** Re-runs the advisor against the current room and rebuilds the list. */
    public void refresh() {
        suggestions.clear();
        if (configuration == null) return;
        suggestions.setAll(advisor.analyze(configuration));
    }

    /** Returns the currently displayed suggestions (in ranked order). */
    public List<AcousticTreatment> getCurrentSuggestions() {
        return List.copyOf(suggestions);
    }

    // ------------------------------------------------------------------
    // Cell rendering
    // ------------------------------------------------------------------

    private final class SuggestionCell extends ListCell<AcousticTreatment> {

        private final Canvas thumbnail = new Canvas(96, 72);
        private final Label title = new Label();
        private final Label subtitle = new Label();
        private final Label improvement = new Label();
        private final Button applyButton = new Button("Apply");
        private final BooleanProperty whyExpanded = new SimpleBooleanProperty(false);
        private final Label whyBody = new Label();
        private final TitledPane why = new TitledPane("Why?", whyBody);
        private final BorderPane root = new BorderPane();

        SuggestionCell() {
            title.setStyle("-fx-font-weight: bold;");
            subtitle.setStyle("-fx-text-fill: #888;");
            improvement.setStyle("-fx-text-fill: #2a8;");
            whyBody.setWrapText(true);
            whyBody.setMaxWidth(320);
            why.setExpanded(false);
            why.expandedProperty().bindBidirectional(whyExpanded);

            VBox centre = new VBox(2, title, subtitle, improvement, why);
            centre.setPadding(new Insets(0, 8, 0, 8));
            HBox.setHgrow(centre, Priority.ALWAYS);

            applyButton.setOnAction(e -> {
                AcousticTreatment item = getItem();
                if (item != null) applyListener.accept(item);
            });

            HBox row = new HBox(8, thumbnail, centre, applyButton);
            row.setAlignment(Pos.CENTER_LEFT);
            root.setCenter(row);
            root.setPadding(new Insets(6));
        }

        @Override
        protected void updateItem(AcousticTreatment item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            title.setText(titleFor(item));
            subtitle.setText(locationSummary(item.location()));
            improvement.setText(
                    String.format("+%.2f LUFS predicted improvement", item.predictedImprovementLufs()));
            whyBody.setText(explain(item, configuration));
            drawThumbnail(thumbnail.getGraphicsContext2D(), item, configuration);
            setGraphic(root);
            setText(null);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String titleFor(AcousticTreatment t) {
        return switch (t.kind()) {
            case ABSORBER_BROADBAND -> "Broadband Absorber";
            case ABSORBER_LF_TRAP   -> "Low-Frequency Trap";
            case DIFFUSER_SKYLINE   -> "Skyline Diffuser";
            case DIFFUSER_QUADRATIC -> "Quadratic-Residue Diffuser";
        };
    }

    private static String locationSummary(WallAttachment location) {
        return switch (location) {
            case WallAttachment.OnSurface on ->
                    "On %s at (%.2f m, %.2f m)".formatted(
                            friendly(on.surface()), on.u(), on.v());
            case WallAttachment.InCorner in ->
                    "In corner between %s and %s, h=%.2f m".formatted(
                            friendly(in.surfaceA()), friendly(in.surfaceB()), in.z());
        };
    }

    private static String friendly(RoomSurface s) {
        return switch (s) {
            case FLOOR       -> "floor";
            case CEILING     -> "ceiling";
            case LEFT_WALL   -> "left wall";
            case RIGHT_WALL  -> "right wall";
            case FRONT_WALL  -> "front wall";
            case BACK_WALL   -> "back wall";
        };
    }

    static String explain(AcousticTreatment t, RoomConfiguration config) {
        String loc = locationSummary(t.location());
        return switch (t.kind()) {
            case ABSORBER_BROADBAND -> "This location is at a first-reflection point "
                    + "between your sources and microphone. Installing a broadband "
                    + "absorber here reduces early reflections that smear transients "
                    + "and blur stereo imaging. " + loc + ".";
            case ABSORBER_LF_TRAP -> "Room corners accumulate low-frequency pressure "
                    + "and drive standing-wave modes. A bass trap at this corner "
                    + "evens out the low end, especially in small rooms. " + loc + ".";
            case DIFFUSER_SKYLINE -> "A skyline diffuser on the rear wall preserves "
                    + "the room's sense of space while breaking up flutter echoes "
                    + "that build up between parallel walls. " + loc + ".";
            case DIFFUSER_QUADRATIC -> "A quadratic-residue diffuser scatters a "
                    + "narrow band of frequencies, ideal for taming a specific "
                    + "flutter or comb-filter artifact. " + loc + ".";
        };
    }

    static void drawThumbnail(GraphicsContext gc, AcousticTreatment t, RoomConfiguration config) {
        double w = gc.getCanvas().getWidth();
        double h = gc.getCanvas().getHeight();
        gc.setFill(Color.rgb(22, 28, 38));
        gc.fillRect(0, 0, w, h);

        double pad = 6;
        double drawW = w - 2 * pad;
        double drawH = h - 2 * pad;

        double roomW = config == null ? 4 : config.getDimensions().width();
        double roomL = config == null ? 5 : config.getDimensions().length();
        double scale = Math.min(drawW / roomW, drawH / roomL);
        double offX = (w - roomW * scale) / 2.0;
        double offY = (h - roomL * scale) / 2.0;

        gc.setStroke(Color.rgb(180, 200, 220));
        gc.setLineWidth(1.2);
        gc.strokeRect(offX, offY, roomW * scale, roomL * scale);

        Color accent = switch (t.kind()) {
            case ABSORBER_BROADBAND -> Color.rgb(60, 150, 230);
            case ABSORBER_LF_TRAP   -> Color.rgb(220, 80, 90);
            case DIFFUSER_SKYLINE   -> Color.rgb(140, 200, 120);
            case DIFFUSER_QUADRATIC -> Color.rgb(240, 190, 70);
        };

        switch (t.location()) {
            case WallAttachment.OnSurface on -> {
                double markerX = offX;
                double markerY = offY;
                double markerW = roomW * scale;
                double markerH = roomL * scale;
                double thickness = 4;
                switch (on.surface()) {
                    case LEFT_WALL   -> { markerW = thickness; }
                    case RIGHT_WALL  -> { markerX += roomW * scale - thickness; markerW = thickness; }
                    case FRONT_WALL  -> { markerH = thickness; }
                    case BACK_WALL   -> { markerY += roomL * scale - thickness; markerH = thickness; }
                    case FLOOR, CEILING -> {
                        double px = offX + Math.max(0, Math.min(on.u(), roomW)) * scale - 3;
                        double py = offY + Math.max(0, Math.min(on.v(), roomL)) * scale - 3;
                        gc.setFill(accent);
                        gc.fillOval(px, py, 6, 6);
                        return;
                    }
                }
                gc.setFill(accent);
                gc.fillRect(markerX, markerY, markerW, markerH);
            }
            case WallAttachment.InCorner in -> {
                double cx = (isSurface(in, RoomSurface.RIGHT_WALL) ? roomW : 0.0) * scale + offX;
                double cy = (isSurface(in, RoomSurface.BACK_WALL) ? roomL : 0.0) * scale + offY;
                gc.setFill(accent);
                gc.fillOval(cx - 5, cy - 5, 10, 10);
            }
        }
    }

    private static boolean isSurface(WallAttachment.InCorner in, RoomSurface s) {
        return in.surfaceA() == s || in.surfaceB() == s;
    }

    /** Returns a custom region so callers can embed the panel with a sensible default size. */
    public Region asRegion() {
        return this;
    }

    /** Exposes the suggestion kinds used for tooltip text in styling. */
    public static String kindTitle(TreatmentKind k) {
        return switch (k) {
            case ABSORBER_BROADBAND -> "Broadband Absorber";
            case ABSORBER_LF_TRAP   -> "Low-Frequency Trap";
            case DIFFUSER_SKYLINE   -> "Skyline Diffuser";
            case DIFFUSER_QUADRATIC -> "Quadratic-Residue Diffuser";
        };
    }
}
