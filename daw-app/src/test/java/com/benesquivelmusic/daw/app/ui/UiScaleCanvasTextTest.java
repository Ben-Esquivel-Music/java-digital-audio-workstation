package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.Knob;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.KnobSkin;
import com.benesquivelmusic.daw.app.ui.controls.skin.LevelMeterSkin;
import com.benesquivelmusic.daw.app.ui.status.CheckpointCountdownCanvas;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.theme.UiScale;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.fx.GpuCanvas;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 335 (studio ruling R6; amendments A15 and A20) — the canvases that
 * draw their own text read the UI scale: geometry and text scale
 * together, so a 150% scale gives each a 1.5× preferred size and a 1.5×
 * effective glyph size (font size × the graphics context's transform scale),
 * on a live scale change, and the ruler's two text rows never overlap at
 * 200%. ({@code LevelMeterDisplay} draws no text: its clip bar and ticks are
 * checked by {@code LevelMeterDisplayUiScaleTest}.)
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UiScaleCanvasTextTest {

    private Preferences prefs;
    private ThemeManager appearance;

    @BeforeEach
    void isolateTheThemeManager() {
        prefs = Preferences.userRoot().node("uiScaleCanvasText_" + System.nanoTime());
        appearance = new ThemeManager(prefs);
        ThemeManager.setDefaultForTest(appearance);
    }

    @AfterEach
    void restore() throws BackingStoreException {
        ThemeManager.setDefaultForTest(null);
        prefs.removeNode();
    }

    /** A canvas under test: its node, the canvas it draws into, and its fixed size at 100%. */
    private record Measured(double prefWidth, double prefHeight, double glyph) {
    }

    @Test
    void theTimelineRulerScalesItsHeightAndItsText() throws Exception {
        assertScalesByOneAndAHalf("TimelineRuler", () -> new TimelineRuler(new Transport()), false);
    }

    /**
     * No size class: the size is the skin's own (its inline variant), not a
     * stylesheet length the scale sheet re-states (review 2b-java S4.1).
     */
    @Test
    void theLevelMeterSkinScalesItsSizeVariantAndItsTickLabels() throws Exception {
        assertScalesByOneAndAHalf("LevelMeterSkin", () -> {
            LevelMeter meter = new LevelMeter();
            meter.setMeterTickMarks(true);
            meter.setSkin(new LevelMeterSkin(meter));
            return meter;
        }, true);
    }

    /**
     * A 36 px knob's unit glyph is the skin's own 8 px floor (36 × 0.18 is
     * less), so the glyph pins the skin's scaling of that floor, not a
     * stylesheet length the scale sheet re-states (review 2b-java S4.1).
     */
    @Test
    void theKnobSkinScalesItsSizeVariantAndItsUnitGlyph() throws Exception {
        assertScalesByOneAndAHalf("KnobSkin", () -> {
            Knob knob = new Knob();
            knob.getStyleClass().add("size-36");
            knob.setUnit("dB");
            knob.setSkin(new KnobSkin(knob));
            return knob;
        }, true);
    }

    /**
     * Review 2b-java S4.1 — without a size class the knob's size is the
     * skin's own 28 px default, which follows the scale.
     */
    @Test
    void aKnobWithoutASizeClassTakesItsSkinsSizeTimesTheScale() throws Exception {
        double[] sizes = Story307TestSupport.onFx(() -> {
            Knob knob = new Knob();
            knob.setSkin(new KnobSkin(knob));
            Group root = new Group(knob);
            Scene scene = new Scene(root, 300, 300);
            appearance.applyTo(scene);
            root.applyCss();
            double atOneHundred = knob.prefWidth(-1);
            appearance.setUiScale(UiScale.P150);
            root.applyCss();
            return new double[] {atOneHundred, knob.prefWidth(-1), knob.prefHeight(-1)};
        }, 40, TimeUnit.SECONDS);

        assertThat(sizes[0]).as("the skin's 28 px at 100%%").isEqualTo(28.0);
        assertThat(sizes[1]).as("pref width at 150%%").isCloseTo(42.0, within(1e-6));
        assertThat(sizes[2]).as("pref height at 150%%").isCloseTo(42.0, within(1e-6));
    }

    @Test
    void theCheckpointCountdownScalesItsCellAndItsText() throws Exception {
        assertScalesByOneAndAHalf("CheckpointCountdownCanvas", () -> {
            CheckpointCountdownCanvas countdown = new CheckpointCountdownCanvas();
            countdown.setReduceMotion(true);
            countdown.setNextCheckpointInstant(Instant.now().plus(Duration.ofMinutes(3)));
            return countdown;
        }, true);
    }

    /**
     * At 200% the ruler's bar-label row (above the major ticks) and its
     * tempo / time-signature row (along the bottom) keep disjoint drawn (ink)
     * vertical extents — the geometry scales with the text.
     */
    @Test
    void atTwoHundredPercentTheRulersBarLabelsAndTempoTextDoNotOverlap() throws Exception {
        double[] rows = Story307TestSupport.onFx(() -> {
            appearance.setUiScale(UiScale.P200);
            TimelineRuler ruler = new TimelineRuler(new Transport());
            Scene scene = new Scene(new StackPane(ruler), 800, 200);
            ruler.applyCss();
            ruler.getParent().layout();
            ruler.resize(800, ruler.prefHeight(-1));
            ruler.redraw();
            double h = ruler.getHeight();
            Font labelFont = canvasOf(ruler).getGraphicsContext2D().getFont();
            double[] bar = rowExtent(ruler.barLabelBaseline(h), labelFont, "1.1.1");
            double[] tempo = rowExtent(ruler.tempoBaseline(h), ruler.tempoFont(), "120.0 BPM  4/4");
            assertThat(scene).isNotNull();
            return new double[] {h, bar[0], bar[1], tempo[0], tempo[1], labelFont.getSize()};
        }, 40, TimeUnit.SECONDS);

        assertThat(rows[5]).as("bar-label font at 200%%").isCloseTo(20.0, within(1e-6));
        assertThat(rows[2]).as("bar-label row [%s, %s] ends above the tempo row [%s, %s]",
                rows[1], rows[2], rows[3], rows[4]).isLessThanOrEqualTo(rows[3]);
        assertThat(rows[1]).as("the bar-label row starts inside the ruler").isGreaterThanOrEqualTo(0.0);
        assertThat(rows[4]).as("the tempo row ends inside the ruler").isLessThanOrEqualTo(rows[0]);
        assertThat(rows[0]).as("ruler height at 200%%").isCloseTo(64.0, within(1e-6));
    }

    /**
     * Review 2b-java B2 — the ruler is built before its scene exists
     * ({@code MainController.initialize()} runs before {@code DawApplication}
     * creates the Scene). While it has no scene its scale observation is not
     * subscribed to the scale property, so only the ruler itself can hold it:
     * after a garbage collection it must still follow a live scale change.
     */
    @Test
    void aRulerBuiltBeforeItsSceneStillFollowsALiveScaleChangeAfterACollection() throws Exception {
        assertFollowsTheScaleAfterACollection("TimelineRuler", () -> new TimelineRuler(new Transport()));
    }

    /** Review 2b-java B2 — the same for the countdown cell, which leaves and re-enters scenes with its strip. */
    @Test
    void aCountdownBuiltBeforeItsSceneStillFollowsALiveScaleChangeAfterACollection() throws Exception {
        assertFollowsTheScaleAfterACollection("CheckpointCountdownCanvas", () -> {
            CheckpointCountdownCanvas countdown = new CheckpointCountdownCanvas();
            countdown.setReduceMotion(true);
            return countdown;
        });
    }

    /**
     * Story 335 review (engineer S2) — the time axis keeps its 100% pixels
     * while the label font follows the scale, so at 200% and 20 px per beat
     * (80 px bars, where 100% labels every bar cleanly) the ruler labels every
     * 2nd, 4th… bar, counted from bar 1, and the drawn labels' ink never runs
     * into the next one. Ticks are unaffected.
     */
    @Test
    void atTwoHundredPercentTheBarLabelsNeverRunIntoOneAnother() throws Exception {
        DrawnLabels drawn = drawBarLabels(UiScale.P200);
        List<TimelineRuler.DrawnBarLabel> labels = drawn.labels();
        Font font = drawn.font();

        assertThat(labels).as("labels drawn for bars 9..20").hasSizeGreaterThanOrEqualTo(3);
        for (int i = 0; i + 1 < labels.size(); i++) {
            double[] left = inkExtent(labels.get(i), font);
            double[] right = inkExtent(labels.get(i + 1), font);
            assertThat(right[0]).as("'%s' starts after '%s' ends (%s)", labels.get(i + 1).text(),
                    labels.get(i).text(), Arrays.toString(left)).isGreaterThan(left[1]);
        }
        for (TimelineRuler.DrawnBarLabel label : labels) {
            int bar = Integer.parseInt(label.text().substring(0, label.text().indexOf(':')));
            assertThat((bar - 1) % 2).as("bar %d is on a power-of-two stride from bar 1", bar).isZero();
        }
    }

    /** Engineer S2 — at the same zoom 100% still labels every bar: its look is unchanged. */
    @Test
    void atOneHundredPercentTheSameZoomLabelsEveryBar() throws Exception {
        List<TimelineRuler.DrawnBarLabel> labels = drawBarLabels(UiScale.P100).labels();

        List<Integer> bars = new ArrayList<>();
        for (TimelineRuler.DrawnBarLabel label : labels) {
            bars.add(Integer.parseInt(label.text().substring(0, label.text().indexOf(':'))));
        }
        assertThat(bars).as("every visible bar from 9 on").hasSizeGreaterThanOrEqualTo(12);
        for (int i = 0; i < bars.size(); i++) {
            assertThat(bars.get(i)).isEqualTo(bars.getFirst() + i);
        }
        assertThat(bars.getFirst()).isEqualTo(9);
    }

    /**
     * Review 2b-conformance S6 — a {@code TrackStrip}'s row height (24/28/32
     * px by density) follows the scale on a live change, and its name label is
     * not truncated at 200%.
     */
    @Test
    void aTrackStripRowFollowsALiveScaleChangeAndItsNameFitsAtTwoHundredPercent() throws Exception {
        double[] measured = Story307TestSupport.onFx(() -> {
            TrackStrip strip = new TrackStrip();
            strip.setTrackName("Vox");
            StackPane root = new StackPane(strip);
            Scene scene = new Scene(root, 600, 120);
            appearance.applyTo(scene);
            root.applyCss();
            root.layout();
            double atOneHundred = strip.prefHeight(-1);
            appearance.setUiScale(UiScale.P150);
            root.applyCss();
            root.layout();
            double atOneFifty = strip.prefHeight(-1);
            appearance.setUiScale(UiScale.P200);
            root.applyCss();
            root.layout();
            Label name = (Label) strip.lookup(".track-strip-name");
            Text rendered = (Text) name.lookup(".text");
            return new double[] {atOneHundred, atOneFifty, rendered.getText().equals(name.getText()) ? 1 : 0};
        }, 40, TimeUnit.SECONDS);

        assertThat(measured[0]).as("row height at 100%%").isPositive();
        assertThat(measured[1]).as("row height at 150%%").isCloseTo(measured[0] * 1.5, within(1e-6));
        assertThat(measured[2]).as("the track name is not truncated at 200%%").isEqualTo(1.0);
    }

    private void assertFollowsTheScaleAfterACollection(String name, Supplier<Region> factory) throws Exception {
        Region node = Story307TestSupport.onFx(factory::get, 15, TimeUnit.SECONDS);
        double atOneHundred = Story307TestSupport.onFx(() -> node.prefHeight(-1), 15, TimeUnit.SECONDS);
        collectGarbage();
        double atOneFifty = Story307TestSupport.onFx(() -> {
            Group root = new Group(node);
            Scene scene = new Scene(root, 900, 600);
            appearance.applyTo(scene);
            root.applyCss();
            root.layout();
            appearance.setUiScale(UiScale.P150);
            root.applyCss();
            root.layout();
            return node.prefHeight(-1);
        }, 15, TimeUnit.SECONDS);

        assertThat(atOneHundred).as("%s preferred height at 100%%", name).isPositive();
        assertThat(atOneFifty).as("%s preferred height after a collection and a live switch to 150%%", name)
                .isCloseTo(atOneHundred * 1.5, within(1e-6));
    }

    /** Runs collections until a weakly held sentinel is gone (bounded), so a weakly held binding is too. */
    private static void collectGarbage() throws InterruptedException {
        WeakReference<Object> sentinel = new WeakReference<>(new Object());
        for (int attempt = 0; attempt < 100 && sentinel.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(sentinel.get()).as("a garbage collection ran").isNull();
        System.gc();
    }

    /** The bar labels a ruler draws, with the font it drew them in. */
    private record DrawnLabels(List<TimelineRuler.DrawnBarLabel> labels, Font font) {
    }

    /** The bar labels a 1280 px ruler at {@code step} draws at 20 px per beat, 4/4, scrolled to bar 9. */
    private DrawnLabels drawBarLabels(UiScale step) throws Exception {
        return Story307TestSupport.onFx(() -> {
            appearance.setUiScale(step);
            TimelineRuler ruler = new TimelineRuler(new Transport());
            StackPane root = new StackPane(ruler);
            Scene scene = new Scene(root, 1280, 200);
            appearance.applyTo(scene);
            root.applyCss();
            root.layout();
            ruler.setAutoScroll(false);
            ruler.setPixelsPerBeat(20);
            ruler.setScrollOffsetBeats(32);
            ruler.resize(1280, ruler.prefHeight(-1));
            ruler.redraw();
            return new DrawnLabels(ruler.lastDrawnBarLabels(), ruler.labelFont());
        }, 40, TimeUnit.SECONDS);
    }

    /** The drawn (ink) horizontal extent of a bar label. */
    private static double[] inkExtent(TimelineRuler.DrawnBarLabel label, Font font) {
        Text text = new Text(label.text());
        text.setFont(font);
        text.setBoundsType(TextBoundsType.VISUAL);
        return new double[] {label.x() + text.getLayoutBounds().getMinX(), label.x() + text.getLayoutBounds().getMaxX()};
    }

    private void assertScalesByOneAndAHalf(String name, Supplier<Region> factory, boolean fixedWidth)
            throws Exception {
        Measured[] measured = Story307TestSupport.onFx(() -> {
            Region node = factory.get();
            Group root = new Group(node);
            Scene scene = new Scene(root, 900, 600);
            appearance.applyTo(scene);
            Measured atOneHundred = measure(node);
            appearance.setUiScale(UiScale.P150);
            Measured atOneFifty = measure(node);
            return new Measured[] {atOneHundred, atOneFifty};
        }, 40, TimeUnit.SECONDS);

        Measured base = measured[0];
        Measured scaled = measured[1];
        assertThat(base.glyph()).as("%s draws text at 100%%", name).isPositive();
        assertThat(scaled.glyph()).as("%s effective glyph size at 150%%", name)
                .isCloseTo(base.glyph() * 1.5, within(1e-6));
        if (base.prefHeight() > 0) {
            assertThat(scaled.prefHeight()).as("%s preferred height at 150%%", name)
                    .isCloseTo(base.prefHeight() * 1.5, within(1e-6));
        }
        if (fixedWidth) {
            assertThat(scaled.prefWidth()).as("%s preferred width at 150%%", name)
                    .isCloseTo(base.prefWidth() * 1.5, within(1e-6));
        }
    }

    /**
     * Lays the node out at its preferred size (a {@link Group} root autosizes
     * it; the ruler, whose width follows its container, gets 800 px), draws,
     * and reads the size and the drawn glyph size.
     */
    private static Measured measure(Region node) {
        Parent root = node.getParent();
        root.applyCss();
        root.layout();
        double prefWidth = node.prefWidth(-1);
        double prefHeight = node.prefHeight(-1);
        if (node instanceof TimelineRuler ruler) {
            ruler.resize(800, prefHeight);
            ruler.layout();
            ruler.redraw();
        }
        for (GpuCanvas gpu : descendants(node, GpuCanvas.class)) {
            gpu.requestRender();
        }
        Canvas canvas = canvasOf(node);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double transformScale = Math.sqrt(Math.abs(gc.getTransform().determinant()));
        return new Measured(prefWidth, prefHeight, gc.getFont().getSize() * transformScale);
    }

    /** The drawn (ink) vertical extent of {@code sample} on {@code baseline}. */
    private static double[] rowExtent(double baseline, Font font, String sample) {
        Text text = new Text(sample);
        text.setFont(font);
        text.setBoundsType(TextBoundsType.VISUAL);
        return new double[] {baseline + text.getLayoutBounds().getMinY(), baseline + text.getLayoutBounds().getMaxY()};
    }

    private static Canvas canvasOf(Node node) {
        List<Canvas> canvases = descendants(node, Canvas.class);
        assertThat(canvases).as("a canvas under %s", node).isNotEmpty();
        return canvases.getLast();
    }

    private static <T> List<T> descendants(Node node, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(node, type, out);
        return out;
    }

    private static <T> void collect(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, out);
            }
        }
    }
}
