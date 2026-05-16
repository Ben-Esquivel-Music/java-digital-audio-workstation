package com.benesquivelmusic.daw.app.ui.controls.skin;

import com.benesquivelmusic.daw.app.ui.controls.Knob;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Skin for {@link Knob} — renders the §5.8 dial onto a plain {@link Canvas}
 * and wires up the §2.8 keyboard / mouse interaction contract.
 *
 * <h2>Geometry</h2>
 *
 * <p>Every internal dimension (border stroke width, arc radius, indicator
 * length, focus-ring offset, font size) is derived from
 * {@code size = Math.min(w, h)} inside {@link #layoutChildren(double,
 * double, double, double)}. There are no fixed pixel offsets — the knob
 * remains circular and correctly proportioned at any size a parent
 * assigns, not just the CSS default.
 *
 * <h2>Drawing rules (UI Design Book §5.8)</h2>
 *
 * <ol>
 *   <li>Dial: filled circle in {@code -knob-dial-color}, 1-px
 *       {@code -knob-border-color} border.</li>
 *   <li>Travel arc: 1.5-px stroke along the outer edge — full sweep in
 *       {@code -knob-track-color} as the underlay, then
 *       {@code -knob-arc-color} overlay from min→current (unipolar) or
 *       centre→current (bipolar).</li>
 *   <li>Indicator: a 2-px line from centre to dial edge at the current
 *       angle, in {@code -knob-indicator-color}.</li>
 *   <li>Focus ring: a thin ring outside the dial when {@code :focused},
 *       in {@code -knob-focus-ring-color}.</li>
 * </ol>
 *
 * <p>The dial's angular travel runs from {@code 225°} (lower-left,
 * 7:30 position) clockwise to {@code -45°} / {@code 315°} (lower-right,
 * 4:30 position), a 270° sweep with the 12-o'clock detent at the top.
 *
 * <h2>Interaction (UI Design Book §2.8)</h2>
 *
 * <ul>
 *   <li>Vertical drag: full range over 200 px by default
 *       (overridable via {@code -knob-drag-sensitivity}).
 *       {@code Shift}: 10× slower.</li>
 *   <li>{@code Ctrl/Cmd + click}: reset to default. Double-click: reset.</li>
 *   <li>Scroll wheel: {@code (max - min) / 100} per notch; Shift: 10×
 *       finer.</li>
 *   <li>Keyboard: ↑/Right and ↓/Left step by 1%; Shift: 10× finer;
 *       PgUp/PgDn: 10× coarser; Home/End: min/max; {@code 0}: reset;
 *       Enter: open the value-entry popover (stub — fires an
 *       accessibility event hook for now).</li>
 * </ul>
 *
 * <p>The centre-detent click animation (60 ms ease) is suppressed when
 * {@link Knob#isAnimated()} is {@code false} (Reduce Motion).
 */
public final class KnobSkin extends SkinBase<Knob> {

    /** Total sweep of the dial in degrees (UI Design Book §5.8). */
    private static final double SWEEP_DEG = 270.0;
    /** Start angle of the sweep (lower-left, 7:30 on the dial). */
    private static final double START_DEG = 225.0;

    /** Dial border stroke (px) at the default 28-px size. Scales with size. */
    private static final double BORDER_FRAC = 1.0 / 28.0;
    /** Travel-arc stroke (px) at the default 28-px size. */
    private static final double ARC_STROKE_FRAC = 1.5 / 28.0;
    /** Indicator stroke (px) at the default 28-px size. */
    private static final double INDICATOR_STROKE_FRAC = 2.0 / 28.0;
    /** Focus-ring inset (px) outside the dial at the default 28-px size. */
    private static final double FOCUS_RING_OFFSET_FRAC = 3.0 / 28.0;
    /** Centre-detent animation duration. */
    private static final Duration DETENT_DURATION = Duration.millis(60);

    private final Canvas canvas;

    private final ChangeListener<Object> repaintListener;
    private final ChangeListener<Boolean> focusListener;
    private final javafx.event.EventHandler<KeyEvent> keyHandler;

    /**
     * Drives the visual position during a centre-detent snap animation.
     * Distinct from {@link Knob#valueProperty()} so the animation can
     * play without writing intermediate values back to the model.
     */
    private final SimpleDoubleProperty animatedValue = new SimpleDoubleProperty();
    private Timeline detentTimeline;

    private double dragAnchorY;
    private double dragAnchorValue;
    private boolean dragging;
    private boolean disposed;
    private int registeredListenerCount;

    /**
     * @param control the {@link Knob} this skin renders
     */
    public KnobSkin(Knob control) {
        super(control);

        canvas = new Canvas();
        canvas.setFocusTraversable(false);
        getChildren().add(canvas);

        repaintListener = (obs, o, n) -> {
            if (!disposed) {
                paint();
            }
        };
        focusListener = (obs, was, now) -> {
            if (!disposed) {
                paint();
            }
        };

        addRepaint(control.valueProperty());
        addRepaint(control.minProperty());
        addRepaint(control.maxProperty());
        addRepaint(control.bipolarProperty());
        addRepaint(control.unitProperty());
        addRepaint(control.knobDialColorProperty());
        addRepaint(control.knobBorderColorProperty());
        addRepaint(control.knobArcColorProperty());
        addRepaint(control.knobTrackColorProperty());
        addRepaint(control.knobIndicatorColorProperty());
        addRepaint(control.knobFocusRingColorProperty());

        control.focusedProperty().addListener(focusListener);
        registeredListenerCount++;

        // Mouse interaction.
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> dragging = false);
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onMouseClicked);
        canvas.addEventHandler(ScrollEvent.SCROLL, this::onScroll);

        // Keyboard interaction routes through the control (it owns focus).
        keyHandler = this::onKeyPressed;
        control.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
        registeredListenerCount++;
    }

    private void addRepaint(ObservableValue<?> v) {
        @SuppressWarnings("unchecked")
        ChangeListener<Object> l = (ChangeListener<Object>) repaintListener;
        v.addListener(l);
        registeredListenerCount++;
    }

    private void removeRepaint(ObservableValue<?> v) {
        @SuppressWarnings("unchecked")
        ChangeListener<Object> l = (ChangeListener<Object>) repaintListener;
        v.removeListener(l);
    }

    /** @return the backing canvas (test seam). */
    public Canvas canvas() {
        return canvas;
    }

    /** @return whether a centre-detent animation is currently running. */
    public boolean isDetentAnimationRunning() {
        return detentTimeline != null
                && detentTimeline.getStatus() == Animation.Status.RUNNING;
    }

    /** @return whether {@link #dispose()} has run. */
    public boolean isDisposed() {
        return disposed;
    }

    /** @return the number of still-registered listeners (test seam). */
    public int registeredListenerCount() {
        return registeredListenerCount;
    }

    // ── Interaction ───────────────────────────────────────────────────────

    private void onMousePressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Knob c = getSkinnable();
        c.requestFocus();
        if (e.isShortcutDown()) {
            // Ctrl/Cmd + click → reset to default.
            resetToDefault();
            return;
        }
        dragAnchorY = e.getScreenY();
        dragAnchorValue = c.getValue();
        dragging = true;
        e.consume();
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging) {
            return;
        }
        Knob c = getSkinnable();
        double range = c.getMax() - c.getMin();
        if (range <= 0) {
            return;
        }
        double sensitivity = Math.max(1.0, c.getKnobDragSensitivity());
        if (e.isShiftDown()) {
            // Shift → 10× slower (fine adjust). UI Design Book §2.8.
            sensitivity *= 10.0;
        }
        // Drag UP increases (negative dy = up in screen coords).
        double dy = dragAnchorY - e.getScreenY();
        double delta = (dy / sensitivity) * range;
        c.setValue(dragAnchorValue + delta);
        e.consume();
    }

    private void onMouseClicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (e.getClickCount() == 2) {
            resetToDefault();
            e.consume();
        }
    }

    private void onScroll(ScrollEvent e) {
        Knob c = getSkinnable();
        double range = c.getMax() - c.getMin();
        if (range <= 0) {
            return;
        }
        double step = range / 100.0;
        if (e.isShiftDown()) {
            step /= 10.0;
        }
        double dir = Math.signum(e.getDeltaY());
        if (dir == 0) {
            dir = Math.signum(e.getDeltaX());
        }
        if (dir == 0) {
            return;
        }
        c.setValue(c.getValue() + dir * step);
        e.consume();
    }

    private void onKeyPressed(KeyEvent e) {
        Knob c = getSkinnable();
        double range = c.getMax() - c.getMin();
        if (range <= 0) {
            return;
        }
        double step = range / 100.0;
        double fine = step / 10.0;
        double coarse = step * 10.0;
        boolean shift = e.isShiftDown();
        boolean handled = true;
        switch (e.getCode()) {
            case UP, RIGHT -> c.setValue(c.getValue() + (shift ? fine : step));
            case DOWN, LEFT -> c.setValue(c.getValue() - (shift ? fine : step));
            case PAGE_UP -> c.setValue(c.getValue() + coarse);
            case PAGE_DOWN -> c.setValue(c.getValue() - coarse);
            case HOME -> c.setValue(c.getMin());
            case END -> c.setValue(c.getMax());
            case DIGIT0, NUMPAD0 -> resetToDefault();
            case ENTER -> openValueEntry();
            default -> handled = false;
        }
        if (handled) {
            e.consume();
        }
    }

    /**
     * Resets to {@link Knob#getDefaultValue()}, optionally animating the
     * snap (60 ms ease) when {@link Knob#isAnimated()}.
     */
    public void resetToDefault() {
        Knob c = getSkinnable();
        double target = c.getDefaultValue();
        if (!c.isAnimated()) {
            // Reduce Motion: snap with no transition timeline (story 279).
            c.setValue(target);
            return;
        }
        // Animate the visual indicator only — model value still snaps
        // immediately so observers see the new value at once.
        animatedValue.set(c.getValue());
        c.setValue(target);
        if (detentTimeline != null) {
            detentTimeline.stop();
        }
        detentTimeline = new Timeline(new KeyFrame(DETENT_DURATION,
                new KeyValue(animatedValue, target)));
        detentTimeline.setOnFinished(ev -> paint());
        detentTimeline.play();
        animatedValue.addListener((o, w, n) -> paint());
    }

    /**
     * Value-entry stub. The popover styling is the responsibility of
     * story 276 (dialogs & popovers); this story stops at "Enter opens
     * the entry control". Subclasses or app-side wiring can override by
     * adding a separate KEY_PRESSED handler on the control before this
     * skin consumes the event.
     */
    private void openValueEntry() {
        // Intentionally minimal: signal the open via the accessible
        // text so observers / tests can verify Enter routes here.
        Knob c = getSkinnable();
        c.setAccessibleHelp("Value entry: " + c.formatValue());
    }

    // ── Geometry & painting ───────────────────────────────────────────────

    /**
     * @return the current normalised value in {@code [0, 1]} along the
     *         dial sweep. Returns {@code 0} for a degenerate range.
     */
    public double normalizedValue() {
        Knob c = getSkinnable();
        double range = c.getMax() - c.getMin();
        if (range <= 0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (c.getValue() - c.getMin()) / range));
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        // The knob is always circular: size = min(w, h), centred in the
        // available area. Every internal dimension scales from `size`.
        double size = Math.max(0.0, Math.min(w, h));
        canvas.setWidth(size);
        canvas.setHeight(size);
        canvas.relocate(x + (w - size) / 2.0, y + (h - size) / 2.0);
        paint();
    }

    private void paint() {
        if (disposed) {
            return;
        }
        double size = canvas.getWidth();
        if (size <= 0) {
            return;
        }
        Knob c = getSkinnable();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, size, size);

        double border = Math.max(1.0, size * BORDER_FRAC);
        double arcStroke = Math.max(1.0, size * ARC_STROKE_FRAC);
        double indicatorStroke = Math.max(1.0, size * INDICATOR_STROKE_FRAC);
        double focusRingOffset = Math.max(2.0, size * FOCUS_RING_OFFSET_FRAC);

        double cx = size / 2.0;
        double cy = size / 2.0;
        // Leave room for the travel arc to sit JUST outside the dial.
        double arcRadius = (size / 2.0) - arcStroke;
        double dialRadius = arcRadius - arcStroke;
        if (dialRadius < 1) {
            dialRadius = Math.max(1, size / 4.0);
        }

        // 1) Dial fill + border.
        gc.setFill(c.getKnobDialColor());
        gc.fillOval(cx - dialRadius, cy - dialRadius,
                2 * dialRadius, 2 * dialRadius);
        gc.setStroke(c.getKnobBorderColor());
        gc.setLineWidth(border);
        gc.strokeOval(cx - dialRadius, cy - dialRadius,
                2 * dialRadius, 2 * dialRadius);

        // 2) Travel arc — full track underlay + accent overlay.
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setLineWidth(arcStroke);
        // JavaFX arc angles: 0° at 3 o'clock, increasing counter-clockwise.
        // Our sweep starts at 225° (7:30) going clockwise to -45° (4:30):
        // in JavaFX, that is start = -45 (or 315), extent = -270 going
        // clockwise. The standard mapping: paint angle = 270 - 225 = -45.
        gc.setStroke(c.getKnobTrackColor());
        gc.strokeArc(cx - arcRadius, cy - arcRadius,
                2 * arcRadius, 2 * arcRadius,
                START_DEG, -SWEEP_DEG, ArcType.OPEN);

        double n = normalizedValue();
        boolean bipolar = c.isBipolar();
        double centre = 0.5;
        gc.setStroke(c.getKnobArcColor());
        if (bipolar) {
            // From centre to current — direction depends on sign.
            double angleAtCentre = START_DEG - SWEEP_DEG * centre;
            double extent = -SWEEP_DEG * (n - centre);
            if (extent != 0) {
                gc.strokeArc(cx - arcRadius, cy - arcRadius,
                        2 * arcRadius, 2 * arcRadius,
                        angleAtCentre, extent, ArcType.OPEN);
            }
        } else {
            // From min (start) to current.
            double extent = -SWEEP_DEG * n;
            if (extent != 0) {
                gc.strokeArc(cx - arcRadius, cy - arcRadius,
                        2 * arcRadius, 2 * arcRadius,
                        START_DEG, extent, ArcType.OPEN);
            }
        }

        // 3) Indicator line — from centre to dial edge at current angle.
        // Use the animated value during a detent snap.
        double displayN = n;
        if (isDetentAnimationRunning()) {
            double range = c.getMax() - c.getMin();
            if (range > 0) {
                displayN = (animatedValue.get() - c.getMin()) / range;
                displayN = Math.max(0.0, Math.min(1.0, displayN));
            }
        }
        double angleDeg = START_DEG - SWEEP_DEG * displayN;
        double angleRad = Math.toRadians(angleDeg);
        // JavaFX canvas Y grows downward, but trig is mathematical (Y up),
        // so flip the Y component.
        double ex = cx + Math.cos(angleRad) * dialRadius;
        double ey = cy - Math.sin(angleRad) * dialRadius;
        gc.setStroke(c.getKnobIndicatorColor());
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineWidth(indicatorStroke);
        gc.strokeLine(cx, cy, ex, ey);

        // 4) Focus ring — only when the control has focus.
        if (c.isFocused()) {
            gc.setStroke(c.getKnobFocusRingColor());
            gc.setLineWidth(border);
            double r = arcRadius + focusRingOffset;
            gc.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
        }

        // Optional unit / value glyph beneath the dial when there is room.
        String unit = c.getUnit();
        if (unit != null && !unit.isEmpty() && size >= 36) {
            gc.setFill(c.getKnobIndicatorColor());
            double fontPx = Math.max(8.0, size * 0.18);
            gc.setFont(Font.font(fontPx));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(unit, cx, size - 1);
        }

        // Accessible value narration — screen readers announce changes
        // via the accessible-text update.
        c.setAccessibleText(
                String.format(Locale.ROOT, "Knob: %s", c.formatValue()));
    }

    // ── Sizing ────────────────────────────────────────────────────────────

    /**
     * @return the active size variant px (28 / 36 / 48), defaulting to 28
     *         when no size style class is present.
     */
    private int sizeVariantPx() {
        var classes = getSkinnable().getStyleClass();
        if (classes.contains("size-48")) return 48;
        if (classes.contains("size-36")) return 36;
        return 28;
    }

    @Override
    protected double computePrefWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return sizeVariantPx();
    }

    @Override
    protected double computePrefHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return sizeVariantPx();
    }

    @Override
    protected double computeMinWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 16;
    }

    @Override
    protected double computeMinHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 16;
    }

    @Override
    protected double computeMaxWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 64;
    }

    @Override
    protected double computeMaxHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 64;
    }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (detentTimeline != null) {
            detentTimeline.stop();
            detentTimeline = null;
        }
        Knob c = getSkinnable();
        if (c != null) {
            c.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
            c.focusedProperty().removeListener(focusListener);
            removeRepaint(c.valueProperty());
            removeRepaint(c.minProperty());
            removeRepaint(c.maxProperty());
            removeRepaint(c.bipolarProperty());
            removeRepaint(c.unitProperty());
            removeRepaint(c.knobDialColorProperty());
            removeRepaint(c.knobBorderColorProperty());
            removeRepaint(c.knobArcColorProperty());
            removeRepaint(c.knobTrackColorProperty());
            removeRepaint(c.knobIndicatorColorProperty());
            removeRepaint(c.knobFocusRingColorProperty());
        }
        registeredListenerCount = 0;
        super.dispose();
    }
}
