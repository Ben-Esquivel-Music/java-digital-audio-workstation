package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 — switching the focused plugin updates the right pane's
 * inner content but does NOT unmount or rebuild the
 * {@link PluginViewContainer}. The container's identity is the seam other
 * sub-panes bind to (the breadcrumb header and the clip-detail host live
 * around it), so re-creating it on every selection switch would tear down
 * those bindings.
 *
 * <p>Asserts identity via {@link System#identityHashCode(Object)} of the
 * container before and after a switch — strict reference equality
 * (rather than equals/hashCode) catches even a {@code new
 * PluginViewContainer()} that happened to look identical.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopPluginSwitchTest {

    @Test
    void switchingFromPluginAToPluginBKeepsContainerIdentity() throws Exception {
        onFxThread(() -> {
            WorkshopView view = newWorkshopView();
            new Scene(view, 1280, 800);

            // Capture the container identity BEFORE any plugin is focused.
            PluginViewContainer containerBefore = view.pluginContainer();
            int idBefore = System.identityHashCode(containerBefore);

            // Plugin A on insert A.
            Region pluginA = new Region();
            pluginA.setId("pluginA");
            view.setFocusedPlugin(1, "Plugin A", pluginA);
            assertThat(view.pluginContainer().getPluginView())
                    .as("after focusing plugin A, the container shows plugin A's node")
                    .isSameAs(pluginA);

            // Switch to plugin B on insert B — the right-pane container
            // must update its inner content WITHOUT being unmounted /
            // re-created.
            Region pluginB = new Region();
            pluginB.setId("pluginB");
            view.setFocusedPlugin(2, "Plugin B", pluginB);

            PluginViewContainer containerAfter = view.pluginContainer();
            int idAfter = System.identityHashCode(containerAfter);

            assertThat(containerAfter)
                    .as("the PluginViewContainer instance must be the same object "
                            + "across plugin switches — strict reference identity")
                    .isSameAs(containerBefore);
            assertThat(idAfter)
                    .as("the PluginViewContainer identity-hash must be unchanged "
                            + "after a plugin switch (was %d, now %d)",
                            idBefore, idAfter)
                    .isEqualTo(idBefore);
            assertThat(view.pluginContainer().getPluginView())
                    .as("after the switch, the container's inner content reflects plugin B")
                    .isSameAs(pluginB);
            // And the container is still parented inside the right pane —
            // the swap is purely internal.
            assertThat(view.pluginContainer().getParent())
                    .as("the container stays attached to the right pane VBox")
                    .isSameAs(view.rightPane());
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages, new InspectorSelectionModel());
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
