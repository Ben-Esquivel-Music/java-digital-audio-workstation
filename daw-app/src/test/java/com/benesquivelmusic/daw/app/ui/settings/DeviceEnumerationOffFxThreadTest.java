package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Test
    void anUnprobedAsioDriverIsOfferedInTheOutputChoicesWithoutAFabricatedCount()
            throws Exception {
        // Story 316 review (R4): AsioBackend.listDevices() cannot know a
        // driver's channel counts without loading it, so it reports
        // AudioDeviceInfo.unprobed(...) — the exact shape built here. While
        // that meant "0 channels", supportsOutput() was false and this task
        // filtered every ASIO driver out of both menus, leaving the user with
        // nothing but the blank default to select and persist.
        UnprobedDeviceController controller = new UnprobedDeviceController();
        AtomicReference<DeviceEnumerationTask.Result> result = new AtomicReference<>();
        CountDownLatch succeeded = new CountDownLatch(1);
        try (DeviceEnumerationTask task = new DeviceEnumerationTask(controller,
                enumerated -> { result.set(enumerated); succeeded.countDown(); },
                _ -> { }, failure -> { throw new AssertionError(failure); },
                Runnable::run)) {
            task.start("ASIO");
            assertThat(succeeded.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(result.get().outputDeviceNames())
                .as("a specific ASIO driver is selectable and persistable as the output")
                .containsExactly("", "Driver A", "Driver B");
        assertThat(result.get().inputDeviceNames())
                .containsExactly("", "Driver A", "Driver B");
        assertThat(controller.enumerated)
                .as("nothing the task offered claims a channel count it does not have")
                .isNotEmpty()
                .allSatisfy(device -> assertThat(device.maxOutputChannels())
                        .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN)
                        .isNotPositive());
    }

    @Test
    void aCollidingNameIsQualifiedPerEndpointAndOnlyInTheDirectionWhereItCollides()
            throws Exception {
        // Story 316 review — the two Medium settings findings in one shape.
        // The menus used to be built with
        //   if (supportsX() && !menu.contains(device.name())) menu.add(device.name())
        // and PortAudio enumerates one Windows endpoint once per host API, so
        // that contains-guard COLLAPSED every host-API duplicate into a single
        // entry: the user could not see — let alone pick — the WASAPI
        // "Line In" once the MME one had taken the slot, and the bare name
        // they then persisted was the ambiguous string CallbackBackendAdapter
        // afterwards had to refuse.
        //
        // "Line In" is the discriminating case, and the reason the counts are
        // per direction: it is TWO devices as an input and ONE as an output
        // (the WASAPI entry offers no output channels), so it must be
        // qualified in the input menu and stay BARE in the output menu. A fix
        // that counted bare names across the whole snapshot would qualify both
        // and reset an output selection that was never ambiguous.
        FixedDeviceController controller = new FixedDeviceController(List.of(
                device(0, "Line In", "MME", 2, 2),
                device(1, "Line In", "Windows WASAPI", 2, 0),
                device(2, "Speakers", "MME", 0, 2),
                device(3, "Speakers", "Windows WASAPI", 0, 2),
                device(4, "USB Mic", "Windows WASAPI", 2, 0)));

        DeviceEnumerationTask.Result result = enumerateWith(controller);

        assertThat(result.inputDeviceNames())
                .as("both colliding input endpoints are offered, each under the "
                        + "host-API-qualified label the backend can resolve exactly; "
                        + "the unique name stays bare so its stored value survives")
                .containsExactly("", "Line In [MME]", "Line In [Windows WASAPI]", "USB Mic");
        assertThat(result.outputDeviceNames())
                .as("'Line In' is unique as an OUTPUT, so it is not qualified there — "
                        + "the collision is a per-direction fact")
                .containsExactly("", "Line In", "Speakers [MME]", "Speakers [Windows WASAPI]");
    }

    @Test
    void twoDevicesSharingBothANameAndAHostApiStillCollapseToOneEntry() throws Exception {
        // Story 316 review — the duplicate guard has to survive qualification.
        // Qualifying these two yields the SAME label, because nothing in an
        // enumeration distinguishes two identical interfaces on one driver.
        // Offering it twice would give the user two rows that mean one
        // unresolvable thing; CallbackBackendAdapter refuses that selection
        // whichever row they click.
        FixedDeviceController controller = new FixedDeviceController(List.of(
                device(0, "Duplicate", "ASIO", 2, 2),
                device(1, "Duplicate", "ASIO", 2, 2)));

        DeviceEnumerationTask.Result result = enumerateWith(controller);

        assertThat(result.inputDeviceNames()).containsExactly("", "Duplicate [ASIO]");
        assertThat(result.outputDeviceNames()).containsExactly("", "Duplicate [ASIO]");
    }

    @Test
    void aBackendWhoseNamesDoNotCollideOffersThemAllBare() throws Exception {
        // The negative control for the two tests above, and the whole reason
        // qualification is collision-gated: AsioBackend stamps "ASIO" on every
        // driver and JavaxSoundBackend stamps "Java Sound" on every mixer, so
        // a suffix there distinguishes nothing. Qualifying unconditionally
        // would rewrite every device setting already on disk to buy nothing.
        FixedDeviceController controller = new FixedDeviceController(List.of(
                device(0, "Driver A", "ASIO", 2, 2),
                device(1, "Driver B", "ASIO", 2, 2)));

        DeviceEnumerationTask.Result result = enumerateWith(controller);

        assertThat(result.inputDeviceNames()).containsExactly("", "Driver A", "Driver B");
        assertThat(result.outputDeviceNames()).containsExactly("", "Driver A", "Driver B");
    }

    /** Runs one enumeration to completion on the calling thread's dispatcher. */
    private static DeviceEnumerationTask.Result enumerateWith(FixedDeviceController controller)
            throws InterruptedException {
        AtomicReference<DeviceEnumerationTask.Result> result = new AtomicReference<>();
        CountDownLatch succeeded = new CountDownLatch(1);
        try (DeviceEnumerationTask task = new DeviceEnumerationTask(controller,
                enumerated -> { result.set(enumerated); succeeded.countDown(); },
                _ -> { }, failure -> { throw new AssertionError(failure); },
                Runnable::run)) {
            task.start(FixedDeviceController.BACKEND);
            assertThat(succeeded.await(10, TimeUnit.SECONDS)).isTrue();
        }
        return result.get();
    }

    private static AudioDeviceInfo device(int index, String name, String hostApi,
                                          int maxIn, int maxOut) {
        return new AudioDeviceInfo(index, name, hostApi, maxIn, maxOut, 48_000,
                List.of(SampleRate.fromHz(48_000)), 0, 0);
    }

    @Test
    void aWedgedEnumerationIsAbandonedAtTheBudgetAndItsLateCallbacksAreDropped()
            throws Exception {
        // Story 316 re-review, stall audit A2. cancelAndAwait() used to do a
        // bare worker.join(). Both callers hold
        // SettingsDialog.audioControllerOperationLock across it, so a single
        // enumeration parked in a driver query froze every later Apply, test
        // tone, clock-source switch and driver-panel launch for the lifetime
        // of the dialog, and there was no timeout anywhere on that path:
        // DawScope.close() joins its forks unbounded, and by the time it runs
        // the coordinator's interrupt has already been consumed by joinAll.
        //
        // The wait around cancelAndAwait is bounded here, so the regression
        // FAILS on a timeout instead of hanging the suite.
        CountDownLatch release = new CountDownLatch(1);
        SlowController controller = new SlowController(release, new AtomicBoolean(true));
        BlockingQueue<Runnable> posts = new LinkedBlockingQueue<>();
        AtomicBoolean succeeded = new AtomicBoolean();
        List<Boolean> runningChanges = Collections.synchronizedList(new ArrayList<>());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        DeviceEnumerationTask task = new DeviceEnumerationTask(controller,
                _ -> succeeded.set(true), runningChanges::add,
                failure -> { throw new AssertionError(failure); },
                posted -> { posts.add(posted); posted.run(); });
        try {
            task.start("ASIO");
            assertThat(controller.entered.await(10, TimeUnit.SECONDS))
                    .as("precondition: a fork is parked in a device query that "
                            + "ignores interruption, exactly like a wedged native "
                            + "enumeration")
                    .isTrue();
            assertThat(posts.poll(10, TimeUnit.SECONDS))
                    .as("start() dispatched its busy-state callback")
                    .isNotNull();

            Callable<Boolean> cancelWithBudget = () -> {
                task.cancelAndAwait(Duration.ofMillis(200));
                return Boolean.TRUE;
            };
            assertThat(caller.submit(cancelWithBudget).get(5, TimeUnit.SECONDS))
                    .as("the caller is released at its budget instead of waiting on "
                            + "a wedged driver for the rest of the session")
                    .isTrue();
            assertThat(release.getCount())
                    .as("and it really was ABANDONED rather than joined: the "
                            + "enumeration fork is still parked, so the coordinator "
                            + "cannot have terminated")
                    .isEqualTo(1);

            release.countDown();
            assertThat(posts.poll(10, TimeUnit.SECONDS))
                    .as("the abandoned coordinator does eventually unwind and does "
                            + "dispatch — being dropped is the generation gate's "
                            + "doing, not an absence of callbacks")
                    .isNotNull();
            assertThat(succeeded)
                    .as("no stale enumeration result reaches the dialog")
                    .isFalse();
            assertThat(runningChanges)
                    .as("nor does the abandoned coordinator's busy-state CLEAR, "
                            + "which would otherwise stop the spinner belonging to "
                            + "whichever enumeration replaced it")
                    .containsExactly(true);
        } finally {
            release.countDown();
            caller.shutdown();
            assertThat(caller.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            task.close();
        }
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

    /**
     * A controller whose backend enumerates drivers it has not loaded — the
     * production {@code AsioBackend.listDevices()} shape (story 316 review).
     */
    private static final class UnprobedDeviceController implements AudioEngineController {
        final List<AudioDeviceInfo> enumerated = List.of(
                AudioDeviceInfo.unprobed(0, "Driver A", "ASIO"),
                AudioDeviceInfo.unprobed(1, "Driver B", "ASIO"));

        @Override public String getActiveBackendName() { return "ASIO"; }

        @Override
        public List<String> getAvailableBackendNames() { return List.of("Java Sound", "ASIO"); }

        @Override public List<AudioDeviceInfo> listDevices() { return enumerated; }

        @Override public List<AudioDeviceInfo> listDevices(String backendName) {
            return enumerated;
        }

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            return BufferSizeRange.singleton(256);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            return Set.of(48_000);
        }

        @Override public double getCpuLoadPercent() { return 0; }
        @Override public void applyConfiguration(Request request) { }
        @Override public void playTestTone(String outputDeviceName) { }
    }

    /**
     * A controller that answers one fixed enumeration snapshot, so a test can
     * state the exact device list whose menu labels it is asserting (story 316
     * review).
     */
    private static final class FixedDeviceController implements AudioEngineController {
        static final String BACKEND = "PortAudio";

        private final List<AudioDeviceInfo> devices;

        private FixedDeviceController(List<AudioDeviceInfo> devices) {
            this.devices = devices;
        }

        @Override public String getActiveBackendName() { return BACKEND; }

        @Override
        public List<String> getAvailableBackendNames() { return List.of(BACKEND); }

        @Override public List<AudioDeviceInfo> listDevices() { return devices; }

        @Override public List<AudioDeviceInfo> listDevices(String backendName) {
            return devices;
        }

        @Override
        public BufferSizeRange bufferSizeRange(String backendName, String outputDeviceName) {
            return BufferSizeRange.singleton(256);
        }

        @Override
        public Set<Integer> supportedSampleRates(String backendName, String outputDeviceName) {
            return Set.of(48_000);
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
