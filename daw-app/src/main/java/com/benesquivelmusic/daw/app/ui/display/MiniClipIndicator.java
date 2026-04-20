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
 * Miniature clip indicator shown in the arrangement-view track header
 * (user story 137).
 *
 * <p>Mirrors the latching clip state of the same
 * {@link InputLevelMonitor} that drives the mixer's
 * {@link InputMeterStrip}, so engineers can see a clip warning while
 * looking at the timeline without having to switch to the mixer view.</p>
 *
 * <p>Click to reset the bound monitor's latch; {@code Alt+click} resets
 * every monitor in the bound registry.</p>
 */
public final class MiniClipIndicator extends Region {

    private static final double SIZE = 10.0;

    private static final Color OFF = Color.web("#2a0000");
    private static final Color ON = Color.web("#ff1744");
    private static final Color BORDER = Color.web("#000000", 0.5);

    private final Canvas canvas = new Canvas(SIZE, SIZE);
    private final InputLevelMonitor monitor;
    private final InputLevelMonitorRegistry registry;
    private InputLevelMeter lastSnapshot = InputLevelMeter.SILENCE;
    private AnimationTimer timer;

    public MiniClipIndicator(InputLevelMonitor monitor,
                             InputLevelMonitorRegistry registry) {
        this.monitor = Objects.requireNonNull(monitor, "monitor must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        setPrefSize(SIZE, SIZE);
        setMinSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
        getChildren().add(canvas);

        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.isAltDown()) {
                registry.resetAll();
            } else {
                monitor.reset();
            }
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
        render();
    }

    /** Stops the redraw timer so this indicator can be garbage-collected cleanly. */
    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    /** Returns the monitor this indicator is bound to. */
    public InputLevelMonitor getMonitor() {
        return monitor;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }

    private void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);

        boolean clipped = lastSnapshot.clippedSinceReset();
        gc.setFill(clipped ? ON : OFF);
        gc.fillOval(1, 1, SIZE - 2, SIZE - 2);
        gc.setStroke(BORDER);
        gc.setLineWidth(0.8);
        gc.strokeOval(1, 1, SIZE - 2, SIZE - 2);
    }
}
