package com.benesquivelmusic.daw.app.ui.controls.skin;

import com.benesquivelmusic.daw.app.ui.controls.Fader;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;

import java.util.Locale;

/**
 * Skin for {@link Fader} — renders the §5.4 fader column + wide
 * rectangular cap onto a plain {@link Canvas} and lays out the embedded
 * {@link LevelMeter} to the right.
 *
 * <h2>Geometry (UI Design Book §5.4)</h2>
 *
 * <ul>
 *   <li>Track: vertical bar centred in the fader column, 4 px wide,
 *       filled with {@code -fader-track-color}. The column itself
 *       reserves a wider hit-target around the track so the cap can be
 *       grabbed at any horizontal position within the column.</li>
 *   <li>Cap: a wide rectangle, {@code 70%} of the column's width × 12 px
 *       tall, fill {@code -fader-cap-color}, with a 1 px
 *       {@code -fader-cap-line-color} horizontal centreline. When
 *       {@code :dragging} the centreline brightens.</li>
 *   <li>Zero-dB tick: a horizontal 8 px tick at the 0-dB Y-position when
 *       0 dB is inside {@code [min, max]} (LOG_DB curve only).</li>
 *   <li>Meter: a {@link LevelMeter} laid out to the right at a fixed
 *       4 px gap when {@link Fader#isShowMeter()}. The meter's size
 *       variant tracks the fader: {@code .size-mixer/.size-inspector} →
 *       {@code .size-channel}; {@code .size-performance} →
 *       {@code .size-performance}.</li>
 * </ul>
 *
 * <h2>Interaction (UI Design Book §2.8)</h2>
 *
 * <ul>
 *   <li>Click + drag vertically: scrubs the value.</li>
 *   <li>Click outside the cap: snaps the cap to that Y (Pro Tools style).</li>
 *   <li>Double-click: resets to {@link Fader#getDefaultValue()}.</li>
 *   <li>Scroll wheel: {@code (max - min) / 100} per notch; Shift: 10× finer.</li>
 *   <li>Keyboard: ↑/↓ step by 1%, Shift→10× finer, PgUp/PgDn→10× coarser,
 *       Home/End→max/min, {@code 0}→reset, Enter→value-entry hook.</li>
 * </ul>
 */
public final class FaderSkin extends SkinBase<Fader> {

    /** Pseudo-class applied while the user is dragging the cap. */
    private static final PseudoClass DRAGGING = PseudoClass.getPseudoClass("dragging");

    /** Cap height in px (UI Design Book §5.4). */
    static final double CAP_HEIGHT = 12.0;
    /** Cap width as fraction of column width (UI Design Book §5.4). */
    static final double CAP_WIDTH_FRAC = 0.70;
    /** Track width in px (UI Design Book §5.4). */
    static final double TRACK_WIDTH = 4.0;
    /** Gap between the fader column and the meter (UI Design Book §5.4). */
    static final double METER_GAP = 4.0;

    private final Canvas canvas;
    private LevelMeter attachedMeter;

    private final ChangeListener<Object> repaintListener;
    private final ChangeListener<Boolean> focusListener;
    private final ChangeListener<Boolean> showMeterListener;
    private final javafx.event.EventHandler<KeyEvent> keyHandler;

    private double dragAnchorY;
    private double dragAnchorValue;
    private boolean dragging;
    private boolean dragMoved;
    private boolean disposed;
    private int registeredListenerCount;
    /** Column width (px) computed in {@link #layoutChildren}. */
    private double columnWidth;
    /** Column height (px) — equals the canvas height. */
    private double columnHeight;

    /**
     * @param control the {@link Fader} this skin renders
     */
    public FaderSkin(Fader control) {
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
        showMeterListener = (obs, was, now) -> {
            if (!disposed) {
                syncMeterAttachment();
                control.requestLayout();
            }
        };

        addRepaint(control.valueProperty());
        addRepaint(control.minProperty());
        addRepaint(control.maxProperty());
        addRepaint(control.curveProperty());
        addRepaint(control.unitProperty());
        addRepaint(control.valueFormatterProperty());
        addRepaint(control.animatedProperty());
        addRepaint(control.faderTrackColorProperty());
        addRepaint(control.faderCapColorProperty());
        addRepaint(control.faderCapLineColorProperty());
        addRepaint(control.faderZeroTickColorProperty());
        addRepaint(control.faderFocusRingColorProperty());

        control.focusedProperty().addListener(focusListener);
        registeredListenerCount++;

        control.showMeterProperty().addListener(showMeterListener);
        registeredListenerCount++;

        // Mouse interaction — canvas is the cap+track hit target; the
        // meter has its own handlers (peak-hold reset, etc.).
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        canvas.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onMouseClicked);
        canvas.addEventHandler(ScrollEvent.SCROLL, this::onScroll);

        // Keyboard interaction routes through the control (owns focus).
        keyHandler = this::onKeyPressed;
        control.addEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
        registeredListenerCount++;

        syncMeterAttachment();
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

    /**
     * Lazily attaches / detaches the embedded {@link LevelMeter} as a
     * child of the skin. Idempotent — safe to call repeatedly.
     */
    private void syncMeterAttachment() {
        Fader f = getSkinnable();
        if (f.isShowMeter()) {
            LevelMeter m = f.getMeter();
            // Propagate fader size variant to meter size variant. Default
            // is .size-channel; .size-performance fader → .size-performance
            // meter. We modify the meter's style classes only when the
            // fader actually carries a size variant, and we remove any
            // previously-applied meter size class first so toggles work.
            var classes = m.getStyleClass();
            classes.removeAll("size-inline", "size-channel",
                    "size-master", "size-performance");
            if (f.getStyleClass().contains("size-performance")) {
                classes.add("size-performance");
            } else {
                classes.add("size-channel");
            }
            if (attachedMeter != m) {
                if (attachedMeter != null) {
                    getChildren().remove(attachedMeter);
                }
                if (!getChildren().contains(m)) {
                    getChildren().add(m);
                }
                attachedMeter = m;
            }
        } else if (attachedMeter != null) {
            getChildren().remove(attachedMeter);
            attachedMeter = null;
        }
    }

    /** @return the backing canvas (test seam). */
    public Canvas canvas() {
        return canvas;
    }

    /** @return whether {@link #dispose()} has run. */
    public boolean isDisposed() {
        return disposed;
    }

    /** @return the number of still-registered listeners (test seam). */
    public int registeredListenerCount() {
        return registeredListenerCount;
    }

    /** @return whether the cap is currently being dragged (test seam). */
    public boolean isDragging() {
        return dragging;
    }

    /** @return the current column height (px) (test seam). */
    public double columnHeight() {
        return columnHeight;
    }

    /** @return the current cap centre Y (px in canvas coords) (test seam). */
    public double capCentreY() {
        Fader f = getSkinnable();
        double n = f.positionForValue(f.getValue());
        return travelTopY() + (1.0 - n) * travelRangeHeight();
    }

    /** Top Y (canvas coords) of the cap's travel range. The cap centre
     * can range from {@code travelTopY()} (value at max) to
     * {@code travelTopY() + travelRangeHeight()} (value at min). */
    private double travelTopY() {
        return CAP_HEIGHT / 2.0;
    }

    /** Vertical extent of cap-centre travel — height minus cap so the cap
     * never overflows the column edges. */
    private double travelRangeHeight() {
        return Math.max(0.0, columnHeight - CAP_HEIGHT);
    }

    // ── Interaction ───────────────────────────────────────────────────────

    private void onMousePressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Fader f = getSkinnable();
        f.requestFocus();

        // Pro Tools style: if the press is outside the cap, snap the cap
        // to that Y first; then the drag continues from there.
        double y = e.getY();
        double capY = capCentreY();
        if (Math.abs(y - capY) > CAP_HEIGHT / 2.0) {
            snapToY(y);
        }
        dragAnchorY = e.getScreenY();
        dragAnchorValue = f.getValue();
        dragging = true;
        dragMoved = false;
        f.pseudoClassStateChanged(DRAGGING, true);
        paint();
        e.consume();
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging) {
            return;
        }
        dragMoved = true;
        Fader f = getSkinnable();
        double travel = travelRangeHeight();
        if (travel <= 0) {
            return;
        }
        // Drag UP increases (negative dy = up in screen coords). Drag
        // distance is mapped to position via the active curve so the cap
        // tracks the cursor 1:1 (UI Design Book §5.4).
        double dy = dragAnchorY - e.getScreenY();
        double anchorPos = f.positionForValue(dragAnchorValue);
        double scale = e.isShiftDown() ? 0.1 : 1.0;
        double newPos = anchorPos + scale * (dy / travel);
        f.setValue(f.valueForPosition(newPos));
        e.consume();
    }

    private void onMouseReleased(MouseEvent e) {
        if (dragging) {
            dragging = false;
            getSkinnable().pseudoClassStateChanged(DRAGGING, false);
            paint();
        }
    }

    private void onMouseClicked(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        // Only reset on a genuine double-click that did NOT involve any
        // drag movement.
        if (e.getClickCount() == 2 && !dragMoved) {
            resetToDefault();
            e.consume();
        }
    }

    /** Snaps the cap to the given canvas-Y (Pro Tools click-anywhere style). */
    private void snapToY(double y) {
        Fader f = getSkinnable();
        double travel = travelRangeHeight();
        if (travel <= 0) return;
        double clamped = Math.max(travelTopY(),
                Math.min(travelTopY() + travel, y));
        double pos = 1.0 - (clamped - travelTopY()) / travel;
        f.setValue(f.valueForPosition(pos));
    }

    private void onScroll(ScrollEvent e) {
        Fader f = getSkinnable();
        double range = f.getMax() - f.getMin();
        if (range <= 0) return;
        double step = range / 100.0;
        if (e.isShiftDown()) {
            step /= 10.0;
        }
        double dir = Math.signum(e.getDeltaY());
        if (dir == 0) dir = Math.signum(e.getDeltaX());
        if (dir == 0) return;
        f.setValue(f.getValue() + dir * step);
        e.consume();
    }

    private void onKeyPressed(KeyEvent e) {
        Fader f = getSkinnable();
        double range = f.getMax() - f.getMin();
        if (range <= 0) return;
        double step = range / 100.0;
        double fine = step / 10.0;
        double coarse = step * 10.0;
        boolean shift = e.isShiftDown();
        boolean handled = true;
        switch (e.getCode()) {
            case UP -> f.setValue(f.getValue() + (shift ? fine : step));
            case DOWN -> f.setValue(f.getValue() - (shift ? fine : step));
            case PAGE_UP -> f.setValue(f.getValue() + coarse);
            case PAGE_DOWN -> f.setValue(f.getValue() - coarse);
            // Up is "max" for a fader: Home → max, End → min.
            case HOME -> f.setValue(f.getMax());
            case END -> f.setValue(f.getMin());
            case DIGIT0, NUMPAD0 -> resetToDefault();
            case ENTER -> openValueEntry();
            default -> handled = false;
        }
        if (handled) e.consume();
    }

    /** Resets to {@link Fader#getDefaultValue()}. */
    public void resetToDefault() {
        Fader f = getSkinnable();
        f.setValue(f.getDefaultValue());
    }

    /** Value-entry stub (popover lives in story 276). */
    private void openValueEntry() {
        Fader f = getSkinnable();
        f.setAccessibleHelp("Value entry: " + f.formatValue());
    }

    // ── Layout ────────────────────────────────────────────────────────────

    /**
     * @return the active size variant px values:
     *         {@code [columnWidth, columnHeight, meterIsPerformance ? 1 : 0]}.
     */
    private double[] sizeVariantDims() {
        var classes = getSkinnable().getStyleClass();
        // [columnWidth, columnHeight, meterWidth]
        if (classes.contains("size-performance")) {
            return new double[]{32.0, 320.0, 24.0};
        }
        if (classes.contains("size-inspector")) {
            return new double[]{16.0, 96.0, 8.0};
        }
        // Default = mixer.
        return new double[]{20.0, 160.0, 8.0};
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        Fader f = getSkinnable();
        boolean showMeter = f.isShowMeter();

        double[] dims = sizeVariantDims();
        double preferredMeterWidth = dims[2];

        // Compute column width / height from the assigned area (no fixed
        // pixel offsets keyed to CSS default — UI Design Book §5.4).
        double meterWidth;
        double colW;
        if (showMeter) {
            // Reserve meter + gap from the available width; the column
            // takes the rest. Both proportionally honour the size-variant
            // preferred meter width but adapt to the actual cell.
            double availableForMeter = Math.max(0.0, w - METER_GAP);
            meterWidth = Math.min(preferredMeterWidth, availableForMeter * 0.4);
            meterWidth = Math.max(4.0, meterWidth);
            colW = Math.max(TRACK_WIDTH + 2.0,
                    w - meterWidth - METER_GAP);
        } else {
            meterWidth = 0.0;
            colW = Math.max(TRACK_WIDTH + 2.0, w);
        }
        double colH = Math.max(CAP_HEIGHT + 2.0, h);

        columnWidth = colW;
        columnHeight = colH;

        canvas.setWidth(colW);
        canvas.setHeight(colH);
        canvas.relocate(x, y);

        if (showMeter && attachedMeter != null) {
            attachedMeter.resizeRelocate(
                    x + colW + METER_GAP, y, meterWidth, colH);
        }

        paint();
    }

    // ── Painting ──────────────────────────────────────────────────────────

    private void paint() {
        if (disposed) return;
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        Fader f = getSkinnable();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        double cx = w / 2.0;

        // 1) Track — vertical 4 px bar centred horizontally.
        gc.setFill(f.getFaderTrackColor());
        gc.fillRect(cx - TRACK_WIDTH / 2.0, 0, TRACK_WIDTH, h);

        // 2) Zero-dB tick (LOG_DB only, when 0 dB is inside the range).
        if (f.getCurve() == Fader.TravelCurve.LOG_DB
                && f.getMin() < 0.0 && f.getMax() > 0.0) {
            double zeroPos = f.positionForValue(0.0);
            double zeroY = travelTopY() + (1.0 - zeroPos) * travelRangeHeight();
            gc.setFill(f.getFaderZeroTickColor());
            double tickW = Math.min(w - 2, TRACK_WIDTH * 3.0);
            gc.fillRect(cx - tickW / 2.0, zeroY - 0.5, tickW, 1.0);
        }

        // 3) Cap — wide rectangle, 70% of column width × 12 px tall.
        double capW = Math.max(6.0, w * CAP_WIDTH_FRAC);
        double capCY = capCentreY();
        double capX = cx - capW / 2.0;
        double capY = capCY - CAP_HEIGHT / 2.0;
        gc.setFill(f.getFaderCapColor());
        gc.fillRect(capX, capY, capW, CAP_HEIGHT);

        // 4) Cap centreline — 1 px horizontal line across the cap centre.
        // While :dragging, the line brightens (use the cap-line colour at
        // full alpha; the CSS bumps the line colour up in the :dragging
        // pseudo-class so the change is observable from the colour alone).
        Color lineColor = f.getFaderCapLineColor();
        if (dragging && lineColor != null) {
            // Brighten by stepping toward pure white in HSB space.
            lineColor = lineColor.deriveColor(0, 1.0, 1.2, 1.0);
        }
        gc.setFill(lineColor);
        gc.fillRect(capX, capCY - 0.5, capW, 1.0);

        // 5) Focus ring — 1 px outline around the cap when :focused.
        if (f.isFocused()) {
            gc.setStroke(f.getFaderFocusRingColor());
            gc.setLineWidth(1.0);
            gc.strokeRect(capX - 1.0, capY - 1.0, capW + 2.0, CAP_HEIGHT + 2.0);
        }

        // Accessible value narration.
        f.setAccessibleText(
                String.format(Locale.ROOT, "Fader: %s", f.formatValue()));
    }

    // ── Sizing ────────────────────────────────────────────────────────────

    @Override
    protected double computePrefWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        double[] d = sizeVariantDims();
        double w = d[0];
        if (getSkinnable().isShowMeter()) {
            w += METER_GAP + d[2];
        }
        return w;
    }

    @Override
    protected double computePrefHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return sizeVariantDims()[1];
    }

    @Override
    protected double computeMinWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        double w = TRACK_WIDTH + 4.0;
        if (getSkinnable().isShowMeter()) {
            w += METER_GAP + 4.0;
        }
        return w;
    }

    @Override
    protected double computeMinHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return CAP_HEIGHT + 16.0;
    }

    @Override
    protected double computeMaxWidth(double height,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 64.0;
    }

    @Override
    protected double computeMaxHeight(double width,
            double topInset, double rightInset, double bottomInset, double leftInset) {
        return 480.0;
    }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        Fader c = getSkinnable();
        if (c != null) {
            c.removeEventHandler(KeyEvent.KEY_PRESSED, keyHandler);
            c.focusedProperty().removeListener(focusListener);
            c.showMeterProperty().removeListener(showMeterListener);
            removeRepaint(c.valueProperty());
            removeRepaint(c.minProperty());
            removeRepaint(c.maxProperty());
            removeRepaint(c.curveProperty());
            removeRepaint(c.unitProperty());
            removeRepaint(c.valueFormatterProperty());
            removeRepaint(c.animatedProperty());
            removeRepaint(c.faderTrackColorProperty());
            removeRepaint(c.faderCapColorProperty());
            removeRepaint(c.faderCapLineColorProperty());
            removeRepaint(c.faderZeroTickColorProperty());
            removeRepaint(c.faderFocusRingColorProperty());
        }
        registeredListenerCount = 0;
        super.dispose();
    }
}
