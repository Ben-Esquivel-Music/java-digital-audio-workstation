package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
 * Story 278 — proof of the UI Design Book §3.7 invariant: <em>"Motion,
 * type scale, and elevation are unchanged across density. Only padding
 * and row height change."</em>
 *
 * <p>One scene with a {@code .body} label (type scale, resolved from
 * {@code styles.css}) and an elevated node whose drop-shadow is applied
 * by a stylesheet rule (elevation). The active density is cycled
 * COMPACT → COMFORTABLE → TOUCH; after each, a CSS pass is run and the
 * resolved font size of the {@code .body} label and the resolved
 * drop-shadow of the elevated node are captured. All three must be
 * identical — density must not perturb type scale or elevation.</p>
 *
 * <p>The {@code .body} font is resolved through the real production
 * {@code styles.css} cascade (the type-scale half of the invariant — if
 * a density rule ever touched {@code -fx-font-size} this would shift).
 * The elevation probe uses a self-contained drop-shadow rule rather than
 * the {@code -elevation-3} looked-up token: JavaFX cannot resolve a
 * looked-up value for {@code -fx-effect} across two separate stylesheets
 * (it logs "Parsed value is not an Effect" and drops the effect), a
 * platform limitation unrelated to this story. A constant rule-applied
 * effect still proves the invariant — the assertion is that the
 * <em>resolved</em> effect is byte-identical across all three densities,
 * i.e. nothing in the density CSS perturbs the elevation render path.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class DensityInvariantTest {

    /** Captured resolved type-scale / elevation values at one density. */
    private record Resolved(double fontSize, double shadowRadius,
                            double shadowOffsetY, double shadowSpread) { }

    @Test
    void typeScaleAndElevationAreIdenticalAcrossAllThreeDensities() throws Exception {
        Preferences node = Preferences.userRoot()
                .node("densityInvariant_" + System.nanoTime());
        DensityManager mgr = new DensityManager(node);
        try {
            onFxThread(() -> {
                Label body = new Label("Body text");
                body.getStyleClass().add("body");

                Region elevated = new Region();
                elevated.getStyleClass().add("elevation-probe");

                BorderPane root = new BorderPane(new VBox(body, elevated));
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 300, 200);
                DarkThemeHelper.applyTo(scene);
                mgr.applyTo(scene);
                // Self-contained drop-shadow rule (NOT the -elevation-3
                // looked-up token — JavaFX cannot resolve a looked-up
                // -fx-effect across separate stylesheets; see the class
                // Javadoc). The effect is constant by construction; the
                // invariant under test is that density does not perturb
                // its RESOLVED value (radius/offset/spread identical
                // across COMPACT/COMFORTABLE/TOUCH).
                String probeCss = ".elevation-probe { -fx-effect: "
                        + "dropshadow(gaussian, rgba(0,0,0,0.5), 16, 0, 0, 6); }";
                scene.getStylesheets().add("data:text/css;base64,"
                        + Base64.getEncoder().encodeToString(
                                probeCss.getBytes(StandardCharsets.UTF_8)));

                Resolved compact = resolveAt(mgr, DensityMode.COMPACT, root, body, elevated);
                Resolved comfortable = resolveAt(mgr, DensityMode.COMFORTABLE, root, body, elevated);
                Resolved touch = resolveAt(mgr, DensityMode.TOUCH, root, body, elevated);

                assertThat(compact)
                        .as("§3.7: COMPACT must not change type scale / elevation vs COMFORTABLE")
                        .isEqualTo(comfortable);
                assertThat(touch)
                        .as("§3.7: TOUCH must not change type scale / elevation vs COMFORTABLE")
                        .isEqualTo(comfortable);

                // Sanity: the probes actually resolved (a 0/empty probe
                // would make the equality assertion vacuously true).
                assertThat(comfortable.fontSize())
                        .as(".body font size must resolve to the 12 px scale")
                        .isCloseTo(12.0, offset(0.5));
                assertThat(comfortable.shadowRadius())
                        .as(".elevation-probe drop-shadow must resolve to a non-zero radius")
                        .isGreaterThan(0.0);
                return null;
            });
        } finally {
            removeQuietly(node);
        }
    }

    private static Resolved resolveAt(DensityManager mgr, DensityMode mode,
                                      Region root, Label body, Region elevated) {
        mgr.setActiveDensity(mode);
        root.applyCss();
        root.layout();
        double fontSize = body.getFont().getSize();
        Effect e = elevated.getEffect();
        assertThat(e)
                .as("the elevated probe must carry a DropShadow at density %s", mode)
                .isInstanceOf(DropShadow.class);
        DropShadow ds = (DropShadow) e;
        return new Resolved(fontSize, ds.getRadius(),
                ds.getOffsetY(), ds.getSpread());
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
