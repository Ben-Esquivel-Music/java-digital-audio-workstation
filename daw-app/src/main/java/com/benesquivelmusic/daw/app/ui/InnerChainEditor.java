package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight VBox that lists the plugins of one inner chain (MID or SIDE)
 * and offers add (picker), remove (right-click), and parameter edit
 * (double-click) operations.
 *
 * <p>Mirrors the workflow of {@link InsertEffectRack} but operates on a
 * {@link DawPlugin} list exposed by {@link MidSideWrapperPlugin}, which is
 * the data model the wrapper actually owns.</p>
 *
 * <p>Extracted from {@link MidSideWrapperPluginView} to keep each class
 * under the ~200-line soft cap defined in CONTRIBUTING.md.</p>
 */
final class InnerChainEditor extends VBox {

    private static final Logger LOG = Logger.getLogger(InnerChainEditor.class.getName());

    private static final String CHAIN_TOOLTIP =
            "Plugins in MID see the (L+R)/2 channel; "
          + "plugins in SIDE see the (L−R)/2 channel.";

    private final ChainOwner owner;
    private final MidSideWrapperPlugin wrapper;
    private final ListView<DawPlugin> listView;

    InnerChainEditor(MidSideWrapperPlugin wrapper, ChainOwner owner, String displayLabel) {
        this.wrapper = wrapper;
        this.owner = owner;
        setSpacing(4);
        setPadding(new Insets(6));
        setStyle("-fx-background-color: #1f1f1f; -fx-border-color: #444; -fx-border-radius: 4;");

        Label header = new Label(displayLabel);
        header.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffcc66;");
        header.setTooltip(new Tooltip(CHAIN_TOOLTIP));

        Button addBtn = new Button("+ Add");
        addBtn.setOnAction(_ -> showPicker());
        HBox headerRow = new HBox(8, header, addBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        listView = new ListView<>();
        listView.setPrefHeight(120);
        listView.setCellFactory(_ -> new PluginCell());
        listView.setStyle("-fx-control-inner-background: #232323; -fx-text-fill: #eee;");
        listView.setTooltip(new Tooltip(CHAIN_TOOLTIP));

        getChildren().addAll(headerRow, listView);
    }

    ChainOwner getOwner() { return owner; }
    ListView<DawPlugin> getListViewForTest() { return listView; }

    void refresh() {
        listView.getItems().setAll(wrapper.getChain(owner));
    }

    // ── Effect picker ───────────────────────────────────────────────────

    /**
     * Display wrapper around {@link BuiltInDawPlugin.MenuEntry} that
     * overrides {@link #toString()} to show the human-readable label in
     * the {@link ChoiceDialog} dropdown (which relies on toString).
     */
    private record EffectChoice(BuiltInDawPlugin.MenuEntry entry) {
        @Override
        public String toString() {
            return entry.label();
        }
    }

    private void showPicker() {
        List<EffectChoice> choices = BuiltInDawPlugin.menuEntries().stream()
                .filter(e -> e.category() == BuiltInPluginCategory.EFFECT)
                .filter(e -> e.pluginClass() != MidSideWrapperPlugin.class) // no nesting
                .map(EffectChoice::new)
                .toList();
        if (choices.isEmpty()) {
            return;
        }
        var dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        dialog.setTitle("Add " + owner + " Effect");
        dialog.setHeaderText("Select an effect to add to the " + owner + " chain");
        dialog.setContentText("Effect:");
        Optional<EffectChoice> picked = dialog.showAndWait();
        picked.ifPresent(choice -> addBuiltIn(choice.entry()));
    }

    private void addBuiltIn(BuiltInDawPlugin.MenuEntry entry) {
        try {
            BuiltInDawPlugin instance = entry.pluginClass().getConstructor().newInstance();
            wrapper.addPlugin(owner, instance);
            refresh();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to instantiate built-in plugin "
                    + entry.pluginClass().getName(), e);
            Alert a = new Alert(Alert.AlertType.ERROR,
                    "Failed to add " + entry.label() + ": " + e.getMessage());
            a.setHeaderText(null);
            a.showAndWait();
        }
    }

    // ── List cell ───────────────────────────────────────────────────────

    /** Renders a chain entry with double-click → param editor and a Remove context menu. */
    private final class PluginCell extends ListCell<DawPlugin> {
        @Override
        protected void updateItem(DawPlugin item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setContextMenu(null);
                setOnMouseClicked(null);
                setStyle("");
                return;
            }
            setText(item.getDescriptor().name());
            setStyle("-fx-text-fill: #eee;");
            setTooltip(new Tooltip(CHAIN_TOOLTIP));

            ContextMenu menu = new ContextMenu();
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(_ -> {
                wrapper.removePlugin(owner, item);
                refresh();
            });
            menu.getItems().add(remove);
            setContextMenu(menu);

            setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2) {
                    openParameterEditor(item);
                }
            });
        }

        private void openParameterEditor(DawPlugin plugin) {
            List<PluginParameter> params = plugin.getParameters();
            if (params == null || params.isEmpty()) {
                return;
            }
            PluginParameterEditorPanel editor = new PluginParameterEditorPanel(params);
            editor.setOnParameterChanged((id, value) ->
                    plugin.setAutomatableParameter(id, value));
            Stage stage = new Stage();
            stage.setTitle(plugin.getDescriptor().name() + " — Parameters ("
                    + owner + " chain)");
            stage.setScene(new Scene(editor, 420, 320));
            stage.show();
        }
    }
}
