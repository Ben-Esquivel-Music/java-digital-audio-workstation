package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * A mixer channel insert-effects rack component showing up to
 * {@link MixerChannel#MAX_INSERT_SLOTS} insert effect slots.
 *
 * <p>Each slot can be:
 * <ul>
 *   <li><b>Empty</b> — renders a clickable button that opens an effect picker
 *       dialog listing the available built-in DSP processors.</li>
 *   <li><b>Populated</b> — renders the effect name, a bypass toggle, and
 *       supports double-click to open the {@link PluginParameterEditorPanel}
 *       for editing that effect's parameters, right-click to remove, and
 *       drag-and-drop for reordering.</li>
 * </ul>
 *
 * <p>When an {@link UndoManager} is provided, all insert add/remove/reorder
 * operations are registered as undoable actions.</p>
 */
public final class InsertEffectRack extends VBox {

    private static final Logger LOG = Logger.getLogger(InsertEffectRack.class.getName());
    private static final double RACK_WIDTH = 70;
    private static final DataFormat SLOT_INDEX_FORMAT = new DataFormat("application/x-insert-slot-index");

    private final MixerChannel channel;
    private final int audioChannels;
    private final double sampleRate;
    private final int bufferSize;
    private final UndoManager undoManager;
    private final UndoHistoryListener historyListener;
    private PluginRegistry pluginRegistry;
    private Runnable onSlotsChanged;

    /**
     * Resources associated with an externally-loaded plugin that must be
     * released when the rack is disposed.
     */
    private record ExternalPluginResources(DawPlugin plugin, URLClassLoader classLoader) {}

    /** Tracks external-plugin resources keyed by the InsertSlot they belong to. */
    private final Map<InsertSlot, ExternalPluginResources> externalResources = new HashMap<>();

    /**
     * Creates a new insert-effects rack for the given mixer channel.
     *
     * @param channel       the mixer channel to manage inserts for
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     * @param sampleRate    the project sample rate in Hz
     * @param bufferSize    the project buffer size in sample frames
     * @param undoManager   the undo manager (may be {@code null})
     */
    public InsertEffectRack(MixerChannel channel, int audioChannels, double sampleRate,
                            int bufferSize, UndoManager undoManager) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.audioChannels = audioChannels;
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;
        this.undoManager = undoManager;

        setSpacing(2);
        setAlignment(Pos.TOP_CENTER);
        setPrefWidth(RACK_WIDTH);
        setMaxWidth(RACK_WIDTH);
        getStyleClass().add("insert-effects-rack");

        Label header = new Label("INSERTS");
        header.getStyleClass().add("mixer-channel-name");
        getChildren().add(header);

        rebuildSlots();

        // Register listener to rebuild slots after undo/redo operations
        if (undoManager != null) {
            historyListener = _ -> {
                if (Platform.isFxApplicationThread()) {
                    rebuildSlots();
                } else {
                    Platform.runLater(this::rebuildSlots);
                }
            };
            undoManager.addHistoryListener(historyListener);
        } else {
            historyListener = null;
        }
    }

    /**
     * Rebuilds the slot UI from the current state of the channel's insert list.
     * Call after external modifications to the channel's insert slots.
     */
    public void rebuildSlots() {
        // Remove all slot nodes (keep the header label at index 0)
        if (getChildren().size() > 1) {
            getChildren().remove(1, getChildren().size());
        }

        List<InsertSlot> slots = channel.getInsertSlots();

        // Reconcile external-plugin resources: dispose any entries whose
        // InsertSlot is no longer present on the channel (removed via
        // undo/redo, reorder, or direct removal).
        var iter = externalResources.entrySet().iterator();
        while (iter.hasNext()) {
            var mapEntry = iter.next();
            if (!slots.contains(mapEntry.getKey())) {
                disposeExternalResources(mapEntry.getValue().plugin(),
                        mapEntry.getValue().classLoader());
                iter.remove();
            }
        }

        for (int i = 0; i < slots.size(); i++) {
            getChildren().add(buildPopulatedSlot(i, slots.get(i)));
        }

        // Add empty slots up to MAX_INSERT_SLOTS
        // Only the next available slot is enabled; further empty slots are disabled
        for (int i = slots.size(); i < MixerChannel.MAX_INSERT_SLOTS; i++) {
            Button emptyBtn = buildEmptySlot(i);
            if (i > slots.size()) {
                emptyBtn.setDisable(true);
            }
            getChildren().add(emptyBtn);
        }

        // Notify listener (e.g., MixerView latency label) that slots changed
        Runnable cb = onSlotsChanged;
        if (cb != null) {
            cb.run();
        }
    }

    /**
     * Returns the mixer channel this rack manages.
     */
    MixerChannel getChannel() {
        return channel;
    }

    /**
     * Sets a callback invoked after the slot UI is rebuilt (e.g., when
     * inserts are added, removed, reordered, or bypassed). The
     * {@link MixerView} uses this to refresh latency labels.
     *
     * @param callback the callback to invoke, or {@code null} to clear
     */
    void setOnSlotsChanged(Runnable callback) {
        this.onSlotsChanged = callback;
    }

    /**
     * Removes the undo history listener registered during construction.
     * Call this when the rack is no longer needed (e.g., when the mixer view
     * is rebuilt) to prevent stale listeners from accumulating.
     */
    public void dispose() {
        if (undoManager != null && historyListener != null) {
            undoManager.removeHistoryListener(historyListener);
        }
        // Dispose all tracked external-plugin resources
        for (ExternalPluginResources res : externalResources.values()) {
            disposeExternalResources(res.plugin(), res.classLoader());
        }
        externalResources.clear();
    }

    /**
     * Sets the plugin registry for this rack. When set, the effect picker
     * dialog also offers registered external plugins (those with
     * {@link PluginType#EFFECT} type) as additional insert options.
     *
     * @param registry the plugin registry, or {@code null} to disable
     */
    public void setPluginRegistry(PluginRegistry registry) {
        this.pluginRegistry = registry;
    }

    /**
     * Inserts a {@link DawPlugin} into the mixer channel at the given slot
     * index using the unified {@link DawPlugin#asAudioProcessor()} contract.
     *
     * <p>The plugin must already be initialized before calling this method.
     * If the plugin provides an audio processor (via {@code asAudioProcessor()}),
     * an {@link InsertSlot} is created and added to the channel's effects chain.
     * If the plugin does not process audio (returns empty), this method returns
     * {@code false} and the channel is unchanged.</p>
     *
     * @param slotIndex the slot index at which to insert the plugin
     * @param plugin    the initialized plugin to insert
     * @return {@code true} if the plugin was inserted, {@code false} if it
     *         does not support audio processing
     */
    public boolean insertPlugin(int slotIndex, DawPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(plugin);
        if (optSlot.isEmpty()) {
            return false;
        }
        addEffect(slotIndex, optSlot.get());
        return true;
    }

    // ── Empty slot ──────────────────────────────────────────────────────────

    private Button buildEmptySlot(int slotIndex) {
        Button btn = new Button("—");
        btn.getStyleClass().add("insert-slot-empty");
        btn.setPrefWidth(RACK_WIDTH - 4);
        btn.setMaxHeight(18);
        btn.setStyle("-fx-font-size: 9px; -fx-padding: 1 2 1 2;");
        btn.setTooltip(new Tooltip("Click to add effect"));
        btn.setOnAction(_ -> showEffectPicker(slotIndex));
        return btn;
    }

    // ── Populated slot ──────────────────────────────────────────────────────

    private HBox buildPopulatedSlot(int slotIndex, InsertSlot slot) {
        HBox row = new HBox(2);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("insert-slot-populated");
        row.setStyle("-fx-padding: 1 2 1 2;");

        // Bypass toggle
        ToggleButton bypassBtn = new ToggleButton();
        bypassBtn.setSelected(!slot.isBypassed());
        bypassBtn.setStyle(bypassButtonStyle(!slot.isBypassed()));
        bypassBtn.setMaxSize(12, 14);
        bypassBtn.setMinSize(12, 14);
        bypassBtn.setTooltip(new Tooltip("Bypass"));
        bypassBtn.setOnAction(_ -> {
            boolean active = bypassBtn.isSelected();
            boolean newBypassed = !active;
            if (undoManager != null) {
                undoManager.execute(new ToggleBypassAction(channel, slotIndex, newBypassed));
            } else {
                channel.setInsertBypassed(slotIndex, newBypassed);
            }
            bypassBtn.setStyle(bypassButtonStyle(active));
        });

        // Effect name label
        Label nameLabel = new Label(truncate(slot.getName(), 8));
        nameLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #e0e0e0;");
        nameLabel.setTooltip(new Tooltip(slot.getName()));
        nameLabel.setMaxWidth(RACK_WIDTH - 20);

        // Double-click to open parameter editor
        nameLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openParameterEditor(slot);
            }
        });

        // Right-click context menu for remove
        ContextMenu contextMenu = new ContextMenu();
        MenuItem removeItem = new MenuItem("Remove");
        removeItem.setOnAction(_ -> removeEffect(slotIndex));
        contextMenu.getItems().add(removeItem);
        row.setOnContextMenuRequested(event ->
                contextMenu.show(row, event.getScreenX(), event.getScreenY()));

        // Drag-and-drop for reordering
        row.setOnDragDetected(event -> {
            Dragboard db = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(SLOT_INDEX_FORMAT, slotIndex);
            db.setContent(content);
            event.consume();
        });
        row.setOnDragOver(event -> {
            if (event.getGestureSource() != row
                    && event.getDragboard().hasContent(SLOT_INDEX_FORMAT)
                    && event.getGestureSource() instanceof javafx.scene.Node sourceNode
                    && sourceNode.getParent() == InsertEffectRack.this) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });
        int targetIndex = slotIndex;
        row.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            Object content = db.getContent(SLOT_INDEX_FORMAT);
            if (content instanceof Integer fromIndex) {
                if (fromIndex != targetIndex) {
                    reorderEffect(fromIndex, targetIndex);
                }
                event.setDropCompleted(true);
            } else {
                event.setDropCompleted(false);
            }
            event.consume();
        });

        row.getChildren().addAll(bypassBtn, nameLabel);
        return row;
    }

    // ── Effect picker ───────────────────────────────────────────────────────

    /**
     * A choice in the effect picker dialog — either a built-in effect type or
     * an external plugin entry. Using a sealed interface avoids reliance on
     * string-based {@code indexOf} matching, which breaks on duplicate names.
     */
    private sealed interface EffectChoice {
        String displayName();
    }

    private record BuiltInChoice(InsertEffectType type) implements EffectChoice {
        @Override public String displayName() { return type.getDisplayName(); }
        @Override public String toString() { return displayName(); }
    }

    private record ExternalChoice(ExternalPluginEntry entry, String name) implements EffectChoice {
        @Override public String displayName() { return "[ext] " + name; }
        @Override public String toString() { return displayName(); }
    }

    private void showEffectPicker(int slotIndex) {
        List<EffectChoice> choices = new ArrayList<>();

        // Built-in effects
        InsertEffectFactory.availableTypes().stream()
                .filter(t -> t != InsertEffectType.STEREO_IMAGER || audioChannels == 2)
                .forEach(t -> choices.add(new BuiltInChoice(t)));

        // Registered external plugins that process audio (EFFECT type)
        if (pluginRegistry != null) {
            for (var mapEntry : pluginRegistry.getLoadedPlugins().entrySet()) {
                DawPlugin plugin = mapEntry.getValue();
                if (plugin.getDescriptor().type() == PluginType.EFFECT) {
                    choices.add(new ExternalChoice(
                            mapEntry.getKey(), plugin.getDescriptor().name()));
                }
            }
        }

        if (choices.isEmpty()) {
            return;
        }

        ChoiceDialog<EffectChoice> dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        dialog.setTitle("Add Insert Effect");
        dialog.setHeaderText("Select an effect for slot " + (slotIndex + 1));
        dialog.setContentText("Effect:");

        Optional<EffectChoice> result = dialog.showAndWait();
        result.ifPresent(selected -> {
            switch (selected) {
                case BuiltInChoice b -> {
                    InsertSlot slot = InsertEffectFactory.createSlot(
                            b.type(), audioChannels, sampleRate);
                    addEffect(slotIndex, slot);
                }
                case ExternalChoice ext ->
                        loadAndInsertExternalPlugin(slotIndex, ext.entry());
            }
        });
    }

    /**
     * Loads a fresh instance of an external plugin from its JAR entry,
     * initializes it, and inserts it via the unified
     * {@link DawPlugin#asAudioProcessor()} contract.
     *
     * <p>Uses {@link ExternalPluginLoader#loadWithClassLoader(ExternalPluginEntry)}
     * to keep the classloader open for the lifetime of the plugin (lazy class
     * loading remains available). The classloader is closed if insertion fails.</p>
     */
    private void loadAndInsertExternalPlugin(int slotIndex, ExternalPluginEntry entry) {
        ExternalPluginLoader.LoadResult result;
        try {
            result = ExternalPluginLoader.loadWithClassLoader(entry);
        } catch (PluginLoadException e) {
            showPickerError("Failed to load plugin: " + e.getMessage());
            return;
        }

        DawPlugin freshPlugin = result.plugin();
        URLClassLoader classLoader = result.classLoader();

        try {
            freshPlugin.initialize(new PluginContext() {
                @Override public double getSampleRate() { return sampleRate; }
                @Override public int getBufferSize() { return bufferSize; }
                @Override public void log(String message) { LOG.info(message); }
            });
            Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(freshPlugin);
            if (optSlot.isEmpty()) {
                disposeExternalResources(freshPlugin, classLoader);
                showPickerError("Plugin \"" + freshPlugin.getDescriptor().name()
                        + "\" does not support audio processing.");
                return;
            }
            InsertSlot slot = optSlot.get();
            externalResources.put(slot, new ExternalPluginResources(freshPlugin, classLoader));
            addEffect(slotIndex, slot);
        } catch (Exception e) {
            disposeExternalResources(freshPlugin, classLoader);
            showPickerError("Failed to initialize plugin: " + e.getMessage());
        }
    }

    private void showPickerError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Insert Effect Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // ── Undo-aware operations ───────────────────────────────────────────────

    private void addEffect(int index, InsertSlot slot) {
        int safeIndex = Math.min(index, channel.getInsertCount());
        if (undoManager != null) {
            undoManager.execute(new InsertEffectAction(channel, safeIndex, slot));
        } else {
            channel.insertInsert(safeIndex, slot);
        }
        rebuildSlots();
    }

    private void removeEffect(int index) {
        if (undoManager != null) {
            undoManager.execute(new RemoveEffectAction(channel, index));
        } else {
            channel.removeInsert(index);
        }
        rebuildSlots();
    }

    private void reorderEffect(int fromIndex, int toIndex) {
        if (undoManager != null) {
            undoManager.execute(new ReorderEffectAction(channel, fromIndex, toIndex));
        } else {
            channel.moveInsert(fromIndex, toIndex);
        }
        rebuildSlots();
    }

    // ── Parameter editor ────────────────────────────────────────────────────

    private void openParameterEditor(InsertSlot slot) {
        InsertEffectType type = slot.getEffectType();
        if (type == null) {
            return;
        }

        List<PluginParameter> params = InsertEffectFactory.getParameterDescriptors(type);
        if (params.isEmpty()) {
            return;
        }

        PluginParameterEditorPanel editor = new PluginParameterEditorPanel(params);
        BiConsumer<Integer, Double> handler =
                InsertEffectFactory.createParameterHandler(type, slot.getProcessor());
        editor.setOnParameterChanged(handler);

        // Initialize editor controls with the processor's current parameter values
        Map<Integer, Double> currentValues =
                InsertEffectFactory.getParameterValues(type, slot.getProcessor());
        if (!currentValues.isEmpty()) {
            editor.getState().loadValues(currentValues);
            editor.refreshControls();
        }

        Stage stage = new Stage();
        stage.setTitle(slot.getName() + " — Parameters");
        stage.setScene(new Scene(editor, 420, 320));
        stage.show();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Best-effort disposal of an external plugin and its classloader.
     * The classloader is always closed even if {@code plugin.dispose()} throws.
     */
    private static void disposeExternalResources(DawPlugin plugin, URLClassLoader classLoader) {
        try {
            plugin.dispose();
        } finally {
            ExternalPluginLoader.closeQuietly(classLoader);
        }
    }

    private static String bypassButtonStyle(boolean active) {
        return active
                ? "-fx-font-size: 8px; -fx-background-color: #00e676; -fx-background-radius: 2;"
                : "-fx-font-size: 8px; -fx-background-color: #555555; -fx-background-radius: 2;";
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }
}
