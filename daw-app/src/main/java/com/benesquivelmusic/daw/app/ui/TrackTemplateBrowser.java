package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.template.ChannelStripPreset;
import com.benesquivelmusic.daw.core.template.InsertEffectSpec;
import com.benesquivelmusic.daw.core.template.SendSpec;
import com.benesquivelmusic.daw.core.template.TrackTemplate;
import com.benesquivelmusic.daw.core.template.TrackTemplateFactory;
import com.benesquivelmusic.daw.core.template.TrackTemplateStore;
import com.benesquivelmusic.daw.core.template.TrackTemplateXml;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified browser for {@link TrackTemplate}s and {@link ChannelStripPreset}s.
 *
 * <p>Presents a {@link TabPane} with two tabs (Track Templates,
 * Channel-Strip Presets). Each tab shows a {@link ListView} of available
 * items (factory + user) on the left and a preview pane on the right that
 * lists the captured inserts and sends. The button bar exposes
 * <em>Insert</em>, <em>Duplicate</em>, <em>Delete</em>, <em>Import</em>,
 * <em>Export</em>, and <em>Close</em> actions.</p>
 *
 * <p>The dialog itself is purely a picker: it remembers the user's
 * <em>Insert</em> selection so the caller (typically
 * {@link TrackTemplateController}) can apply or instantiate it. Persistence
 * mutations (Duplicate, Delete, Import) are routed through the
 * {@link TrackTemplateStore} the controller hands us.</p>
 */
public final class TrackTemplateBrowser extends Dialog<ButtonType> {

    private static final Logger LOG = Logger.getLogger(TrackTemplateBrowser.class.getName());

    /** Which tab the browser opens on. */
    public enum InitialTab { TEMPLATES, PRESETS }

    private final TrackTemplateController controller;
    private final boolean restrictedToSingleTab;

    private final ListView<TrackTemplate> templateList = new ListView<>();
    private final ListView<ChannelStripPreset> presetList = new ListView<>();
    private final TextArea templatePreview = new TextArea();
    private final TextArea presetPreview = new TextArea();

    private final TabPane tabPane = new TabPane();
    private final Tab templatesTab;
    private final Tab presetsTab;

    private TrackTemplate selectedTemplate;
    private ChannelStripPreset selectedPreset;

    /**
     * Creates a browser bound to the given controller (used for store
     * access and notifications). Both tabs are shown.
     *
     * @param controller the owning controller
     * @param initialTab the tab to show on open
     */
    public TrackTemplateBrowser(TrackTemplateController controller, InitialTab initialTab) {
        this(controller, initialTab, false);
    }

    /**
     * Creates a browser bound to the given controller.
     *
     * @param controller           the owning controller
     * @param initialTab           the tab to show on open
     * @param restrictToInitialTab when {@code true} only the initial tab is
     *                             shown — the other tab is hidden so users
     *                             cannot Insert the wrong selection type
     */
    public TrackTemplateBrowser(TrackTemplateController controller, InitialTab initialTab,
                                boolean restrictToInitialTab) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.restrictedToSingleTab = restrictToInitialTab;
        Objects.requireNonNull(initialTab, "initialTab must not be null");

        setTitle("Templates and Presets");
        setHeaderText("Manage track templates and channel-strip presets");
        setResizable(true);

        templatesTab = new Tab("Track Templates",
                buildTab(templateList, templatePreview, true));
        templatesTab.setClosable(false);
        presetsTab = new Tab("Channel-Strip Presets",
                buildTab(presetList, presetPreview, false));
        presetsTab.setClosable(false);
        tabPane.getTabs().addAll(templatesTab, presetsTab);

        // When restricted to a single tab (e.g. "Add Track from Template"
        // or "Apply Channel Strip Preset" workflows), hide the other tab
        // so users cannot Insert the wrong selection type.
        if (restrictToInitialTab) {
            if (initialTab == InitialTab.TEMPLATES) {
                tabPane.getTabs().remove(presetsTab);
            } else {
                tabPane.getTabs().remove(templatesTab);
            }
        }

        DialogPane pane = getDialogPane();
        VBox content = new VBox(8, tabPane);
        content.setPadding(new Insets(8));
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        pane.setContent(content);
        pane.setPrefSize(720, 480);

        ButtonType insertType = new ButtonType("Insert", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(insertType, ButtonType.CLOSE);
        Button insertBtn = (Button) pane.lookupButton(insertType);
        insertBtn.setDefaultButton(true);
        // Only enable Insert when the *active* tab has a selection, so
        // switching tabs with an old selection on the other tab cannot
        // produce a null-selection Insert.
        Runnable updateInsertDisable = () -> {
            boolean onTemplatesTab = tabPane.getSelectionModel().getSelectedItem() == templatesTab;
            boolean hasSelection = onTemplatesTab
                    ? templateList.getSelectionModel().getSelectedItem() != null
                    : presetList.getSelectionModel().getSelectedItem() != null;
            insertBtn.setDisable(!hasSelection);
        };
        templateList.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> updateInsertDisable.run());
        presetList.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> updateInsertDisable.run());
        tabPane.getSelectionModel().selectedItemProperty().addListener((_, _, _) -> updateInsertDisable.run());
        insertBtn.setDisable(true); // initially disabled
        insertBtn.addEventFilter(javafx.event.ActionEvent.ACTION, _ -> captureSelection());

        // Initial population
        refreshTemplates();
        refreshPresets();
        tabPane.getSelectionModel().select(initialTab == InitialTab.TEMPLATES ? templatesTab : presetsTab);

        // Double-click on a list row acts as Insert.
        templateList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && templateList.getSelectionModel().getSelectedItem() != null) {
                captureSelection();
                setResult(insertType);
                close();
            }
        });
        presetList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && presetList.getSelectionModel().getSelectedItem() != null) {
                captureSelection();
                setResult(insertType);
                close();
            }
        });

        // Live preview update.
        templateList.getSelectionModel().selectedItemProperty().addListener(
                (_, _, t) -> templatePreview.setText(previewOf(t)));
        presetList.getSelectionModel().selectedItemProperty().addListener(
                (_, _, p) -> presetPreview.setText(previewOf(p)));
    }

    /** Returns the template selected at <em>Insert</em> time, or {@code null}. */
    public TrackTemplate getSelectedTemplate() {
        return selectedTemplate;
    }

    /** Returns the preset selected at <em>Insert</em> time, or {@code null}. */
    public ChannelStripPreset getSelectedPreset() {
        return selectedPreset;
    }

    private void captureSelection() {
        selectedTemplate = templateList.getSelectionModel().getSelectedItem();
        selectedPreset = presetList.getSelectionModel().getSelectedItem();
    }

    // ── Tab construction ────────────────────────────────────────────────────

    private SplitPane buildTab(ListView<?> list, TextArea preview, boolean templatesTab) {
        if (templatesTab) {
            @SuppressWarnings("unchecked")
            ListView<TrackTemplate> typed = (ListView<TrackTemplate>) list;
            typed.setCellFactory(_ -> new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(TrackTemplate item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : formatRow(item));
                }
            });
        } else {
            @SuppressWarnings("unchecked")
            ListView<ChannelStripPreset> typed = (ListView<ChannelStripPreset>) list;
            typed.setCellFactory(_ -> new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(ChannelStripPreset item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : formatRow(item));
                }
            });
        }

        preview.setEditable(false);
        preview.setWrapText(true);
        preview.setPrefRowCount(12);

        VBox listBox = new VBox(4, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        listBox.setPadding(new Insets(4));

        VBox previewBox = new VBox(4, new Label("Preview"), preview, buildButtonRow(templatesTab));
        VBox.setVgrow(preview, Priority.ALWAYS);
        previewBox.setPadding(new Insets(4));

        SplitPane split = new SplitPane(listBox, previewBox);
        split.setDividerPositions(0.45);
        return split;
    }

    private HBox buildButtonRow(boolean templates) {
        Button duplicate = new Button("Duplicate\u2026");
        duplicate.setOnAction(_ -> {
            if (templates) {
                duplicateTemplate();
            } else {
                duplicatePreset();
            }
        });

        Button delete = new Button("Delete");
        delete.setOnAction(_ -> {
            if (templates) {
                deleteTemplate();
            } else {
                deletePreset();
            }
        });

        Button importBtn = new Button("Import\u2026");
        importBtn.setOnAction(_ -> importXml(templates));

        Button exportBtn = new Button("Export\u2026");
        exportBtn.setOnAction(_ -> exportXml(templates));

        HBox row = new HBox(6, duplicate, delete, importBtn, exportBtn);
        row.setPadding(new Insets(4, 0, 0, 0));
        return row;
    }

    // ── Refresh ─────────────────────────────────────────────────────────────

    void refreshTemplates() {
        ObservableList<TrackTemplate> items = FXCollections.observableArrayList(
                controller.loadAllTemplates());
        templateList.setItems(items);
    }

    void refreshPresets() {
        ObservableList<ChannelStripPreset> items = FXCollections.observableArrayList(
                controller.loadAllPresets());
        presetList.setItems(items);
    }

    // ── Mutations ───────────────────────────────────────────────────────────

    private void duplicateTemplate() {
        TrackTemplate selected = templateList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        promptForName("Duplicate Template", "New template name:",
                selected.templateName() + " (copy)").ifPresent(name -> {
            TrackTemplate copy = new TrackTemplate(name, selected.trackType(),
                    selected.nameHint(), selected.inserts(), selected.sends(),
                    selected.volume(), selected.pan(), selected.color(),
                    selected.inputRouting(), selected.outputRouting());
            try {
                controller.store().saveTemplate(copy);
                refreshTemplates();
            } catch (IOException e) {
                error("Failed to duplicate template", e);
            }
        });
    }

    private void duplicatePreset() {
        ChannelStripPreset selected = presetList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        promptForName("Duplicate Preset", "New preset name:",
                selected.presetName() + " (copy)").ifPresent(name -> {
            ChannelStripPreset copy = new ChannelStripPreset(name,
                    selected.inserts(), selected.sends(),
                    selected.volume(), selected.pan());
            try {
                controller.store().savePreset(copy);
                refreshPresets();
            } catch (IOException e) {
                error("Failed to duplicate preset", e);
            }
        });
    }

    private void deleteTemplate() {
        TrackTemplate selected = templateList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (isFactoryTemplate(selected)) {
            warn("Factory templates cannot be deleted.");
            return;
        }
        if (!confirm("Delete template \u201C" + selected.templateName() + "\u201D?")) {
            return;
        }
        try {
            controller.store().deleteTemplate(selected.templateName());
            refreshTemplates();
        } catch (IOException e) {
            error("Failed to delete template", e);
        }
    }

    private void deletePreset() {
        ChannelStripPreset selected = presetList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (isFactoryPreset(selected)) {
            warn("Factory presets cannot be deleted.");
            return;
        }
        if (!confirm("Delete preset \u201C" + selected.presetName() + "\u201D?")) {
            return;
        }
        try {
            controller.store().deletePreset(selected.presetName());
            refreshPresets();
        } catch (IOException e) {
            error("Failed to delete preset", e);
        }
    }

    private void importXml(boolean templates) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(templates ? "Import Track Template" : "Import Channel-Strip Preset");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        java.io.File file = chooser.showOpenDialog(getOwner());
        if (file == null) {
            return;
        }
        try {
            String xml = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (templates) {
                TrackTemplate t = TrackTemplateXml.deserializeTemplate(xml);
                controller.store().saveTemplate(t);
                refreshTemplates();
            } else {
                ChannelStripPreset p = TrackTemplateXml.deserializePreset(xml);
                controller.store().savePreset(p);
                refreshPresets();
            }
        } catch (IOException | RuntimeException e) {
            error("Failed to import file", e);
        }
    }

    private void exportXml(boolean templates) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        try {
            if (templates) {
                TrackTemplate selected = templateList.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    return;
                }
                chooser.setTitle("Export Track Template");
                chooser.setInitialFileName(
                        TrackTemplateStore.sanitizeFileName(selected.templateName()) + ".xml");
                java.io.File file = chooser.showSaveDialog(getOwner());
                if (file == null) {
                    return;
                }
                Files.writeString(file.toPath(), TrackTemplateXml.serializeTemplate(selected),
                        StandardCharsets.UTF_8);
            } else {
                ChannelStripPreset selected = presetList.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    return;
                }
                chooser.setTitle("Export Channel-Strip Preset");
                chooser.setInitialFileName(
                        TrackTemplateStore.sanitizeFileName(selected.presetName()) + ".xml");
                java.io.File file = chooser.showSaveDialog(getOwner());
                if (file == null) {
                    return;
                }
                Files.writeString(file.toPath(), TrackTemplateXml.serializePreset(selected),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            error("Failed to export", e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String formatRow(TrackTemplate t) {
        return t.templateName() + "  \u2014  " + t.trackType().name().toLowerCase(Locale.ROOT)
                + "  (" + t.inserts().size() + " inserts)";
    }

    private static String formatRow(ChannelStripPreset p) {
        return p.presetName() + "  \u2014  " + p.inserts().size() + " inserts, "
                + p.sends().size() + " sends";
    }

    private static String previewOf(TrackTemplate t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Name:        ").append(t.templateName()).append('\n');
        sb.append("Type:        ").append(t.trackType()).append('\n');
        sb.append("Suggested:   ").append(t.nameHint()).append('\n');
        sb.append("Color:       ").append(t.color()).append('\n');
        sb.append("Volume:      ").append(t.volume()).append('\n');
        sb.append("Pan:         ").append(t.pan()).append('\n');
        appendInserts(sb, t.inserts());
        appendSends(sb, t.sends());
        return sb.toString();
    }

    private static String previewOf(ChannelStripPreset p) {
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Name:    ").append(p.presetName()).append('\n');
        sb.append("Volume:  ").append(p.volume()).append('\n');
        sb.append("Pan:     ").append(p.pan()).append('\n');
        appendInserts(sb, p.inserts());
        appendSends(sb, p.sends());
        return sb.toString();
    }

    private static void appendInserts(StringBuilder sb, List<InsertEffectSpec> inserts) {
        sb.append("\nInserts:\n");
        if (inserts.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (InsertEffectSpec s : inserts) {
            sb.append("  - ").append(s.type().getDisplayName());
            if (s.bypassed()) {
                sb.append(" [bypassed]");
            }
            sb.append('\n');
        }
    }

    private static void appendSends(StringBuilder sb, List<SendSpec> sends) {
        sb.append("\nSends:\n");
        if (sends.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (SendSpec s : sends) {
            sb.append("  - ").append(s.targetName())
                    .append(" @ ").append(s.level())
                    .append(" (").append(s.mode()).append(")\n");
        }
    }

    /**
     * An item is considered a factory template (non-deletable) only when
     * there is <em>no</em> corresponding file in the user store. If a
     * user imports or saves a template whose name collides with a factory
     * default, the user-store copy is user-deletable because a backing
     * file exists on disk.
     */
    private boolean isFactoryTemplate(TrackTemplate t) {
        Path userFile = controller.store().getTemplatesDirectory().resolve(
                TrackTemplateStore.sanitizeFileName(t.templateName()) + ".xml");
        if (Files.exists(userFile)) {
            return false; // user-authored — deletable
        }
        for (TrackTemplate f : TrackTemplateFactory.factoryTemplates()) {
            if (f.templateName().equals(t.templateName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same logic as {@link #isFactoryTemplate(TrackTemplate)}: a preset
     * backed by a user-store file is always deletable even if its name
     * matches a factory default.
     */
    private boolean isFactoryPreset(ChannelStripPreset p) {
        Path userFile = controller.store().getPresetsDirectory().resolve(
                TrackTemplateStore.sanitizeFileName(p.presetName()) + ".xml");
        if (Files.exists(userFile)) {
            return false; // user-authored — deletable
        }
        for (ChannelStripPreset f : TrackTemplateFactory.factoryPresets()) {
            if (f.presetName().equals(p.presetName())) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> promptForName(String title, String label, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(label);
        if (getOwner() != null) {
            dialog.initOwner(getOwner());
        }
        return dialog.showAndWait().map(String::trim).filter(s -> !s.isBlank());
    }

    private boolean confirm(String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message,
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        if (getOwner() != null) {
            a.initOwner(getOwner());
        }
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    private void warn(String message) {
        Alert a = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        a.setHeaderText(null);
        if (getOwner() != null) {
            a.initOwner(getOwner());
        }
        a.showAndWait();
    }

    private void error(String message, Throwable t) {
        LOG.log(Level.WARNING, message, t);
        Alert a = new Alert(Alert.AlertType.ERROR,
                message + ": " + t.getMessage(), ButtonType.OK);
        a.setHeaderText(null);
        if (getOwner() != null) {
            a.initOwner(getOwner());
        }
        a.showAndWait();
    }
}
