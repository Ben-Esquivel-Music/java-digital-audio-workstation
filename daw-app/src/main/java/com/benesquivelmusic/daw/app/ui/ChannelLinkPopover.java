package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.ChannelLinkManager;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.UnlinkChannelsAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.Objects;

/**
 * A small floating popover exposing the per-attribute toggles and
 * {@link LinkMode} selector of a single {@link ChannelLink} — the link-detail
 * view opened by right-clicking the chain glyph between two paired
 * {@code MixerChannel} strips (Story 159 — Mixer Channel Link).
 *
 * <p>The popover edits the link by calling
 * {@link ChannelLinkManager#replace(ChannelLink)} so every change is
 * immediately visible to listeners (the {@code MixerView} that owns the
 * chain icon and the L/R badges) and survives a project save/load round
 * trip via {@code ProjectSerializer}. The "Unlink" button removes the link
 * via {@link UnlinkChannelsAction} so the operation is undoable like any
 * other mixer edit.</p>
 *
 * <p>Threading: all popover interactions happen on the JavaFX application
 * thread; no separate synchronisation is required.</p>
 */
public final class ChannelLinkPopover {

    private final ChannelLinkManager manager;
    private final UndoManager undoManager;
    private final Popup popup;
    /**
     * The link the popover is currently editing. Replaced via
     * {@link ChannelLinkManager#replace(ChannelLink)} on every toggle so the
     * stored value mirrors the manager state.
     */
    private ChannelLink currentLink;

    private final ComboBox<LinkMode> modeCombo;
    private final CheckBox fadersBox;
    private final CheckBox pansBox;
    private final CheckBox muteSoloBox;
    private final CheckBox insertsBox;
    private final CheckBox sendsBox;
    private final Button unlinkButton;

    /**
     * Creates a new popover bound to the given link.
     *
     * @param manager     the link manager that owns {@code link}
     * @param undoManager the undo manager to push the "Unlink" action onto;
     *                    may be {@code null} for unit-test fixtures with no
     *                    undo history
     * @param link        the link to edit
     */
    public ChannelLinkPopover(ChannelLinkManager manager,
                              UndoManager undoManager,
                              ChannelLink link) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
        this.undoManager = undoManager;
        this.currentLink = Objects.requireNonNull(link, "link must not be null");

        Label header = new Label("Channel Link");
        header.setStyle("-fx-font-weight: bold;");

        Label modeLabel = new Label("Mode:");
        modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(LinkMode.values());
        modeCombo.getSelectionModel().select(link.mode());
        modeCombo.setOnAction(_ -> applyChange(currentLink.withMode(modeCombo.getValue())));
        HBox modeRow = new HBox(6, modeLabel, modeCombo);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        fadersBox   = checkBox("Link Faders",      link.linkFaders(),
                v -> currentLink.withLinkFaders(v));
        pansBox     = checkBox("Link Pans",        link.linkPans(),
                v -> currentLink.withLinkPans(v));
        muteSoloBox = checkBox("Link Mute / Solo", link.linkMuteSolo(),
                v -> currentLink.withLinkMuteSolo(v));
        insertsBox  = checkBox("Link Inserts",     link.linkInserts(),
                v -> currentLink.withLinkInserts(v));
        sendsBox    = checkBox("Link Sends",       link.linkSends(),
                v -> currentLink.withLinkSends(v));

        unlinkButton = new Button("Unlink");
        unlinkButton.setTooltip(new javafx.scene.control.Tooltip(
                "Remove the link. Both channels keep their current values."));
        unlinkButton.setOnAction(_ -> doUnlink());

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox bottomRow = new HBox(6, spacer, unlinkButton);
        bottomRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(6,
                header, new Separator(), modeRow,
                fadersBox, pansBox, muteSoloBox, insertsBox, sendsBox,
                new Separator(), bottomRow);
        root.setPadding(new Insets(8));
        root.getStyleClass().add("channel-link-popover");
        // Inline style avoids depending on a CSS rule that may not be loaded
        // in headless tests; matches the dark mixer chrome.
        root.setStyle(
                "-fx-background-color: #2a2a2a;"
                        + " -fx-border-color: #5a5a5a;"
                        + " -fx-border-width: 1;"
                        + " -fx-text-fill: #eeeeee;");
        root.setMinWidth(180);

        popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(root);
    }

    private CheckBox checkBox(String label, boolean initial,
                              java.util.function.Function<Boolean, ChannelLink> withFn) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected(initial);
        cb.setStyle("-fx-text-fill: #eeeeee;");
        cb.setOnAction(_ -> applyChange(withFn.apply(cb.isSelected())));
        return cb;
    }

    private void applyChange(ChannelLink updated) {
        manager.replace(updated);
        // Track the latest version so subsequent edits chain correctly.
        currentLink = updated;
    }

    private void doUnlink() {
        UnlinkChannelsAction action =
                new UnlinkChannelsAction(manager, currentLink.leftChannelId());
        if (undoManager != null) {
            undoManager.execute(action);
        } else {
            action.execute();
        }
        popup.hide();
    }

    /** Shows the popover anchored relative to the given owner node's screen position. */
    public void show(Node owner, double screenX, double screenY) {
        Window window = owner.getScene() != null ? owner.getScene().getWindow() : null;
        if (window != null) {
            popup.show(window, screenX, screenY);
        } else {
            popup.show(owner, screenX, screenY);
        }
    }

    /** Hides the popover if it is currently visible. */
    public void hide() {
        popup.hide();
    }

    /** Returns whether the popover is currently visible. Visible for testing. */
    public boolean isShowing() {
        return popup.isShowing();
    }

    /** Returns the current edited link. Visible for testing. */
    public ChannelLink getCurrentLink() {
        return currentLink;
    }

    // Visible for testing
    ComboBox<LinkMode> getModeCombo() { return modeCombo; }
    CheckBox getFadersBox()           { return fadersBox; }
    CheckBox getPansBox()             { return pansBox; }
    CheckBox getMuteSoloBox()         { return muteSoloBox; }
    CheckBox getInsertsBox()          { return insertsBox; }
    CheckBox getSendsBox()            { return sendsBox; }
    Button getUnlinkButton()          { return unlinkButton; }
}
