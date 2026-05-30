package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.app.ui.layout.PanelDockRequestedEvent;

import javafx.beans.property.ObjectProperty;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;

import java.util.Objects;

/**
 * Installs the dock drop-target behaviour on the five {@code BorderPane}
 * zone slots of the main window: drag-over highlight, transfer acceptance,
 * and the {@link PanelDockRequestedEvent} fired on drop. This is the
 * <em>event producer</em> counterpart to the {@code rootPane} bridge in
 * {@code MainController.installLayoutManager()} (story 286) — the gesture
 * surface story 282 promised but 285 / 286 left unwired.
 *
 * <h2>Why bind to slot properties, not nodes</h2>
 *
 * <p>The main {@code BorderPane}'s slots are not stable nodes: the CENTER
 * slot is swapped on every view switch ({@code ViewNavigationController
 * .switchView()} does {@code rootPane.setCenter(target)}), the LEFT slot is
 * {@code null} while the browser is hidden, and the Performance-Stage
 * save/restore replaces TOP/LEFT/RIGHT/CENTER wholesale. So
 * {@link #bindSlot(ObjectProperty, DockZone)} attaches the drop handlers to
 * whatever {@link Node} currently occupies the slot and re-attaches them
 * (removing the old handlers first) whenever that node changes. The drop
 * zone follows the live content automatically.</p>
 *
 * <h2>Drag vocabulary isolation</h2>
 *
 * <p>Handlers are added with {@code addEventHandler} (never the
 * {@code setOnDragOver}/{@code setOnDragDropped} setters) so they compose
 * with the clip/sample DnD handlers already present on the arrangement and
 * browser nodes (story 248). For a non-dock drag the {@code DRAG_OVER}
 * handler returns <em>without consuming</em>, leaving the event to reach
 * the existing handlers; it reacts only when the dragboard carries
 * {@link PanelGripHandle#DOCK_PANEL_FORMAT}.</p>
 *
 * <h2>Testability</h2>
 *
 * <p>A {@link DragEvent} with a real {@link Dragboard} cannot be built
 * headless, so the side-effecting cores take already-extracted values:
 * {@link #applyHighlight(Region, boolean)} and
 * {@link #fireDock(DockZone, String)}. The installed handlers are thin
 * adapters over those.</p>
 */
public final class DockDropZones {

    /** Style class toggled on a zone node while a dock drag hovers it. */
    public static final String DROP_TARGET_ACTIVE_CLASS = "dock-drop-target-active";

    private final Node eventTarget;

    /**
     * Creates a drop-zone controller that fires dock events on
     * {@code eventTarget} (the {@code rootPane}, so events bubble to the
     * MainController bridge).
     *
     * @param eventTarget node that {@link PanelDockRequestedEvent}s are
     *                    fired on (non-null)
     */
    public DockDropZones(Node eventTarget) {
        this.eventTarget = Objects.requireNonNull(eventTarget, "eventTarget must not be null");
    }

    /**
     * Binds dock drop behaviour to a {@code BorderPane} slot. The handlers
     * follow the slot's content: they attach to the node currently in the
     * slot and re-attach (removing the old handlers) whenever the slot's
     * node changes. A {@code null} slot value detaches the handlers until a
     * node is installed again.
     *
     * @param slot the {@code BorderPane} slot property
     *             (e.g. {@code rootPane.centerProperty()})
     * @param zone the dock zone this slot represents
     */
    public void bindSlot(ObjectProperty<Node> slot, DockZone zone) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
        if (zone == DockZone.FLOATING) {
            throw new IllegalArgumentException("FLOATING is not a drop slot");
        }

        SlotBinding binding = new SlotBinding(zone);
        // The SlotBinding stays reachable through the slot's change-listener
        // below (which captures it), so no separate registry is needed.
        binding.attachTo(slot.get());
        slot.addListener((obs, oldNode, newNode) -> {
            binding.detachFrom(oldNode);
            binding.attachTo(newNode);
        });
    }

    /**
     * Adds or removes the {@link #DROP_TARGET_ACTIVE_CLASS} highlight on a
     * zone node. Per UI Design Book §7.3 the active state is a background
     * swap to {@code -accent-soft} via this style class — never a border —
     * and it is instantaneous (no fade, per the Reduce-Motion default of
     * story 279).
     *
     * @param zoneNode the node occupying the dock slot
     * @param active   {@code true} to highlight, {@code false} to clear
     */
    void applyHighlight(Region zoneNode, boolean active) {
        if (zoneNode == null) {
            return;
        }
        if (active) {
            if (!zoneNode.getStyleClass().contains(DROP_TARGET_ACTIVE_CLASS)) {
                zoneNode.getStyleClass().add(DROP_TARGET_ACTIVE_CLASS);
            }
        } else {
            zoneNode.getStyleClass().remove(DROP_TARGET_ACTIVE_CLASS);
        }
    }

    /**
     * Fires a {@link PanelDockRequestedEvent} carrying the geometric drop
     * zone on the configured event target so it bubbles to the MainController
     * bridge. The bridge coerces the panel to its canonical home zone (the
     * reconciler ignores arbitrary zones for the fixed-home panels) before
     * calling {@code DockManager.moveToEnd(...)}.
     *
     * @param zone    the dock zone the panel was dropped over
     * @param panelId the dragged panel's id
     */
    void fireDock(DockZone zone, String panelId) {
        eventTarget.fireEvent(new PanelDockRequestedEvent(panelId, zone));
    }

    /**
     * Holds the live handler instances for one bound slot so they can be
     * removed from the previous node when the slot's content changes, and
     * tracks the currently bound node so the highlight targets it directly
     * (rather than {@code DragEvent.getSource()}, which JavaFX rewrites per
     * node on bubble — {@code feedback_javafx_bubbling_event_test_pitfall.md}).
     * The highlight is applied only when the slot node is a {@link Region}
     * (every real {@code BorderPane} child here is one); for the rare
     * non-{@code Region} node the accept/drop logic still works, only the
     * background swap is skipped.
     */
    private final class SlotBinding {
        private final DockZone zone;
        private final EventHandler<DragEvent> overHandler;
        private final EventHandler<DragEvent> exitedHandler;
        private final EventHandler<DragEvent> droppedHandler;
        private Node boundNode;

        SlotBinding(DockZone zone) {
            this.zone = zone;
            this.overHandler = this::onDragOver;
            this.exitedHandler = this::onDragExited;
            this.droppedHandler = this::onDragDropped;
        }

        void attachTo(Node node) {
            if (node == null) {
                return;
            }
            boundNode = node;
            node.addEventHandler(DragEvent.DRAG_OVER, overHandler);
            node.addEventHandler(DragEvent.DRAG_EXITED, exitedHandler);
            node.addEventHandler(DragEvent.DRAG_DROPPED, droppedHandler);
        }

        void detachFrom(Node node) {
            if (node == null) {
                return;
            }
            node.removeEventHandler(DragEvent.DRAG_OVER, overHandler);
            node.removeEventHandler(DragEvent.DRAG_EXITED, exitedHandler);
            node.removeEventHandler(DragEvent.DRAG_DROPPED, droppedHandler);
            highlightBoundNode(node, false);
            if (boundNode == node) {
                boundNode = null;
            }
        }

        private void onDragOver(DragEvent event) {
            if (!hasDockPayload(event)) {
                // Not a dock drag — leave it for the existing clip/sample
                // handlers (do NOT consume).
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            highlightBoundNode(boundNode, true);
            event.consume();
        }

        private void onDragExited(DragEvent event) {
            highlightBoundNode(boundNode, false);
        }

        private void onDragDropped(DragEvent event) {
            if (!hasDockPayload(event)) {
                return;
            }
            Object payload = event.getDragboard().getContent(PanelGripHandle.DOCK_PANEL_FORMAT);
            String panelId = payload instanceof String s ? s : null;
            boolean completed = false;
            if (panelId != null && !panelId.isBlank()) {
                fireDock(zone, panelId);
                completed = true;
            }
            highlightBoundNode(boundNode, false);
            event.setDropCompleted(completed);
            event.consume();
        }

        private void highlightBoundNode(Node node, boolean active) {
            if (node instanceof Region region) {
                applyHighlight(region, active);
            }
        }

        private boolean hasDockPayload(DragEvent event) {
            Dragboard db = event.getDragboard();
            return db != null && db.hasContent(PanelGripHandle.DOCK_PANEL_FORMAT);
        }
    }
}
