package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Device discovery is virtual-thread work with an explicit FX dispatch edge. */
@ExtendWith(JavaFxToolkitExtension.class)
class DeviceEnumerationOffFxThreadTest {

    @Test
    void slowEnumerationDoesNotBlockConstructionAndResultCrossesDispatcher() throws Exception {
        CountDownLatch releaseEnumeration = new CountDownLatch(1);
        AtomicBoolean enumeratedOnFx = new AtomicBoolean(true);
        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();
        AtomicReference<DeviceEnumerationTask.Result> result = new AtomicReference<>();
        SlowController controller = new SlowController(releaseEnumeration, enumeratedOnFx);

        AtomicReference<DeviceEnumerationTask> taskReference = new AtomicReference<>();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        CountDownLatch startReturnedOnFx = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                DeviceEnumerationTask created = new DeviceEnumerationTask(controller,
                        result::set, _ -> { }, failure -> { throw new AssertionError(failure); },
                        fxPosts::add);
                created.start("ASIO");
                taskReference.set(created);
            } catch (Throwable failure) {
                startFailure.set(failure);
            } finally {
                startReturnedOnFx.countDown();
            }
        });
        assertThat(startReturnedOnFx.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(startFailure.get()).isNull();
        DeviceEnumerationTask task = taskReference.get();
        assertThat(task).isNotNull();

        // The progress callback proves start posted through the dispatcher.
        runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        releaseEnumeration.countDown();
        while (result.get() == null) {
            runPostedOnFx(fxPosts.poll(10, TimeUnit.SECONDS));
        }

        assertThat(enumeratedOnFx.get()).isFalse();
        assertThat(controller.enumerationThread.get()).isNotNull();
        assertThat(controller.enumerationThread.get().isVirtual()).isTrue();
        assertThat(result.get().backendNames()).containsExactly("Java Sound", "ASIO");
        assertThat(result.get().inputDeviceNames()).containsExactly("", "USB Interface");
        assertThat(result.get().outputDeviceNames()).containsExactly("", "USB Interface");
        assertThat(result.get().sampleRates())
                .containsExactly(44_100.0, 48_000.0, 50_000.0, 88_200.0,
                        96_000.0, 176_400.0, 192_000.0);
        assertThat(result.get().supportedSampleRates()).containsExactly(48_000.0, 50_000.0);
        assertThat(result.get().bufferSizes()).containsExactly(192);
        onFx(() -> { task.close(); return null; });
    }

    @Test
    void closeInterruptsInFlightEnumerationWithoutJoiningTheFxThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean enumeratedOnFx = new AtomicBoolean(true);
        SlowController controller = new SlowController(release, enumeratedOnFx);
        BlockingQueue<Runnable> fxPosts = new LinkedBlockingQueue<>();
        DeviceEnumerationTask task = onFx(() -> {
            DeviceEnumerationTask created = new DeviceEnumerationTask(controller,
                    _ -> { }, _ -> { }, _ -> { }, fxPosts::add);
            created.start("ASIO");
            return created;
        });
        assertThat(controller.entered.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch closeReturnedOnFx = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try {
            Platform.runLater(() -> {
                try {
                    task.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeReturnedOnFx.countDown();
                }
            });
            assertThat(closeReturnedOnFx.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closeFailure.get()).isNull();
            assertThat(controller.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            // The fake deliberately ignores cancellation until the test releases it.
            // If close() ever joins the worker, this finally block prevents a hung suite.
            release.countDown();
        }
    }

    @Test
    void blankRequestEnumeratesTheProvisionedBackendNotTheHonestActiveOne() throws Exception {
        // Story 316 review: getActiveBackendName() is the OPEN stream's
        // backend and answers BACKEND_NONE whenever the transport is
        // stopped — which is exactly when the Settings dialog enumerates.
        // A blank request must therefore resolve to the PROVISIONED backend
        // (the one the next open will try) for every per-backend query, or
        // the dialog would enumerate devices, buffer sizes, rates and
        // clocks for "None".
        ProvisionedController controller = new ProvisionedController();
        AtomicReference<DeviceEnumerationTask.Result> result = new AtomicReference<>();
        CountDownLatch succeeded = new CountDownLatch(1);
        try (DeviceEnumerationTask task = new DeviceEnumerationTask(controller,
                enumerated -> { result.set(enumerated); succeeded.countDown(); },
                _ -> { }, failure -> { throw new AssertionError(failure); },
                Runnable::run)) {
            task.start("");
            assertThat(succeeded.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(controller.queriedBackends)
                .as("every per-backend query resolved the blank request to the "
                        + "provisioned backend")
                .isNotEmpty()
                .containsOnly(ProvisionedController.PROVISIONED);
        assertThat(controller.queriedBackends)
                .doesNotContain(AudioEngineController.BACKEND_NONE);
        assertThat(result.get().outputDeviceNames()).containsExactly("", "Provisioned Out");
    }

    private static void runPostedOnFx(Runnable posted) throws Exception {
        assertThat(posted).as("a callback was dispatched").isNotNull();
        onFx(() -> { posted.run(); return null; });
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                value.set(callable.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() instanceof Error e) throw e;
        if (failure.get() instanceof Exception e) throw e;
        return value.get();
    }

    /**
     * A controller whose transport is stopped: nothing is active, but a
     * backend is provisioned. Records the backend name of every per-backend
     * query so the test can prove which of the two the task resolved a blank
     * request to (story 316 review).
     */
    private static final class ProvisionedController implements AudioEngineController {
        static final String PROVISIONED = "Mock";
        final List<String> queriedBackends = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public String getActiveBackendName() { return BACKEND_NONE; }

        @Override public String getProvisionedBackendName() { return PROVISIONED; }

        @Override
        public List<String> getAvailableBackendNames() {
            return List.of("Java Sound", PROVISIONED);
        }

        @Override public List<AudioDeviceInfo> listDevices() { return listDevices(PROVISIONED); }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            queriedBackends.add(backendName);
            if (!PROVISIONED.equals(backendName)) {
                return List.of();
            }
            return List.of(new AudioDeviceInfo(0, "Provisioned Out", PROVISIONED, 0, 2,
                    48_000, List.of(SampleRate.fromHz(48_000)), 0, 2));
        }

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            queriedBackends.add(backendName);
            return BufferSizeRange.singleton(128);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            queriedBackends.add(backendName);
            return Set.of(48_000);
        }

        @Override
        public List<com.benesquivelmusic.daw.sdk.audio.ClockSource> clockSources(
                String backendName, String outputDeviceName) {
            queriedBackends.add(backendName);
            return List.of();
        }

        @Override public double getCpuLoadPercent() { return 0; }
        @Override public void applyConfiguration(Request request) { }
        @Override public void playTestTone(String outputDeviceName) { }
    }

    private static final class SlowController implements AudioEngineController {
        private final CountDownLatch release;
        private final AtomicBoolean enumeratedOnFx;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final AtomicReference<Thread> enumerationThread = new AtomicReference<>();

        private SlowController(CountDownLatch release, AtomicBoolean enumeratedOnFx) {
            this.release = release;
            this.enumeratedOnFx = enumeratedOnFx;
        }

        @Override public String getActiveBackendName() { return "ASIO"; }

        @Override
        public List<String> getAvailableBackendNames() {
            enumeratedOnFx.set(Platform.isFxApplicationThread());
            return List.of("Java Sound", "ASIO");
        }

        @Override public List<AudioDeviceInfo> listDevices() { return listDevices("ASIO"); }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            enumerationThread.set(Thread.currentThread());
            enumeratedOnFx.set(Platform.isFxApplicationThread());
            entered.countDown();
            boolean restoreInterrupt = false;
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException cancelled) {
                    interrupted.countDown();
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return List.of(new AudioDeviceInfo(0, "USB Interface", "ASIO", 8, 8,
                    48_000, List.of(SampleRate.fromHz(48_000)), 2, 2));
        }

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            return BufferSizeRange.singleton(192);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            return Set.of(48_000, 50_000);
        }

        @Override public double getCpuLoadPercent() { return 0; }
        @Override public void applyConfiguration(Request request) { }
        @Override public void playTestTone(String outputDeviceName) { }
    }
}
