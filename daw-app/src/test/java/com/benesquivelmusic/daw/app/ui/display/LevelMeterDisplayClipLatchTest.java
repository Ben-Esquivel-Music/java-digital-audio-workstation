package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 318 — the clip latch of {@link LevelMeterDisplay} (headless): one
 * clipping update latches, quiet updates keep it, {@code resetClip()} or a
 * primary-button click inside the clip bar clears it, and a click anywhere
 * else (or with another button) is a no-op — the {@link InputMeterStrip}
 * hit-zone rule.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LevelMeterDisplayClipLatchTest {

    private static final LevelData CLIPPING = new LevelData(1.0, 0.7, 0.0, -3.0, true);
    private static final LevelData QUIET = new LevelData(0.1, 0.05, -20.0, -26.0, false);

    private static <T> T onFx(Supplier<T> supplier) throws Exception {
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
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("FX action completes").isTrue();
        if (err.get() != null) {
            throw new AssertionError("FX action threw", err.get());
        }
        return ref.get();
    }

    private static void onFxRun(Runnable r) throws Exception {
        onFx(() -> {
            r.run();
            return null;
        });
    }

    private static LevelMeterDisplay newDisplay(boolean vertical, double width, double height) throws Exception {
        return onFx(() -> {
            LevelMeterDisplay d = new LevelMeterDisplay(vertical);
            d.resize(width, height);
            return d;
        });
    }

    private static MouseEvent click(MouseButton button, double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED,
                x, y, x, y, button, 1,
                false, false, false, false,
                false, false, false, false, false, true, null);
    }

    @Test
    void oneClippingUpdateLatchesAndQuietUpdatesKeepIt() throws Exception {
        LevelMeterDisplay display = newDisplay(true, 40, 120);
        try {
            assertThat(display.isClipLatched()).isFalse();
            onFxRun(() -> display.update(CLIPPING));
            assertThat(display.isClipLatched()).isTrue();

            onFxRun(() -> {
                display.update(QUIET);
                display.update(QUIET);
                display.update(LevelData.SILENCE);
            });
            assertThat(display.isClipLatched()).as("stays latched across quiet blocks").isTrue();
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void resetClipClearsTheLatch() throws Exception {
        LevelMeterDisplay display = newDisplay(true, 40, 120);
        try {
            onFxRun(() -> display.update(CLIPPING));
            onFxRun(display::resetClip);
            assertThat(display.isClipLatched()).isFalse();

            onFxRun(() -> display.update(CLIPPING));
            assertThat(display.isClipLatched()).as("re-latches on the next clipping block").isTrue();
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void primaryClickInsideTheVerticalClipBarClears() throws Exception {
        LevelMeterDisplay display = newDisplay(true, 40, 120);
        try {
            onFxRun(() -> display.update(CLIPPING));
            onFxRun(() -> Event.fireEvent(display, click(MouseButton.PRIMARY, 20, 2)));
            assertThat(display.isClipLatched()).isFalse();
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void primaryClickInsideTheHorizontalClipBarClears() throws Exception {
        LevelMeterDisplay display = newDisplay(false, 120, 20);
        try {
            onFxRun(() -> display.update(CLIPPING));
            onFxRun(() -> Event.fireEvent(display, click(MouseButton.PRIMARY, 118, 10)));
            assertThat(display.isClipLatched()).isFalse();
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void clickOnTheMeterBodyOrWithAnotherButtonIsANoOp() throws Exception {
        LevelMeterDisplay vertical = newDisplay(true, 40, 120);
        LevelMeterDisplay horizontal = newDisplay(false, 120, 20);
        try {
            onFxRun(() -> {
                vertical.update(CLIPPING);
                horizontal.update(CLIPPING);
            });
            onFxRun(() -> {
                Event.fireEvent(vertical, click(MouseButton.PRIMARY, 20, 60));
                Event.fireEvent(vertical, click(MouseButton.PRIMARY, 20, LevelMeterDisplay.CLIP_BAR_PX + 1));
                Event.fireEvent(vertical, click(MouseButton.SECONDARY, 20, 2));
                Event.fireEvent(horizontal, click(MouseButton.PRIMARY, 60, 10));
                Event.fireEvent(horizontal, click(MouseButton.MIDDLE, 118, 10));
                // Cross-axis: the clip bar is painted inset by 2 px on its
                // OTHER axis, so a click level with the bar but past its
                // painted end is outside the LED and must not reset.
                Event.fireEvent(vertical, click(MouseButton.PRIMARY, 0.5, 2));
                Event.fireEvent(vertical, click(MouseButton.PRIMARY, 39.5, 2));
                Event.fireEvent(horizontal, click(MouseButton.PRIMARY, 118, 0.5));
                Event.fireEvent(horizontal, click(MouseButton.PRIMARY, 118, 19.5));
            });
            assertThat(vertical.isClipLatched()).as("vertical body / other button").isTrue();
            assertThat(horizontal.isClipLatched()).as("horizontal body / other button").isTrue();
        } finally {
            onFxRun(() -> {
                vertical.dispose();
                horizontal.dispose();
            });
        }
    }
}
