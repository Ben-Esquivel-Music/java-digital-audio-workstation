package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.RoomTelemetryDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Full-screen Sound Wave Telemetry view.
 *
 * <p>Wraps the {@link RoomTelemetryDisplay} canvas in a view panel with a
 * header label, suitable for display as a primary content view in the DAW's
 * center pane. An internal {@link AnimationTimer} drives continuous rendering
 * of particle animations, sonar ripples, and RT60 glow effects.</p>
 *
 * <p>Uses existing CSS classes: {@code .content-area}, {@code .panel-header},
 * {@code .placeholder-label}.</p>
 */
public final class TelemetryView extends VBox {

    private final RoomTelemetryDisplay display;
    private final AnimationTimer animationTimer;
    private long lastNanos;

    /**
     * Creates a new telemetry view panel.
     */
    public TelemetryView() {
        getStyleClass().add("content-area");
        setSpacing(0);

        // Header
        Label header = new Label("Sound Wave Telemetry");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.OSCILLOSCOPE, 16));
        header.setPadding(new Insets(6, 10, 6, 10));

        // Display canvas — fills all available space
        display = new RoomTelemetryDisplay();
        VBox.setVgrow(display, Priority.ALWAYS);

        getChildren().addAll(header, display);

        // Animation timer drives particle/ripple/pulse animations
        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastNanos == 0) {
                    lastNanos = now;
                    return;
                }
                double deltaSecs = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                display.updateAnimation(deltaSecs);
            }
        };
    }

    /**
     * Updates the telemetry data displayed in the view.
     *
     * @param data the latest telemetry snapshot (may be {@code null} to show placeholder)
     */
    public void setTelemetryData(RoomTelemetryData data) {
        display.setTelemetryData(data);
    }

    /**
     * Returns the underlying {@link RoomTelemetryDisplay}.
     *
     * @return the telemetry display canvas
     */
    public RoomTelemetryDisplay getDisplay() {
        return display;
    }

    /**
     * Starts the animation timer for continuous rendering.
     * Call this when the telemetry view becomes the active view.
     */
    public void startAnimation() {
        lastNanos = 0;
        animationTimer.start();
    }

    /**
     * Stops the animation timer to conserve resources.
     * Call this when the user switches away from the telemetry view.
     */
    public void stopAnimation() {
        animationTimer.stop();
    }
}
