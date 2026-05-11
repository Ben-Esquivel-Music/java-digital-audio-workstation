package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 260 — smoke test for the semantic-token contract.
 *
 * <p>Builds a tiny FX scene whose root is a {@code BorderPane} carrying the
 * {@code root-pane} style class (the same anchor {@code main-view.fxml}
 * declares at its root) and attaches {@code styles.css}. The test then
 * resolves every Palette A token in turn by giving a child {@code Region}
 * the inline style {@code -fx-background-color: -<token>;} and reading the
 * computed background colour after a CSS pass.
 *
 * <p>If the tokens were missing or pointed at the wrong hex, the resolved
 * colours would not match Palette A and this test would fail at the first
 * offending token — surfacing the regression before any visual screen does.
 *
 * <p>Note: this fixture does <em>not</em> load {@code main-view.fxml}
 * directly. The full FXML transitively constructs {@link MainController},
 * which {@code @FXML initialize()} spins up an {@code AudioEngine},
 * {@code AudioBackendFactory}, autosave timers, etc. — none of which are
 * needed (or safe) for a pure CSS-token smoke check. The same
 * {@code .root-pane} anchor is reproduced here so the lookup resolution
 * path is identical to production.
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class TokenResolutionSmokeTest {

    private static final String STYLES_CSS_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void everyPaletteATokenResolvesToExpectedColour() throws Exception {
        // Palette A — "Onyx Refined" — UI_DESIGN_BOOK.md §3.1.
        // Order matches the token declaration block in styles.css so that
        // any future re-ordering is easy to cross-check against this list.
        Map<String, Color> expected = new LinkedHashMap<>();
        expected.put("-surface-bg",      Color.web("#0B0B0E"));
        expected.put("-surface-1",       Color.web("#15161B"));
        expected.put("-surface-2",       Color.web("#1D1F26"));
        expected.put("-surface-3",       Color.web("#272A33"));
        expected.put("-surface-overlay", Color.web("#0F1014"));
        expected.put("-line-soft",       Color.web("#22242C"));
        expected.put("-line-strong",     Color.web("#2E323D"));
        expected.put("-focus-ring",      Color.web("#5C8CFF"));
        expected.put("-text-hi",         Color.web("#ECEEF2"));
        expected.put("-text",            Color.web("#B7BCC7"));
        expected.put("-text-mute",       Color.web("#7A808C"));
        expected.put("-text-on-accent",  Color.web("#0B0B0E"));
        expected.put("-accent",          Color.web("#7C8CFF"));
        expected.put("-accent-soft",     Color.rgb(124, 140, 255, 0.14));
        expected.put("-ok",              Color.web("#5BD2A0"));
        expected.put("-warn",            Color.web("#E6B450"));
        expected.put("-danger",          Color.web("#E5484D"));
        expected.put("-meter-low",       Color.web("#3FBF7F"));
        expected.put("-meter-mid",       Color.web("#B6D451"));
        expected.put("-meter-hi",        Color.web("#E6B450"));
        expected.put("-meter-clip",      Color.web("#E5484D"));

        Map<String, Color> resolved = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");

            // Stage one Region per token, each requesting the token as its
            // background. After applyCss(), the resolved fill is the
            // looked-up color's value — that's what we compare to Palette A.
            Map<String, Region> probes = new LinkedHashMap<>();
            for (String token : expected.keySet()) {
                Region probe = new Region();
                probe.setStyle("-fx-background-color: " + token + ";");
                probes.put(token, probe);
                rootPane.getChildren().add(probe);
            }

            Scene scene = new Scene(rootPane, 100, 100);
            URL css = getClass().getResource(STYLES_CSS_RESOURCE);
            assertThat(css)
                    .as("styles.css must be on the test classpath at %s", STYLES_CSS_RESOURCE)
                    .isNotNull();
            scene.getStylesheets().add(css.toExternalForm());
            rootPane.applyCss();
            rootPane.layout();

            Map<String, Color> out = new LinkedHashMap<>();
            for (Map.Entry<String, Region> e : probes.entrySet()) {
                Paint fill = e.getValue().getBackground().getFills().get(0).getFill();
                assertThat(fill)
                        .as("token %s must resolve to a Color (not %s)", e.getKey(),
                                fill == null ? "null" : fill.getClass().getName())
                        .isInstanceOf(Color.class);
                out.put(e.getKey(), (Color) fill);
            }
            return out;
        });

        for (Map.Entry<String, Color> e : expected.entrySet()) {
            String token = e.getKey();
            Color want = e.getValue();
            Color got = resolved.get(token);
            // Component-wise comparison with a small tolerance: CSS color
            // parsing rounds 8-bit channels through double precision, so
            // exact Color.equals() would be brittle. 1/255 ≈ 0.004 is the
            // smallest visible difference; we use half of that.
            assertThat(got.getRed())
                    .as("token %s red component", token)
                    .isCloseTo(want.getRed(), offset(0.002));
            assertThat(got.getGreen())
                    .as("token %s green component", token)
                    .isCloseTo(want.getGreen(), offset(0.002));
            assertThat(got.getBlue())
                    .as("token %s blue component", token)
                    .isCloseTo(want.getBlue(), offset(0.002));
            assertThat(got.getOpacity())
                    .as("token %s alpha (opacity)", token)
                    .isCloseTo(want.getOpacity(), offset(0.002));
        }
    }

    @Test
    void rootPaneAdoptsSurfaceBgFromTokens() throws Exception {
        // The .root-pane rule sets -fx-background-color: -surface-bg. This
        // is the single visible application of the token system on the
        // production root and the most direct regression canary if any of
        // the lookup wiring breaks.
        Color rootFill = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");
            Scene scene = new Scene(rootPane, 100, 100);
            scene.getStylesheets().add(getClass().getResource(STYLES_CSS_RESOURCE).toExternalForm());
            rootPane.applyCss();
            rootPane.layout();
            Paint fill = rootPane.getBackground().getFills().get(0).getFill();
            assertThat(fill).isInstanceOf(Color.class);
            return (Color) fill;
        });

        Color expected = Color.web("#0B0B0E");
        assertThat(rootFill.getRed()).isCloseTo(expected.getRed(), offset(0.002));
        assertThat(rootFill.getGreen()).isCloseTo(expected.getGreen(), offset(0.002));
        assertThat(rootFill.getBlue()).isCloseTo(expected.getBlue(), offset(0.002));
    }

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
            throw new RuntimeException("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
