package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 — when track 3's insert 1 is focused on a plugin named
 * <em>Serum</em>, the right-pane breadcrumb reads exactly
 * <strong>{@code Track 03 ▸ Insert 1 ▸ Serum}</strong> (the §4 Concept F
 * mock string).
 *
 * <p>Reads the breadcrumb segments directly from the
 * {@link com.benesquivelmusic.daw.app.ui.controls.BreadcrumbBar}'s observable
 * segments list rather than walking child nodes — the layout exposes its
 * model so the test cannot drift on a render detail (mirrors the
 * "extract pure transforms" guidance: assert on the model the renderer
 * consumes, not the rendered nodes).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopBreadcrumbTest {

    @Test
    void selectingTrack3Insert1OnSerumYieldsTrack03Insert1SerumBreadcrumb() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            new Scene(view, 1280, 800);

            // Simulate the application controller wiring: pick track 3,
            // open insert 1's plugin ("Serum"), push the focus to the
            // workshop view.
            Region pluginNode = new Region();
            pluginNode.setId("serum");
            view.setFocusedPlugin(/* trackIndex= */ 3, "Serum", pluginNode);

            // Assert on the model (the BreadcrumbBar's segments list) —
            // not on the rendered Label children, which would couple the
            // test to the separator-glyph layout.
            List<String> segments = view.breadcrumb().getSegments();
            assertThat(segments)
                    .as("breadcrumb segments must read 'Track 03 ▸ Insert 1 ▸ Serum'")
                    .containsExactly("Track 03", "Insert 1", "Serum");

            // Sanity: the static composer matches the runtime composer so
            // future callers can reproduce the exact string off-thread.
            assertThat(WorkshopView.buildSegments(messagesBundle(), 3, 1, "Serum"))
                    .as("WorkshopView.buildSegments must produce the same triple")
                    .containsExactly("Track 03", "Insert 1", "Serum");
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        return new WorkshopView(messagesBundle());
    }

    private static ResourceBundle messagesBundle() {
        return ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
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
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 15 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
