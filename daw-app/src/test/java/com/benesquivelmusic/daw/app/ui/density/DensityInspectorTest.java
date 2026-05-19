package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 278 — the inspector half of the global density contract.
 *
 * <p>The 278 Goal asked for a {@code .inspector-section-row} density
 * rule, but that selector never existed: story 272's
 * {@link InspectorSection} exposes a {@code .inspector-section-header}
 * (HBox) and a {@code .inspector-section-body} (VBox), styled by the
 * {@link InspectorDrawer}'s own component user-agent stylesheet
 * ({@code inspector/inspector.css}). The density rules added to
 * {@code styles.css} (AUTHOR origin) override that USER_AGENT sheet by
 * CSS origin, so the inspector follows the global density like every
 * other Phase-2 row.</p>
 *
 * <p>This builds a real {@code InspectorDrawer}, applies the production
 * {@code styles.css}, cycles COMPACT → COMFORTABLE → TOUCH and captures
 * the <em>resolved</em> {@code -fx-padding} of the track section's
 * header and body. It asserts three things:</p>
 *
 * <ol>
 *   <li><b>No-op at Comfortable</b> — the resolved padding equals the
 *       {@code inspector.css} baseline verbatim (header {@code 4 8 4 8},
 *       body {@code 4 8 8 8}); adding the Comfortable class must not
 *       move a pixel (the snapshot-suite invariant).</li>
 *   <li><b>Exact density values</b> — Compact / Touch resolve to the
 *       tighter / looser padding declared in {@code styles.css}.</li>
 *   <li><b>Monotonic progression</b> — vertical padding strictly
 *       increases Compact &lt; Comfortable &lt; Touch for both header
 *       and body, so this is not merely pinning literals: it proves the
 *       density actually drives the inspector row rhythm.</li>
 * </ol>
 *
 * <p>Assertions run on the test thread against values captured inside
 * the FX runnable — an assertion thrown inside a {@code Platform.runLater}
 * body is swallowed by the FX event loop (it would make this test pass
 * green on a real regression).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class DensityInspectorTest {

    /** Resolved {@code -fx-padding} of one inspector sub-region. */
    private record Pad(double top, double right, double bottom, double left) {
        static Pad of(Insets i) {
            return new Pad(i.getTop(), i.getRight(), i.getBottom(), i.getLeft());
        }
        double vertical() {
            return top + bottom;
        }
    }

    /** Header + body padding captured at one density. */
    private record SectionPad(Pad header, Pad body) { }

    @Test
    void inspectorSectionPaddingFollowsGlobalDensity() throws Exception {
        Preferences node = Preferences.userRoot()
                .node("densityInspector_" + System.nanoTime());
        DensityManager mgr = new DensityManager(node);
        try {
            SectionPad[] captured = onFxThread(() -> {
                InspectorDrawer drawer = new InspectorDrawer();
                drawer.setAnimated(false);

                BorderPane root = new BorderPane(drawer);
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 480, 600);
                // Production cascade: base styles.css (AUTHOR) carries the
                // density rules; the InspectorDrawer attaches its own
                // inspector.css (USER_AGENT) which the AUTHOR rules win
                // over by origin.
                DarkThemeHelper.applyTo(scene);
                mgr.applyTo(scene);

                InspectorSection section = drawer.getTrackSection();
                return new SectionPad[] {
                        measureAt(mgr, DensityMode.COMPACT, root, section),
                        measureAt(mgr, DensityMode.COMFORTABLE, root, section),
                        measureAt(mgr, DensityMode.TOUCH, root, section),
                };
            });

            SectionPad compact = captured[0];
            SectionPad comfortable = captured[1];
            SectionPad touch = captured[2];

            // 1 — Comfortable is a strict no-op: it must resolve to the
            //     inspector.css baseline verbatim (header 4 8 4 8,
            //     body 4 8 8 8). Insets order is (top,right,bottom,left).
            assertPad(comfortable.header(), 4, 8, 4, 8,
                    "Comfortable header == inspector.css baseline (no-op)");
            assertPad(comfortable.body(), 4, 8, 8, 8,
                    "Comfortable body == inspector.css baseline (no-op)");

            // 2 — Compact tightens, Touch loosens (styles.css values).
            assertPad(compact.header(), 2, 8, 2, 8, "Compact header");
            assertPad(compact.body(), 2, 8, 4, 8, "Compact body");
            assertPad(touch.header(), 8, 8, 8, 8, "Touch header");
            assertPad(touch.body(), 8, 8, 12, 8, "Touch body");

            // 3 — Monotonic density progression (not just pinned
            //     literals): vertical padding strictly grows
            //     Compact < Comfortable < Touch for header AND body.
            assertThat(compact.header().vertical())
                    .as("header vertical padding: Compact must be tighter than Comfortable")
                    .isLessThan(comfortable.header().vertical());
            assertThat(comfortable.header().vertical())
                    .as("header vertical padding: Comfortable must be tighter than Touch")
                    .isLessThan(touch.header().vertical());
            assertThat(compact.body().vertical())
                    .as("body vertical padding: Compact must be tighter than Comfortable")
                    .isLessThan(comfortable.body().vertical());
            assertThat(comfortable.body().vertical())
                    .as("body vertical padding: Comfortable must be tighter than Touch")
                    .isLessThan(touch.body().vertical());
        } finally {
            removeQuietly(node);
        }
    }

    private static SectionPad measureAt(DensityManager mgr, DensityMode mode,
                                        BorderPane root, InspectorSection section) {
        mgr.setActiveDensity(mode);
        root.applyCss();
        root.layout();
        return new SectionPad(
                Pad.of(section.getHeader().getPadding()),
                Pad.of(section.getBodyContainer().getPadding()));
    }

    private static void assertPad(Pad pad, double top, double right,
                                  double bottom, double left, String label) {
        assertThat(pad.top()).as("%s — top", label).isCloseTo(top, offset(0.5));
        assertThat(pad.right()).as("%s — right", label).isCloseTo(right, offset(0.5));
        assertThat(pad.bottom()).as("%s — bottom", label).isCloseTo(bottom, offset(0.5));
        assertThat(pad.left()).as("%s — left", label).isCloseTo(left, offset(0.5));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static <T> T onFxThread(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 5 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }

    private static void removeQuietly(Preferences node) {
        try {
            node.removeNode();
        } catch (BackingStoreException ignored) {
            // Best-effort cleanup; must not mask test results.
        }
    }
}
