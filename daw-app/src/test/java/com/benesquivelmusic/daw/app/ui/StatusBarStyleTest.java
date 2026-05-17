package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 274 — renders the project-filename status-bar cell and asserts the
 * UI Design Book §5.11 colour contract: the project filename is
 * {@code -text} weight 500, with <strong>no</strong> purple accent (the
 * {@code .project-info-label} purple was removed; §7.6 veto).
 *
 * <p>Fixture pattern mirrors {@link TokenResolutionSmokeTest}: a
 * {@code BorderPane.root-pane} (the same token anchor {@code main-view.fxml}
 * declares) with {@code styles.css} attached, then {@code applyCss()} +
 * {@code layout()} on the FX thread. It deliberately does <em>not</em> load
 * {@code main-view.fxml} via {@link javafx.fxml.FXMLLoader} — that boots
 * {@code MainController.initialize()} (real AudioEngine, autosave timers)
 * and would hang a headless test.
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class StatusBarStyleTest {

    private static final String STYLES_CSS_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    /** Palette A — UI Design Book §3.1. */
    private static final Color TEXT = Color.web("#B7BCC7");
    private static final Color ACCENT = Color.web("#7C8CFF");

    @Test
    void projectFilenameCellResolvesToTextColourNotPurple() throws Exception {
        Color fill = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");

            // Reproduce the project-filename cell exactly: same style
            // classes (.body composes the -text/weight-500 colour;
            // .numeric-value composes the mono typography) inside a
            // .status-bar HBox with the same numeric padding the FXML
            // declares (top=4 right=16 bottom=4 left=16).
            HBox statusBar = new HBox(16);
            statusBar.getStyleClass().add("status-bar");
            statusBar.setPadding(new Insets(4, 16, 4, 16));

            Label projectInfo = new Label("Untitled Project  ·  96 kHz / 24-bit / 8ch");
            projectInfo.getStyleClass().addAll("body", "numeric-value");
            statusBar.getChildren().add(projectInfo);

            rootPane.setBottom(statusBar);

            Scene scene = new Scene(rootPane, 600, 200);
            URL css = getClass().getResource(STYLES_CSS_RESOURCE);
            assertThat(css)
                    .as("styles.css must be on the test classpath at %s", STYLES_CSS_RESOURCE)
                    .isNotNull();
            scene.getStylesheets().add(css.toExternalForm());
            rootPane.applyCss();
            rootPane.layout();

            Paint textFill = projectInfo.getTextFill();
            assertThat(textFill)
                    .as("project-filename cell text fill must be a Color")
                    .isInstanceOf(Color.class);
            return (Color) textFill;
        });

        // Strict: must be -text (#B7BCC7), component-wise within the same
        // tolerance the token smoke test uses (CSS rounds 8-bit channels
        // through double precision).
        assertThat(fill.getRed())
                .as("project filename red component must resolve to -text")
                .isCloseTo(TEXT.getRed(), offset(0.002));
        assertThat(fill.getGreen())
                .as("project filename green component must resolve to -text")
                .isCloseTo(TEXT.getGreen(), offset(0.002));
        assertThat(fill.getBlue())
                .as("project filename blue component must resolve to -text")
                .isCloseTo(TEXT.getBlue(), offset(0.002));

        // And it must NOT be the purple accent (the §7.6 regression guard).
        boolean isAccent =
                Math.abs(fill.getRed() - ACCENT.getRed()) < 0.01
                        && Math.abs(fill.getGreen() - ACCENT.getGreen()) < 0.01
                        && Math.abs(fill.getBlue() - ACCENT.getBlue()) < 0.01;
        assertThat(isAccent)
                .as("project filename must NOT be the -accent purple "
                        + "(#7C8CFF) — the .project-info-label purple was "
                        + "removed in story 274 (UI Design Book §7.6).")
                .isFalse();
    }

    @Test
    void statusBarHeightResolvesToApproximately24px() throws Exception {
        double height = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");

            HBox statusBar = new HBox(16);
            statusBar.getStyleClass().add("status-bar");
            statusBar.setPadding(new Insets(4, 16, 4, 16));
            Label cell = new Label("44.1 kHz");
            cell.getStyleClass().addAll("body", "numeric-value");
            statusBar.getChildren().add(cell);
            rootPane.setBottom(statusBar);

            Scene scene = new Scene(rootPane, 600, 200);
            URL css = getClass().getResource(STYLES_CSS_RESOURCE);
            scene.getStylesheets().add(css.toExternalForm());
            rootPane.applyCss();
            rootPane.layout();
            return statusBar.getHeight();
        });

        // §5.11 prescribes 24 px (8 px vertical padding + ~12 px mono line
        // height + 1 px top border). Headless font metrics vary, so a
        // ±5 px tolerance keeps the assertion meaningful without being
        // flaky on the headless Glass platform — the text-fill assertion
        // above is the strict contract; this is a sanity bound.
        assertThat(height)
                .as("status bar height should resolve to ~24 px "
                        + "(story 274 §5.11) — got %.2f px", height)
                .isBetween(19.0, 29.0);
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
