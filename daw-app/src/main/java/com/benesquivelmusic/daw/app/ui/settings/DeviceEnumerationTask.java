package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.concurrent.DawScope;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
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

    /**
     * Builds the immutable menus, qualifying a device label with its host API
     * ONLY when that bare name actually collides in that direction (story 316
     * review).
     *
     * <p><strong>The defect.</strong> This method used to build each menu with
     * {@code if (supports… && !menu.contains(device.name())) menu.add(device.name())}.
     * PortAudio enumerates the same physical endpoint once per host API, which
     * on Windows is the norm: one interface appears as {@code "Speakers"} under
     * MME, DirectSound, WASAPI and WDM-KS, four entries with four different
     * indices, latencies and channel counts. The {@code contains} guard
     * COLLAPSED those four into one menu entry, so the user could not see &mdash;
     * let alone pick &mdash; the other three, and the bare name they then
     * persisted was the ambiguous string the backend afterwards had to resolve.</p>
     *
     * <p><strong>Why collision-only, rather than always qualifying.</strong> The
     * label this method emits is not decoration: it is the value the Settings
     * dialog PERSISTS for {@code audio.inputDevice} / {@code audio.outputDevice}
     * and hands to {@code AudioEngineController} for every open, capability
     * query and test tone. Two facts make unconditional qualification the wrong
     * trade:</p>
     * <ul>
     *   <li>A bare name is what every setting already on disk holds &mdash; this
     *       method is the only producer of those options, for both the Settings
     *       dialog and the first-run wizard &mdash; and
     *       {@code CallbackBackendAdapter}
     *       still resolves it: its matcher tries an exact
     *       {@link AudioDeviceInfo#qualifiedName()} match first and falls back
     *       to a bare {@link AudioDeviceInfo#name()} match, which resolves
     *       exactly as it always did whenever it hits a single device.</li>
     *   <li>Qualification only discriminates where the host API differs. Both
     *       non-PortAudio backends stamp one CONSTANT host API on every device
     *       they enumerate &mdash; {@code AsioBackend} reports {@code "ASIO"}
     *       for each driver, {@code JavaxSoundBackend} reports
     *       {@code "Java Sound"} for each mixer &mdash; because neither
     *       enumerates an endpoint once per API the way PortAudio does. Suffixing
     *       those unconditionally would rewrite every stored value and lengthen
     *       every menu entry while distinguishing nothing.</li>
     * </ul>
     *
     * <p><strong>The honest consequence.</strong> A stored bare name that was
     * unambiguous when it was saved can later collide, because a newly plugged
     * device enumerates under the same name on another host API. The menu then
     * offers only qualified labels for it, the stored bare value is no longer
     * among the options, and
     * {@code SettingRow.replaceChoiceOptions(options, "")} &mdash; the
     * two-argument overload {@code SettingsDialog.applyDeviceEnumerationResult}
     * calls for both device rows &mdash; replaces it with the {@code ""}
     * default. That reset is REPORTED, not silent:
     * {@code applyDeviceEnumerationResult} captures each row's previous value
     * and passes it to {@code logCapabilityFallback}, which on any change logs
     * a warning and raises {@code shell.showOperationNotice(…)}. And it is the
     * correct outcome rather than a regression, because the same collision
     * makes {@code CallbackBackendAdapter} refuse that bare selection outright
     * &mdash; falling back to the default beats silently opening whichever
     * endpoint happened to enumerate first.</p>
     *
     * <p><strong>No migration is written for stored values, deliberately.</strong>
     * There is nothing for one to do. While a name is unambiguous the menu
     * still offers the bare form, so a stored bare value is still present and
     * nothing resets; once it IS ambiguous the stored value is genuinely
     * unresolvable and no rewrite could pick a host API on the user's behalf
     * without making exactly the silent-substitution guess this whole change
     * exists to stop.</p>
     *
     * <p>The two directions are counted independently. A device that collides
     * as an input but is unique as an output is qualified only in the input
     * menu; qualifying it in both would churn a stored output name that was
     * never ambiguous. The duplicate guard survives qualification, because two
     * devices can share a name AND a host API (two identical interfaces on one
     * driver) &mdash; nothing in an enumeration can tell those apart, so they
     * collapse to one entry here and the backend refuses the selection.</p>
     */
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
        Map<String, Integer> inputNameCounts =
                countBareNames(devices, AudioDeviceInfo::supportsInput);
        Map<String, Integer> outputNameCounts =
                countBareNames(devices, AudioDeviceInfo::supportsOutput);
        for (AudioDeviceInfo device : devices) {
            if (device.supportsInput()) {
                addDeviceChoice(inputs, device, inputNameCounts);
            }
            if (device.supportsOutput()) {
                addDeviceChoice(outputs, device, outputNameCounts);
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

    /**
     * Counts how many devices supporting {@code direction} share each bare
     * {@link AudioDeviceInfo#name()} &mdash; the first of the two passes
     * {@link #toResult} describes. Counted per direction because a name can
     * collide as an input while staying unique as an output.
     *
     * @param devices   the enumeration snapshot
     * @param direction {@link AudioDeviceInfo#supportsInput()} or
     *                  {@link AudioDeviceInfo#supportsOutput()}
     * @return bare name to the number of devices answering to it in that
     *         direction
     */
    private static Map<String, Integer> countBareNames(
            List<AudioDeviceInfo> devices, Predicate<AudioDeviceInfo> direction) {
        Map<String, Integer> counts = new HashMap<>();
        for (AudioDeviceInfo device : devices) {
            if (direction.test(device)) {
                counts.merge(device.name(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Appends one device to a menu in enumeration order, host-API-qualified
     * only when its bare name collides in that direction.
     *
     * <p>The duplicate guard is applied AFTER qualification, so it now only
     * suppresses entries that are still identical once the host API is
     * attached &mdash; devices that no enumeration can distinguish.</p>
     *
     * @param menu           the menu being built, mutated in place
     * @param device         the device to offer
     * @param bareNameCounts the per-direction counts from
     *                       {@link #countBareNames(List, Predicate)}
     */
    private static void addDeviceChoice(List<String> menu,
                                        AudioDeviceInfo device,
                                        Map<String, Integer> bareNameCounts) {
        String label = bareNameCounts.getOrDefault(device.name(), 0) > 1
                ? device.qualifiedName()
                : device.name();
        if (!menu.contains(label)) {
            menu.add(label);
        }
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
