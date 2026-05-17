package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.BrowserPanel.BrowserSection;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 275 / UI Design Book §7.1 (glow veto), §7.3 (border veto) — a
 * hovered browser row's background resolves to {@code -surface-3} with
 * NO drop-shadow / effect.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class BrowserHoverStyleTest {

    private <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.call());
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) {
            throw err.get();
        }
        return ref.get();
    }

    @SuppressWarnings("unchecked")
    @Test
    void hoveredRowResolvesToSurface3WithNoEffect() throws Exception {
        Color[] result = onFx(() -> {
            BrowserPanel panel = new BrowserPanel();
            panel.selectSection(BrowserSection.SAMPLES);
            panel.addSamples(List.of("kick.wav"));

            StackPane root = new StackPane(panel);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 320, 600);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            root.snapshot(null, null);
            root.applyCss();
            root.layout();

            ListCell<String> cell = null;
            Set<Node> cells = panel.getSamplesListView().lookupAll(".list-cell");
            for (Node n : cells) {
                if (n instanceof ListCell<?> lc && lc.getItem() != null) {
                    cell = (ListCell<String>) lc;
                    break;
                }
            }
            if (cell == null) {
                return null;
            }

            // Drive :hover via the pseudo-class directly — synthetic
            // MOUSE_ENTERED is unreliable headless.
            cell.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
            cell.applyCss();
            root.layout();

            Color bg = cell.getBackground() == null
                    || cell.getBackground().getFills().isEmpty()
                    ? null
                    : (Color) cell.getBackground().getFills().getLast().getFill();

            // Sibling probe for the expected -surface-3 token value
            // (palette-swap safe — no hex literal pinned in the test).
            javafx.scene.layout.Region probe = new javafx.scene.layout.Region();
            probe.setStyle("-fx-background-color: -surface-3;");
            root.getChildren().add(probe);
            root.applyCss();
            root.layout();
            Color expected = (Color) probe.getBackground().getFills().getFirst().getFill();

            boolean hasEffect = cell.getEffect() != null;
            return new Color[] {
                    bg, expected, hasEffect ? Color.RED : null
            };
        });

        assertThat(result).as("an audio row cell was realized").isNotNull();
        Color hoverBg = result[0];
        Color surface3 = result[1];

        assertThat(hoverBg).as("hovered row background resolves (not null)").isNotNull();
        assertThat(hoverBg.getRed()).as("hover bg red == -surface-3")
                .isCloseTo(surface3.getRed(), offset(0.01));
        assertThat(hoverBg.getGreen()).as("hover bg green == -surface-3")
                .isCloseTo(surface3.getGreen(), offset(0.01));
        assertThat(hoverBg.getBlue()).as("hover bg blue == -surface-3")
                .isCloseTo(surface3.getBlue(), offset(0.01));
        // Prove it differs from the default transparent / unstyled state.
        assertThat(hoverBg)
                .as("hover bg must NOT be transparent (proves the cascade fired)")
                .isNotEqualTo(Color.TRANSPARENT);

        // §7.1 — no glow/drop-shadow on hover. result[2] is non-null only
        // if the cell carried an Effect.
        assertThat(result[2])
                .as("hovered row must have NO effect (§7.1 glow veto)")
                .isNull();
    }
}
