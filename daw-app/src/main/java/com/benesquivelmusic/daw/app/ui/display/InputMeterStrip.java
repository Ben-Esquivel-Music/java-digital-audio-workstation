package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * Input-signal meter column used for the second mixer-strip meter on armed
 * tracks (user story 137).
 *
 * <p>Renders a vertical LED-style bar with green (below −18 dBFS), yellow
 * (−18 to −6 dBFS), and red (−6 to 0 dBFS) segments, plus a latching red
 * clip LED above the bar that lights whenever the bound
 * {@link InputLevelMonitor} reports
 * {@link InputLevelMeter#clippedSinceReset() clippedSinceReset == true}.</p>
 *
 * <h2>Interaction</h2>
 *
 * <ul>
 *     <li><b>Click</b> on the clip LED — resets the bound monitor.</li>
 *     <li><b>Alt+click</b> on the clip LED — resets every monitor in the
 *     bound {@link InputLevelMonitorRegistry} ("clear all clips" gesture).</li>
 * </ul>
 *
 * <p>The strip is driven by a lightweight {@link AnimationTimer} that polls
 * {@link InputLevelMonitor#snapshot()} each frame; the monitor itself owns
 * real-time metering, so no audio-thread work happens in the UI layer.</p>
 */
public final class InputMeterStrip extends Region {

    private static final double CLIP_LED_HEIGHT = 10.0;
    private static final double LED_GAP = 2.0;

    private static final double MIN_DB = -60.0;
    private static final double MAX_DB = 0.0;

    // Color thresholds — match the conventional green/yellow/red LED zones
    // used on hardware consoles and referenced in the user story.
    private static final double GREEN_UPPER_DB = -18.0;
    private static final double YELLOW_UPPER_DB = -6.0;

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color GREEN_ON = Color.web("#00e676");
    private static final Color GREEN_OFF = Color.web("#003d1a");
    private static final Color YELLOW_ON = Color.web("#ffea00");
    private static final Color YELLOW_OFF = Color.web("#403d00");
    private static final Color RED_ON = Color.web("#ff1744");
    private static final Color RED_OFF = Color.web("#400a0d");
    private static final Color CLIP_OFF = Color.web("#2a0000");
    private static final Color LED_BORDER = Color.web("#000000", 0.4);

    private final Canvas canvas = new Canvas();
    private final InputLevelMonitor monitor;
    private final InputLevelMonitorRegistry registry;

    private InputLevelMeter lastSnapshot = InputLevelMeter.SILENCE;
    private AnimationTimer timer;

    /**
     * Creates a strip bound to a specific monitor and registry.
     *
     * @param monitor  the monitor to visualize (must not be {@code null})
     * @param registry the registry used for {@code Alt+click} "reset all"
     *                 (must not be {@code null})
     */
    public InputMeterStrip(InputLevelMonitor monitor,
                           InputLevelMonitorRegistry registry) {
        this.monitor = Objects.requireNonNull(monitor, "monitor must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());

        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            // Only the top area (clip LED) resets. Click anywhere else on
            // the bar is a no-op so users can't accidentally wipe clip
            // state by touching the meter body.
            if (event.getY() > CLIP_LED_HEIGHT + LED_GAP) {
                return;
            }
            if (event.isAltDown()) {
                registry.resetAll();
            } else {
                monitor.reset();
            }
            // Reflect immediately in the UI without waiting for next tick.
            lastSnapshot = monitor.snapshot();
            render();
        });

        start();
    }

    /** Starts the redraw timer. Idempotent. */
    public void start() {
        if (timer != null) {
            return;
        }
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                InputLevelMeter snap = monitor.snapshot();
                if (snap != lastSnapshot) {
                    lastSnapshot = snap;
                    render();
                }
            }
        };
        timer.start();
    }

    /** Stops the redraw timer so this strip can be garbage-collected cleanly. */
    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    /** Returns the monitor this strip is bound to. */
    public InputLevelMonitor getMonitor() {
        return monitor;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }

    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        double clipTop = 0.0;
        double clipBottom = CLIP_LED_HEIGHT;
        double barTop = clipBottom + LED_GAP;
        double barHeight = Math.max(0.0, h - barTop);

        // ── Clip LED ────────────────────────────────────────────────────
        boolean clipped = lastSnapshot.clippedSinceReset();
        gc.setFill(clipped ? RED_ON : CLIP_OFF);
        gc.fillRect(1, clipTop, w - 2, clipBottom);
        gc.setStroke(LED_BORDER);
        gc.setLineWidth(0.8);
        gc.strokeRect(1, clipTop, w - 2, clipBottom);

        if (barHeight <= 0) {
            return;
        }

        // ── Segmented LED bar (green → yellow → red) ────────────────────
        double peakDb = lastSnapshot.peakDbfs();
        // Draw 12 segments from bottom to top.
        int segments = 12;
        double segH = barHeight / segments;
        double segGap = Math.min(1.0, segH * 0.15);

        for (int i = 0; i < segments; i++) {
            // Segment upper-edge dB value (0 at top, −60 at bottom).
            double segDbTop = MAX_DB - (MAX_DB - MIN_DB) * ((double) (segments - i - 1) / segments);

            Color onColor;
            Color offColor;
            if (segDbTop > YELLOW_UPPER_DB) {
                onColor = RED_ON;
                offColor = RED_OFF;
            } else if (segDbTop > GREEN_UPPER_DB) {
                onColor = YELLOW_ON;
                offColor = YELLOW_OFF;
            } else {
                onColor = GREEN_ON;
                offColor = GREEN_OFF;
            }

            boolean on = peakDb >= segDbTop - (MAX_DB - MIN_DB) / segments;
            gc.setFill(on ? onColor : offColor);
            double y = barTop + i * segH;
            gc.fillRect(1, y + segGap * 0.5, w - 2, segH - segGap);
        }
    }
}
