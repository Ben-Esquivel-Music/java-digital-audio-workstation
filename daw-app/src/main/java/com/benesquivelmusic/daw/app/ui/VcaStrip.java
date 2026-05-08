package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.AssignVcaMemberAction;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.SetVcaGainAction;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.VcaGroupManager;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Mixer-strip UI for a single {@link VcaGroup}.
 *
 * <p>VCA strips render on the right side of {@link MixerView} (after the
 * master strip) and provide proportional fader control over every member
 * channel without introducing an additional summing point. The strip's
 * fader writes through {@link SetVcaGainAction}, so all gain moves are
 * undoable; the mute and solo buttons mute or solo every member channel
 * in one shot, mirroring the behavior of every console / DAW VCA channel.</p>
 *
 * <p>Drag-to-assign: the strip accepts JavaFX drag-and-drop drops carrying
 * {@link #CHANNEL_ID_FORMAT} payloads and assigns the channel via
 * {@link AssignVcaMemberAction}. While a compatible drag is hovering, the
 * strip's border is replaced with a thicker accent border so the engineer
 * sees an obvious drop target — the simple-style-change fallback documented
 * in the issue when {@code DragVisualAdvisor} (story 197) is not in use.</p>
 *
 * <p>The visual layout matches a stripped-down output channel: badge → name
 * field → color swatch → level/fader → mute / solo / delete button row.</p>
 */
public final class VcaStrip extends VBox {

    /**
     * Drag-and-drop payload format for a mixer channel id (UUID string).
     * Channel strips publish this when a drag begins; VCA strips accept it
     * to perform the assign.
     */
    public static final DataFormat CHANNEL_ID_FORMAT =
            new DataFormat("application/x-mixer-channel-id");

    private static final double STRIP_WIDTH = 80;
    private static final double FADER_HEIGHT = 150;

    private final VcaGroup group;
    private final VcaGroupManager manager;
    private final UndoManager undoManager;
    private final Runnable onChange;
    private final Function<UUID, MixerChannel> channelLookup;

    private final TextField nameField;
    private final Slider gainFader;
    private final Button muteBtn;
    private final Button soloBtn;

    /** Original border style restored when a hovering drag leaves the strip. */
    private final String defaultStyle;

    /**
     * Creates a strip bound to the given VCA group.
     *
     * @param group         the group to render
     * @param manager       the manager owning the group; mutations route through it
     * @param undoManager   undo manager for fader/assign actions; may be {@code null}
     * @param channelLookup resolves a member-channel UUID to its
     *                      {@link MixerChannel} so mute/solo can update each
     *                      member; pass {@code id -> null} if no mapping is
     *                      available (mute/solo become no-ops)
     * @param onChange      callback invoked after any structural change
     *                      (rename, color, delete, member-add/remove) so
     *                      {@link MixerView} can re-render dependent strips
     */
    public VcaStrip(VcaGroup group,
                    VcaGroupManager manager,
                    UndoManager undoManager,
                    Function<UUID, MixerChannel> channelLookup,
                    Runnable onChange) {
        this.group = Objects.requireNonNull(group, "group must not be null");
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.undoManager = undoManager;
        this.channelLookup = Objects.requireNonNull(channelLookup, "channelLookup must not be null");
        this.onChange = onChange != null ? onChange : () -> {};

        getStyleClass().addAll("mixer-channel", "vca-strip");
        setAlignment(Pos.TOP_CENTER);
        setPrefWidth(STRIP_WIDTH);
        setMinWidth(STRIP_WIDTH);
        setSpacing(4);
        this.defaultStyle = buildBorderStyle(group.color(), false);
        setStyle(defaultStyle);

        // ── VCA badge label (always visible, distinguishes from track strips) ──
        Label badge = new Label("VCA");
        badge.getStyleClass().add("mixer-channel-name");
        badge.setStyle("-fx-background-color: #455a64; -fx-text-fill: #ffffff;"
                + " -fx-padding: 1 6 1 6; -fx-font-weight: bold; -fx-font-size: 9px;"
                + " -fx-background-radius: 3;");

        // ── Editable name field ────────────────────────────────────────────
        nameField = new TextField(group.label());
        nameField.getStyleClass().add("mixer-channel-name");
        nameField.setMaxWidth(STRIP_WIDTH - 12);
        nameField.setTooltip(new Tooltip("VCA name (Enter to apply)"));
        Runnable applyName = () -> {
            String newName = nameField.getText();
            VcaGroup current = manager.getById(group.id());
            if (current == null) return;
            if (newName != null && !newName.isBlank()
                    && !newName.equals(current.label())) {
                manager.replace(current.withLabel(newName));
                onChange.run();
            } else {
                nameField.setText(current.label());
            }
        };
        nameField.setOnAction(_ -> applyName.run());
        nameField.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) applyName.run();
        });

        // ── Color swatch button ────────────────────────────────────────────
        ColorPicker colorPicker = new ColorPicker();
        colorPicker.setMaxWidth(STRIP_WIDTH - 12);
        colorPicker.setTooltip(new Tooltip("VCA color"));
        if (group.color() != null) {
            colorPicker.setValue(Color.web(group.color().getHexColor()));
        }
        colorPicker.setOnAction(_ -> {
            Color picked = colorPicker.getValue();
            if (picked == null) return;
            VcaGroup current = manager.getById(group.id());
            if (current == null) return;
            String hex = String.format("#%02X%02X%02X",
                    (int) Math.round(picked.getRed() * 255),
                    (int) Math.round(picked.getGreen() * 255),
                    (int) Math.round(picked.getBlue() * 255));
            manager.replace(current.withColor(TrackColor.custom(hex, "VCA")));
            onChange.run();
        });

        // ── Master gain fader (dB) ─────────────────────────────────────────
        gainFader = new Slider(VcaGroup.MIN_GAIN_DB, VcaGroup.MAX_GAIN_DB,
                group.masterGainDb());
        gainFader.setOrientation(Orientation.VERTICAL);
        gainFader.setPrefHeight(FADER_HEIGHT);
        gainFader.getStyleClass().add("mixer-fader");
        gainFader.setTooltip(new Tooltip("VCA master gain (dB)"));
        // Live preview while dragging; commit an undoable action on release
        // so the user gets a single, exact undo entry per fader move.
        double[] dragStart = {group.masterGainDb()};
        gainFader.setOnMousePressed(_ -> {
            VcaGroup current = manager.getById(group.id());
            dragStart[0] = current != null ? current.masterGainDb() : group.masterGainDb();
        });
        gainFader.valueProperty().addListener((_, _, v) -> {
            // Apply directly so meters / member previews update in real time.
            manager.setMasterGainDb(group.id(), v.doubleValue());
        });
        gainFader.setOnMouseReleased(_ -> {
            double finalValue = gainFader.getValue();
            // Restore pre-drag value so SetVcaGainAction.execute() captures it
            // as the previous value, then re-applies finalValue. Mirrors the
            // pattern already used by mixer send sliders.
            manager.setMasterGainDb(group.id(), dragStart[0]);
            if (undoManager != null) {
                undoManager.execute(new SetVcaGainAction(manager, group.id(), finalValue));
            } else {
                manager.setMasterGainDb(group.id(), finalValue);
            }
        });

        // ── Mute / Solo / Delete row ───────────────────────────────────────
        muteBtn = new Button("M");
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute every member channel"));
        muteBtn.setOnAction(_ -> applyToAllMembers(MixerChannel::isMuted, MixerChannel::setMuted));

        soloBtn = new Button("S");
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo every member channel"));
        soloBtn.setOnAction(_ -> applyToAllMembers(MixerChannel::isSolo, MixerChannel::setSolo));

        Button deleteBtn = new Button("✕");
        deleteBtn.getStyleClass().add("track-arm-button");
        deleteBtn.setTooltip(new Tooltip("Delete VCA group"));
        deleteBtn.setOnAction(_ -> {
            manager.removeVcaGroup(group.id());
            onChange.run();
        });

        HBox buttonRow = new HBox(2, muteBtn, soloBtn, deleteBtn);
        buttonRow.setAlignment(Pos.CENTER);

        // ── Members context menu (right-click anywhere on the strip) ───────
        ContextMenu membersMenu = new ContextMenu();
        if (group.memberChannelIds().isEmpty()) {
            MenuItem none = new MenuItem("(no members)");
            none.setDisable(true);
            membersMenu.getItems().add(none);
        } else {
            membersMenu.getItems().add(disabledHeader("Members"));
            for (UUID memberId : group.memberChannelIds()) {
                MixerChannel ch = channelLookup.apply(memberId);
                String name = ch != null ? ch.getName() : memberId.toString();
                MenuItem remove = new MenuItem("Remove “" + name + "”");
                remove.setOnAction(_ -> {
                    AssignVcaMemberAction action =
                            new AssignVcaMemberAction(manager, group.id(), memberId, false);
                    if (undoManager != null) {
                        undoManager.execute(action);
                    } else {
                        action.execute();
                    }
                    onChange.run();
                });
                membersMenu.getItems().add(remove);
            }
            membersMenu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem deleteItem = new MenuItem("Delete VCA");
        deleteItem.setOnAction(deleteBtn.getOnAction());
        membersMenu.getItems().add(deleteItem);
        setOnContextMenuRequested(e -> membersMenu.show(this, e.getScreenX(), e.getScreenY()));

        // ── Drag-and-drop drop target ──────────────────────────────────────
        installDropTarget();

        getChildren().addAll(badge, nameField, colorPicker, gainFader, buttonRow);
    }

    /** Returns the VCA group this strip currently renders. Visible for testing. */
    public VcaGroup getGroup() {
        return group;
    }

    /** Returns the editable name field. Visible for testing. */
    TextField getNameField() {
        return nameField;
    }

    /** Returns the master-gain fader. Visible for testing. */
    Slider getGainFader() {
        return gainFader;
    }

    /** Returns the mute button. Visible for testing. */
    Button getMuteButton() {
        return muteBtn;
    }

    /** Returns the solo button. Visible for testing. */
    Button getSoloButton() {
        return soloBtn;
    }

    private void installDropTarget() {
        setOnDragOver(event -> {
            if (event.getGestureSource() != this
                    && event.getDragboard().hasContent(CHANNEL_ID_FORMAT)) {
                event.acceptTransferModes(TransferMode.LINK, TransferMode.MOVE);
            }
            event.consume();
        });
        setOnDragEntered(event -> {
            if (event.getDragboard().hasContent(CHANNEL_ID_FORMAT)) {
                setStyle(buildBorderStyle(group.color(), true));
            }
            event.consume();
        });
        setOnDragExited(event -> {
            setStyle(defaultStyle);
            event.consume();
        });
        setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            Object payload = db.getContent(CHANNEL_ID_FORMAT);
            boolean ok = false;
            if (payload instanceof String s) {
                try {
                    UUID channelId = UUID.fromString(s);
                    VcaGroup current = manager.getById(group.id());
                    if (current == null) { event.setDropCompleted(false); event.consume(); return; }
                    boolean alreadyMember = current.hasMember(channelId);
                    AssignVcaMemberAction action = new AssignVcaMemberAction(
                            manager, group.id(), channelId, !alreadyMember);
                    if (undoManager != null) {
                        undoManager.execute(action);
                    } else {
                        action.execute();
                    }
                    onChange.run();
                    ok = true;
                } catch (IllegalArgumentException ignored) {
                    // not a UUID — drop ignored
                }
            }
            event.setDropCompleted(ok);
            setStyle(defaultStyle);
            event.consume();
        });
    }

    private void applyToAllMembers(java.util.function.Predicate<MixerChannel> getter,
                                   java.util.function.BiConsumer<MixerChannel, Boolean> setter) {
        // Toggle based on the *first* member's state, then propagate to all,
        // so the engineer sees a single coherent "all members on" / "all
        // members off" transition instead of a per-channel flip-flop.
        Boolean target = null;
        for (UUID id : group.memberChannelIds()) {
            MixerChannel ch = channelLookup.apply(id);
            if (ch == null) continue;
            if (target == null) target = !getter.test(ch);
            setter.accept(ch, target);
        }
        onChange.run();
    }

    private static MenuItem disabledHeader(String text) {
        MenuItem header = new MenuItem(text);
        header.setDisable(true);
        return header;
    }

    private static String buildBorderStyle(TrackColor color, boolean dropHighlight) {
        String hex = color != null ? color.getHexColor() : "#9c27b0";
        if (dropHighlight) {
            return "-fx-border-color: #ffeb3b; -fx-border-width: 3;"
                    + " -fx-background-color: derive(" + hex + ", -60%);";
        }
        return "-fx-border-color: " + hex + "; -fx-border-width: 2;"
                + " -fx-background-color: derive(" + hex + ", -75%);";
    }
}
