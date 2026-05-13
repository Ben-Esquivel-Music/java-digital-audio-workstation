package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 263 — UI Design Book §1.3, §3.3, §5.1.
 *
 * <p>The three legacy button systems were unified behind {@code .dawg-button}
 * with explicit size variants so a sidebar button can sit next to a transport
 * button without using a different style class. This test instantiates three
 * buttons in different parents — a sidebar-styled default button, a transport
 * button, and a dialog-footer-styled default button — applies the project
 * stylesheet, and asserts that the two {@code size-default} buttons render to
 * the same pixel height even though they live in different parents.
 *
 * <p>The test runs on the JavaFX Application Thread; the JavaFX toolkit is
 * started lazily by {@link JavaFxToolkitExtension}. A live display (or Xvfb)
 * is required.
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ButtonAlignmentTest {

    private static final String STYLES_CSS =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void defaultSizedDawgButtonsRenderToSamePixelHeight() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<double[]> heights = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                // Sidebar / default-sized button (sibling of a transport bar).
                Button sidebarButton = new Button("Settings");
                sidebarButton.getStyleClass().addAll("dawg-button", "size-default");

                // Transport button — size-transport, larger.
                Button transportButton = new Button("Play");
                transportButton.getStyleClass().addAll("dawg-button", "size-transport");

                // Dialog footer / default-sized button, different parent.
                Button dialogButton = new Button("OK");
                dialogButton.getStyleClass().addAll("dawg-button", "size-default");

                HBox sidebar = new HBox(sidebarButton);
                sidebar.getStyleClass().add("toolbar-sidebar");
                HBox transportBar = new HBox(transportButton);
                transportBar.getStyleClass().add("transport-bar");
                HBox dialogFooter = new HBox(dialogButton);
                dialogFooter.getStyleClass().add("dialog-pane");

                VBox root = new VBox(sidebar, transportBar, dialogFooter);
                root.getStyleClass().add("root-pane");

                Scene scene = new Scene(root, 320, 240);
                String css = ButtonAlignmentTest.class
                        .getResource(STYLES_CSS).toExternalForm();
                scene.getStylesheets().add(css);

                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();

                // Force layout so getLayoutBounds reflects rendered size.
                root.applyCss();
                root.layout();

                double a = sidebarButton.getLayoutBounds().getHeight();
                double b = transportButton.getLayoutBounds().getHeight();
                double c = dialogButton.getLayoutBounds().getHeight();
                heights.set(new double[] { a, b, c });

                stage.close();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(15, TimeUnit.SECONDS))
                .as("JavaFX layout pass must complete within 15 s")
                .isTrue();
        if (failure.get() != null) {
            throw new AssertionError("FX-thread failure: " + failure.get(), failure.get());
        }

        double[] h = heights.get();
        assertThat(h).as("button heights must be captured").isNotNull();
        double sidebar = h[0];
        double transport = h[1];
        double dialog = h[2];

        assertThat(Math.abs(sidebar - dialog))
                .as("Two .dawg-button.size-default buttons in different parents "
                        + "must render to the same pixel height (sidebar=%s, dialog=%s) "
                        + "— story 263, UI Design Book §1.3.", sidebar, dialog)
                .isLessThan(1.0);

        // Sanity check: the size-transport variant is strictly taller than
        // size-default. If this fails, padding tokens have drifted.
        assertThat(transport)
                .as(".dawg-button.size-transport (height=%s) must be taller than "
                        + ".dawg-button.size-default (height=%s) — story 263, §5.1.",
                        transport, sidebar)
                .isGreaterThan(sidebar);
    }
}
