package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 337, rulings E12 and E13 — Restart Audio Engine
 * ({@code MainController.restartAudioEngine}): on its own virtual thread,
 * {@code daw-audio-engine-restart}, it applies through the controller the
 * configuration the persisted settings ask for — the Settings ▸ Apply path,
 * with startup's blank-backend rule — and then runs what follows a successful
 * restart, the clearing of the engine fault. A declined record guard keeps the
 * take: nothing more runs. A failed restart runs nothing more either: the
 * controller has already shown its own failure. Nor does a restart that
 * returned normally but left closed a stream that was open when it began,
 * whether the controller swallowed a failed reopen or the record guard's Stop
 * closed the stream (ruling E62); with no stream open at the start, a normal
 * return is a success.
 *
 * <p>The static helper is driven with a controller double; the window's
 * instance method passes its own settings, controller and the drain's
 * clearing, which {@code FailureSpineWiringSourceTest} pins.</p>
 */
class MainControllerRestartAudioEngineTest {

    private static final Duration GUARD = Duration.ofSeconds(20);

    private final List<Preferences> nodes = new CopyOnWriteArrayList<>();

    @AfterEach
    void removeTheSettingsNodes() throws BackingStoreException {
        for (Preferences node : nodes) {
            node.removeNode();
        }
    }

    @Test
    void theRestartAppliesTheSettingsOnItsOwnVirtualThreadAndThenRunsWhatFollowsASuccess() throws Exception {
        SettingsModel settings = settings();
        settings.setAudioBackend("ASIO");
        settings.setAudioInputDevice("Studio In");
        settings.setAudioOutputDevice("Studio Out");
        settings.setSampleRate(48_000);
        settings.setBufferSize(128);
        settings.setBitDepth(24);
        settings.setWorkerPoolSize(4);
        RestartController controller = new RestartController(request -> { });
        AtomicReference<Thread> restartedOn = new AtomicReference<>();
        AtomicInteger restarted = new AtomicInteger();

        Thread worker = MainController.restartAudioEngine(settings, controller, () -> true, () -> {
            restartedOn.set(Thread.currentThread());
            restarted.incrementAndGet();
        });

        assertThat(worker.join(GUARD)).as("the restart ended").isTrue();
        assertThat(worker.getName()).isEqualTo("daw-audio-engine-restart");
        assertThat(worker.isVirtual()).as("never the FX thread or the render drive (ruling E12)").isTrue();
        assertThat(controller.requests).as("the Settings ▸ Apply path, with the persisted configuration")
                .containsExactly(new AudioEngineController.Request("ASIO", "Studio In", "Studio Out",
                        SampleRate.HZ_48000, 128, 24, 4));
        assertThat(controller.appliedOn).hasValue(worker);
        assertThat(restarted).as("what follows a successful restart ran once").hasValue(1);
        assertThat(restartedOn).hasValue(worker);
    }

    @Test
    void aBlankPersistedBackendRestartsTheProvisionedOne() throws Exception {
        SettingsModel settings = settings();
        settings.setAudioBackend("");
        RestartController controller = new RestartController(request -> { });
        controller.provisioned = "PortAudio";

        Thread worker = MainController.restartAudioEngine(settings, controller, () -> false, () -> { });

        assertThat(worker.join(GUARD)).isTrue();
        assertThat(controller.requests).singleElement()
                .satisfies(request -> assertThat(request.backendName()).isEqualTo("PortAudio"));
        assertThat(MainController.audioConfigurationRequest(settings, controller))
                .as("the one request builder startup and the restart share").isEqualTo(controller.requests.getFirst());
    }

    @Test
    void aDeclinedRecordGuardKeepsTheTakeAndRunsNothingMore() throws Exception {
        RestartController controller = new RestartController(request -> {
            throw new AudioConfigurationDeclinedException();
        });
        AtomicInteger restarted = new AtomicInteger();

        Thread worker = MainController.restartAudioEngine(settings(), controller, () -> true,
                restarted::incrementAndGet);

        assertThat(worker.join(GUARD)).isTrue();
        assertThat(controller.requests).as("the controller was asked").hasSize(1);
        assertThat(restarted).as("no restart: the fault stays latched").hasValue(0);
    }

    @Test
    void aRestartWhoseReopenFailedLeavesTheFaultLatched() throws Exception {
        RestartController controller = new RestartController(request -> { });
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger restarted = new AtomicInteger();

        // Open when the restart began; closed when it returned: the controller swallowed a failed reopen.
        Thread worker = MainController.restartAudioEngine(settings(), controller,
                () -> asked.getAndIncrement() == 0, restarted::incrementAndGet);

        assertThat(worker.join(GUARD)).isTrue();
        assertThat(controller.requests).as("the restart was applied and returned normally").hasSize(1);
        assertThat(asked).as("the stream was asked about before and after the restart").hasValue(2);
        assertThat(restarted).as("not a successful restart, so the fault stays latched (ruling E62)").hasValue(0);
    }

    @Test
    void aRestartWithNoStreamOpenClearsTheFault() throws Exception {
        RestartController controller = new RestartController(request -> { });
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger restarted = new AtomicInteger();

        Thread worker = MainController.restartAudioEngine(settings(), controller, () -> {
            asked.incrementAndGet();
            return false;
        }, restarted::incrementAndGet);

        assertThat(worker.join(GUARD)).isTrue();
        assertThat(asked).as("asked before the restart; with nothing open, nothing had to reopen").hasValue(1);
        assertThat(restarted).as("nothing was open to reopen: a successful restart (ruling E62)").hasValue(1);
    }

    @Test
    void aFailedRestartRunsNothingMoreAndIsLoggedOnce() throws Exception {
        IllegalStateException reopenFailed = new IllegalStateException("the device did not reopen");
        RestartController controller = new RestartController(request -> {
            throw reopenFailed;
        });
        AtomicInteger restarted = new AtomicInteger();
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getThrown() == reopenFailed) {
                    logged.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger windowLog = Logger.getLogger(MainController.class.getName());
        windowLog.addHandler(capture);
        try {
            Thread worker = MainController.restartAudioEngine(settings(), controller, () -> true,
                    restarted::incrementAndGet);

            assertThat(worker.join(GUARD)).isTrue();
        } finally {
            windowLog.removeHandler(capture);
        }
        assertThat(restarted).as("no restart happened, so the fault stays latched").hasValue(0);
        assertThat(logged).as("the controller showed its own failure; the restart logs it once more, never"
                + " silently").singleElement().satisfies(record -> {
                    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                    assertThat(record.getMessage()).isEqualTo("Restart Audio Engine failed");
                });
    }

    private SettingsModel settings() {
        Preferences node = Preferences.userRoot().node("restartAudioEngine_" + System.nanoTime());
        nodes.add(node);
        return new SettingsModel(node);
    }

    /** An audio engine controller that records each request and answers it with {@code onApply}. */
    private static final class RestartController implements AudioEngineController {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> appliedOn = new AtomicReference<>();
        final Consumer<Request> onApply;
        volatile String provisioned = "Java Sound";

        RestartController(Consumer<Request> onApply) {
            this.onApply = onApply;
        }

        @Override
        public void applyConfiguration(Request request) {
            requests.add(request);
            appliedOn.set(Thread.currentThread());
            onApply.accept(request);
        }

        @Override
        public String getActiveBackendName() {
            return BACKEND_NONE;
        }

        @Override
        public String getProvisionedBackendName() {
            return provisioned;
        }

        @Override
        public List<String> getAvailableBackendNames() {
            return List.of(provisioned);
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return List.of();
        }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            return List.of();
        }

        @Override
        public double getCpuLoadPercent() {
            return -1.0;
        }

        @Override
        public void playTestTone(String outputDeviceName) {
        }
    }
}
