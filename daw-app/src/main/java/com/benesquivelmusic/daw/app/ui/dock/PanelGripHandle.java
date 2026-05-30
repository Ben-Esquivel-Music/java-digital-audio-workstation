package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;
import com.benesquivelmusic.daw.app.ui.layout.PanelDetachRequestedEvent;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.Objects;

/**
 * Reusable {@code ⋮⋮} grip affordance mounted in the chrome of every
 * dockable panel (UI Design Book §4 Concept D mockup). Dragging the grip
 * starts a JavaFX drag-and-drop gesture that drives the panel
 * detach / re-dock flow promised by story 282 but not shipped by stories
 * 285 / 286 (which delivered only the {@code DockManager} machinery and the
 * receiving event bridge).
 *
 * <p>This is a one-off application layout node, so it is a plain
 * {@link Region} subclass (an {@link HBox} wrapping a single
 * {@link DawgIcon}) rather than a {@code Control}/{@code Skin} pair, per
 * the JavaFX application-design skill (§3 / §16). It contributes one
 * affordance and one gesture and nothing else.</p>
 *
 * <h2>Drag vocabulary</h2>
 *
 * <p>The gesture carries the panel id under the dedicated
 * {@link #DOCK_PANEL_FORMAT} {@link DataFormat}. The dock drop zones
 * ({@code DockDropZones}) react <em>only</em> to that format, so this
 * vocabulary stays distinct from the clip/sample drags (story 248's
 * {@code DragVisualAdvisor} path, which uses {@code putString}) and the
 * mixer channel-strip drags ({@code VcaStrip.CHANNEL_ID_FORMAT}). The
 * grip's own {@code setOnDragDetected} consumes the event so it never
 * collides with a sibling strip gesture.</p>
 *
 * <h2>Detach vs. re-dock</h2>
 *
 * <ul>
 *   <li>Released over <em>no</em> accepting target → {@link #completeGesture}
 *       fires a {@link PanelDetachRequestedEvent} (with drop-point bounds)
 *       on this node; it bubbles to the {@code rootPane} bridge in
 *       {@code MainController.installLayoutManager()} which calls
 *       {@code DockManager.float_(panelId, bounds)}.</li>
 *   <li>Released over a dock zone → that zone accepts the transfer and fires
 *       the {@code PanelDockRequestedEvent} itself, so this node does
 *       nothing on drag-done.</li>
 * </ul>
 *
 * <p>Real drag-and-drop {@code DragEvent}s cannot be synthesised in a
 * headless test (a {@link Dragboard} is not constructible), so the
 * side-effecting tail of the gesture is factored into the
 * {@link #completeGesture(TransferMode, double, double)} seam that tests
 * invoke directly. The seam is {@code public} only because constructing a
 * {@code PanelGripHandle} (it builds a {@code DawgIcon} and installs a
 * {@code Tooltip}) requires the started JavaFX toolkit, which in turn
 * pins its test into the {@code …app.ui} package — a different package
 * from this class — by the JPMS {@code @ExtendWith} quirk
 * ({@code project_extendwith_jpms_test_env.md}). The class itself is
 * internal to the non-exported {@code daw.app} module, so this widens no
 * public surface.</p>
 */
public final class PanelGripHandle extends HBox {

    /**
     * Custom drag {@link DataFormat} carrying the dragged panel's id.
     * Resolved via lookup-or-create because a bare {@code new
     * DataFormat(mime)} throws {@code IllegalArgumentException} if the mime
     * type is already registered — which happens in the shared Surefire
     * test fork the moment a second {@code PanelGripHandle} class is loaded.
     */
    public static final DataFormat DOCK_PANEL_FORMAT = resolveFormat();

    private static DataFormat resolveFormat() {
        DataFormat existing = DataFormat.lookupMimeType("application/x-dawg-dock-panel");
        return existing != null ? existing : new DataFormat("application/x-dawg-dock-panel");
    }

    private final String panelId;
    private final Region boundsSource;

    /**
     * Creates a grip handle for the panel identified by {@code panelId}.
     *
     * @param panelId      stable {@code Dockable#dockId()} of the host panel
     *                     (non-null, non-blank)
     * @param boundsSource the panel node whose current width/height sizes
     *                     the floating window when the panel is detached
     *                     (non-null; panels pass {@code this})
     */
    public PanelGripHandle(String panelId, Region boundsSource) {
        this.panelId = Objects.requireNonNull(panelId, "panelId must not be null");
        if (panelId.isBlank()) {
            throw new IllegalArgumentException("panelId must not be blank");
        }
        this.boundsSource = Objects.requireNonNull(boundsSource, "boundsSource must not be null");

        getStyleClass().add("dock-grip");
        getChildren().add(DawgIcon.of("grip-vertical", DawgIcon.Size.SIZE_16));

        Tooltip.install(this, new Tooltip("Drag to detach or re-dock"));
        setAccessibleText("Drag handle — detach or re-dock this panel");

        setOnDragDetected(event -> {
            Dragboard db = startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(DOCK_PANEL_FORMAT, panelId);
            db.setContent(content);
            // No explicit drag view: the panel stays put during the gesture
            // and the platform shows its default move cursor. The drop zones
            // give the visual feedback (the §7.3 background-swap highlight),
            // so rasterising a drag image here would add nothing.
            event.consume();
        });

        setOnDragDone(event ->
                completeGesture(event.getTransferMode(), event.getScreenX(), event.getScreenY()));
    }

    /** Stable {@code Dockable#dockId()} this grip detaches / re-docks. */
    public String panelId() {
        return panelId;
    }

    /**
     * Side-effecting tail of the drag gesture, extracted as a testable seam
     * (a real {@code DragEvent} with a {@link Dragboard} cannot be built
     * headless).
     *
     * <p>When {@code transferMode} is {@code null} the drop landed on no
     * accepting target, so the panel should detach: a
     * {@link PanelDetachRequestedEvent} is fired on this node (bubbling to
     * the scene-root bridge) carrying drop-point bounds when they can be
     * derived. When {@code transferMode} is non-null a dock zone already
     * accepted the drop and fired the dock event, so this method does
     * nothing.</p>
     *
     * @param transferMode  the completed transfer mode, or {@code null} if
     *                      the drop was not accepted by any target
     * @param dropScreenX   screen x of the release point
     * @param dropScreenY   screen y of the release point
     */
    public void completeGesture(TransferMode transferMode, double dropScreenX, double dropScreenY) {
        if (transferMode != null) {
            // A dock zone accepted the drop and fired PanelDockRequestedEvent.
            return;
        }
        fireEvent(new PanelDetachRequestedEvent(panelId, detachBounds(dropScreenX, dropScreenY)));
    }

    /**
     * Builds the floating-window bounds for a detach, anchored at the drop
     * point and sized from the host panel's current dimensions. Returns
     * {@code null} (let {@code DockManager} fall back to remembered/default
     * bounds) when the panel has no measured size yet or the drop point is
     * non-finite, so a {@link Rectangle2D} is never constructed with a
     * negative or NaN extent.
     */
    private Rectangle2D detachBounds(double dropScreenX, double dropScreenY) {
        double w = boundsSource.getWidth();
        double h = boundsSource.getHeight();
        if (w <= 0 || h <= 0
                || !Double.isFinite(dropScreenX) || !Double.isFinite(dropScreenY)) {
            return null;
        }
        return new Rectangle2D(dropScreenX, dropScreenY, w, h);
    }
}
