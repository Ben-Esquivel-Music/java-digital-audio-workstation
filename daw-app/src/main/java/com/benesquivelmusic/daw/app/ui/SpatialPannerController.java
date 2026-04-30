package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.SpatialPannerDisplay;
import com.benesquivelmusic.daw.app.ui.spatial.SpatialTrajectoryOverlay;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationRecorder;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicEncoder;
import com.benesquivelmusic.daw.core.spatial.panner.VbapPanner;
import com.benesquivelmusic.daw.core.track.AutomationMode;
import com.benesquivelmusic.daw.sdk.spatial.*;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Controller linking a {@link SpatialPannerDisplay} to a {@link SpatialPanner}
 * and optionally an {@link AmbisonicEncoder}.
 *
 * <p>Handles mouse drag interactions on the display to update the 3D source
 * position in real time, recalculates speaker gains, and pushes the updated
 * visualization data to the display.</p>
 *
 * <p>The top-down view (left 65 %) controls azimuth and distance via X/Y
 * mouse coordinates. The side view (right 35 %) controls elevation via
 * vertical mouse movement.</p>
 */
public final class SpatialPannerController {

    private static final double DEFAULT_WINDOW_WIDTH = 520;
    private static final double DEFAULT_WINDOW_HEIGHT = 380;

    private final SpatialPanner panner;
    private final AmbisonicEncoder ambisonicEncoder;
    private final SpatialPannerDisplay display;
    private final String channelName;

    private boolean draggingTopView;
    private boolean draggingSideView;

    // ── Object-parameter automation (story 172) ────────────────────
    // The controller is decoupled from the automation system: when no
    // automation context is wired the controller behaves exactly as before.
    private AutomationData automationData;
    private AutomationRecorder automationRecorder;
    private String objectInstanceId;
    private SpatialTrajectoryOverlay trajectoryOverlay;
    private boolean recordTrajectoryArmed;
    private AutomationMode recordTrajectoryMode = AutomationMode.WRITE;
    /** Provides the current playhead time in beats while recording. */
    private java.util.function.DoubleSupplier playheadBeatsSupplier = () -> 0.0;

    /**
     * Creates a controller that binds a spatial panner to a display widget.
     *
     * @param panner           the spatial panner instance (usually a {@link VbapPanner})
     * @param ambisonicEncoder the optional Ambisonic encoder, or {@code null}
     * @param channelName      the mixer channel name (used in the window title)
     */
    public SpatialPannerController(SpatialPanner panner,
                                   AmbisonicEncoder ambisonicEncoder,
                                   String channelName) {
        this.panner = Objects.requireNonNull(panner, "panner must not be null");
        this.ambisonicEncoder = ambisonicEncoder;
        this.channelName = Objects.requireNonNull(channelName, "channelName must not be null");
        this.display = new SpatialPannerDisplay();
        wireMouseHandlers();
        refreshDisplay();
    }

    /**
     * Creates a controller with only a VBAP panner (no Ambisonic encoder).
     *
     * @param panner      the spatial panner instance
     * @param channelName the mixer channel name
     */
    public SpatialPannerController(SpatialPanner panner, String channelName) {
        this(panner, null, channelName);
    }

    /**
     * Returns the display widget managed by this controller.
     *
     * @return the display
     */
    public SpatialPannerDisplay getDisplay() {
        return display;
    }

    /**
     * Returns the panner managed by this controller.
     *
     * @return the spatial panner
     */
    public SpatialPanner getPanner() {
        return panner;
    }

    /**
     * Returns the channel name used for this controller.
     *
     * @return the channel name
     */
    public String getChannelName() {
        return channelName;
    }

    /**
     * Opens the 3D panner display in a new floating window.
     *
     * @return the created stage
     */
    public Stage openWindow() {
        Stage stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("3D Panner — " + channelName);

        StackPane root = new StackPane(display);
        root.setStyle("-fx-background-color: #0a0a1e;");

        Scene scene = new Scene(root, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        stage.setScene(scene);
        stage.setMinWidth(360);
        stage.setMinHeight(260);
        stage.show();

        refreshDisplay();
        return stage;
    }

    /**
     * Updates the source position and refreshes the display.
     *
     * @param position the new source position
     */
    public void setSourcePosition(SpatialPosition position) {
        Objects.requireNonNull(position, "position must not be null");
        panner.setPosition(position);
        syncAmbisonicEncoder(position);
        captureTrajectoryFrame(position);
        refreshDisplay();
    }

    /**
     * Refreshes the display with the current panner state.
     */
    public void refreshDisplay() {
        SpatialPannerData data = panner.getPannerData();
        display.update(data);
    }

    /**
     * Creates a default {@link VbapPanner} configured for the given speaker layout.
     *
     * @param layout the speaker layout
     * @return the configured panner
     */
    public static VbapPanner createDefaultPanner(SpeakerLayout layout) {
        Objects.requireNonNull(layout, "layout must not be null");
        List<SpatialPosition> positions = new ArrayList<>();
        for (SpeakerLabel label : layout.speakers()) {
            positions.add(label.toSpatialPosition());
        }
        return new VbapPanner(positions);
    }

    // ── Object-parameter automation (story 172) ───────────────────

    /**
     * Wires this controller to the track's automation infrastructure so that
     * the panner can produce {@link ObjectParameterTarget} lanes for its
     * X / Y / Z / SIZE / DIVERGENCE / GAIN parameters and capture spatial
     * trajectories during playback.
     *
     * <p>This method is optional. Until it is called, the controller behaves
     * exactly as before — context-menu items still build but cannot create
     * lanes, and {@link #setRecordTrajectoryArmed(boolean)} has no effect.</p>
     *
     * @param automationData     the track's automation container
     * @param automationRecorder the track's recorder (typically built on
     *                           {@code automationData})
     * @param objectInstanceId   stable id for this panner instance — the same
     *                           value the host stores against the track
     * @param beatsPerBar        time-signature numerator, used by the
     *                           trajectory overlay to compute its window
     */
    public void setAutomationContext(AutomationData automationData,
                                     AutomationRecorder automationRecorder,
                                     String objectInstanceId,
                                     double beatsPerBar) {
        this.automationData = Objects.requireNonNull(automationData,
                "automationData must not be null");
        this.automationRecorder = Objects.requireNonNull(automationRecorder,
                "automationRecorder must not be null");
        this.objectInstanceId = Objects.requireNonNull(objectInstanceId,
                "objectInstanceId must not be null");
        this.trajectoryOverlay = new SpatialTrajectoryOverlay(
                automationData, objectInstanceId, beatsPerBar);
    }

    /**
     * Returns the trajectory-overlay sampler, or {@code null} when no
     * automation context has been wired.
     */
    public SpatialTrajectoryOverlay getTrajectoryOverlay() {
        return trajectoryOverlay;
    }

    /**
     * Returns the bound automation data, or {@code null} when no context
     * has been wired via {@link #setAutomationContext}.
     */
    public AutomationData getAutomationData() {
        return automationData;
    }

    /** Returns the current object-instance id, or {@code null} if unset. */
    public String getObjectInstanceId() {
        return objectInstanceId;
    }

    /**
     * Sets the supplier of the current playhead position in beats. Used by
     * the record-trajectory mode to time-stamp captured automation points.
     */
    public void setPlayheadBeatsSupplier(java.util.function.DoubleSupplier supplier) {
        this.playheadBeatsSupplier = Objects.requireNonNull(supplier,
                "supplier must not be null");
    }

    /**
     * Returns the {@link ObjectParameterTarget} for the given parameter on
     * this panner instance. Throws if no automation context has been wired.
     */
    public ObjectParameterTarget targetFor(ObjectParameter parameter) {
        Objects.requireNonNull(parameter, "parameter must not be null");
        if (objectInstanceId == null) {
            throw new IllegalStateException(
                    "automation context not wired — call setAutomationContext first");
        }
        return new ObjectParameterTarget(objectInstanceId, parameter);
    }

    /**
     * Adds an automation lane for the given object parameter under this
     * panner's track (creating it if it does not yet exist), and returns
     * the lane. Equivalent of selecting "Automate &lt;param&gt;" from the
     * panner's right-click context menu.
     *
     * @param parameter the object parameter to automate
     * @return the lane (existing or newly created)
     */
    public AutomationLane automateParameter(ObjectParameter parameter) {
        ObjectParameterTarget target = targetFor(parameter);
        AutomationLane lane = automationData.getOrCreateObjectLane(target);
        lane.setVisible(true);
        return lane;
    }

    /**
     * Builds the per-parameter context-menu entries shown when the user
     * right-clicks the 3D panner. Each item, when selected, calls
     * {@link #automateParameter(ObjectParameter)} for its parameter so the
     * caller does not have to reproduce the wiring.
     *
     * @return one {@link MenuItem} per {@link ObjectParameter}
     */
    public List<MenuItem> buildAutomationMenuItems() {
        List<MenuItem> items = new ArrayList<>();
        for (ObjectParameter parameter : ObjectParameter.values()) {
            MenuItem item = new MenuItem("Automate " + parameter.displayName());
            ObjectParameter captured = parameter;
            item.setOnAction(e -> automateParameter(captured));
            items.add(item);
        }
        return items;
    }

    /** Sets the {@link AutomationMode} used while record-trajectory is armed. */
    public void setRecordTrajectoryMode(AutomationMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        if (!mode.writesAutomation()) {
            throw new IllegalArgumentException(
                    "mode must be a writing mode: " + mode);
        }
        this.recordTrajectoryMode = mode;
    }

    /**
     * Arms or disarms the record-trajectory mode. While armed, mouse-drag
     * motion on the panner (and any handle moves) is captured into the
     * X / Y / Z / SIZE / DIVERGENCE / GAIN lanes through
     * {@link AutomationRecorder} at the configured grid resolution.
     *
     * <p>Pressing it again stops capture. The captured points become editable
     * automation breakpoints.</p>
     *
     * @param armed {@code true} to start recording, {@code false} to stop
     * @return the undoable action representing the captured pass when
     *         disarming, or {@code null}
     */
    public com.benesquivelmusic.daw.core.undo.UndoableAction setRecordTrajectoryArmed(
            boolean armed) {
        if (armed == recordTrajectoryArmed) {
            return null;
        }
        if (automationRecorder == null) {
            // No-op when no context wired (matches "graceful degradation"
            // expected by the host menu wiring).
            return null;
        }
        if (armed) {
            automationRecorder.beginRecording(recordTrajectoryMode);
            recordTrajectoryArmed = true;
            return null;
        }
        com.benesquivelmusic.daw.core.undo.UndoableAction action =
                automationRecorder.finishRecording("Record Spatial Trajectory");
        recordTrajectoryArmed = false;
        return action;
    }

    /** Returns {@code true} if record-trajectory mode is currently armed. */
    public boolean isRecordTrajectoryArmed() {
        return recordTrajectoryArmed;
    }

    /**
     * Records the X / Y / Z components of the given position into the
     * corresponding object-parameter lanes at the current playhead. No-op
     * when not armed or when no automation context is wired.
     *
     * <p>Called automatically from the mouse-drag handlers; exposed publicly
     * so non-mouse handle moves (e.g. SIZE / DIVERGENCE / GAIN sliders) and
     * tests can drive the recorder.</p>
     *
     * @param position the new source position
     */
    public void captureTrajectoryFrame(SpatialPosition position) {
        if (!recordTrajectoryArmed || automationRecorder == null) {
            return;
        }
        double t = playheadBeatsSupplier.getAsDouble();
        if (t < 0.0) {
            return;
        }
        double x = clampUnit(position.x());
        double y = clampUnit(position.y());
        double z = clampUnit(position.z());
        automationRecorder.recordValue(targetFor(ObjectParameter.X), t, x);
        automationRecorder.recordValue(targetFor(ObjectParameter.Y), t, y);
        automationRecorder.recordValue(targetFor(ObjectParameter.Z), t, z);
    }

    /**
     * Records a single object-parameter value at the current playhead — the
     * hook used by handle moves (SIZE / DIVERGENCE / GAIN). No-op when not
     * armed.
     */
    public void captureParameterValue(ObjectParameter parameter, double value) {
        if (!recordTrajectoryArmed || automationRecorder == null) {
            return;
        }
        double t = playheadBeatsSupplier.getAsDouble();
        if (t < 0.0) {
            return;
        }
        automationRecorder.recordValue(targetFor(parameter), t, value);
    }

    private static double clampUnit(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    // ── Mouse interaction ─────────────────────────────────────────

    private void wireMouseHandlers() {
        display.setOnMousePressed(this::onMousePressed);
        display.setOnMouseDragged(this::onMouseDragged);
        display.setOnMouseReleased(this::onMouseReleased);
    }

    private void onMousePressed(MouseEvent event) {
        double w = display.getWidth();
        double h = display.getHeight();
        double drawHeight = h - SpatialPannerDisplay.READOUT_HEIGHT;
        double topViewWidth = w * SpatialPannerDisplay.TOP_VIEW_RATIO;

        if (event.getX() < topViewWidth && event.getY() < drawHeight) {
            draggingTopView = true;
            updateFromTopView(event.getX(), event.getY(), w, drawHeight);
        } else if (event.getX() >= topViewWidth && event.getY() < drawHeight) {
            draggingSideView = true;
            updateFromSideView(event.getY(), w, drawHeight);
        }
    }

    private void onMouseDragged(MouseEvent event) {
        double w = display.getWidth();
        double h = display.getHeight();
        double drawHeight = h - SpatialPannerDisplay.READOUT_HEIGHT;

        if (draggingTopView) {
            updateFromTopView(event.getX(), event.getY(), w, drawHeight);
        } else if (draggingSideView) {
            updateFromSideView(event.getY(), w, drawHeight);
        }
    }

    private void onMouseReleased(MouseEvent event) {
        draggingTopView = false;
        draggingSideView = false;
    }

    private void updateFromTopView(double mouseX, double mouseY,
                                   double totalWidth, double drawHeight) {
        double topViewWidth = totalWidth * SpatialPannerDisplay.TOP_VIEW_RATIO;
        double topCenterX = topViewWidth / 2.0;
        double topCenterY = drawHeight / 2.0;
        double topRadius = Math.min(topViewWidth, drawHeight) / 2.0 - SpatialPannerDisplay.PADDING;

        if (topRadius <= 0) {
            return;
        }

        double spatialX = SpatialPannerDisplay.pixelXToSpatialX(mouseX, topCenterX, topRadius);
        double spatialY = SpatialPannerDisplay.pixelYToSpatialY(mouseY, topCenterY, topRadius);

        // Preserve current elevation
        SpatialPosition current = panner.getPosition();
        SpatialPosition newPos = SpatialPosition.fromCartesian(spatialX, spatialY, current.z());

        panner.setPosition(newPos);
        syncAmbisonicEncoder(newPos);
        captureTrajectoryFrame(newPos);
        refreshDisplay();
    }

    private void updateFromSideView(double mouseY, double totalWidth, double drawHeight) {
        double sideViewWidth = totalWidth * SpatialPannerDisplay.SIDE_VIEW_RATIO;
        double sideCenterY = drawHeight / 2.0;
        double sideRadius = Math.min(sideViewWidth, drawHeight) / 2.0 - SpatialPannerDisplay.PADDING;

        if (sideRadius <= 0) {
            return;
        }

        double spatialZ = SpatialPannerDisplay.pixelYToSpatialZ(mouseY, sideCenterY, sideRadius);

        // Preserve current azimuth and X/Y
        SpatialPosition current = panner.getPosition();
        SpatialPosition newPos = SpatialPosition.fromCartesian(current.x(), current.y(), spatialZ);

        panner.setPosition(newPos);
        syncAmbisonicEncoder(newPos);
        captureTrajectoryFrame(newPos);
        refreshDisplay();
    }

    private void syncAmbisonicEncoder(SpatialPosition position) {
        if (ambisonicEncoder != null) {
            double azRad = Math.toRadians(position.azimuthDegrees());
            double elRad = Math.toRadians(position.elevationDegrees());
            ambisonicEncoder.setDirection(azRad, elRad);
        }
    }
}
