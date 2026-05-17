package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI Design Book §5.10 / §3.5 — the toast auto-dismisses after a flat
 * 5 s by default; the dismissal Timeline is stopped on early dismiss;
 * and with Reduce Motion (story 279 hook) dismissal is immediate.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NotificationLifecycleTest {

    private NotificationBar createOnFxThread() throws Exception {
        AtomicReference<NotificationBar> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new NotificationBar());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void defaultAutoDismissIsFiveSeconds() {
        // §5.10 — flat 5 s for all levels (no per-level override set).
        assertThat(NotificationBar.DEFAULT_AUTO_DISMISS_MS).isEqualTo(5_000);
    }

    @Test
    void shortOverrideAutoDismissesBar() throws Exception {
        NotificationBar bar = createOnFxThread();

        runOnFxThread(() -> {
            bar.setAutoDismissMillis(80);
            bar.show(NotificationLevel.INFO, "Auto-dismiss me");
        });
        assertThat(bar.isVisible()).isTrue();

        // Bounded await — the fade-out (200 ms) plus the 80 ms timer
        // resolves well within a couple of seconds; never sleep 5 s.
        AtomicBoolean hidden = new AtomicBoolean(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            CountDownLatch poll = new CountDownLatch(1);
            Platform.runLater(() -> {
                hidden.set(!bar.isVisible());
                poll.countDown();
            });
            poll.await(2, TimeUnit.SECONDS);
            if (hidden.get()) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(hidden.get())
                .as("bar should auto-dismiss after the short override elapses")
                .isTrue();
    }

    @Test
    void reduceMotionDismissesImmediately() throws Exception {
        NotificationBar bar = createOnFxThread();

        runOnFxThread(() -> {
            bar.setAnimated(false);
            bar.show(NotificationLevel.ERROR, "Plugin failed to load");
        });
        assertThat(bar.isVisible()).isTrue();

        // With Reduce Motion the fade is skipped — the bar is hidden
        // synchronously within the same FX pulse, no 200 ms wait.
        runOnFxThread(bar::dismiss);

        assertThat(bar.isVisible())
                .as("dismiss must be immediate under Reduce Motion (story 279 hook)")
                .isFalse();
        assertThat(bar.isManaged()).isFalse();
        // getCurrentLevel() gates on isVisible(), so it would read null
        // even if cleanup were skipped — assert the actual level-style
        // teardown instead so this test bites if clearLevelStyle is lost.
        assertThat(bar.getStyleClass())
                .as("the bar's level style class must be removed (accent-bar reset)")
                .doesNotContain(NotificationLevel.ERROR.styleClass());
        assertThat(bar.getPill().getCurrentLevel())
                .as("the shared pill's level state must be cleared on dismiss")
                .isNull();
    }
}
