package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.SpatialPannerDisplay;
import com.benesquivelmusic.daw.core.spatial.ambisonics.AmbisonicEncoder;
import com.benesquivelmusic.daw.core.spatial.panner.VbapPanner;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPanner;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPannerData;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import javafx.scene.Scene;
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
