package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.ClockKind;
import com.benesquivelmusic.daw.sdk.audio.ClockLockEvent;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ClockStatusIndicator} — the transport-bar badge that
 * shows the active hardware clock source ("INT 48k", "EXT W/C 96k") and
 * flashes red when the driver reports the external clock as unlocked,
 * additionally pushing a notification and pausing recording.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClockStatusIndicatorTest {

    private static final DeviceId MOCK_DEVICE = new DeviceId(MockAudioBackend.NAME, "Mock Device");

    @Test
    void showsDimPlaceholderUntilSourceIsAssigned() throws Exception {
        AtomicReference<ClockStatusIndicator> ref = new AtomicReference<>();
        runOnFxAndWait(() -> ref.set(new ClockStatusIndicator(NotificationManager.noop(), null)));
        assertThat(ref.get().getText()).isEqualTo("CLK —");
        assertThat(ref.get().isLocked()).isTrue();
    }

    @Test
    void showSourceRendersShortLabelAndSampleRate() throws Exception {
        AtomicReference<ClockStatusIndicator> ref = new AtomicReference<>();
        runOnFxAndWait(() -> ref.set(new ClockStatusIndicator(NotificationManager.noop(), null)));
        ClockStatusIndicator ind = ref.get();
        ClockSource wordClock = new ClockSource(1, "Word Clock", true, new ClockKind.WordClock());
        ind.showSource(wordClock, 96_000);
        runOnFxAndWait(() -> { /* flush */ });
        assertThat(ind.getText()).isEqualTo("W/C 96k");

        ClockSource spdif = new ClockSource(2, "S/PDIF", true, new ClockKind.Spdif());
        ind.showSource(spdif, 44_100);
        runOnFxAndWait(() -> { /* flush */ });
        // 44.1 kHz must render as "44.1k" — the user expects the engineer
        // shorthand exactly as ASIO panels print it.
        assertThat(ind.getText()).isEqualTo("SPDIF 44.1k");
    }

    @Test
    void lockLossEventTriggersNotificationAndPauseHandlerExactlyOnce() throws Exception {
        AtomicInteger notifyCount = new AtomicInteger();
        AtomicReference<String> lastMessage = new AtomicReference<>();
        AtomicInteger pauseCount = new AtomicInteger();
        NotificationManager notifications = msg -> {
            notifyCount.incrementAndGet();
            lastMessage.set(msg);
        };

        AtomicReference<ClockStatusIndicator> ref = new AtomicReference<>();
        runOnFxAndWait(() -> ref.set(new ClockStatusIndicator(notifications, pauseCount::incrementAndGet)));
        ClockStatusIndicator ind = ref.get();
        ClockSource wordClock = new ClockSource(1, "Word Clock", true, new ClockKind.WordClock());
        ind.showSource(wordClock, 48_000);
        runOnFxAndWait(() -> { /* flush */ });

        // Driver reports an unlock — the indicator must notify the user
        // and trigger the recording-pause handler.
        ind.onLockEvent(new ClockLockEvent(MOCK_DEVICE, 1, false));
        runOnFxAndWait(() -> { /* flush */ });
        assertThat(notifyCount.get()).isEqualTo(1);
        assertThat(lastMessage.get())
                .contains("External clock not locked")
                .contains("recording quality");
        assertThat(pauseCount.get()).isEqualTo(1);
        assertThat(ind.isLocked()).isFalse();
        assertThat(ind.getText()).contains("UNLOCKED");

        // A second unlock event for the same already-unlocked state must
        // not re-notify or re-pause — that would spam the user with a
        // duplicate toast on every 1 Hz poll while the cable is unplugged.
        ind.onLockEvent(new ClockLockEvent(MOCK_DEVICE, 1, false));
        runOnFxAndWait(() -> { /* flush */ });
        assertThat(notifyCount.get()).isEqualTo(1);
        assertThat(pauseCount.get()).isEqualTo(1);
    }

    @Test
    void lockRegainedClearsTheUnlockedDisplay() throws Exception {
        AtomicReference<ClockStatusIndicator> ref = new AtomicReference<>();
        runOnFxAndWait(() -> ref.set(new ClockStatusIndicator(NotificationManager.noop(), null)));
        ClockStatusIndicator ind = ref.get();
        ClockSource wordClock = new ClockSource(1, "Word Clock", true, new ClockKind.WordClock());
        ind.showSource(wordClock, 48_000);
        ind.onLockEvent(new ClockLockEvent(MOCK_DEVICE, 1, false));
        runOnFxAndWait(() -> { /* flush */ });
        assertThat(ind.isLocked()).isFalse();
        ind.onLockEvent(new ClockLockEvent(MOCK_DEVICE, 1, true));
        runOnFxAndWait(() -> { /* flush */ });
        assertThat(ind.isLocked()).isTrue();
        assertThat(ind.getText()).doesNotContain("UNLOCKED");
    }

    @Test
    void eventsForInactiveSourcesAreIgnored() throws Exception {
        // While locked to Word Clock (id 1), an unlock event for S/PDIF
        // (id 2) is irrelevant — drivers may publish lock state for every
        // configured input even when only one is selected.
        AtomicInteger pauseCount = new AtomicInteger();
        AtomicReference<ClockStatusIndicator> ref = new AtomicReference<>();
        runOnFxAndWait(() -> ref.set(new ClockStatusIndicator(NotificationManager.noop(),
                pauseCount::incrementAndGet)));
        ClockStatusIndicator ind = ref.get();
        ind.showSource(new ClockSource(1, "Word Clock", true, new ClockKind.WordClock()), 48_000);
        ind.onLockEvent(new ClockLockEvent(MOCK_DEVICE, 2 /* inactive */, false));
        runOnFxAndWait(() -> { /* flush */ });
        assertThat(pauseCount.get()).isEqualTo(0);
        assertThat(ind.isLocked()).isTrue();
    }

    @Test
    void bindSubscribesToBackendClockLockPublisher() throws Exception {
        // End-to-end wiring: a MockAudioBackend simulates a lock-loss
        // event and the indicator (subscribed via bind) reacts.
        AtomicBoolean paused = new AtomicBoolean();
        AtomicReference<ClockStatusIndicator> ref = new AtomicReference<>();
        runOnFxAndWait(() -> ref.set(new ClockStatusIndicator(NotificationManager.noop(),
                () -> paused.set(true))));
        ClockStatusIndicator ind = ref.get();
        ind.showSource(new ClockSource(1, "Word Clock", true, new ClockKind.WordClock()), 48_000);

        try (MockAudioBackend backend = new MockAudioBackend()) {
            backend.setClockSources(List.of(
                    new ClockSource(1, "Word Clock", true, new ClockKind.WordClock())));
            ind.bind(backend.clockLockEvents());
            backend.simulateClockLock(MOCK_DEVICE, 1, false);
            // The submission publisher delivers asynchronously, so allow
            // a short window for the event to propagate.
            for (int i = 0; i < 50 && !paused.get(); i++) {
                Thread.sleep(20);
            }
            assertThat(paused.get()).isTrue();
        }
    }

    private static void runOnFxAndWait(Runnable action) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw new RuntimeException("FX action failed", err.get());
        }
    }
}
