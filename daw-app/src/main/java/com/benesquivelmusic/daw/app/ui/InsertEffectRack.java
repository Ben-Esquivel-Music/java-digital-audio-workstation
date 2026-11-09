package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.drag.DragSourceKind;
import com.benesquivelmusic.daw.app.ui.drag.DragVisualAdvisor;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.PluginBrowser;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.mixer.*;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
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
 *   <li><b>Offline</b> — an insert a project open kept at its place,
 *       passing audio through, because it could not load what the file saved
 *       (story 334, studio-engineer ruling E22): a warn mark in place of the
 *       bypass toggle, the muted name, a tooltip naming the cause and what to
 *       do, Remove and reordering only — no editor, no "Expensive" flag, no
 *       sidechain selector.</li>
 * </ul>
 *
 * <p>When an {@link UndoManager} is provided, all insert add/remove/reorder
 * operations are registered as undoable actions.</p>
 *
 * <p>Every edit the rack's controls raise — add (the plug-in browser
 * included), remove, reorder, bypass, the "Expensive" flag and the sidechain
 * source — reads the window's edit gate when it is handled, whatever raised
 * it: while the gate says no, the edit changes nothing, the control that
 * raised it shows the slot again, and an external plug-in that finishes
 * loading then is not inserted (PR #989 review 5711460812). The editor the
 * rack opens in its own window is handed the same gate for its frame and
 * parameter grid.</p>
 */
public final class InsertEffectRack extends VBox {

    private static final Logger LOG = Logger.getLogger(InsertEffectRack.class.getName());
    private static final double RACK_WIDTH = 70;
    private static final DataFormat SLOT_INDEX_FORMAT = new DataFormat("application/x-insert-slot-index");
    /** Style class an offline insert's row adds beside {@code insert-slot-populated} (story 334, ruling E22). */
    static final String OFFLINE_STYLE_CLASS = "insert-slot-offline";
    /** Style class of the warn mark standing in an offline row for the bypass toggle. */
    static final String OFFLINE_MARK_STYLE_CLASS = "insert-slot-offline-mark";
    /** Style class of an offline row's muted name. */
    static final String OFFLINE_NAME_STYLE_CLASS = "insert-slot-offline-name";

    private final MixerChannel channel;
    private final int audioChannels;
    private final double sampleRate;
    private final int bufferSize;
    private final UndoManager undoManager;
    private final UndoHistoryListener historyListener;
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path. May be {@code null} in a pure-unit context (the compatibility
     * constructor defaults it to {@link FxDispatcher#getDefault()});
     * {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;
    /**
     * Whether the project may be edited now: the window's edit gate on the
     * production path, {@code () -> true} from the shorter constructors. FX
     * thread (PR #989 review 5711460812).
     */
    private final BooleanSupplier projectActionsAllowed;
    private PluginRegistry pluginRegistry;
    private Mixer mixer;
    private Runnable onSlotsChanged;
    private BiConsumer<MixerChannel, InsertSlot> onOpenEditor;
    private MeterFeed meterFeed;
    private Runnable removeSlotPulse;
    private List<InsertSlot> renderedSlots = List.of();
    private List<Boolean> renderedBypass = List.of();
    /**
     * Shared {@link DragVisualAdvisor} consulted during plugin
     * reorder-drag gestures (story 197). Optional — when {@code null}
     * the rack falls back to bare JavaFX drag-and-drop.
     */
    private DragVisualAdvisor dragVisualAdvisor;

    private boolean disposed;
    private final Map<InsertSlot, Stage> slotEditors = new HashMap<>();
    private Runnable removeEditorPulse;

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
        this(channel, audioChannels, sampleRate, bufferSize, undoManager,
                FxDispatcher.getDefault());
    }

    /**
     * Creates a new insert-effects rack with an explicit FX-thread marshalling
     * seam (story 289).
     *
     * @param channel       the mixer channel to manage inserts for
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     * @param sampleRate    the project sample rate in Hz
     * @param bufferSize    the project buffer size in sample frames
     * @param undoManager   the undo manager (may be {@code null})
     * @param fxDispatcher  the FX-thread marshalling seam, or {@code null} to use
     *                      the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public InsertEffectRack(MixerChannel channel, int audioChannels, double sampleRate,
                            int bufferSize, UndoManager undoManager,
                            FxDispatcher fxDispatcher) {
        this(channel, audioChannels, sampleRate, bufferSize, undoManager, fxDispatcher, () -> true);
    }

    /**
     * Creates a new insert-effects rack whose edits read the window's edit
     * gate (PR #989 review 5711460812).
     *
     * @param channel               the mixer channel to manage inserts for
     * @param audioChannels         the number of audio channels (e.g., 2 for stereo)
     * @param sampleRate            the project sample rate in Hz
     * @param bufferSize            the project buffer size in sample frames
     * @param undoManager           the undo manager (may be {@code null})
     * @param fxDispatcher          the FX-thread marshalling seam, or {@code null} to use
     *                              the {@link FxDispatcher#getDefault() app-scoped default}
     * @param projectActionsAllowed whether the project may be edited now; while it says no, every
     *                              edit the rack's controls raise is refused; must not be {@code null}
     */
    public InsertEffectRack(MixerChannel channel, int audioChannels, double sampleRate,
                            int bufferSize, UndoManager undoManager,
                            FxDispatcher fxDispatcher, BooleanSupplier projectActionsAllowed) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.projectActionsAllowed = Objects.requireNonNull(
                projectActionsAllowed, "projectActionsAllowed must not be null");
        this.audioChannels = audioChannels;
        this.sampleRate = sampleRate;
        this.bufferSize = bufferSize;
        this.undoManager = undoManager;
        // May be null in a pure-unit context; postFx() falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;

        setSpacing(2);
        setAlignment(Pos.TOP_CENTER);
        setPrefWidth(RACK_WIDTH);
        setMaxWidth(RACK_WIDTH);
        getStyleClass().add("insert-effects-rack");

        Label header = new Label("INSERTS");
        header.getStyleClass().add("mixer-channel-name");
        getChildren().add(header);

        rebuildSlots();
        if (fxDispatcher != null) {
            removeSlotPulse = fxDispatcher.addPulseParticipant(this::refreshIfChanged);
        }

        // Register listener to rebuild slots after undo/redo operations
        if (undoManager != null) {
            historyListener = (_, _) -> {
                if (Platform.isFxApplicationThread()) {
                    rebuildSlots();
                } else {
                    postFx(this::rebuildSlots);
                }
            };
            undoManager.addHistoryListener(historyListener);
        } else {
            historyListener = null;
        }
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
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
        renderedSlots = List.copyOf(slots);
        renderedBypass = slots.stream().map(InsertSlot::isBypassed).toList();
        closeRemovedEditors();

        for (int i = 0; i < slots.size(); i++) {
            getChildren().add(buildPopulatedSlot(i, slots.get(i)));
        }
        if (slots.stream().anyMatch(slot -> slot.offline().isPresent())) {
            tintOfflineMarks();
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
        if (disposed) return;
        disposed = true;
        if (removeSlotPulse != null) removeSlotPulse.run();
        removeSlotPulse = null;
        for (Stage editor : List.copyOf(slotEditors.values())) editor.close();
        if (removeEditorPulse != null) {
            removeEditorPulse.run();
            removeEditorPulse = null;
        }
        if (undoManager != null && historyListener != null) {
            undoManager.removeHistoryListener(historyListener);
        }

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
     * Sets the mixer reference used to populate sidechain source dropdowns.
     * When set, insert slots whose processor reports
     * {@link com.benesquivelmusic.daw.core.plugin.PluginCapabilities#providesSidechainInput()
     * providesSidechainInput} show a sidechain source selector listing
     * available channels and buses.
     *
     * @param mixer the mixer, or {@code null} to disable sidechain UI
     */
    public void setMixer(Mixer mixer) {
        this.mixer = mixer;
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
     *         does not support audio processing or the window's edit gate
     *         refused the insert (PR #989 review 5711460812)
     */
    public boolean insertPlugin(int slotIndex, DawPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Optional<InsertSlot> optSlot = InsertEffectFactory.createSlotFromPlugin(plugin);
        if (optSlot.isEmpty()) {
            return false;
        }
        if (!addEffect(slotIndex, optSlot.get())) {
            return false;
        }
        if (plugin instanceof com.benesquivelmusic.daw.core.plugin.LiveAnalyzerPlugin) plugin.activate();
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

    private javafx.scene.Node buildPopulatedSlot(int slotIndex, InsertSlot slot) {
        HBox row = new HBox(2);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("insert-slot-populated");
        row.setStyle("-fx-padding: 1 2 1 2;");

        Optional<OfflineInsert> offline = slot.offline();
        if (offline.isPresent()) {
            buildOfflineSlot(row, slotIndex, slot, offline.get());
            return row;
        }

        // Bypass toggle
        ToggleButton bypassBtn = new ToggleButton();
        bypassBtn.setSelected(!slot.isBypassed());
        bypassBtn.setStyle(bypassButtonStyle(!slot.isBypassed()));
        bypassBtn.setMaxSize(12, 14);
        bypassBtn.setMinSize(12, 14);
        bypassBtn.setTooltip(new Tooltip("Bypass"));
        bypassBtn.setOnAction(_ -> {
            if (!projectActionsAllowed.getAsBoolean()) {
                // The press already flipped the toggle: the same node shows the slot again.
                bypassBtn.setSelected(!slot.isBypassed());
                bypassBtn.setStyle(bypassButtonStyle(!slot.isBypassed()));
                return;
            }
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
        // Story 129 (UI) — toggle the per-insert "expensive" flag so
        // the BypassExpensive degradation policy can pick this insert
        // for selective bypass when the per-track CPU budget trips.
        // CheckMenuItem renders a tick when the slot is currently
        // marked expensive; the change is undoable via UndoManager.
        javafx.scene.control.CheckMenuItem expensiveItem =
                new javafx.scene.control.CheckMenuItem("Expensive (eligible for bypass)");
        expensiveItem.setSelected(slot.isExpensive());
        expensiveItem.setOnAction(_ -> {
            if (!projectActionsAllowed.getAsBoolean()) {
                expensiveItem.setSelected(slot.isExpensive());
                return;
            }
            boolean newExpensive = expensiveItem.isSelected();
            if (undoManager != null) {
                undoManager.execute(new ToggleExpensiveAction(channel, slotIndex, newExpensive));
            } else {
                slot.setExpensive(newExpensive);
            }
        });
        MenuItem removeItem = new MenuItem("Remove");
        removeItem.setOnAction(_ -> removeEffect(slotIndex));
        contextMenu.getItems().addAll(expensiveItem, new SeparatorMenuItem(), removeItem);
        row.setOnContextMenuRequested(event ->
                contextMenu.show(row, event.getScreenX(), event.getScreenY()));

        installReorderDrag(row, nameLabel, slotIndex);

        row.getChildren().addAll(bypassBtn, nameLabel);

        // Add sidechain source selector for processors whose capabilities
        // report sidechain input support (reflection-based introspection).
        if (mixer != null && slot.getCapabilities().providesSidechainInput()) {
            VBox wrapper = new VBox(1);
            wrapper.getChildren().addAll(row, buildSidechainSelector(slot));
            return wrapper;
        }

        return row;
    }

    /**
     * Fills {@code row} for an offline insert — one a project open kept at its
     * place, passing audio through, because it could not load what the file
     * saved (story 334; studio-engineer rulings E22 and E3). The row keeps
     * {@code insert-slot-populated} and adds {@code insert-slot-offline}: a
     * warn-tinted {@link DawIcon#WARNING} mark stands where the bypass toggle
     * would be (a toggle with no audible effect is a dead control; the stored
     * bypass decision is kept as saved), the name is muted and opens no editor,
     * the row's tooltip says why the insert is offline and what to do, and its
     * context menu holds Remove only. It moves like any row and shows no
     * sidechain selector (its saved key is kept, ruling E1).
     */
    private void buildOfflineSlot(HBox row, int slotIndex, InsertSlot slot, OfflineInsert offline) {
        row.getStyleClass().add(OFFLINE_STYLE_CLASS);
        String tooltip = OfflineInsertText.tooltip(slot.getName(), offline.cause());
        Tooltip.install(row, new Tooltip(tooltip));
        row.setAccessibleText(OfflineInsertText.accessible(slot.getName()));

        Label nameLabel = new Label(truncate(slot.getName(), 8));
        nameLabel.getStyleClass().add(OFFLINE_NAME_STYLE_CLASS);
        nameLabel.setStyle("-fx-font-size: 9px;");
        nameLabel.setTooltip(new Tooltip(tooltip));
        nameLabel.setMaxWidth(RACK_WIDTH - 20);

        MenuItem removeItem = new MenuItem(OfflineInsertText.removeItem());
        removeItem.setOnAction(_ -> removeEffect(slotIndex));
        ContextMenu contextMenu = new ContextMenu(removeItem);
        row.setOnContextMenuRequested(event ->
                contextMenu.show(row, event.getScreenX(), event.getScreenY()));

        installReorderDrag(row, nameLabel, slotIndex);

        row.getChildren().addAll(offlineMark(), nameLabel);
    }

    /**
     * The offline state mark: a {@link DawIcon#WARNING} glyph the size of the
     * bypass toggle it replaces, tinted to the label's CSS-resolved text fill
     * (the {@code -warn} role) whenever that fill resolves or changes — an SVG
     * glyph's paint has no CSS hook of its own.
     */
    private static Label offlineMark() {
        Label mark = new Label();
        mark.getStyleClass().add(OFFLINE_MARK_STYLE_CLASS);
        mark.setMaxSize(12, 14);
        mark.setMinSize(12, 14);
        Node glyph = IconNode.of(DawIcon.WARNING, 10);
        mark.setGraphic(glyph);
        mark.textFillProperty().addListener((_, _, fill) -> IconNode.tint(glyph, fill));
        IconNode.tint(glyph, mark.getTextFill());
        return mark;
    }

    /**
     * Tints every offline mark the last rebuild rendered to its resolved text
     * fill, once CSS has been applied to the rows it added: deterministic,
     * where the marks' text-fill listener depends on the fill changing. A
     * rack not yet in a scene is tinted by that listener when CSS first
     * resolves.
     */
    private void tintOfflineMarks() {
        if (getScene() == null) {
            return;
        }
        applyCss();
        for (Node node : lookupAll("." + OFFLINE_MARK_STYLE_CLASS)) {
            if (node instanceof Label mark) {
                IconNode.tint(mark.getGraphic(), mark.getTextFill());
            }
        }
    }

    /** Lets {@code row} be dragged onto a sibling row to reorder the chain. */
    private void installReorderDrag(HBox row, Label nameLabel, int slotIndex) {
        row.setOnDragDetected(event -> {
            Dragboard db = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(SLOT_INDEX_FORMAT, slotIndex);
            db.setContent(content);
            notifyAdvisorBeginPluginDrag(
                    nameLabel.getText(), event.getScreenX(), event.getScreenY());
            event.consume();
        });
        row.setOnDragOver(event -> {
            if (event.getGestureSource() != row
                    && event.getDragboard().hasContent(SLOT_INDEX_FORMAT)
                    && event.getGestureSource() instanceof javafx.scene.Node sourceNode
                    && sourceNode.getParent() == InsertEffectRack.this) {
                event.acceptTransferModes(TransferMode.MOVE);
                if (!row.getStyleClass().contains("drop-target-active")) {
                    row.getStyleClass().add("drop-target-active");
                }
            }
            event.consume();
        });
        row.setOnDragExited(event -> {
            row.getStyleClass().remove("drop-target-active");
            event.consume();
        });
        int targetIndex = slotIndex;
        row.setOnDragDropped(event -> {
            row.getStyleClass().remove("drop-target-active");
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
        row.setOnDragDone(this::notifyAdvisorEndDrag);
    }

    // ── Effect picker ───────────────────────────────────────────────────────

    /**
     * Opens the §6.7 categorical {@link PluginBrowser} for the empty slot at
     * {@code slotIndex} and applies the user's choice: a built-in effect goes
     * through the existing {@link InsertEffectFactory#createSlot} path, an
     * external plugin through {@link #loadAndInsertExternalPlugin}. The browser
     * itself applies the Stereo-Imager stereo rule and the EFFECT-type filter,
     * and offers the drag-a-JAR install flow (story 303).
     */
    public void showEffectPicker(int slotIndex) {
        if (disposed) return;
        if (!projectActionsAllowed.getAsBoolean()) {
            return; // no browser opens while the project cannot be edited
        }
        if (channel.getInsertCount() >= MixerChannel.MAX_INSERT_SLOTS) return;
        String channelName = channel.getName();
        String targetLabel = (channelName == null || channelName.isBlank())
                ? "Insert " + (slotIndex + 1)
                : channelName + " / Insert " + (slotIndex + 1);

        PluginBrowser browser = new PluginBrowser(pluginRegistry, audioChannels == 2, targetLabel);
        browser.setFxDispatcher(fxDispatcher);
        browser.showAndWait().ifPresent(selection -> applyPickerSelection(slotIndex, selection));
    }

    /**
     * Applies the plug-in browser's choice for the empty slot at
     * {@code slotIndex}: a built-in effect is inserted at once, an external
     * plug-in is loaded on a worker and inserted when it lands. Read again
     * after the browser returns, the window's edit gate refuses a choice made
     * while it says no, silently: no load starts, the rack does not grey, and
     * the empty slot shows the model (PR #989 review 5711460812). FX thread.
     *
     * @param slotIndex the empty slot the browser was opened for
     * @param selection the user's choice
     */
    void applyPickerSelection(int slotIndex, PluginBrowser.Selection selection) {
        if (!projectActionsAllowed.getAsBoolean()) {
            return;
        }
        switch (selection) {
            case PluginBrowser.BuiltInSelection builtIn -> {
                InsertSlot slot = InsertEffectFactory.createSlot(
                        builtIn.type(), audioChannels, sampleRate);
                addEffect(slotIndex, slot);
            }
            case PluginBrowser.ExternalSelection external ->
                    loadAndInsertExternalPlugin(slotIndex, external.entry());
        }
    }

    /**
     * Loads a fresh instance of an external plugin from its JAR entry,
     * initializes it, and inserts it via the unified
     * {@link DawPlugin#asAudioProcessor()} contract.
     *
     * <p>The loaded resources belong to the slot and retire only after the owning
     * channel has quiesced its render snapshots.</p>
     */
    private void loadAndInsertExternalPlugin(int slotIndex, ExternalPluginEntry entry) {
        setDisable(true);
        var format = new com.benesquivelmusic.daw.core.audio.AudioFormat(sampleRate, audioChannels, 24, bufferSize);
        // Virtual threads (JEP 444, final since Java 21) keep third-party construction off FX.
        Thread.ofVirtual().name("insert-plugin-load").start(() -> {
            try {
                InsertSlot slot = com.benesquivelmusic.daw.app.ui.plugin.PluginSlotLoader.load(entry, format);
                postFx(() -> completeExternalInsert(slotIndex, slot));
            } catch (RuntimeException | Error failure) {
                postFx(() -> {
                    setDisable(false);
                    if (!disposed) showPickerError("Could not load plugin: " + failure.getMessage());
                });
            }
        });
    }

    /**
     * Applies a worker result on FX only while this rack still belongs to a
     * live view. A load started before the window's edit gate said no, that
     * lands while it says no, is not inserted: its slot is disposed as a
     * cancelled load's is, no editor opens, and that is logged at INFO
     * (ruling R-1.5; PR #989 review 5711460812).
     */
    void completeExternalInsert(int slotIndex, InsertSlot slot) {
        setDisable(false);
        if (disposed) {
            Thread.ofVirtual().name("cancelled-plugin-dispose").start(slot::disposeAfterQuiescence);
            return;
        }
        if (!projectActionsAllowed.getAsBoolean()) {
            LOG.info(() -> "The plug-in “" + slot.getName()
                    + "” finished loading while the project could not be edited; it was not inserted.");
            Thread.ofVirtual().name("refused-plugin-dispose").start(slot::disposeAfterQuiescence);
            return;
        }
        try {
            addEffect(slotIndex, slot);
            openParameterEditor(slot);
        } catch (RuntimeException | Error failure) {
            if (!channel.getInsertSlots().contains(slot)) {
                Thread.ofVirtual().name("failed-plugin-dispose").start(slot::disposeAfterQuiescence);
            }
            showPickerError("Could not insert plugin: " + failure.getMessage());
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

    /**
     * Inserts {@code slot} at {@code index}, unless the window's edit gate
     * says no (PR #989 review 5711460812).
     *
     * @return whether the slot was inserted
     */
    private boolean addEffect(int index, InsertSlot slot) {
        if (!projectActionsAllowed.getAsBoolean()) {
            return false;
        }
        int safeIndex = Math.min(index, channel.getInsertCount());
        if (undoManager != null) {
            undoManager.execute(new InsertEffectAction(channel, safeIndex, slot));
        } else {
            channel.insertInsert(safeIndex, slot);
        }
        rebuildSlots();
        return true;
    }

    private void removeEffect(int index) {
        if (!projectActionsAllowed.getAsBoolean()) {
            return;
        }
        if (undoManager != null) {
            undoManager.execute(new RemoveEffectAction(channel, index));
        } else {
            channel.removeInsert(index);
        }
        rebuildSlots();
    }

    private void reorderEffect(int fromIndex, int toIndex) {
        if (!projectActionsAllowed.getAsBoolean()) {
            return;
        }
        if (undoManager != null) {
            undoManager.execute(new ReorderEffectAction(channel, fromIndex, toIndex));
        } else {
            channel.moveInsert(fromIndex, toIndex);
        }
        rebuildSlots();
    }

    // ── Parameter editor ────────────────────────────────────────────────────

    /** Opens every slot kind through the graph-bound contract editor. */
    void openParameterEditor(InsertSlot slot) {
        if (!channel.getInsertSlots().contains(slot)) return;
        if (onOpenEditor != null) {
            onOpenEditor.accept(channel, slot);
            return;
        }
        Stage existing = slotEditors.get(slot);
        if (existing != null) {
            existing.toFront();
            existing.requestFocus();
            return;
        }
        if (getScene() == null || getScene().getWindow() == null) {
            throw new IllegalStateException("Attach the insert rack to a window before opening its editor");
        }
        var session = com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession.open(
                channel, slot, new com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession.Deps(
                        () -> sampleRate, null, null, null, projectActionsAllowed));
        if (meterFeed != null) session.bindInsertMeters(meterFeed, slot.getPluginInstanceId());
        Stage stage = new Stage();
        stage.initOwner(getScene().getWindow());
        stage.setTitle(slot.getName());
        stage.setScene(new Scene(session.frame(), 640, 420));
        ThemeManager.getDefault().applyTo(stage.getScene());
        slotEditors.put(slot, stage);
        if (removeEditorPulse == null && fxDispatcher != null) {
            removeEditorPulse = fxDispatcher.addPulseParticipant(this::closeRemovedEditors);
        }
        stage.setOnHidden(_ -> {
            session.dispose();
            slotEditors.remove(slot);
            if (slotEditors.isEmpty() && removeEditorPulse != null) {
                removeEditorPulse.run();
                removeEditorPulse = null;
            }
        });
        session.frame().setOnCloseRequested(stage::close);
        stage.show();
    }

    public void setOnOpenEditor(BiConsumer<MixerChannel, InsertSlot> handler) {
        onOpenEditor = handler;
    }

    public void setMeterFeed(MeterFeed feed) { meterFeed = feed; }

    private void refreshIfChanged() {
        var slots = channel.getInsertSlots();
        if (!renderedSlots.equals(slots)
                || !renderedBypass.equals(slots.stream().map(InsertSlot::isBypassed).toList())) {
            rebuildSlots();
        }
    }

    private void closeRemovedEditors() {
        var slots = channel.getInsertSlots();
        for (var entry : List.copyOf(slotEditors.entrySet())) {
            if (!slots.contains(entry.getKey())) entry.getValue().close();
        }
    }

    /**
     * Builds a small ComboBox that lets the user select which mixer channel
     * provides the sidechain detection signal for this insert slot. The
     * first entry is "None" (internal detection). All mixer channels and
     * return buses are listed, except the channel that owns this rack
     * (self-sidechain would be meaningless).
     */
    private ComboBox<String> buildSidechainSelector(InsertSlot slot) {
        List<MixerChannel> allChannels = mixer.getChannels();
        List<MixerChannel> allReturnBuses = mixer.getReturnBuses();

        List<String> names = new ArrayList<>();
        List<MixerChannel> sources = new ArrayList<>();
        names.add("None");
        sources.add(null);

        for (MixerChannel ch : allChannels) {
            if (ch != channel) {
                names.add(ch.getName());
                sources.add(ch);
            }
        }
        for (MixerChannel rb : allReturnBuses) {
            if (rb != channel) {
                names.add("[Ret] " + rb.getName());
                sources.add(rb);
            }
        }

        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(names);
        combo.setMaxWidth(RACK_WIDTH - 4);
        combo.setMaxHeight(16);
        combo.setStyle("-fx-font-size: 8px; -fx-padding: 0 1 0 1;");
        combo.setTooltip(new Tooltip("Sidechain source"));

        // Select current source
        combo.getSelectionModel().select(sidechainIndex(sources, slot));

        combo.setOnAction(_ -> {
            if (!projectActionsAllowed.getAsBoolean()) {
                // The same combo shows the slot's source again; the re-select
                // re-enters here only if it changed, and is refused again.
                combo.getSelectionModel().select(sidechainIndex(sources, slot));
                return;
            }
            int idx = combo.getSelectionModel().getSelectedIndex();
            MixerChannel source = (idx >= 0 && idx < sources.size()) ? sources.get(idx) : null;
            slot.setSidechainSource(source);
        });

        return combo;
    }

    /** The index in {@code sources} of {@code slot}'s sidechain source; 0 ("None") when it has none. */
    private static int sidechainIndex(List<MixerChannel> sources, InsertSlot slot) {
        MixerChannel current = slot.getSidechainSource();
        if (current != null) {
            for (int i = 1; i < sources.size(); i++) {
                if (sources.get(i) == current) {
                    return i;
                }
            }
        }
        return 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Installs the shared {@link DragVisualAdvisor}. When set, every
     * plugin reorder-drag will consult the advisor so the application's
     * drag-feedback layer (ghost preview, drop-zone highlight, modifier
     * cursor) renders over the gesture — see user story 197.
     *
     * @param advisor the shared advisor, or {@code null} to disable
     */
    public void setDragVisualAdvisor(DragVisualAdvisor advisor) {
        this.dragVisualAdvisor = advisor;
    }

    /** Returns the advisor, primarily for tests. */
    DragVisualAdvisor getDragVisualAdvisor() {
        return dragVisualAdvisor;
    }

    private void notifyAdvisorBeginPluginDrag(String label, double screenX, double screenY) {
        if (dragVisualAdvisor == null
                || dragVisualAdvisor.state() != DragVisualAdvisor.State.IDLE) {
            return;
        }
        try {
            dragVisualAdvisor.beginDrag(
                    DragSourceKind.PLUGIN,
                    label == null || label.isEmpty() ? "plugin" : label,
                    screenX, screenY,
                    /* ghostWidth */ 100, /* ghostHeight */ 40);
        } catch (RuntimeException ignored) {
            // Advisor must never break the underlying JavaFX drag.
        }
    }

    private void notifyAdvisorEndDrag(javafx.scene.input.DragEvent event) {
        if (dragVisualAdvisor == null) {
            return;
        }
        try {
            switch (dragVisualAdvisor.state()) {
                case DRAGGING -> {
                    if (event.getTransferMode() != null) {
                        dragVisualAdvisor.commit();
                    } else {
                        dragVisualAdvisor.cancel();
                        dragVisualAdvisor.revertCompleted();
                    }
                }
                case REVERTING -> dragVisualAdvisor.revertCompleted();
                case IDLE -> { /* nothing to do */ }
            }
        } catch (RuntimeException ignored) {
            // Advisor must never break the underlying JavaFX drag.
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
