package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight VBox that lists the plugins of one inner chain (MID or SIDE)
 * of a {@link MidSideWrapperPlugin} and offers add (picker), remove
 * (right-click), and parameter edit (double-click) operations.
 *
 * <p>Story 302 §8.3 port of the retired {@code daw-app}
 * {@code InnerChainEditor}, behaviour-preserving: the add picker lists the
 * {@link BuiltInPluginCategory#EFFECT EFFECT}-category built-ins (excluding
 * the wrapper itself — no nesting), instantiates the chosen entry via its
 * public no-arg constructor, and routes every edit through
 * {@link MidSideWrapperPlugin#addPlugin(ChainOwner, DawPlugin)} /
 * {@link MidSideWrapperPlugin#removePlugin(ChainOwner, DawPlugin)} so the
 * DSP chain stays wired. Double-clicking an entry opens
 * {@link InnerPluginParameterDialog} on that inner plugin's parameters.</p>
 */
final class InnerChainPane extends VBox {

    private static final Logger LOG = Logger.getLogger(InnerChainPane.class.getName());

    private static final String CHAIN_TOOLTIP =
            "Plugins in MID see the (L+R)/2 channel; "
          + "plugins in SIDE see the (L−R)/2 channel.";

    private final ChainOwner owner;
    private final MidSideWrapperPlugin wrapper;
    private final ListView<DawPlugin> listView;

    InnerChainPane(MidSideWrapperPlugin wrapper, ChainOwner owner, String displayLabel) {
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(displayLabel, "displayLabel must not be null");
        setSpacing(4);
        setPadding(new Insets(6));

        Label header = new Label(displayLabel);
        header.setStyle("-fx-font-weight: bold;");
        header.setTooltip(new Tooltip(CHAIN_TOOLTIP));

        Button addBtn = new Button("+ Add");
        addBtn.setOnAction(_ -> showPicker());
        HBox headerRow = new HBox(8, header, addBtn);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        listView = new ListView<>();
        listView.setPrefHeight(120);
        listView.setCellFactory(_ -> new PluginCell());
        listView.setTooltip(new Tooltip(CHAIN_TOOLTIP));

        getChildren().addAll(headerRow, listView);
    }

    /** Rebuilds the list view from the wrapper's current chain contents. */
    void refresh() {
        listView.getItems().setAll(wrapper.getChain(owner));
    }

    // ── Effect picker ───────────────────────────────────────────────────

    /**
     * Pure filter behind the add-effect picker: keeps only the
     * {@link BuiltInPluginCategory#EFFECT EFFECT}-category entries and drops
     * {@link MidSideWrapperPlugin} itself so a wrapper can never nest inside
     * its own inner chains.
     */
    static List<BuiltInDawPlugin.MenuEntry> effectMenuEntries(
            List<BuiltInDawPlugin.MenuEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        return entries.stream()
                .filter(e -> e.category() == BuiltInPluginCategory.EFFECT)
                .filter(e -> e.pluginClass() != MidSideWrapperPlugin.class) // no nesting
                .toList();
    }

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
        List<EffectChoice> choices = effectMenuEntries(BuiltInDawPlugin.menuEntries())
                .stream()
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
                setTooltip(null);
                setContextMenu(null);
                setOnMouseClicked(null);
                return;
            }
            setText(item.getDescriptor().name());
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
            InnerPluginParameterDialog.open(plugin, owner);
        }
    }
}
