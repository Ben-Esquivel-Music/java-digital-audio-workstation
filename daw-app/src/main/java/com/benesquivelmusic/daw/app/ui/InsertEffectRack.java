package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

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

    private static final double RACK_WIDTH = 70;
    private static final DataFormat SLOT_INDEX_FORMAT = new DataFormat("application/x-insert-slot-index");

    private final MixerChannel channel;
    private final int audioChannels;
    private final double sampleRate;
    private final UndoManager undoManager;
    private final UndoHistoryListener historyListener;

    /**
     * Creates a new insert-effects rack for the given mixer channel.
     *
     * @param channel       the mixer channel to manage inserts for
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     * @param sampleRate    the project sample rate in Hz
     * @param undoManager   the undo manager (may be {@code null})
     */
    public InsertEffectRack(MixerChannel channel, int audioChannels, double sampleRate,
                            UndoManager undoManager) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.audioChannels = audioChannels;
        this.sampleRate = sampleRate;
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
    }

    /**
     * Returns the mixer channel this rack manages.
     */
    MixerChannel getChannel() {
        return channel;
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

    private void showEffectPicker(int slotIndex) {
        List<InsertEffectType> types = InsertEffectFactory.availableTypes().stream()
                .filter(t -> t != InsertEffectType.STEREO_IMAGER || audioChannels == 2)
                .toList();
        List<String> names = types.stream()
                .map(InsertEffectType::getDisplayName)
                .toList();

        ChoiceDialog<String> dialog = new ChoiceDialog<>(names.getFirst(), names);
        dialog.setTitle("Add Insert Effect");
        dialog.setHeaderText("Select an effect for slot " + (slotIndex + 1));
        dialog.setContentText("Effect:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(selected -> {
            InsertEffectType type = types.get(names.indexOf(selected));
            InsertSlot slot = InsertEffectFactory.createSlot(type, audioChannels, sampleRate);
            addEffect(slotIndex, slot);
        });
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

    private static String bypassButtonStyle(boolean active) {
        return active
                ? "-fx-font-size: 8px; -fx-background-color: #00e676; -fx-background-radius: 2;"
                : "-fx-font-size: 8px; -fx-background-color: #555555; -fx-background-radius: 2;";
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }
}
