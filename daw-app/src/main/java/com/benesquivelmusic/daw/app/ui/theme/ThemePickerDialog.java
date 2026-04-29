package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;

import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dialog that lets the user pick, preview, audit, and customize an
 * accessible theme.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>Left</b> — list of themes (bundled + user).</li>
 *   <li><b>Center</b> — live preview built from the selected theme's colors:
 *       a stylized arrangement-view + mixer strip.</li>
 *   <li><b>Right</b> — the WCAG contrast-audit pane: every declared
 *       foreground/background pair with its computed ratio and tier.</li>
 *   <li><b>Bottom</b> — "Duplicate and edit" + OK/Cancel.</li>
 * </ul>
 *
 * <p>Clicking <em>Duplicate and edit</em> opens a color-picker grid for
 * each theme color. Picking a color that produces a contrast below
 * {@link ThemeContrastValidator#AA_NORMAL 4.5:1} for any pair the color
 * participates in flags the row inline with the computed ratio and a
 * "this will fail AA" note.</p>
 *
 * <p>Saving the customized theme writes a JSON file under
 * {@link ThemeRegistry#userThemesDir()} via
 * {@link ThemeRegistry#saveUserTheme(Theme)}.</p>
 */
public final class ThemePickerDialog extends Dialog<Theme> {

    private static final Logger LOG = Logger.getLogger(ThemePickerDialog.class.getName());

    private final ThemeRegistry registry;
    private final ListView<Theme> themeList;
    private final BorderPane previewPane = new BorderPane();
    private final VBox auditPane = new VBox(4);
    private final SimpleObjectProperty<Theme> selected = new SimpleObjectProperty<>();

    public ThemePickerDialog(ThemeRegistry registry, String activeId) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        setTitle("Themes");
        setHeaderText("Choose an accessible color theme");
        getDialogPane().setPrefSize(900, 560);

        themeList = new ListView<>(FXCollections.observableArrayList(registry.themes()));
        themeList.setCellFactory(lv -> new ThemeCell());
        themeList.setPrefWidth(220);
        themeList.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldT, newT) -> {
                    selected.set(newT);
                    rebuildForTheme(newT);
                });

        // Audit pane container
        auditPane.setPadding(new Insets(8));
        auditPane.setPrefWidth(280);

        // Preview container
        previewPane.setPadding(new Insets(8));
        VBox.setVgrow(previewPane, Priority.ALWAYS);

        BorderPane content = new BorderPane();
        content.setLeft(themeList);
        content.setCenter(previewPane);
        content.setRight(new VBox(headerLabel("Contrast audit"), auditPane));

        // Buttons
        ButtonType duplicate = new ButtonType("Duplicate and edit\u2026",
                javafx.scene.control.ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(duplicate, ButtonType.OK, ButtonType.CANCEL);

        // Wire "Duplicate and edit" without closing the dialog automatically.
        Button duplicateButton = (Button) getDialogPane().lookupButton(duplicate);
        duplicateButton.addEventFilter(javafx.event.ActionEvent.ACTION, evt -> {
            evt.consume();
            Theme current = selected.get();
            if (current != null) {
                openCustomizeEditor(current, saved -> {
                    themeList.setItems(FXCollections.observableArrayList(registry.themes()));
                    themeList.getSelectionModel().select(saved);
                });
            }
        });

        getDialogPane().setContent(content);

        setResultConverter(bt -> bt == ButtonType.OK ? selected.get() : null);

        // Apply DAW dark theme stylesheet so the dialog inherits the look.
        DarkThemeHelper.applyTo(this);

        // Pre-select active or default theme.
        Theme initial = registry.findOrDefault(activeId);
        themeList.getSelectionModel().select(initial);
    }

    private static Label headerLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-padding: 8 8 0 8;");
        return l;
    }

    /** Returns the property holding the currently selected theme. */
    public javafx.beans.value.ObservableValue<Theme> selectedThemeProperty() {
        return selected;
    }

    private void rebuildForTheme(Theme theme) {
        if (theme == null) {
            previewPane.setCenter(null);
            auditPane.getChildren().clear();
            return;
        }
        previewPane.setCenter(buildPreview(theme));
        rebuildAuditPane(ThemeAuditReport.audit(theme));
    }

    private void rebuildAuditPane(ThemeAuditReport report) {
        auditPane.getChildren().clear();
        for (ThemeAuditReport.Entry entry : report.entries()) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Rectangle swatch = new Rectangle(48, 16);
            swatch.setFill(Color.web(entry.backgroundHex()));
            swatch.setStroke(Color.GRAY);
            Label fgLabel = new Label("Aa");
            fgLabel.setStyle("-fx-text-fill: " + entry.foregroundHex()
                    + "; -fx-background-color: " + entry.backgroundHex()
                    + "; -fx-padding: 0 6 0 6;");
            Label desc = new Label(entry.pair().foreground() + " on " + entry.pair().background()
                    + "  " + ThemeContrastValidator.describe(entry.ratio()));
            desc.setStyle(tierStyle(entry.tier()));
            row.getChildren().addAll(swatch, fgLabel, desc);
            auditPane.getChildren().add(row);
        }
        Label summary = new Label(
                report.passesAAA() ? "All pairs pass AAA"
                        : report.passesAA() ? "All pairs pass AA"
                        : "\u26A0 Some pairs fail AA");
        summary.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        auditPane.getChildren().add(summary);
    }

    private static String tierStyle(ThemeContrastValidator.Tier tier) {
        return switch (tier) {
            case AAA -> "-fx-text-fill: #4caf50;";
            case AA  -> "-fx-text-fill: #ffd54f;";
            case FAIL -> "-fx-text-fill: #ff5252; -fx-font-weight: bold;";
        };
    }

    private Node buildPreview(Theme theme) {
        String bg = theme.hex("background");
        String surface = theme.colors().containsKey("surface")
                ? theme.hex("surface") : bg;
        String fg = theme.hex("foreground");
        String accent = theme.colors().containsKey("accent")
                ? theme.hex("accent") : fg;
        String muted = theme.colors().containsKey("mutedForeground")
                ? theme.hex("mutedForeground") : fg;

        // Arrangement view: title bar + 3 mock track lanes.
        VBox arrangement = new VBox();
        arrangement.setStyle("-fx-background-color: " + bg + ";");
        Label title = new Label("Arrangement \u2014 " + theme.name());
        title.setStyle("-fx-text-fill: " + fg + "; -fx-font-weight: bold; -fx-padding: 8;");
        arrangement.getChildren().add(title);
        for (int i = 0; i < 3; i++) {
            HBox lane = new HBox(8);
            lane.setStyle("-fx-background-color: " + surface
                    + "; -fx-padding: 6; -fx-border-color: " + muted + "; -fx-border-width: 0 0 1 0;");
            Label name = new Label("Track " + (i + 1));
            name.setStyle("-fx-text-fill: " + fg + ";");
            Region clip = new Region();
            clip.setPrefSize(120 + 60.0 * i, 16);
            clip.setStyle("-fx-background-color: " + accent + "; -fx-background-radius: 3;");
            lane.getChildren().addAll(name, clip);
            arrangement.getChildren().add(lane);
        }

        // Mixer strip
        VBox mixer = new VBox(4);
        mixer.setAlignment(Pos.TOP_CENTER);
        mixer.setPadding(new Insets(8));
        mixer.setStyle("-fx-background-color: " + surface + ";");
        Label mixerHeader = new Label("Mixer");
        mixerHeader.setStyle("-fx-text-fill: " + fg + "; -fx-font-weight: bold;");
        Region meter = new Region();
        meter.setPrefSize(20, 120);
        meter.setStyle("-fx-background-color: linear-gradient(to top, "
                + accent + ", " + fg + ");");
        Label db = new Label("-6 dBFS");
        db.setStyle("-fx-text-fill: " + muted + ";");
        mixer.getChildren().addAll(mixerHeader, meter, db);

        BorderPane preview = new BorderPane();
        preview.setCenter(arrangement);
        preview.setRight(mixer);
        preview.setStyle("-fx-background-color: " + bg + "; -fx-border-color: " + muted
                + "; -fx-border-width: 1;");
        return preview;
    }

    /**
     * Opens a customize editor that duplicates {@code source}, prompts the
     * user for a new id/name, and lets them re-pick each color. Saves on
     * confirmation and notifies {@code onSaved}.
     */
    private void openCustomizeEditor(Theme source, Consumer<Theme> onSaved) {
        TextInputDialog idDialog = new TextInputDialog(source.id() + "-custom");
        idDialog.setTitle("New theme id");
        idDialog.setHeaderText("Choose an id for the duplicated theme");
        idDialog.setContentText("id:");
        DarkThemeHelper.applyTo(idDialog);
        String newId = idDialog.showAndWait().orElse(null);
        if (newId == null || newId.isBlank()) {
            return;
        }
        // Sanitize: filename-safe lowercase
        newId = newId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");

        Map<String, Theme.Color> editable = new LinkedHashMap<>(source.colors());
        Map<String, ColorPicker> pickers = new LinkedHashMap<>();
        Map<String, Label> warningLabels = new LinkedHashMap<>();

        Dialog<Theme> editor = new Dialog<>();
        editor.setTitle("Edit theme: " + source.name());
        editor.setHeaderText("Pick colors. Pairs failing AA contrast (<4.5:1) are flagged.");
        editor.getDialogPane().setPrefSize(640, 520);
        DarkThemeHelper.applyTo(editor);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        for (Map.Entry<String, Theme.Color> e : editable.entrySet()) {
            String name = e.getKey();
            Theme.Color color = e.getValue();
            Label nameLabel = new Label(name + "  (" + color.role() + ")");
            ColorPicker picker = new ColorPicker(Color.web(color.value()));
            Label warn = new Label();
            warn.setWrapText(true);
            grid.add(nameLabel, 0, row);
            grid.add(picker, 1, row);
            grid.add(warn, 2, row);
            pickers.put(name, picker);
            warningLabels.put(name, warn);
            picker.setOnAction(evt -> updateLiveWarnings(source, pickers, warningLabels));
            row++;
        }
        // Initial pass.
        updateLiveWarnings(source, pickers, warningLabels);

        editor.getDialogPane().setContent(grid);
        editor.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        String finalId = newId;
        editor.setResultConverter(bt -> {
            if (bt != ButtonType.OK) {
                return null;
            }
            Map<String, Theme.Color> updated = new LinkedHashMap<>();
            for (Map.Entry<String, Theme.Color> e : editable.entrySet()) {
                String hex = toHex(pickers.get(e.getKey()).getValue());
                updated.put(e.getKey(), new Theme.Color(hex, e.getValue().role()));
            }
            return new Theme(
                    finalId,
                    source.name() + " (custom)",
                    "Customized from " + source.id(),
                    source.dark(),
                    updated,
                    source.pairs());
        });

        Theme edited = editor.showAndWait().orElse(null);
        if (edited == null) {
            return;
        }
        try {
            registry.saveUserTheme(edited);
            onSaved.accept(edited);
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, "Failed to save user theme " + edited.id(), ioe);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Failed to save theme: " + ioe.getMessage(), ButtonType.OK);
            DarkThemeHelper.applyTo(alert);
            alert.showAndWait();
        }
    }

    /**
     * Recomputes contrast for every pair after each color pick and writes
     * the per-color warning into {@code warningLabels}. A color is flagged
     * if any pair it participates in falls below
     * {@link ThemeContrastValidator#AA_NORMAL}.
     */
    private void updateLiveWarnings(Theme source,
                                    Map<String, ColorPicker> pickers,
                                    Map<String, Label> warningLabels) {
        Map<String, String> hexByName = new LinkedHashMap<>();
        for (Map.Entry<String, ColorPicker> e : pickers.entrySet()) {
            hexByName.put(e.getKey(), toHex(e.getValue().getValue()));
        }
        // Reset.
        for (Label l : warningLabels.values()) {
            l.setText("");
            l.setStyle("");
        }
        for (Theme.Pair pair : source.pairs()) {
            String fg = hexByName.get(pair.foreground());
            String bg = hexByName.get(pair.background());
            if (fg == null || bg == null) continue;
            double ratio = ThemeContrastValidator.contrastRatio(fg, bg);
            ThemeContrastValidator.Tier tier = ThemeContrastValidator.classify(ratio);
            if (tier == ThemeContrastValidator.Tier.FAIL) {
                String fmt = String.format(Locale.ROOT, "%.2f:1", ratio);
                appendWarning(warningLabels.get(pair.foreground()),
                        "vs " + pair.background() + ": " + fmt
                                + " \u2014 this will fail AA");
                appendWarning(warningLabels.get(pair.background()),
                        "vs " + pair.foreground() + ": " + fmt
                                + " \u2014 this will fail AA");
            }
        }
    }

    private static void appendWarning(Label l, String text) {
        if (l == null) return;
        String existing = l.getText();
        l.setText(existing == null || existing.isBlank() ? text : existing + "\n" + text);
        l.setStyle("-fx-text-fill: #ff5252;");
    }

    private static String toHex(Color c) {
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    /** Cell rendering for the theme list. */
    private static final class ThemeCell extends ListCell<Theme> {
        @Override
        protected void updateItem(Theme theme, boolean empty) {
            super.updateItem(theme, empty);
            if (empty || theme == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Rectangle swatch = new Rectangle(16, 16);
            swatch.setFill(Color.web(theme.hex("background")));
            swatch.setStroke(Color.web(theme.hex("foreground")));
            Label name = new Label(theme.name());
            row.getChildren().addAll(swatch, name);
            setText(null);
            setGraphic(row);
        }
    }
}
