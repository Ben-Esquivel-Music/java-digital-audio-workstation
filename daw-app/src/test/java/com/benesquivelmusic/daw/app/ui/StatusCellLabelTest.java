package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 274 S1 — {@link StatusCellLabel} is the single seam that keeps the
 * leading "· " separator on the non-first status-bar cells
 * ({@code checkpointLabel}, {@code statusBarLabel}) no matter which of the
 * ~30 writers in MainController / TransportController /
 * ProjectLifecycleController set the text. These tests pin that behaviour
 * directly (the FXML test only pins that the <em>type</em> is used).
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class StatusCellLabelTest {

    @Test
    void bareTextGetsTheLeadingSeparator() {
        assertThat(textAfterSet("Stopped"))
                .isEqualTo(StatusCellLabel.CELL_SEPARATOR + "Stopped");
    }

    @Test
    void dynamicWriterMessageGetsTheSeparator() {
        // The exact shape ProjectLifecycleController emits — the S1 case.
        assertThat(textAfterSet("Saved (checkpoint #3)"))
                .isEqualTo("· Saved (checkpoint #3)");
    }

    @Test
    void alreadyPrefixedTextIsLeftUnchanged_idempotent() {
        // Bundle values / design-time FXML carry the dot for cross-cell
        // uniformity; the seam must not double it.
        assertThat(textAfterSet("· Auto-save: ON"))
                .isEqualTo("· Auto-save: ON");
    }

    @Test
    void blankAndNullAreLeftUntouched() {
        assertThat(textAfterSet("")).isEmpty();
        assertThat(textAfterSet("   ")).isEqualTo("   ");
        assertThat(textAfterSet(null)).isNull();
    }

    private static String textAfterSet(String value) {
        return onFxThread(() -> {
            StatusCellLabel label = new StatusCellLabel();
            label.setText(value);
            return label.getText();
        });
    }

    private static <T> T onFxThread(Supplier<T> supplier) {
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
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX thread did not complete within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for FX thread", e);
        }
        if (err.get() != null) {
            throw new RuntimeException("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
