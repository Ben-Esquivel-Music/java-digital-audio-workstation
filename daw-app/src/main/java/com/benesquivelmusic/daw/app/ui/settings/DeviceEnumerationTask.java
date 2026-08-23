package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.concurrent.DawScope;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Lifecycle-owned audio-device discovery for the Settings Audio category.
 *
 * <p>A coordinator virtual thread owns a shutdown-on-failure {@link DawScope}
 * (the repository's stable, non-preview implementation of JEP 505 semantics).
 * Backend and device queries fan out onto child virtual threads (JEP 444), and
 * only the immutable result is posted to JavaFX through {@link FxDispatcher}.
 * Re-starting or closing cancels the current scope without joining on the FX
 * thread; the coordinator performs the blocking scope cleanup itself.</p>
 */
public final class DeviceEnumerationTask implements AutoCloseable {

    private static final Logger LOG =
            Logger.getLogger(DeviceEnumerationTask.class.getName());

    /**
     * How long {@link #cancelAndAwait()} waits for an ALREADY-CANCELLED
     * coordinator to unwind before abandoning it.
     *
     * <p>This is deliberately not "how long a device enumeration takes". By
     * the time the join starts, the scope has been cancelled and the
     * coordinator interrupted, so the only thing that can legitimately delay
     * its exit is a forked query parked in a call that does not observe
     * interruption &mdash; and the longest such call this codebase permits is
     * one ASIO control-thread downcall, budgeted at fifteen seconds
     * ({@code AsioControlThread.DEFAULT_BUDGET}, which is package-private in
     * {@code daw-sdk} and so cannot be referenced here). Matching that budget
     * means a cancelled enumeration whose last query is merely slow still
     * unwinds cleanly and is joined, while a genuinely wedged driver can no
     * longer hold the caller &mdash; and the Settings dialog's
     * {@code audioControllerOperationLock}, which both callers hold across
     * this call &mdash; for the rest of the session.</p>
     */
    static final Duration CANCEL_JOIN_BUDGET = Duration.ofSeconds(15);

    /** Immutable choice lists ready for the three generic setting rows. */
    public record Result(List<String> backendNames,
                         List<String> inputDeviceNames,
                         List<String> outputDeviceNames,
                         List<Double> sampleRates,
                         List<Double> supportedSampleRates,
                         List<Integer> bufferSizes,
                         List<ClockSource> clockSources,
                         double preferredSampleRate,
                         int preferredBufferSize) {
        public Result {
            backendNames = List.copyOf(backendNames);
            inputDeviceNames = List.copyOf(inputDeviceNames);
            outputDeviceNames = List.copyOf(outputDeviceNames);
            sampleRates = List.copyOf(sampleRates);
            supportedSampleRates = List.copyOf(supportedSampleRates);
            bufferSizes = List.copyOf(bufferSizes);
            clockSources = List.copyOf(clockSources);
        }
    }

    private final AudioEngineController controller;
    private final Consumer<Result> onSucceeded;
    private final Consumer<Boolean> onRunningChanged;
    private final Consumer<Throwable> onFailed;
    private final Consumer<Runnable> fxDispatcher;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<DawScope> activeScope = new AtomicReference<>();
    private final AtomicReference<Thread> coordinator = new AtomicReference<>();

    private volatile boolean closed;

    /** Creates a production task whose callbacks are always posted to JavaFX. */
    public DeviceEnumerationTask(AudioEngineController controller,
                                 Consumer<Result> onSucceeded,
                                 Consumer<Boolean> onRunningChanged,
                                 Consumer<Throwable> onFailed) {
        this(controller, onSucceeded, onRunningChanged, onFailed,
                FxDispatcher::runOnFx);
    }

    /**
     * Test seam for observing the dispatch boundary without starting JavaFX.
     */
    DeviceEnumerationTask(AudioEngineController controller,
                          Consumer<Result> onSucceeded,
                          Consumer<Boolean> onRunningChanged,
                          Consumer<Throwable> onFailed,
                          Consumer<Runnable> fxDispatcher) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.onSucceeded = Objects.requireNonNull(onSucceeded, "onSucceeded must not be null");
        this.onRunningChanged = Objects.requireNonNull(
                onRunningChanged, "onRunningChanged must not be null");
        this.onFailed = Objects.requireNonNull(onFailed, "onFailed must not be null");
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher must not be null");
    }

    /**
     * Starts or replaces discovery for {@code backendName}. Returns before any
     * controller query begins, so slow native enumeration cannot block JavaFX.
     */
    public void start(String backendName) {
        start(backendName, "");
    }

    /** Starts discovery for a backend and its selected output endpoint. */
    public void start(String backendName, String outputDeviceName) {
        if (closed) {
            return;
        }
        long requestGeneration = generation.incrementAndGet();
        cancelActive();
        dispatchIfCurrent(requestGeneration, () -> onRunningChanged.accept(true));

        String requestedBackend = backendName == null ? "" : backendName;
        String requestedOutput = outputDeviceName == null ? "" : outputDeviceName;
        Thread worker = Thread.ofVirtual()
                .name("settings-audio-device-enumeration")
                .unstarted(() -> enumerate(
                        requestGeneration, requestedBackend, requestedOutput));
        coordinator.set(worker);
        worker.start();
    }

    private void enumerate(long requestGeneration,
                           String requestedBackend,
                           String requestedOutput) {
        DawScope scope = DawScope.openShutdownOnFailure("settings-audio-devices");
        try (scope) {
            activeScope.set(scope);
            var backends = scope.fork("backends", controller::getAvailableBackendNames);
            // Story 316 review: a blank request enumerates the PROVISIONED
            // backend — the one the next open will try — not the honest
            // "active" query, which answers BACKEND_NONE whenever the
            // transport is stopped and would enumerate nothing.
            String effectiveBackend = requestedBackend.isBlank()
                    ? controller.getProvisionedBackendName() : requestedBackend;
            var devices = scope.fork("devices", () -> controller.listDevices(effectiveBackend));
            var bufferRange = scope.fork("buffer-range",
                    () -> controller.bufferSizeRange(effectiveBackend, requestedOutput));
            var supportedRates = scope.fork("sample-rates",
                    () -> controller.supportedSampleRates(effectiveBackend, requestedOutput));
            var clockSources = scope.fork("clock-sources",
                    () -> controller.clockSources(effectiveBackend, requestedOutput));
            scope.joinAll();
            Result result = toResult(backends.resultNow(), devices.resultNow(),
                    bufferRange.resultNow(), supportedRates.resultNow(),
                    clockSources.resultNow());
            dispatchIfCurrent(requestGeneration, () -> {
                onSucceeded.accept(result);
                onRunningChanged.accept(false);
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dispatchIfCurrent(requestGeneration, () -> onRunningChanged.accept(false));
        } catch (ExecutionException | CancellationException failure) {
            dispatchIfCurrent(requestGeneration, () -> {
                onFailed.accept(rootCause(failure));
                onRunningChanged.accept(false);
            });
        } catch (RuntimeException failure) {
            dispatchIfCurrent(requestGeneration, () -> {
                onFailed.accept(failure);
                onRunningChanged.accept(false);
            });
        } finally {
            activeScope.compareAndSet(scope, null);
            coordinator.compareAndSet(Thread.currentThread(), null);
        }
    }

    private static Result toResult(List<String> backends,
                                   List<AudioDeviceInfo> devices,
                                   BufferSizeRange reportedRange,
                                   Set<Integer> reportedRates,
                                   List<ClockSource> reportedClockSources) {
        LinkedHashSet<String> backendNames = new LinkedHashSet<>(backends);
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        // Empty string is the persisted canonical value for automatic/default.
        inputs.add("");
        outputs.add("");
        for (AudioDeviceInfo device : devices) {
            if (device.supportsInput() && !inputs.contains(device.name())) {
                inputs.add(device.name());
            }
            if (device.supportsOutput() && !outputs.contains(device.name())) {
                outputs.add(device.name());
            }
        }
        BufferSizeRange range = reportedRange == null
                ? BufferSizeRange.DEFAULT_RANGE : reportedRange;
        Set<Integer> supportedRates = reportedRates == null || reportedRates.isEmpty()
                ? Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
                : Set.copyOf(reportedRates);
        List<Double> supportedRateValues = supportedRates.stream()
                .filter(rate -> rate != null && rate > 0)
                .sorted()
                .map(Integer::doubleValue)
                .toList();
        LinkedHashSet<Double> sampleRateMenu = new LinkedHashSet<>(
                List.of(44_100.0, 48_000.0, 88_200.0, 96_000.0, 176_400.0, 192_000.0));
        sampleRateMenu.addAll(supportedRateValues);
        List<Double> rates = sampleRateMenu.stream().sorted().toList();
        double preferredRate = preferredSampleRate(supportedRates);
        return new Result(List.copyOf(backendNames), inputs, outputs,
                rates, supportedRateValues, range.expandedSizes(),
                reportedClockSources == null ? List.of() : reportedClockSources,
                preferredRate, range.preferred());
    }

    private static double preferredSampleRate(Set<Integer> supportedRates) {
        if (supportedRates.contains(48_000)) {
            return 48_000.0;
        }
        if (supportedRates.contains(44_100)) {
            return 44_100.0;
        }
        return supportedRates.stream().filter(rate -> rate != null && rate > 0)
                .min(Integer::compareTo).orElse(48_000).doubleValue();
    }

    private void dispatchIfCurrent(long requestGeneration, Runnable callback) {
        fxDispatcher.accept(() -> {
            if (!closed && generation.get() == requestGeneration) {
                callback.run();
            }
        });
    }

    private static Throwable rootCause(Throwable failure) {
        return failure.getCause() == null ? failure : failure.getCause();
    }

    /** Cancels in-flight work without joining on the caller (normally FX) thread. */
    private Thread cancelActive() {
        DawScope scope = activeScope.get();
        if (scope != null) {
            scope.cancel();
        }
        Thread worker = coordinator.getAndSet(null);
        if (worker != null) {
            worker.interrupt();
        }
        return worker;
    }

    /**
     * Cancels discovery and waits &mdash; for a BOUNDED time &mdash; for its
     * coordinator to finish. This is for background engine-reconfigure
     * workers only; callers must never invoke it on the JavaFX application
     * thread.
     *
     * <p>The wait is bounded at {@link #CANCEL_JOIN_BUDGET} (story 316
     * re-review). The unbounded {@code join()} this replaced was a stall
     * hazard rather than a safety property: both callers &mdash;
     * {@code SettingsDialog}'s serialized audio-utility worker and its audio
     * reconfigure worker &mdash; hold {@code audioControllerOperationLock}
     * across this call, so one enumeration wedged in a driver query froze
     * every subsequent Apply, test tone, clock-source switch and
     * driver-panel launch for the lifetime of the dialog, with no timeout
     * anywhere on the path to break it.</p>
     *
     * <p>Abandoning the coordinator on timeout is the CORRECT outcome here,
     * not a lesser evil. It is a virtual thread parked in I/O (JEP 444), so
     * it costs a stack rather than an OS thread; and it cannot publish a
     * stale result, because {@link #generation} is incremented BEFORE the
     * cancel and every callback this class makes goes through
     * {@link #dispatchIfCurrent(long, Runnable)}, which drops any dispatch
     * whose generation is no longer current. An abandoned coordinator's
     * {@code onSucceeded}, {@code onFailed} and {@code onRunningChanged}
     * therefore never reach the dialog. Its {@code finally} clears
     * {@link #activeScope} and {@link #coordinator} with compare-and-set, so
     * it cannot clobber a newer enumeration on its way out either.</p>
     *
     * <p>Note that the two hazards compose: a coordinator can only be
     * interruptible if the controller monitor it needs is free, which is why
     * the driver control panel no longer runs under that monitor
     * ({@code DefaultAudioEngineController#openControlPanel()}).</p>
     */
    public void cancelAndAwait() throws InterruptedException {
        cancelAndAwait(CANCEL_JOIN_BUDGET);
    }

    /**
     * {@link #cancelAndAwait()} with an explicit budget, so a test can pin
     * the boundedness without waiting {@link #CANCEL_JOIN_BUDGET}.
     *
     * @param budget how long to wait for the cancelled coordinator to exit
     * @throws InterruptedException if the caller is interrupted while waiting
     */
    void cancelAndAwait(Duration budget) throws InterruptedException {
        generation.incrementAndGet();
        Thread worker = cancelActive();
        if (worker == null || worker == Thread.currentThread()) {
            return;
        }
        if (worker.join(budget)) {
            return;
        }
        LOG.warning(() -> "Audio device enumeration did not unwind within "
                + budget.toMillis() + " ms of being cancelled; abandoning "
                + worker + ". Its result is already stale (generation "
                + generation.get() + ") and is dropped rather than reaching"
                + " the Settings dialog.");
    }

    /**
     * Invalidates queued callbacks and interrupts the owned scope. This method
     * is deliberately non-blocking; the coordinator closes/joins its scope.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        generation.incrementAndGet();
        cancelActive();
        fxDispatcher.accept(() -> onRunningChanged.accept(false));
    }
}
