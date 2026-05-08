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
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX view for the built-in {@link MidSideWrapperPlugin}.
 *
 * <p>Presents a header (title, preset combo, bypass toggle) above two
 * stacked {@link InnerChainEditor inner chain editors} — one for the
 * Mid (M = (L+R)/2) chain and one for the Side (S = (L−R)/2) chain.
 * Inner-chain edits flow through {@link MidSideWrapperPlugin#addPlugin(ChainOwner, DawPlugin)}
 * and {@link MidSideWrapperPlugin#removePlugin(ChainOwner, DawPlugin)} so the
 * underlying {@link com.benesquivelmusic.daw.core.dsp.MidSideWrapperProcessor}
 * is kept in sync automatically.</p>
 *
 * <p>The {@link #PRESET_NONE} option keeps the user's hand-edited chains;
 * picking one of the factory presets wipes both chains and applies the
 * preset's plugins via the wrapper API (the inner-chain plugins are
 * package-private to {@code MidSideWrapperPlugin} so we route through the
 * static {@code stereoWidenerPreset} / {@code monoBassPreset} /
 * {@code centerFocusPreset} factories).</p>
 *
 * <p><b>Story 157 — Mid/Side Processing Wrapper for Any Insert Slot.</b></p>
 */
public final class MidSideWrapperPluginView extends BorderPane {

    private static final Logger LOG = Logger.getLogger(MidSideWrapperPluginView.class.getName());

    static final String PRESET_NONE          = "Custom";
    static final String PRESET_STEREO_WIDEN  = "Stereo Widener";
    static final String PRESET_MONO_BASS     = "Mono Bass";
    static final String PRESET_CENTER_FOCUS  = "Center Focus";

    private static final String CHAIN_TOOLTIP =
            "Plugins in MID see the (L+R)/2 channel; "
          + "plugins in SIDE see the (L−R)/2 channel.";

    private final MidSideWrapperPlugin wrapper;
    private final InnerChainEditor midEditor;
    private final InnerChainEditor sideEditor;
    private final ToggleButton bypassToggle;
    private final ComboBox<String> presetCombo;

    /**
     * Creates a view bound to {@code wrapper}. The wrapper must already be
     * {@link MidSideWrapperPlugin#initialize initialized} so its underlying
     * processor is non-null; new inner plugins added through this view are
     * initialized via the wrapper's own (mono) {@code PluginContext}.
     *
     * @param wrapper the wrapper plugin to edit
     */
    public MidSideWrapperPluginView(MidSideWrapperPlugin wrapper) {
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper must not be null");
        if (wrapper.getProcessor() == null) {
            throw new IllegalStateException("wrapper must be initialized before opening its view");
        }

        setPadding(new Insets(12));
        setStyle("-fx-background-color: #2b2b2b;");

        // ── Header ─────────────────────────────────────────────────────
        Label title = new Label("Mid/Side Wrapper");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(
                PRESET_NONE, PRESET_STEREO_WIDEN, PRESET_MONO_BASS, PRESET_CENTER_FOCUS);
        presetCombo.setValue(PRESET_NONE);
        presetCombo.setTooltip(new Tooltip("Apply a factory preset (replaces both chains)"));
        presetCombo.setOnAction(_ -> applyPreset(presetCombo.getValue()));

        bypassToggle = new ToggleButton("Bypass");
        bypassToggle.setTooltip(new Tooltip(
                "Bypass the wrapper. Encode → decode is identity at unity gain, "
              + "so output equals input bit-for-bit (null test)."));
        bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
        bypassToggle.setOnAction(_ -> wrapper.getProcessor().setBypassed(bypassToggle.isSelected()));

        HBox header = new HBox(12, title, new Label("Preset:"), presetCombo, bypassToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        for (var node : header.getChildren()) {
            if (node instanceof Label l && l != title) {
                l.setStyle("-fx-text-fill: #ccc;");
            }
        }
        setTop(header);

        // ── Two stacked inner chain editors ────────────────────────────
        midEditor  = new InnerChainEditor(ChainOwner.MID,  "MID");
        sideEditor = new InnerChainEditor(ChainOwner.SIDE, "SIDE");
        VBox center = new VBox(8, midEditor, sideEditor);
        setCenter(center);

        refresh();
    }

    /** Returns the underlying wrapper plugin. */
    public MidSideWrapperPlugin getWrapper() {
        return wrapper;
    }

    ToggleButton getBypassToggleForTest()    { return bypassToggle; }
    ComboBox<String> getPresetComboForTest() { return presetCombo; }
    InnerChainEditor getMidEditorForTest()   { return midEditor; }
    InnerChainEditor getSideEditorForTest()  { return sideEditor; }

    /** Rebuilds both inner-chain list views from the wrapper's current state. */
    public void refresh() {
        midEditor.refresh();
        sideEditor.refresh();
        bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
    }

    // ── Preset application ─────────────────────────────────────────────

    private void applyPreset(String preset) {
        if (preset == null || PRESET_NONE.equals(preset)) {
            return;
        }
        clearChain(ChainOwner.MID);
        clearChain(ChainOwner.SIDE);
        Supplier<MidSideWrapperPlugin> factory = switch (preset) {
            case PRESET_STEREO_WIDEN -> MidSideWrapperPlugin::stereoWidenerPreset;
            case PRESET_MONO_BASS    -> MidSideWrapperPlugin::monoBassPreset;
            case PRESET_CENTER_FOCUS -> MidSideWrapperPlugin::centerFocusPreset;
            default -> null;
        };
        if (factory == null) {
            return;
        }
        // The static factory builds a fresh wrapper with uninitialized inner
        // plugins. We hand its plugins to *our* wrapper via addPlugin(...),
        // which re-initializes them with the host context and wires their
        // processors into the live MidSideWrapperProcessor.
        MidSideWrapperPlugin template = factory.get();
        for (DawPlugin p : new ArrayList<>(template.getMidChain())) {
            wrapper.addPlugin(ChainOwner.MID, p);
        }
        for (DawPlugin p : new ArrayList<>(template.getSideChain())) {
            wrapper.addPlugin(ChainOwner.SIDE, p);
        }
        refresh();
    }

    private void clearChain(ChainOwner owner) {
        // Snapshot first since removePlugin mutates the underlying list.
        for (DawPlugin p : new ArrayList<>(wrapper.getChain(owner))) {
            wrapper.removePlugin(owner, p);
        }
    }

    // ── Inner chain editor ─────────────────────────────────────────────

    /**
     * Lightweight VBox that lists the plugins of one inner chain (MID or
     * SIDE) and offers add (picker), remove (right-click), and parameter
     * edit (double-click) operations. Mirrors the workflow of
     * {@link InsertEffectRack} but operates on a {@link DawPlugin} list
     * exposed by {@link MidSideWrapperPlugin}, which is the data model the
     * wrapper actually owns.
     */
    final class InnerChainEditor extends VBox {

        private final ChainOwner owner;
        private final ListView<DawPlugin> listView;

        InnerChainEditor(ChainOwner owner, String displayLabel) {
            this.owner = owner;
            setSpacing(4);
            setPadding(new Insets(6));
            setStyle("-fx-background-color: #1f1f1f; -fx-border-color: #444; -fx-border-radius: 4;");

            Label header = new Label(displayLabel);
            header.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffcc66;");
            header.setTooltip(new Tooltip(CHAIN_TOOLTIP));

            javafx.scene.control.Button addBtn = new javafx.scene.control.Button("+ Add");
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

        private void showPicker() {
            List<BuiltInDawPlugin.MenuEntry> effects = BuiltInDawPlugin.menuEntries().stream()
                    .filter(e -> e.category() == BuiltInPluginCategory.EFFECT)
                    .filter(e -> e.pluginClass() != MidSideWrapperPlugin.class) // no nesting
                    .toList();
            if (effects.isEmpty()) {
                return;
            }
            var dialog = new ChoiceDialog<>(effects.getFirst(), effects);
            dialog.setTitle("Add " + owner + " Effect");
            dialog.setHeaderText("Select an effect to add to the " + owner + " chain");
            dialog.setContentText("Effect:");
            // Show only the human-readable label in the dropdown.
            dialog.setSelectedItem(effects.getFirst());
            // Override toString-based rendering by converting to a labelled
            // StringConverter on the underlying ComboBox is out of reach for
            // ChoiceDialog; instead rely on MenuEntry.toString fallback.
            Optional<BuiltInDawPlugin.MenuEntry> picked = dialog.showAndWait();
            picked.ifPresent(this::addBuiltIn);
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

    // (No additional helper methods.)
}
