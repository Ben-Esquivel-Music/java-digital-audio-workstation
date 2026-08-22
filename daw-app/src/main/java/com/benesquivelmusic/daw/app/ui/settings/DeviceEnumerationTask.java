package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.concurrent.DawScope;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;

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
     * Cancels discovery and waits for its coordinator to finish. This is for
     * background engine-reconfigure workers only; callers must never invoke it
     * on the JavaFX application thread.
     */
    public void cancelAndAwait() throws InterruptedException {
        generation.incrementAndGet();
        Thread worker = cancelActive();
        if (worker != null && worker != Thread.currentThread()) {
            worker.join();
        }
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
