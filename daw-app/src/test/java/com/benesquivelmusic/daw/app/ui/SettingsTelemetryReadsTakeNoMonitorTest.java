package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Settings dialog reads the active backend on the FX thread twice a
 * second; a device loss or a Reconnect holds the controller's monitor across
 * a driver call, so the read must not wait for it.
 */
class SettingsTelemetryReadsTakeNoMonitorTest {

    private final AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
    private final DefaultAudioEngineController controller =
            new DefaultAudioEngineController(engine, null, new RecordingNotifications());

    @AfterEach
    void tearDown() {
        controller.shutdown();
    }

    @Test
    void theActiveBackendIsReadWhileAnotherThreadHoldsTheControllersMonitor() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            synchronized (controller) {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<String> read = CompletableFuture.supplyAsync(controller::getActiveBackendName);

            assertThat(read.get(5, TimeUnit.SECONDS)).as("no stream is open")
                    .isEqualTo(AudioEngineController.BACKEND_NONE);
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }
    }
}
