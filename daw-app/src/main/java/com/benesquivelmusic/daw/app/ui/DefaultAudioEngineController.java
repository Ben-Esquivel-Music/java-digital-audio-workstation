package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioEngineSettings;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.CallbackBackendAdapter;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.audio.performance.XrunDetector;
import com.benesquivelmusic.daw.core.audio.portaudio.PortAudioBackend;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;
import com.benesquivelmusic.daw.sdk.audio.JavaxSoundBackend;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.WasapiBackend;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link AudioEngineController} that drives a real {@link AudioEngine}.
 *
 * <p>Owns the engine's {@link StreamingProvision} — the requested SDK
 * {@link AudioBackend} plus its explicit fallback ladder (story 316) — and
 * coordinates reconfiguration: stop the current stream, mutate the engine's
 * format, rebuild the provision when the requested backend or device
 * changed, then restart the stream through the engine's one open seam.
 * Each engine re-configuration reuses the same {@code AudioEngine} instance
 * so that all sub-controllers (transport, mixer, editors) keep their
 * existing engine references intact. The controller owns every provision
 * backend instance's lifecycle; the engine only opens and closes streams on
 * them.</p>
 */
final class DefaultAudioEngineController implements AudioEngineController {

    private static final Logger LOG = Logger.getLogger(DefaultAudioEngineController.class.getName());

    private final AudioEngine audioEngine;
    private final TestTonePlayer tonePlayer;
    private final Runnable postReconfigureCallback;
    private final NotificationManager notifications;
    private final IncompleteTakeStore incompleteTakeStore;
    /**
     * Maps a UI-facing backend name (e.g. {@code "ASIO"}, {@code "WASAPI"},
     * {@code "CoreAudio"}, {@code "JACK"}, {@code "Mock"}) to a fresh SDK
     * {@link AudioBackend} instance. Story 130: the selector is the single
     * place that knows the {@link AudioBackend} sealed permits, so this
     * controller no longer hand-rolls a {@code switch} that fell through
     * to {@code null} for every platform backend.
     */
    private final AudioBackendSelector backendSelector;
    private final SubmissionPublisher<EngineState> engineStatePublisher = new SubmissionPublisher<>();
    private final SubmissionPublisher<AudioDeviceEvent> deviceEventPublisher =
            new SubmissionPublisher<>();
    private volatile XrunDetector xrunDetector;
    private volatile EngineState engineState = EngineState.STOPPED;
    /** Backend whose deviceEvents() we are currently subscribed to. */
    private final AtomicReference<AudioBackend> boundBackend = new AtomicReference<>();
    /**
     * The endpoint whose hot-plug events this controller is SUBSCRIBED to —
     * the provisioned first rung while the engine is stopped, then the rung
     * that actually won once an open completes, because the engine's
     * stream-open seam re-points it on every open.
     *
     * <p>It is an identity used to answer "is this incoming
     * {@link AudioDeviceEvent} about my endpoint?", and deliberately NOT a
     * claim that a stream is open: nothing clears it on close, so after an
     * ordinary Stop it still names the last endpoint watched.
     * {@link AudioEngine#isStreamOpen()} is the only authority for whether a
     * stream is open, so a handler whose behaviour presumes one —
     * {@link #onDeviceRemoved(DeviceId)} — asks the engine in addition to
     * matching on this value (story 316 review).</p>
     */
    private final AtomicReference<DeviceId> watchedDevice = new AtomicReference<>();
    /** Lifecycle-owned subscriber for the currently bound backend generation. */
    private final AtomicReference<DeviceEventSubscriber> deviceEventSubscriber =
            new AtomicReference<>();
    private final AtomicBoolean controllerClosed = new AtomicBoolean();

    /**
     * The requested endpoint (backend + device names) the currently
     * installed {@link StreamingProvision} was built from, or {@code null}
     * when no provision has been applied through
     * {@link #applyConfiguration(Request)} yet. Repeated reconfigures that
     * only change buffer size / sample rate leave the provision (and its
     * backend instances) untouched so they neither re-create backends nor
     * re-emit fallback notifications; any endpoint change rebuilds it so
     * the requested rung's {@link DeviceId} always names the configured
     * device (story 316 — the device is honoured on every open).
     */
    private ProvisionEndpoint appliedProvisionEndpoint;

    /** The (backend, input device, output device) triple a provision serves. */
    private record ProvisionEndpoint(
            String backendName, String inputDeviceName, String outputDeviceName) {
    }

    /**
     * Per-device calibration overrides (in sample frames) accepted by
     * the user via {@code LatencyCalibrationDialog} — story 217. Keyed
     * by the device-key encoding used by {@link AudioSettingsStore.Settings#deviceKey(DeviceId)}.
     * The override for the currently {@linkplain #watchedDevice watched}
     * device — calibration is accepted while the transport is STOPPED, so
     * it cannot be keyed on an open stream — is folded into
     * {@link #reportedLatency()} so the recording pipeline shifts
     * captured clips by the calibrated value rather than by the
     * driver's (mistrusted) report. Persisted to
     * {@code ~/.daw/audio-settings.json} through
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore}'s
     * {@code latencyOverrideFramesByDeviceKey} map.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer>
            latencyOverridesByDeviceKey = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Single-thread worker that runs the
     * {@link AudioDeviceEvent.FormatChangeRequested} reopen flow off the
     * device-event thread (story 218). Coalescing of rapid-fire reset
     * requests (the user spinning a buffer-size dropdown produces many
     * events) is implemented by scheduling onto this executor with a
     * 250&nbsp;ms debounce delay; if a fresh request arrives before the
     * scheduled task runs, the previous task is cancelled and replaced.
     *
     * <p>This is <b>not</b> the audio callback thread — it is a
     * dedicated daemon worker named {@code "daw-format-change-worker"}.
     * Format-change reopens involve closing and reopening the audio
     * backend, which is far too heavy for the RT thread.</p>
     */
    private final ScheduledExecutorService formatChangeWorker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "daw-format-change-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * The production drain seam for the {@link PerformanceMonitor}'s
     * deferred RT events (story 314 review follow-up). The RT audio
     * callback records underruns and warning-threshold transitions as
     * lock-free pending state; logging and warning-listener delivery are
     * forbidden on the audio callback, so this dedicated single-thread
     * daemon worker ({@code "daw-monitor-event-drain"}) calls
     * {@link PerformanceMonitor#publishPendingEvents()} every
     * {@value #MONITOR_EVENT_DRAIN_MILLIS}&nbsp;ms for the controller's
     * whole lifetime. That keeps underrun log lines and warning listeners
     * delivering even when every dialog is closed — the drain must never
     * depend on a UI poll such as {@link #getCpuLoadPercent()}.
     *
     * <p>Each tick looks the monitor up via
     * {@link AudioEngine#getPerformanceMonitor()} — never caches it —
     * because {@code EngineBinder.refreshPerformanceMonitor()} replaces
     * the instance after format changes. Shut down in {@link #shutdown()}.</p>
     */
    private final ScheduledExecutorService monitorEventDrain =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "daw-monitor-event-drain");
                t.setDaemon(true);
                return t;
            });

    /** Fixed delay between {@link #monitorEventDrain} ticks. */
    private static final long MONITOR_EVENT_DRAIN_MILLIS = 250L;

    /**
     * Coalescing window: when another
     * {@link AudioDeviceEvent.FormatChangeRequested} arrives within this
     * delay, the previous pending reopen is cancelled and rescheduled
     * (story 218: "the user spinning a buffer-size dropdown produces
     * multiple events").
     */
    private static final long FORMAT_CHANGE_COALESCE_MILLIS = 250L;

    /**
     * Why a format change is refused while the render pump's exit cannot be
     * confirmed — shared by BOTH paths that mutate the engine format
     * ({@link #applyConfiguration(Request)} and the device-event reopen), so
     * the two wordings cannot drift apart (story 316 re-review).
     *
     * <p>This replaced the fixed {@code FORMAT_CHANGE_DRAIN_MILLIS = 200L}
     * sleep the reopen path used to take between its stop and its reopen. A
     * fixed sleep is not quiescence: it proved nothing about whether the
     * render pump had left {@code processBlock}, and it paid its full cost
     * even when the pump had already gone. {@link #stopAndConfirmPumpQuiesced()}
     * asks the engine instead, and answers as soon as the pump is really
     * out.</p>
     */
    private static final String PUMP_STILL_RENDERING =
            "it did not exit in time and may still be inside processBlock, so the engine"
                    + " format must not be changed under it; retry once the pump unblocks";

    /** Holds the currently-pending coalesced reopen task, if any. */
    private final AtomicReference<ScheduledFuture<?>> pendingFormatChange =
            new AtomicReference<>();

    /**
     * Latest pending format-change request — replaced when a new one
     * arrives during the coalescing window so the eventual reopen uses
     * the freshest proposed format / reason.
     */
    private final AtomicReference<AudioDeviceEvent.FormatChangeRequested> latestFormatChange =
            new AtomicReference<>();

    /**
     * Serializes native driver control-panel launches against EACH OTHER,
     * without serializing them against the rest of the controller (story 316
     * re-review).
     *
     * <p>This replaced a {@code synchronized (DefaultAudioEngineController.this)}
     * block wrapped around the panel action. That monitor supplied two
     * properties, only one of which is worth its cost:</p>
     *
     * <ul>
     *   <li><strong>Serialization of the panel action against itself</strong>
     *       &mdash; two concurrent {@code ASIOControlPanel()} downcalls into
     *       one driver is undefined native behaviour. Kept, here.</li>
     *   <li><strong>Serialization of the panel against every other controller
     *       operation</strong> &mdash; dropped. The action blocks for as long
     *       as the user leaves a MODAL driver dialog open, which is unbounded
     *       by construction: the SDK exempts precisely this one downcall from
     *       {@code AsioControlThread}'s fifteen-second budget because "a user
     *       reading a driver dialog is not a wedged driver". Held across the
     *       controller monitor, that unbounded wait froze every
     *       {@code synchronized} method behind the user's attention span
     *       &mdash; device-arrival and device-removal handling, the settings
     *       dialog's enumeration queries, {@link #applyConfiguration(Request)},
     *       {@link #shutdown()}, and {@link #openControlPanel()} itself, which
     *       {@code SettingsDialog.updateAudioUtilityDisabledState()} calls on
     *       the JavaFX application thread. An open driver panel therefore
     *       froze the entire UI until the user closed it.</li>
     * </ul>
     *
     * <p>A {@link ReentrantLock} rather than a second monitor &mdash; and
     * CARRIER PINNING is not the reason, though an earlier revision of this
     * javadoc said it was. This reactor targets JDK&nbsp;26
     * ({@code java.version} 26 in the root POM), and JEP&nbsp;491 removed
     * monitor pinning in JDK&nbsp;24, so a virtual thread blocking on a
     * monitor no longer pins its carrier. The reasons that survive are (a)
     * this is a DIFFERENT lock from the controller monitor the FX thread
     * contends for, which is the entire point of the split, and (b)
     * try/finally makes the critical section's extent explicit at the one
     * call site that takes it, so the lock order below can be read straight
     * off {@link #runControlPanel(AudioBackend, Runnable)}.</p>
     *
     * <h2>Lock order</h2>
     * <p>THREE locks are in play on the panel path since the engine gained
     * its own half of the control-panel guard, and
     * {@link #runControlPanel(AudioBackend, Runnable)} takes the other two
     * as SIBLINGS under this one rather than nesting them:</p>
     * <pre>
     * controlPanelLock &rarr; controller monitor          (brief: one field store)
     * controlPanelLock &rarr; AudioEngine.lifecycleLock   (possibly LONG)
     * </pre>
     * <p>{@link #registerOpenControlPanel(AudioBackend)} and
     * {@link #releaseOpenControlPanel(AudioBackend)} are still
     * {@code synchronized}, but they now do nothing beyond storing
     * {@link #controlPanelBackend}; the engine calls
     * {@link AudioEngine#beginControlPanelSession(AudioBackend)} /
     * {@link AudioEngine#endControlPanelSession(AudioBackend)} are made by
     * {@code runControlPanel} itself, with the monitor already released. The
     * nesting [controller monitor &rarr; lifecycleLock] is GONE from this
     * path, and its removal is the point rather than a tidy-up:
     * {@code endControlPanelSession} does not merely clear a field, it
     * DRAINS the close its registration deferred &mdash; the engine's
     * bounded pump join followed by {@code close()} on the retained handle,
     * a seam that engine's own blocking budget prices as UNBOUNDED for
     * PortAudio and as minutes for a slow ASIO driver. Made from inside the
     * monitor it would queue every {@code synchronized} method behind a
     * native teardown, which is this same stall merely moved from "while the
     * user reads the dialog" to "while the driver tears the stream down".
     * The chain is acyclic because no thread ever walks any part of it
     * backwards:</p>
     * <ul>
     *   <li>This lock is the OUTERMOST link. It is acquired only by
     *       {@code runControlPanel}, from a thread that holds neither the
     *       controller monitor nor the engine's lifecycle lock &mdash;
     *       {@link #openControlPanel()} resolves the backend and its action
     *       under the monitor and RETURNS, so the action runs with the
     *       monitor released. Nothing that holds the monitor, and nothing
     *       inside the engine, ever waits for this lock.</li>
     *   <li>Both edges out of it end at a LEAF, so neither can close a
     *       cycle: the monitor is held here only across a field store, and
     *       the lifecycle lock is taken here with no other lock of ours
     *       held. [controller monitor &rarr; lifecycleLock] still exists
     *       ELSEWHERE &mdash; {@link #applyConfiguration(Request)},
     *       {@link #installDefaultProvision()} and {@link #shutdown()} take
     *       it on every engine lifecycle call they make &mdash; and this
     *       path simply no longer contributes to it.</li>
     *   <li>The reverse edges do not exist. The engine never calls the
     *       application while holding {@code lifecycleLock} &mdash; its
     *       stream-open listener, its fallback events and its RT-clock
     *       releases are all deferred past the unlock through the engine's
     *       {@code PendingAnnouncements} &mdash; so no thread ever holds the
     *       lifecycle lock and then waits for this controller's monitor or
     *       for this lock. Nor does anything holding the monitor wait for
     *       this lock: the only acquisition is in {@code runControlPanel},
     *       reached from the action {@link #openControlPanel()} handed back
     *       after releasing the monitor.</li>
     * </ul>
     */
    private final ReentrantLock controlPanelLock = new ReentrantLock();

    /**
     * The backend instance whose native control panel is open right now, or
     * {@code null}. Guarded by this controller's monitor; read by
     * {@link #closeProvisionBackends(StreamingProvision, StreamingProvision)},
     * which always runs under that monitor. The SAME instance is published to
     * the engine as the other half of the guard, by
     * {@link #runControlPanel(AudioBackend, Runnable)}, which establishes
     * BOTH registrations before it runs the panel action and withdraws both
     * after it returns. They are two separate stores rather than one atomic
     * act, which is all either guard needs: they protect two INDEPENDENT
     * operations (this controller's INSTANCE closes and the engine's HANDLE
     * closes), and {@link #controlPanelLock} brackets the whole register →
     * action → release sequence, so no second panel session can interleave
     * with either store.
     *
     * <p>This is the ONE property the monitor-wrapped panel action genuinely
     * provided and that had to survive its removal, and it now covers BOTH
     * kinds of close that can reach a backend whose dialog is up:</p>
     * <ul>
     *   <li><strong>This controller's own INSTANCE closes.</strong>
     *       {@link #closeProvisionBackends(StreamingProvision,
     *       StreamingProvision)} — the best-effort close of the backend
     *       instances this controller owns, on a provision swap and on
     *       shutdown alike — skips an instance registered here, because that
     *       close frees exactly the native state the dialog is running on.
     *       Registration happens under the monitor, and every path that
     *       reaches that method enters through a {@code synchronized} one
     *       ({@link #applyConfiguration(Request)},
     *       {@link #installDefaultProvision()}, {@link #shutdown()}) and
     *       holds the monitor for its whole duration, so a close either
     *       observes the registration and skips the instance, or completes
     *       strictly before the panel begins.</li>
     *   <li><strong>The ENGINE's own HANDLE closes.</strong> These run inside
     *       {@link AudioEngine}, which is now told about the registration:
     *       {@link #runControlPanel(AudioBackend, Runnable)} calls
     *       {@link AudioEngine#beginControlPanelSession(AudioBackend)} ahead
     *       of the panel action and
     *       {@link AudioEngine#endControlPanelSession(AudioBackend)} in its
     *       {@code finally}. That class calls {@code AudioBackend.close()}
     *       from SIX places; FIVE of them consult the registration and
     *       refuse or defer rather than free the handle:
     *       {@link AudioEngine#stopAudioOutput()} retains the handle (and
     *       still reports quiescence — the pump really did stop, only the
     *       close was deferred),
     *       {@link AudioEngine#setStreamingProvision(StreamingProvision)}
     *       refuses the swap, the retained-handle release ahead of a fresh
     *       open refuses the open, a failed pump start's unwind skips its
     *       close, and a failed ladder hop's unwind reports the handle
     *       unreleased — which abandons the walk rather than opening a
     *       fallback rung beside a device that rung may still hold. The
     *       SIXTH is the engine's own drain inside
     *       {@code endControlPanelSession}, and it deliberately does NOT
     *       consult the registration: it runs only after that same method
     *       has cleared it, so a check there would already answer "no panel"
     *       — dead code rather than a refusal.</li>
     * </ul>
     *
     * <p>The first FOUR of those five refusals are delays rather than leaks:
     * each leaves the engine tracking the handle in
     * {@code RELEASE_PENDING}, and {@code endControlPanelSession} DRAINS
     * exactly that — it retries the close as soon as the dialog returns. The
     * fifth is different in kind. A
     * failed ladder hop happens during an open, before any handle is tracked,
     * so what it skips is that rung's own partial acquisition; the engine
     * reports it unreleased (which is what stops the walk) and then has
     * nothing left to retry. That instance is a provision backend this
     * controller owns, so it is closed by
     * {@link #closeProvisionBackends(StreamingProvision, StreamingProvision)}
     * on the next swap or shutdown — once the dialog has returned and the
     * registration above no longer skips it.</p>
     *
     * <p>So the promise is no longer scoped to a single method: for the
     * dialog's lifetime, no close from either layer frees the native state it
     * is running on. Both reconfigure orders are covered. A swap calls
     * {@link AudioEngine#setStreamingProvision(StreamingProvision)} FIRST
     * (see {@link #installProvision(StreamingProvision)}), and its
     * {@code abandonStreamOnOutgoingBackend} now refuses the swap outright
     * when the outgoing backend's panel is open, leaving the handle tracked
     * instead of closing it; shutdown calls
     * {@link AudioEngine#stopAudioOutput()} first, which retains the tracked
     * stream's handle, and its {@code closeProvisionBackends} then skips the
     * instance.</p>
     *
     * <p>ONE window remains, and it is a PRE-registration window: a close
     * that lands strictly BEFORE this field is set still reaches a backend
     * whose panel is about to open. {@link #openControlPanel()} resolves the
     * instance from {@link AudioEngine#getBackend()} — which IS the tracked
     * open backend whenever a stream is open — under the monitor and then
     * RETURNS an action; {@link #runControlPanel(AudioBackend, Runnable)}
     * registers that instance only when the action is actually invoked,
     * which may be much later and on another thread. A swap or a shutdown
     * that completes in between closes an instance the dialog is then opened
     * on. That is not a new exposure — the monitor-wrapped version also
     * resolved its backend before waiting for the monitor — and narrowing it
     * would mean holding a lock across the launch, which is the UI freeze
     * the {@link #controlPanelLock} split exists to prevent.</p>
     *
     * <p>What DID change is the window's tail. The two halves are now
     * established by two separate acts — this field under the monitor, then
     * the engine's under its lifecycle lock, and no lock a CLOSER takes
     * spans both ({@link #controlPanelLock} does, but only the panel path
     * ever acquires it) — so between them there is an interval in which THIS
     * controller would already skip the instance while the ENGINE would
     * still close the handle. It is narrow (a monitor enter/exit and one uncontended lock
     * acquisition, no I/O) and it is not a new KIND of exposure: an
     * engine-owned close never took this controller's monitor, so it could
     * always land between the two stores. Both are written down rather than
     * papered over.</p>
     */
    private AudioBackend controlPanelBackend;

    DefaultAudioEngineController(AudioEngine audioEngine, Runnable postReconfigureCallback) {
        this(audioEngine, postReconfigureCallback,
                NotificationManager.noop(),
                new IncompleteTakeStore(Paths.get(System.getProperty("java.io.tmpdir"))));
    }

    DefaultAudioEngineController(AudioEngine audioEngine,
                                 Runnable postReconfigureCallback,
                                 NotificationManager notifications,
                                 IncompleteTakeStore incompleteTakeStore) {
        this(audioEngine, postReconfigureCallback, notifications, incompleteTakeStore,
                new AudioBackendSelector());
    }

    /**
     * Test-friendly constructor that injects an {@link AudioBackendSelector}
     * — typically one whose factory map maps {@code "Mock"} (or any other
     * permitted backend name) to {@link com.benesquivelmusic.daw.sdk.audio.MockAudioBackend}.
     */
    DefaultAudioEngineController(AudioEngine audioEngine,
                                 Runnable postReconfigureCallback,
                                 NotificationManager notifications,
                                 IncompleteTakeStore incompleteTakeStore,
                                 AudioBackendSelector backendSelector) {
        this.audioEngine = Objects.requireNonNull(audioEngine, "audioEngine must not be null");
        this.tonePlayer = new TestTonePlayer();
        this.postReconfigureCallback = postReconfigureCallback;
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.incompleteTakeStore = Objects.requireNonNull(
                incompleteTakeStore, "incompleteTakeStore must not be null");
        this.backendSelector = Objects.requireNonNull(
                backendSelector, "backendSelector must not be null");
        this.xrunDetector = createDetectorFor(audioEngine.getFormat());
        // Story 316 review: follow the OPEN stream, not the requested rung.
        // A plain Play calls AudioEngine.startAudioOutput() directly from
        // TransportController — this controller is not in that loop at all —
        // so binding device events only where WE drive the open left the
        // subscription (and watchedDevice with it) pointed at a backend/device
        // the engine had already fallen back from: hot-unplug handling, channel
        // queries and latency overrides then all targeted the wrong endpoint.
        // The engine's stream-open seam fires on EVERY completed open with
        // the winning rung, which is the only place the winner is known.
        // An explicit lambda, not a method reference: bindBackendDeviceEvents
        // is overloaded (2-arg and 3-arg) and a method ref would silently
        // re-target if either overload's shape changed.
        audioEngine.setStreamOpenListener(
                (backend, device) -> bindBackendDeviceEvents(backend, device));
        monitorEventDrain.scheduleWithFixedDelay(this::drainMonitorEvents,
                MONITOR_EVENT_DRAIN_MILLIS, MONITOR_EVENT_DRAIN_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * One {@link #monitorEventDrain} tick — see the field javadoc. The
     * monitor is looked up fresh per tick because
     * {@code EngineBinder.refreshPerformanceMonitor()} swaps the instance
     * after format changes; caching would leave this drain pumping a
     * dead monitor.
     */
    private void drainMonitorEvents() {
        try {
            PerformanceMonitor monitor = audioEngine.getPerformanceMonitor();
            if (monitor != null) {
                monitor.publishPendingEvents();
            }
        } catch (RuntimeException e) {
            // An escaping exception would silently cancel the fixed-delay
            // schedule and kill the drain for the rest of the session —
            // log and keep ticking.
            LOG.log(Level.WARNING, "Performance-monitor event drain tick failed", e);
        }
    }

    @Override
    public synchronized String getActiveBackendName() {
        // Story 316 review — honest reporting (book §3.2 / §5.2): "active"
        // is the backend whose stream is OPEN, full stop. Open means
        // RUNNING or PAUSED: a paused stream renders nothing but still owns
        // the device handle, so it is named — "None" would claim the device
        // is free while the engine still holds it. This used to fall back to
        // the installed provision's first rung when no stream was open at
        // all, so the Settings utility panel kept naming a backend as active
        // after Stop and after an open failure — exactly the
        // requested-vs-active lie the story exists to remove. The "which
        // backend should be queried or configured" question that fallback
        // answered is a different fact and lives in
        // getProvisionedBackendName().
        return audioEngine.openStreamBackendName().orElse(BACKEND_NONE);
    }

    @Override
    public synchronized String getProvisionedBackendName() {
        // Story 316 review — the configured / effective backend: the OPEN
        // stream's backend when one is open; otherwise the installed
        // provision's FIRST RUNG — the backend the next open will actually
        // try, which can differ from requestedBackendName() when the
        // requested backend failed the availability/streaming gate;
        // otherwise "None". Startup configuration, device enumeration and
        // the Settings dialog's live-endpoint resolution read this one:
        // they need a backend to query while the transport is stopped,
        // when getActiveBackendName() honestly answers "None".
        Optional<String> open = audioEngine.openStreamBackendName();
        if (open.isPresent()) {
            return open.get();
        }
        StreamingProvision provision = audioEngine.getStreamingProvision();
        return provision != null ? provision.firstRung().backend().name() : BACKEND_NONE;
    }

    @Override
    public synchronized List<String> getAvailableBackendNames() {
        // Union of the adapted legacy PortAudio backend (story 316: it now
        // streams behind the SDK interface via CallbackBackendAdapter), the
        // selector's available-and-streamable SDK names, and the Java Sound
        // floor. The selector's supportsStreaming() gate keeps backends
        // whose streaming path is not available in this build — sink()
        // discards (WASAPI / CoreAudio / JACK today), or an ASIO whose
        // native streaming shim is missing or incomplete (story 316 review)
        // — out of the offered list. Story 130: the dialog must be able to *select*
        // every backend the controller can *provision*.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        // Story 316 review: the adapter here is an enumeration-only probe and
        // must not outlive this query. AudioBackend is AutoCloseable, and this
        // call site used to drop the instance unclosed, leaking a native
        // PortAudio handle every time the backend list was refreshed. The one
        // production caller is DeviceEnumerationTask, which both the Settings
        // dialog and the FirstRunWizard run off the FX thread on every device
        // refresh — so the leak recurred per refresh, not once per session.
        // try-with-resources releases it on BOTH branches, matching
        // tryCreatePortAudioAdapter()'s closeQuietly and withSdkBackend()'s
        // try (probe).
        try (AudioBackend probe = new CallbackBackendAdapter(new PortAudioBackend())) {
            if (probe.isAvailable()) {
                names.add("PortAudio");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "PortAudio unavailable", e);
        }
        names.addAll(backendSelector.availableBackendNames());
        names.add("Java Sound");
        return List.copyOf(names);
    }

    @Override
    public synchronized List<AudioDeviceInfo> listDevices() {
        // Open-aware: AudioEngine.getBackend() answers with the OPEN
        // stream's backend when one is open, else the provision's FIRST
        // RUNG — which is not necessarily the requested one, since a
        // gate-rejected request starts the ladder on a fallback. That is
        // exactly getProvisionedBackendName()'s rule, so the device list
        // always matches THAT reported backend — never the ACTIVE one,
        // which is "None" whenever no stream is open.
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return List.of();
        }
        try {
            return backend.listDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to list audio devices", e);
            return List.of();
        }
    }

    @Override
    public synchronized List<AudioDeviceInfo> listDevices(String backendName) {
        if (backendName == null || backendName.isBlank() || BACKEND_NONE.equals(backendName)) {
            return List.of();
        }
        AudioBackend provisioned = audioEngine.getBackend();
        if (provisioned != null && backendName.equals(provisioned.name())) {
            return listDevices();
        }
        // Fresh throwaway probe, enumerated and closed. Story 316: every
        // name — including the adapted "PortAudio" and the SDK "Java
        // Sound" — resolves to an SDK AudioBackend instance now.
        try (AudioBackend probe = createStreamingBackendByName(backendName, "")) {
            if (probe == null) {
                return List.of();
            }
            return probe.listDevices();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to enumerate " + backendName + " devices", e);
            return List.of();
        }
    }

    @Override
    public double getCpuLoadPercent() {
        PerformanceMonitor monitor = audioEngine.getPerformanceMonitor();
        if (monitor == null) {
            return -1.0;
        }
        // Deliberately side-effect-free: the monitor's deferred RT events
        // (underrun log lines, warning transitions, warning listeners) are
        // drained by the always-active monitorEventDrain worker, not by
        // this read. A dialog poll must never be load-bearing for event
        // delivery.
        return monitor.getCpuLoadPercent();
    }

    @Override
    public synchronized BufferSizeRange bufferSizeRange(
            String backendName, String outputDeviceName) {
        return withSdkBackend(backendName, backend -> backend.bufferSizeRange(
                deviceId(backendName, outputDeviceName)), BufferSizeRange.DEFAULT_RANGE);
    }

    @Override
    public synchronized Set<Integer> supportedSampleRates(
            String backendName, String outputDeviceName) {
        return withSdkBackend(backendName, backend -> backend.supportedSampleRates(
                deviceId(backendName, outputDeviceName)),
                Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000));
    }

    @Override
    public synchronized List<ClockSource> clockSources(
            String backendName, String outputDeviceName) {
        return withSdkBackend(backendName, backend -> backend.clockSources(
                deviceId(backendName, outputDeviceName)), List.of());
    }

    @Override
    public synchronized void selectClockSource(
            String backendName, String outputDeviceName, int sourceId) {
        withSdkBackend(backendName, backend -> {
            backend.selectClockSource(deviceId(backendName, outputDeviceName), sourceId);
            return null;
        }, null);
    }

    @Override
    public int getActiveThreadCount() {
        return audioEngine.getActiveThreadCount();
    }

    @Override
    public int getWorkerPoolSize() {
        return audioEngine.getWorkerPoolSize();
    }

    @Override
    public synchronized void applyConfiguration(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        LOG.info("Applying audio configuration: " + request);

        try {
            boolean wasOpen = audioEngine.isStreamOpen();
            // Story 316 review — isStreamOpen() is true for RUNNING *or*
            // PAUSED, so "was open" alone does not describe the lifecycle
            // state this reconfigure is about to destroy. The pause fact has
            // to be read HERE, before stopAndConfirmPumpQuiesced() clears
            // it: a user who paused transport and then changed buffer size,
            // device or backend used to get the stream back RUNNING, which
            // restarts the render pump AND the RT clock underneath a
            // transport they deliberately left paused.
            boolean wasPaused = audioEngine.isStreamPaused();
            // Story 316 re-review — this verdict is LOAD-BEARING and used to
            // be discarded. The predicate itself lives in
            // stopAndConfirmPumpQuiesced() because BOTH paths that mutate the
            // engine format have to consult it: this one and
            // performFormatChangeReopen(), which mutates the same field on
            // the same engine from the device-event worker. Guarding one
            // caller of a shared predicate and leaving the sibling open would
            // have left the hazard fully reachable.
            //
            // Refusing WHOLE is the honest outcome here: the rest of this
            // method — worker-pool size, xrun detector, provision rebuild,
            // restart — all presumes a stopped, quiesced engine. The throw
            // lands in the catch below, so the user is notified, the
            // controller ends in STOPPED, and the finally still runs
            // postReconfigureCallback: the settings dialog re-enables and the
            // reconfigure is retryable once the pump unblocks.
            if (!stopAndConfirmPumpQuiesced()) {
                throw new IllegalStateException(
                        "Cannot apply audio configuration while the render pump is still"
                                + " active: " + PUMP_STILL_RENDERING);
            }

            AudioFormat previous = audioEngine.getFormat();
            AudioFormat updated = new AudioFormat(
                    request.sampleRate().getHz(),
                    previous.channels(),
                    request.bitDepth(),
                    request.bufferFrames());
            audioEngine.setFormat(updated);

            // Story 125: apply the new worker-pool size to the engine. The
            // pool is locked at start() so the swap must happen between
            // stop() above and the start{,AudioOutput}() calls below.
            AudioEngineSettings previousSettings = audioEngine.getEngineSettings();
            if (previousSettings.workerPoolSize() != request.workerPoolSize()) {
                audioEngine.setEngineSettings(
                        previousSettings.withWorkerPoolSize(request.workerPoolSize()));
            }

            // Buffer size or sample rate may have changed — rebuild the
            // xrun detector so its deadline matches the new format, and
            // reset the counter per the issue's reset-on-reconfigure rule.
            XrunDetector previousDetector = this.xrunDetector;
            this.xrunDetector = createDetectorFor(updated);
            if (previousDetector != null) {
                previousDetector.close();
            }

            // Story 316: the engine streams through ONE provision slot. Rebuild
            // the provision only when the requested endpoint (backend + device
            // names) actually changed, so repeated buffer-size / sample-rate
            // reconfigures neither re-create backend instances nor re-emit
            // fallback notifications. Any endpoint change rebuilds it — the
            // requested rung's DeviceId is how the configured device is
            // honoured on EVERY subsequent open (book §3.2, never index 0).
            ProvisionEndpoint endpoint = new ProvisionEndpoint(
                    request.backendName(), request.inputDeviceName(), request.outputDeviceName());
            StreamingProvision provision = audioEngine.getStreamingProvision();
            if (provision == null || !endpoint.equals(appliedProvisionEndpoint)) {
                provision = buildStreamingProvision(request);
                installProvision(provision);
                appliedProvisionEndpoint = endpoint;
            }

            // Device hot-plug watch follows the requested (first) rung —
            // the backend the user chose is the one whose device events
            // matter. Binding it HERE, before any open, is what lets the
            // configured endpoint's hot-plug be noticed at all while the
            // transport is stopped; the alternative is a controller deaf to
            // its own device until the first Play. Any open that follows
            // re-points the watch itself via the engine's stream-open seam
            // (story 316 review), including a bare Play straight from
            // TransportController.
            //
            // WATCHING is explicitly not a claim that the device is ACTIVE:
            // no stream is open here, so a removal now no longer forces
            // DEVICE_LOST — onDeviceRemoved() gates on
            // AudioEngine.isStreamOpen(), the only authority for that fact.
            bindBackendDeviceEvents(
                    provision.firstRung().backend(), provision.firstRung().device());

            if (wasOpen) {
                // Story 316 review — the restart is guarded because it can now
                // genuinely fail: Java Sound stopped degrading to a silent
                // no-output stream, so an exhausted fallback ladder propagates
                // (openLadder rethrows the FIRST rung's failure), and
                // requireQuiescedPump() throws when a previous render pump has
                // not exited. Deliberately NOT rethrown: the configuration
                // itself WAS applied (format, worker pool, provision, device
                // watch) — only the restart failed, so the user keeps the new
                // settings and simply re-arms transport. Mirrors
                // runFormatChangeReopen(): notify, then always end up in
                // STOPPED so the user can re-arm transport even after a
                // failed reopen.
                try {
                    audioEngine.startAudioOutput();
                    // No re-point needed here any more (story 316 review):
                    // startAudioOutput() fired the engine's stream-open seam
                    // synchronously, and this controller's listener already
                    // rebound the whole device-event subscription — not just
                    // the watched DeviceId, which is all the line that used
                    // to sit here ever did — to the rung that actually WON. That
                    // rebind now happens for EVERY open, including a bare
                    // Play issued straight from TransportController, which
                    // never reaches this method at all.
                    //
                    // Story 316 review — restore the PAUSED lifecycle state
                    // the reconfigure destroyed. startAudioOutput() always
                    // comes back RUNNING, so without this a stream the user
                    // had paused resumes rendering behind a paused
                    // transport. It runs BEFORE the terminal
                    // setEngineState(...) below so that observers gating on
                    // that state already see the restored pause (side
                    // effects precede the terminal state).
                    //
                    // The reported EngineState deliberately does NOT change:
                    // a transport pause never moved it off RUNNING in the
                    // first place, so RUNNING plus a re-paused stream is
                    // exactly the pre-reconfigure pair. STOPPED means
                    // "intentionally closed", which a PAUSED stream is not —
                    // onDeviceArrived's STOPPED is a different convention,
                    // for a device that just came back and needs manual
                    // re-arming.
                    if (wasPaused) {
                        try {
                            audioEngine.pauseAudioOutput();
                        } catch (RuntimeException pauseFailure) {
                            // Falls through on purpose: a failed re-pause
                            // leaves the stream genuinely open and
                            // rendering, which is exactly what the RUNNING
                            // state below then honestly reports.
                            LOG.log(Level.WARNING,
                                    "Audio output could not be re-paused after reconfigure;"
                                            + " the stream is open and rendering",
                                    pauseFailure);
                        }
                    }
                    setEngineState(EngineState.RUNNING);
                } catch (RuntimeException openFailure) {
                    LOG.log(Level.SEVERE,
                            "Audio output could not be restarted after reconfigure",
                            openFailure);
                    setEngineState(EngineState.STOPPED);
                    try {
                        notifications.notify("Audio output could not be started: "
                                + openFailure.getMessage());
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "NotificationManager rejected message", e);
                    }
                }
            } else {
                audioEngine.start();
                setEngineState(EngineState.STOPPED);
            }
        } catch (RuntimeException e) {
            // Story 316 review: anything else escaping this method used to
            // strand the flow — neither the terminal state nor the
            // post-reconfigure callback ran, so the settings dialog stayed
            // disabled forever. Land in STOPPED, tell the user, then rethrow
            // so callers still see the failure.
            setEngineState(EngineState.STOPPED);
            LOG.log(Level.SEVERE, "Audio configuration could not be applied", e);
            try {
                notifications.notify("Audio configuration could not be applied: "
                        + e.getMessage());
            } catch (RuntimeException notifyFailure) {
                LOG.log(Level.WARNING, "NotificationManager rejected message", notifyFailure);
            }
            throw e;
        } finally {
            // In the finally block so the dialog re-enables on EVERY path,
            // including the rethrown failure above.
            runPostReconfigureCallback();
        }
    }

    @Override
    public synchronized void playTestTone(String outputDeviceName) {
        tonePlayer.play(outputDeviceName == null ? "" : outputDeviceName);
    }

    @Override
    public synchronized void applyMixPrecision(MixPrecision precision) {
        Objects.requireNonNull(precision, "precision must not be null");
        Mixer mixer = audioEngine.getMixer();
        if (mixer != null) {
            mixer.setMixPrecision(precision);
            LOG.info("Mix precision set to " + precision);
        }
    }

    @Override
    public synchronized void applySrcQuality(
            com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier tier) {
        Objects.requireNonNull(tier, "tier must not be null");
        var previous = audioEngine.getSrcQualityTier();
        audioEngine.setSrcQualityTier(tier);
        // Invalidate cached conversions when the tier changes — they
        // were rendered with the previous filter kernel and would
        // otherwise leak across a quality change.
        if (previous != tier) {
            audioEngine.getSampleRateConversionCache().invalidateAll();
        }
        LOG.info("SRC quality set to " + tier);
    }

    @Override
    public Flow.Publisher<XrunEvent> xrunEvents() {
        return xrunDetector.xrunEvents();
    }

    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return deviceEventPublisher;
    }

    @Override
    public EngineState engineState() {
        return engineState;
    }

    @Override
    public Flow.Publisher<EngineState> engineStateEvents() {
        return engineStatePublisher;
    }

    @Override
    public com.benesquivelmusic.daw.sdk.audio.RoundTripLatency reportedLatency() {
        com.benesquivelmusic.daw.sdk.audio.RoundTripLatency driver = driverReportedLatency();
        Integer override = activeDeviceOverride();
        if (override == null) {
            return driver;
        }
        // Fold override into a single-component RoundTripLatency so the
        // total equals the calibrated value while keeping the record
        // shape recording pipelines already understand.
        return new com.benesquivelmusic.daw.sdk.audio.RoundTripLatency(override, 0, 0);
    }

    @Override
    public com.benesquivelmusic.daw.sdk.audio.RoundTripLatency driverReportedLatency() {
        // Open-aware (story 316): the OPEN stream's backend when one is
        // open, else the provision's requested rung. Backends without a
        // native latency query — ASIO until its query story lands — report
        // RoundTripLatency.UNKNOWN honestly instead of a number from a
        // stream that is not actually playing.
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN;
        }
        return backend.reportedLatency();
    }

    @Override
    public Optional<Integer> latencyOverrideFrames() {
        return Optional.ofNullable(activeDeviceOverride());
    }

    @Override
    public void setLatencyOverrideFrames(Optional<Integer> frames) {
        Objects.requireNonNull(frames, "frames must not be null");
        if (frames.isPresent() && frames.get() < 0) {
            throw new IllegalArgumentException(
                    "override frames must be >= 0: " + frames.get());
        }
        DeviceId device = watchedDevice.get();
        if (device == null) {
            // Nothing watched yet — no endpoint to key the override by;
            // ignore silently.
            return;
        }
        String key = com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore
                .Settings.deviceKey(device);
        if (frames.isPresent()) {
            latencyOverridesByDeviceKey.put(key, frames.get());
        } else {
            latencyOverridesByDeviceKey.remove(key);
        }
        // The recording pipeline is owned by the transport controller and
        // queries reportedLatency() at recording-start, so the next
        // recording will pick up the new value automatically. No live
        // mutation is required from this controller.
    }

    /**
     * Returns the calibration override for the {@link #watchedDevice}, or
     * {@code null} if no override is set for that endpoint.
     *
     * <p>Keyed by the WATCHED endpoint on purpose, not by an open stream's
     * device: latency calibration is something the user performs and accepts
     * while the transport is STOPPED (story 217's
     * {@code LatencyCalibrationDialog}), and the value is consumed later, at
     * recording start, through {@link #reportedLatency()}. Keying it on a
     * fact that is only true while a stream is open would make every
     * calibration made from a stopped transport unaddressable.</p>
     */
    private Integer activeDeviceOverride() {
        DeviceId device = watchedDevice.get();
        if (device == null) {
            return null;
        }
        String key = com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore
                .Settings.deviceKey(device);
        return latencyOverridesByDeviceKey.get(key);
    }

    /**
     * Subscribes to {@code backend.deviceEvents()} and records
     * {@code watchedDevice} as the endpoint those events are matched
     * against, so the controller can transition to
     * {@link EngineState#DEVICE_LOST} when the device of an OPEN stream
     * disappears, persist the in-flight recording take, notify the user, and
     * automatically reopen the stream when the matching device returns.
     *
     * <p>Binding is a WATCH, not a claim that {@code watchedDevice} is
     * active: this runs pre-open from {@link #applyConfiguration(Request)}
     * while the engine is stopped, so {@link #onDeviceRemoved(DeviceId)}
     * additionally consults {@link AudioEngine#isStreamOpen()} before
     * declaring the device lost (story 316 review).</p>
     *
     * <p>Calling this method again replaces any previously bound
     * backend; the previous subscription is cancelled.</p>
     */
    @Override
    public synchronized void bindBackendDeviceEvents(
            AudioBackend backend, DeviceId watchedDevice) {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(watchedDevice, "watchedDevice must not be null");
        bindBackendDeviceEvents(backend, watchedDevice, backend.deviceEvents());
    }

    synchronized void bindBackendDeviceEvents(
            AudioBackend backend,
            DeviceId watchedDevice,
            Flow.Publisher<AudioDeviceEvent> events) {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(watchedDevice, "watchedDevice must not be null");
        Objects.requireNonNull(events, "events must not be null");
        DeviceEventSubscriber next = new DeviceEventSubscriber();
        DeviceEventSubscriber previous = deviceEventSubscriber.getAndSet(next);
        if (previous != null) {
            previous.close();
        }
        boundBackend.set(backend);
        this.watchedDevice.set(watchedDevice);
        if (controllerClosed.get()) {
            clearBoundBackendDeviceEvents();
            return;
        }
        events.subscribe(next);
    }

    private void clearBoundBackendDeviceEvents() {
        DeviceEventSubscriber previous = deviceEventSubscriber.getAndSet(null);
        if (previous != null) {
            previous.close();
        }
        boundBackend.set(null);
        watchedDevice.set(null);
    }

    /**
     * Routes a sample-rate change to the SDK backend identified by
     * {@code backendName} (story 220). Resolves the backend through
     * the {@link AudioBackendSelector} — the same lookup used by
     * {@link #listDevices(String)} — so the call reaches the
     * {@link com.benesquivelmusic.daw.sdk.audio.AsioBackend}'s
     * {@code ASIOSetSampleRate} bridge. Backends that do not support
     * sample-rate selection (legacy {@code "PortAudio"} /
     * {@code "Java Sound"}, or any backend whose default
     * {@link AudioBackend#setSampleRate(DeviceId, double)} throws
     * {@link UnsupportedOperationException}) are treated as a no-op so
     * the dialog flow still falls through to the model-only update.
     *
     * <p>{@link com.benesquivelmusic.daw.sdk.audio.AudioBackendException}
     * is rethrown so the dialog can suppress the model update and
     * surface a notification, matching the issue's contract.</p>
     */
    @Override
    public synchronized void setSampleRate(
            String backendName, String outputDeviceName, double rate) {
        if (backendName == null || backendName.isBlank() || BACKEND_NONE.equals(backendName)) {
            return;
        }
        // Skip the legacy backend names — those backends negotiate the
        // rate at stream open and have no separate setter; the dialog's
        // reconfigure step will reopen them.
        if ("PortAudio".equals(backendName) || "Java Sound".equals(backendName)) {
            return;
        }
        try (AudioBackend sdk = createSdkBackendByName(backendName)) {
            if (sdk == null) {
                return;
            }
            // Story 316 review: route through deviceId() like every sibling
            // method. A blank outputDeviceName means "the backend's default
            // device" — not an error — and hand-rolling
            // new DeviceId(backendName, "") tripped DeviceId's compact
            // constructor ("name must not be blank"), whose
            // IllegalArgumentException is not caught below and aborted the
            // whole sample-rate flow. deviceId() also normalizes the
            // "WASAPI (Exclusive)" display name to the backend's real name.
            DeviceId deviceId = deviceId(backendName, outputDeviceName);
            sdk.setSampleRate(deviceId, rate);
        } catch (UnsupportedOperationException ignored) {
            // Backend does not support live sample-rate selection;
            // fall through and let the dialog persist the new rate.
        }
    }

    /**
     * Returns the device this controller is currently watching, or empty when
     * none is bound. It does NOT imply a stream is open on that device — the
     * watch is bound pre-open and never cleared on close, so this still names
     * the last watched endpoint after an ordinary Stop. Ask
     * {@link AudioEngine#isStreamOpen()} for the open fact; use this answer
     * for "which endpoint do I query channels / capabilities on", which must
     * keep working while the transport is stopped.
     */
    Optional<DeviceId> getWatchedDevice() {
        return Optional.ofNullable(watchedDevice.get());
    }

    /**
     * Captures one block of recorded input into the
     * {@link IncompleteTakeStore} so it can be flushed to disk if the
     * device disappears mid-take. Production wiring (the recording
     * subsystem) calls this from the audio callback.
     */
    void captureRecordingFrames(float[][] inputBuffer, int numFrames) {
        incompleteTakeStore.appendCapturedFrames(inputBuffer, numFrames);
    }

    /** Visible for tests. */
    IncompleteTakeStore getIncompleteTakeStore() {
        return incompleteTakeStore;
    }

    private void setEngineState(EngineState newState) {
        EngineState previous = this.engineState;
        if (previous == newState) {
            return;
        }
        this.engineState = newState;
        // Use offer() instead of submit() to avoid blocking under
        // backpressure — a slow UI subscriber must not stall the
        // device-event thread.
        if (!engineStatePublisher.isClosed()) {
            engineStatePublisher.offer(newState, (subscriber, dropped) -> false);
        }
        LOG.info("Engine state " + previous + " -> " + newState);
    }

    /**
     * Handles a hot-unplug. Declares {@link EngineState#DEVICE_LOST} only for
     * the device of an OPEN stream: BOTH the endpoint identity
     * ({@link #watchedDevice}) and {@link AudioEngine#isStreamOpen()} must
     * hold.
     *
     * <p>Story 316 review — the open check is not redundant with the identity
     * check. The watch is bound pre-open by
     * {@link #applyConfiguration(Request)} and no seam clears it on close, so
     * the watched endpoint is also the merely-configured device while the
     * transport is stopped and the last-used device after an ordinary Stop.
     * Without this gate, unplugging an idle interface drove a stopped engine
     * into {@code DEVICE_LOST} and flushed an incomplete take that no stream
     * had recorded — and because {@link #onDeviceArrived(DeviceId)} is gated
     * on {@code DEVICE_LOST}, re-plugging it then OPENED a stream the user
     * never asked for.</p>
     */
    private synchronized void onDeviceRemoved(DeviceId removed) {
        if (!audioEngine.isStreamOpen()) {
            // Routine hot-unplug of an endpoint we merely watch — no stream
            // is holding it, so nothing was interrupted and there is nothing
            // to recover. FINE, not WARNING: this is the ordinary case for a
            // user unplugging an idle interface.
            LOG.log(Level.FINE,
                    () -> "Audio device removed while no stream is open: " + removed);
            return;
        }
        DeviceId watched = watchedDevice.get();
        if (watched == null || !matches(watched, removed)) {
            // Some other device went away — nothing to do.
            return;
        }
        LOG.warning("Active audio device removed: " + removed);
        // Halt the render thread cleanly. Best-effort; never let an
        // exception from the device-event thread escape and crash the
        // engine — the issue requires "no exceptions on the audio thread".
        try {
            audioEngine.stopAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop audio output during disconnect", e);
        }
        try {
            audioEngine.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop engine during disconnect", e);
        }
        // Persist any in-progress recording take so the user can review
        // it after the device returns.
        try {
            incompleteTakeStore.flushIfNotEmpty(removed, audioEngine.getFormat());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to persist incomplete take", e);
        }
        setEngineState(EngineState.DEVICE_LOST);
        try {
            notifications.notify("Audio device disconnected — playback paused");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected message", e);
        }
    }

    /**
     * Reopens the stream when the device lost in
     * {@link #onDeviceRemoved(DeviceId)} comes back.
     *
     * <p>Story 316 review — this matches on {@link #watchedDevice} and
     * deliberately NOT on a fact cleared when the stream closes. The review
     * that added the open-gate above also proposed clearing an active-device
     * field (and its subscription) at close; that is unimplementable here in
     * the direction that matters, because {@code onDeviceRemoved} closes the
     * stream on its way into {@code DEVICE_LOST}. A close-cleared fact would
     * therefore be gone at exactly the moment this handler needs it, and the
     * auto-reopen could never fire. The SUBSCRIPTION and the watched identity
     * must outlive the close by design — what was removed instead is the
     * STORED "active" claim: whether a stream is open is now derived from
     * {@link AudioEngine#isStreamOpen()}, the authority, rather than
     * mirrored into a field nothing clears.</p>
     */
    private synchronized void onDeviceArrived(DeviceId arrived) {
        if (engineState != EngineState.DEVICE_LOST) {
            return;
        }
        DeviceId watched = watchedDevice.get();
        if (watched == null || !matches(watched, arrived)) {
            return;
        }
        LOG.info("Lost audio device returned: " + arrived);
        // Story 316 — reopen through the ENGINE's one open/close seam, never
        // by calling backend.open(...) directly: startAudioOutput() walks
        // the provision ladder (honouring the configured DeviceId) and then
        // pauseAudioOutput() parks the stream open-but-not-rendering, which
        // is the historical post-arrival state — device proven usable, user
        // re-arms transport manually. A later Play resumes on the same open
        // stream. With no provision installed there is nothing to reopen.
        if (audioEngine.getStreamingProvision() != null && !audioEngine.isStreamOpen()) {
            try {
                audioEngine.startAudioOutput();
                audioEngine.pauseAudioOutput();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to reopen audio stream after reconnect", e);
                try {
                    notifications.notify("Audio device reconnected but reopen failed: " + e.getMessage());
                } catch (RuntimeException ignored) { /* best-effort */ }
                return;
            }
        }
        // Resume in STOPPED state — the user re-arms transport manually,
        // so the recovered take can be reviewed first.
        setEngineState(EngineState.STOPPED);
        try {
            notifications.notify("Audio device reconnected — review recovered take and re-arm transport");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected message", e);
        }
    }

    /**
     * Handles a driver-initiated {@link AudioDeviceEvent.FormatChangeRequested}
     * by scheduling a coalesced reopen on the
     * {@link #formatChangeWorker} (story 218). Runs on the device-event
     * thread; off-loads the heavy work — stop transport, drain, close,
     * re-query capabilities, reopen — to the worker so the publisher
     * thread is never blocked.
     *
     * <p>If another {@code FormatChangeRequested} arrives within
     * {@value #FORMAT_CHANGE_COALESCE_MILLIS}&nbsp;ms, the pending task
     * is cancelled and rescheduled — the typical case where the user
     * is spinning a buffer-size dropdown in the driver panel. The
     * eventual reopen always uses the freshest proposed format /
     * reason.</p>
     *
     * @param requested the request to handle; must not be null
     */
    private void onFormatChangeRequested(AudioDeviceEvent.FormatChangeRequested requested) {
        // Endpoint identity only — no open-stream gate. A driver may
        // renegotiate the format of a device we merely watch (the user
        // spinning buffer size in the control panel with the transport
        // stopped), and that new format must still reach the engine.
        // performFormatChangeReopen() re-opens only what
        // AudioEngine.isStreamOpen() said was open (its wasOpen).
        DeviceId watched = watchedDevice.get();
        if (watched == null || !matches(watched, requested.device())) {
            // Some other device — nothing to do.
            return;
        }
        LOG.info("FormatChangeRequested received for " + requested.device()
                + " reason=" + requested.reason()
                + " proposedFormat=" + requested.proposedFormat());
        // Replace the latest pending request so the worker uses the
        // freshest payload when it eventually runs.
        latestFormatChange.set(requested);
        // Coalesce: cancel any in-flight scheduled reopen, then schedule
        // a fresh one 250 ms out. Deliberately not interruptIfRunning —
        // a reopen that has already begun should be allowed to finish.
        ScheduledFuture<?> previous = pendingFormatChange.getAndSet(null);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> task;
        try {
            task = formatChangeWorker.schedule(this::runFormatChangeReopen,
                    FORMAT_CHANGE_COALESCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Executor was shut down (controller closed) — nothing to do.
            LOG.log(Level.FINE, "Format-change worker rejected task", e);
            return;
        }
        pendingFormatChange.set(task);
    }

    /**
     * Runs the coalesced reopen flow on
     * {@link #formatChangeWorker}. Never invoked on the audio callback
     * thread or the device-event thread.
     */
    private void runFormatChangeReopen() {
        AudioDeviceEvent.FormatChangeRequested requested = latestFormatChange.getAndSet(null);
        if (requested == null) {
            // Coalesced into a later task that already ran — nothing to do.
            return;
        }
        DeviceId watched = watchedDevice.get();
        if (watched == null || !matches(watched, requested.device())) {
            return;
        }
        try {
            performFormatChangeReopen(watched, requested);
        } catch (RuntimeException e) {
            // Never let the worker thread die because of one bad reopen —
            // the next event must still be handleable.
            LOG.log(Level.WARNING, "Format-change reopen failed", e);
            try {
                notifications.notify("Audio engine reconfiguration failed: " + e.getMessage());
            } catch (RuntimeException ignored) { /* best-effort */ }
            // Always end up in STOPPED so the user can re-arm transport
            // even after a failed reopen.
            setEngineState(EngineState.STOPPED);
        }
    }

    private synchronized void performFormatChangeReopen(
            DeviceId watched, AudioDeviceEvent.FormatChangeRequested requested) {
        AudioBackend backend = boundBackend.get();
        AudioFormat currentFormat = audioEngine.getFormat();

        // Sample-rate change is treated specially — story 126's SRC is
        // inserted at the device boundary so the project session rate
        // does not change.
        Optional<com.benesquivelmusic.daw.sdk.audio.AudioFormat> proposed = requested.proposedFormat();
        boolean isSampleRateChange = requested.reason() instanceof FormatChangeReason.SampleRateChange;
        boolean rateActuallyDiffers = proposed.isPresent()
                && Double.compare(proposed.get().sampleRate(), currentFormat.sampleRate()) != 0;

        notifyQuietly("Reconfiguring audio engine…");
        setEngineState(EngineState.RECONFIGURING);

        // Whether a stream was open before this reopen — mirrors
        // applyConfiguration: only a previously-open stream is restored
        // after the format change (story 316, engine-seam routing).
        boolean wasOpen = audioEngine.isStreamOpen();
        // ...and WHICH open state it was in, snapshotted here for the same
        // reason and in the same place as applyConfiguration's (story 316
        // review): isStreamOpen() covers RUNNING *and* PAUSED, and the stop
        // at step 1 erases the distinction, so a driver-initiated format
        // change silently promoted a paused stream back to rendering.
        boolean wasPaused = audioEngine.isStreamPaused();

        // 1. Stop the transport AND confirm the render pump left
        //    processBlock. Story 316 re-review: this path discarded that
        //    verdict and then mutated the engine format at step 5, exactly
        //    the hazard applyConfiguration was fixed for — same field, same
        //    engine, same absent engine-side guard (setStreamingProvision
        //    refuses a SWAP, requireQuiescedPump refuses a REOPEN, setFormat
        //    refuses only while isRunning(), which the stop just cleared).
        //    The shared predicate is stopAndConfirmPumpQuiesced(); it also
        //    subsumes the fixed FORMAT_CHANGE_DRAIN_MILLIS sleep that used
        //    to stand in for a drain here (see PUMP_STILL_RENDERING).
        //
        //    A stop that THROWS counts as NOT quiesced: this path logs
        //    rather than throws, and an exception says nothing about whether
        //    the pump exited — the safe reading of "unknown" is the one that
        //    changes no format (same rule as stopAudioOutputForShutdown()).
        boolean quiesced;
        try {
            quiesced = stopAndConfirmPumpQuiesced();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop the engine during format-change reopen", e);
            quiesced = false;
        }
        if (!quiesced) {
            refuseFormatChangeReopen();
            return;
        }

        // 3. If recording was active, behave like DeviceRemoved for the
        //    in-flight take — flush partially captured frames so the
        //    user can review them after the reopen.
        try {
            incompleteTakeStore.flushIfNotEmpty(watched, audioEngine.getFormat());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to persist incomplete take during reopen", e);
        }

        // 4. Re-query capabilities so the new open call uses values the
        //    backend actually allows. The SDK contract for these methods
        //    permits returning defaults when the native shim is absent;
        //    we deliberately call them anyway so the production wiring
        //    exercises the same path the FFM-bound version will.
        if (backend != null) {
            try {
                BufferSizeRange range = backend.bufferSizeRange(watched);
                Set<Integer> rates = backend.supportedSampleRates(watched);
                LOG.fine("Re-queried capabilities for " + watched
                        + ": bufferSizeRange=" + range + " sampleRates=" + rates);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to re-query backend capabilities", e);
            }
        }

        // 5. Compute the reopen format. If proposedFormat is present we
        //    apply it (clamped where we know better), otherwise reopen
        //    with the existing settings.
        AudioFormat reopenFormat;
        if (proposed.isPresent() && !isSampleRateChange) {
            com.benesquivelmusic.daw.sdk.audio.AudioFormat sdkFmt = proposed.get();
            reopenFormat = new AudioFormat(
                    sdkFmt.sampleRate(),
                    currentFormat.channels(),
                    sdkFmt.bitDepth() > 0 ? sdkFmt.bitDepth() : currentFormat.bitDepth(),
                    deriveBufferFrames(requested.reason(), currentFormat, backend, watched));
        } else if (proposed.isPresent() /* sample-rate change — keep project rate */) {
            reopenFormat = currentFormat;
        } else {
            reopenFormat = currentFormat;
        }

        try {
            audioEngine.setFormat(reopenFormat);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to set new format on audio engine", e);
        }

        // Rebuild the xrun detector so its deadline matches the new
        // format — mirrors applyConfiguration() behaviour.
        XrunDetector previousDetector = this.xrunDetector;
        this.xrunDetector = createDetectorFor(reopenFormat);
        if (previousDetector != null) {
            previousDetector.close();
        }

        // 6. Reopen through the ENGINE's one open/close seam (story 316) —
        //    never by calling backend.open(...) directly. Step 1 already
        //    closed the stream via stopAudioOutput(); startAudioOutput()
        //    re-walks the provision ladder at the new format, honouring the
        //    configured DeviceId. Only a stream that was open before the
        //    change is restored, mirroring applyConfiguration — and a stream
        //    that was PAUSED before the change is restored PAUSED.
        //
        //    Story 316 review: startAudioOutput() always returns a RUNNING
        //    stream, so reopening a previously paused one without the
        //    re-pause below restarts the render pump AND the RT clock under
        //    a transport the user deliberately left paused. The re-pause is
        //    part of the restore, so it lands here — well before step 9's
        //    terminal state (side effects precede the terminal state
        //    observers gate on).
        boolean streamRestored = false;
        if (wasOpen) {
            try {
                audioEngine.startAudioOutput();
                streamRestored = true;
                if (wasPaused) {
                    try {
                        audioEngine.pauseAudioOutput();
                    } catch (RuntimeException pauseFailure) {
                        // Deliberately does NOT clear streamRestored: the
                        // stream really WAS reopened, so step 9's RUNNING
                        // stays the correct terminal state — it now reports
                        // a stream that is open and rendering, which is the
                        // truth after a failed re-pause.
                        LOG.log(Level.WARNING,
                                "Failed to re-pause the reopened audio stream after a"
                                        + " format change; it is open and rendering",
                                pauseFailure);
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to reopen audio stream after format change", e);
            }
        }

        // 7. Surface the SRC fallback notification when the driver moved
        //    to a rate that differs from the project's session rate.
        //    Story 126 — the engine-owned RenderPipeline consults the
        //    SampleRateConversionCache at each clip read AND at the
        //    device boundary so the project keeps its authored rate even
        //    when the driver moves to a different rate. Invalidate the
        //    cache here because a session-rate change makes every cached
        //    conversion stale (they all targeted the previous rate).
        //
        //    This must happen BEFORE the STOPPED transition (step 8):
        //    observers waiting on STOPPED rely on a happens-before
        //    guarantee that the user-facing notification AND the cache
        //    invalidation are already in effect once STOPPED is seen.
        if (isSampleRateChange && rateActuallyDiffers) {
            int newRateKhz = (int) Math.round(proposed.get().sampleRate() / 1000.0);
            audioEngine.getSampleRateConversionCache().invalidateAll();
            notifyQuietly("Driver moved to " + newRateKhz
                    + "kHz — SRC inserted at device boundary. "
                    + "Pick the matching project rate from the driver "
                    + "panel to avoid SRC.");
        } else {
            notifyQuietly("Audio engine reconfigured");
        }

        // 8. Run the post-reconfigure callback — the same hook
        //    applyConfiguration() fires. MainController wires it to
        //    reinstall the per-track CPU budget enforcer AND to
        //    EngineBinder.refreshPerformanceMonitor(); the
        //    PerformanceMonitor's per-block budget is fixed at
        //    construction, so a driver-originated buffer/format change
        //    that skipped this hook would leave the monitor reporting
        //    stale CPU load and false underruns. Runs on this
        //    best-effort path regardless of whether the backend reopen
        //    in step 6 logged a failure — setFormat already happened
        //    either way. Must precede the STOPPED transition (step 9)
        //    so observers gating on STOPPED see the refreshed monitor.
        runPostReconfigureCallback();

        // 9. Terminal state. A stream that was open before the change and
        //    was successfully restored in step 6 reports RUNNING (mirroring
        //    applyConfiguration's wasOpen branch); otherwise the engine
        //    resumes in STOPPED — transport does NOT auto-start, the user
        //    re-arms manually, identical to the onDeviceArrived convention.
        //    This is the final step so that any observer waiting on the
        //    terminal state has a reliable happens-before guarantee that
        //    steps 7-8 (notification + cache invalidation + post-
        //    reconfigure callback) completed.
        //
        //    RUNNING here means "the stream was RESTORED", not "the stream
        //    is rendering" (story 316 review). A stream restored to PAUSED
        //    holds its device and renders nothing — exactly as it did before
        //    the change — and still reports RUNNING, because a transport
        //    pause never moved this controller's EngineState in the first
        //    place. STOPPED stays reserved for "no stream was restored".
        setEngineState(streamRestored ? EngineState.RUNNING : EngineState.STOPPED);
    }

    /**
     * Stops any open stream and the engine, and reports whether the engine
     * CONFIRMED that the render pump left {@code processBlock} (story 316
     * re-review). This is the ONE predicate every path that mutates the
     * engine format must consult before calling
     * {@link AudioEngine#setFormat(AudioFormat)}, and it is shared rather
     * than inlined precisely because there are two such paths —
     * {@link #applyConfiguration(Request)} on the settings thread and
     * {@link #performFormatChangeReopen} on the format-change worker.
     *
     * <p>{@code false} means a render pump may STILL be inside
     * {@link AudioEngine#processBlock}, rendering through the
     * {@code RenderPipeline} that a {@code setFormat} is about to
     * invalidate. No engine-side guard covers a format-only reconfigure:
     * {@code setStreamingProvision} refuses a SWAP (on an unconfirmed pump
     * exit, on an open control panel on the outgoing backend, and on a failed
     * close of the outgoing handle), {@code requireQuiescedPump} refuses a
     * REOPEN, and {@code setFormat} itself refuses only while
     * {@link AudioEngine#isRunning()} — which the {@code stop()} here has
     * just cleared. A format-only change installs no provision and opens no
     * stream, so it passes every one of those however wide those refusal sets
     * grow: this predicate is still the only thing standing between it and a
     * live pump.</p>
     *
     * <p>Bounded retry, then the verdict. The retry is nearly free and often
     * decisive: {@code stop()} re-joins a still-RUNNING stream's pump on its
     * own, and the second {@code stopAudioOutput()} costs at most one more
     * bounded join ({@code EngineStreamPump.STOP_JOIN_MILLIS}) — exactly
     * what a pump that was merely mid-block when the first stop landed needs
     * in order to exit. Only a genuinely wedged pump answers {@code false}.</p>
     *
     * <p>A stop that THROWS is deliberately not caught here: the two callers
     * wrap it differently on purpose. {@code applyConfiguration} lets it
     * propagate to its own catch (notify, STOPPED, rethrow to the caller
     * that asked for the change); the device-event reopen has no caller to
     * throw at and logs instead, counting the throw as NOT quiesced.</p>
     *
     * @return {@code true} when the render pump is confirmed gone
     */
    private boolean stopAndConfirmPumpQuiesced() {
        boolean quiesced = audioEngine.stopAudioOutput();
        audioEngine.stop();
        return quiesced || audioEngine.stopAudioOutput();
    }

    /**
     * The device-event reopen's refusal path (story 316 re-review): the
     * render pump could not be confirmed out of {@code processBlock}, so
     * nothing here may touch the engine format.
     *
     * <p>How the refusal SURFACES is a decision, not an accident. This
     * method runs on {@code formatChangeWorker} with no caller to throw at,
     * and this path's contract is to log rather than throw — but a refusal
     * that only logged would be invisible to the user whose driver just
     * renegotiated. So it does exactly what every other terminal path of
     * {@link #performFormatChangeReopen} does, in the same order: the
     * user-facing notification and the post-reconfigure callback FIRST, then
     * the terminal state that observers gate on (side effects precede the
     * terminal state). The callback runs for the same reason it runs on
     * {@code applyConfiguration}'s refusal path — a surface that disabled
     * itself for the reconfigure must re-enable on EVERY path, including
     * this one.</p>
     *
     * <p>STOPPED is the honest terminal state: {@code stop()} has cleared
     * the engine's running flag, so transport really is stopped and the user
     * can re-arm and retry once the pump unblocks. RUNNING would claim a
     * transport that is not running, and leaving the controller in
     * RECONFIGURING would strand every surface in a transition that has
     * ended. Nothing here claims the reconfigure happened: the engine keeps
     * the format, the buffer size and the stream it already had.</p>
     */
    private void refuseFormatChangeReopen() {
        LOG.severe("Format-change reopen refused: " + PUMP_STILL_RENDERING);
        notifyQuietly("Audio device reconfiguration was refused because the render pump"
                + " is still active: " + PUMP_STILL_RENDERING);
        runPostReconfigureCallback();
        setEngineState(EngineState.STOPPED);
    }

    /**
     * Runs {@link #postReconfigureCallback} when one is installed, never
     * letting its failure break the reconfigure flow. Shared by every
     * reconfigure path so the "the callback runs on EVERY path" rule cannot
     * be honoured by some of them and forgotten by others.
     */
    private void runPostReconfigureCallback() {
        if (postReconfigureCallback == null) {
            return;
        }
        try {
            postReconfigureCallback.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Post-reconfigure callback failed", e);
        }
    }

    /**
     * Tells the user, never letting a rejected message break the flow.
     *
     * @param message the user-facing message
     */
    private void notifyQuietly(String message) {
        try {
            notifications.notify(message);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected message", e);
        }
    }

    /**
     * Picks the buffer-frame count to use when reopening after a
     * {@link AudioDeviceEvent.FormatChangeRequested}.
     *
     * <p>The SDK {@code AudioFormat} payload does not carry buffer frames,
     * so a request whose reason is {@link FormatChangeReason.BufferSizeChange}
     * reads the new frame count from
     * {@link FormatChangeReason.BufferSizeChange#newBufferFrames()} when
     * known, or falls back to the backend's
     * {@link BufferSizeRange#preferred()} value. For all other reasons the
     * current engine buffer size is retained to avoid changing multiple
     * variables at once.</p>
     */
    private static int deriveBufferFrames(
            FormatChangeReason reason,
            AudioFormat current,
            AudioBackend backend,
            DeviceId device) {
        if (reason instanceof FormatChangeReason.BufferSizeChange bsc) {
            if (bsc.newBufferFrames() > 0) {
                return bsc.newBufferFrames();
            }
            // Driver signal didn't carry a concrete frame count — fall
            // back to the backend's preferred buffer size.
            if (backend != null) {
                try {
                    return backend.bufferSizeRange(device).preferred();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "Failed to query bufferSizeRange for fallback", e);
                }
            }
        }
        return current.bufferSize();
    }

    private static boolean matches(DeviceId watched, DeviceId other) {
        if (watched.equals(other)) {
            return true;
        }
        // Fall back to friendly-name match across backends — vendor +
        // product + serial cross-checks would happen in a more advanced
        // identity-matching layer, but the issue allows friendly-name
        // fallback when serial information is unavailable.
        return watched.name().equals(other.name());
    }

    private final class DeviceEventSubscriber
            implements Flow.Subscriber<AudioDeviceEvent>, AutoCloseable {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            Objects.requireNonNull(newSubscription, "subscription must not be null");
            if (closed.get() || controllerClosed.get()
                    || deviceEventSubscriber.get() != this
                    || !subscription.compareAndSet(null, newSubscription)) {
                newSubscription.cancel();
                return;
            }
            if ((closed.get() || controllerClosed.get()
                    || deviceEventSubscriber.get() != this)
                    && subscription.compareAndSet(newSubscription, null)) {
                newSubscription.cancel();
                return;
            }
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AudioDeviceEvent event) {
            if (closed.get() || controllerClosed.get()
                    || deviceEventSubscriber.get() != this) {
                return;
            }
            if (!deviceEventPublisher.isClosed()) {
                deviceEventPublisher.offer(event, (subscriber, dropped) -> false);
            }
            // Switch on sealed AudioDeviceEvent — exhaustive over the four
            // permitted records. JEP 441 (final, JDK 21).
            switch (event) {
                case AudioDeviceEvent.DeviceRemoved removed -> onDeviceRemoved(removed.device());
                case AudioDeviceEvent.DeviceArrived arrived -> onDeviceArrived(arrived.device());
                case AudioDeviceEvent.DeviceFormatChanged changed ->
                        LOG.info("Device format changed for " + changed.device() + ": " + changed.newFormat());
                case AudioDeviceEvent.FormatChangeRequested requested ->
                        onFormatChangeRequested(requested);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!closed.get()) {
                LOG.log(Level.WARNING, "Device-event publisher error", throwable);
            }
        }

        @Override
        public void onComplete() {
            close();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Flow.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                try {
                    current.cancel();
                } catch (RuntimeException ignored) {
                    // best-effort cancellation
                }
            }
        }
    }

    /**
     * Returns the PROVISIONED SDK backend's serialized native control-panel
     * action (story 316 review). {@link AudioEngine#getBackend()} answers
     * with the OPEN stream's backend while one is open and the provision's
     * head rung otherwise — {@link #getProvisionedBackendName()}'s rule, not
     * {@link #getActiveBackendName()}'s — so the driver panel stays
     * reachable with the transport stopped, which is when a user goes
     * looking for it.
     *
     * <p>Resolving the backend and asking it for its action is all this
     * method does under the controller monitor. The returned action itself
     * runs OUTSIDE it (story 316 re-review) &mdash; see
     * {@link #controlPanelLock} for why holding a monitor across a modal
     * native dialog froze the UI, and {@link #runControlPanel} for what
     * still protects the backend while the dialog is up: a registration this
     * controller consults before closing an instance it owns, AND publishes
     * to the engine, so an engine-owned close of that backend's stream handle
     * is refused or deferred too.</p>
     */
    @Override
    public synchronized Optional<Runnable> openControlPanel() {
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return Optional.empty();
        }
        return backend.openControlPanel()
                .map(action -> () -> runControlPanel(backend, action));
    }

    /**
     * Runs a resolved control-panel action serialized against other panel
     * launches and registered against concurrent backend closes, with this
     * controller's monitor held across NEITHER the modal dialog NOR the
     * engine calls that bracket it.
     *
     * <p>Everything the monitor was needed for at launch has already
     * happened: {@link #openControlPanel()} resolved the backend instance
     * and its action under the monitor, and both are captured. What remains
     * is the modal, user-paced native call and the two registrations that
     * bracket it — all three on one of the settings dialog's virtual
     * threads, and none of the three may hold the controller MONITOR, which
     * is what the FX thread contends for.</p>
     *
     * <p>Both engine calls are made HERE, as siblings of the two
     * {@code synchronized} helpers rather than from inside them, and that
     * placement is the correction story 316's re-review is about.
     * {@link AudioEngine#endControlPanelSession(AudioBackend)} does not just
     * clear a field: it DRAINS the close its registration deferred, which is
     * the engine's bounded pump join followed by {@code close()} on the
     * retained handle — priced UNBOUNDED for PortAudio and in minutes for a
     * slow ASIO driver by the engine's own blocking budget. Reached from
     * inside the monitor it would re-create the exact stall this split
     * removed, with {@code SettingsDialog.updateAudioUtilityDisabledState()}
     * calling {@link #openControlPanel()} on the FX thread and queueing
     * behind a native teardown. As siblings, the monitor is held only for
     * the two field stores, and the resulting order is
     * [{@link #controlPanelLock} &rarr; monitor] and
     * [{@code controlPanelLock} &rarr; {@code lifecycleLock}], never
     * [monitor &rarr; {@code lifecycleLock}]; see {@link #controlPanelLock}
     * for why both are acyclic.</p>
     *
     * <p>The ORDER inside the {@code finally} is deliberate.
     * {@code endControlPanelSession} runs BEFORE the controller-side clear,
     * so {@link #controlPanelBackend} is still standing while the drain is
     * inside {@code close()} — otherwise a concurrent
     * {@link #closeProvisionBackends(StreamingProvision, StreamingProvision)}
     * on another thread (nothing holds the monitor out here) could free that
     * INSTANCE underneath a drain that is using it. The cover is exact in
     * the case that matters, the retained handle belonging to
     * {@code panelBackend} itself; when the engine's retained handle belongs
     * to a DIFFERENT rung — possible while stopped, see that drain's own
     * javadoc — this registration never covered that instance anyway, and
     * the ordering costs nothing there.
     * Both clears are nonetheless reached whatever that call does: it
     * swallows {@link RuntimeException} itself and deliberately lets an
     * {@link Error} propagate, so the nested {@code finally} is what keeps
     * an {@code Error} from stranding this controller's half of the guard —
     * which would make the instance permanently un-closeable — and from
     * stranding the lock, which would wedge every later panel launch.</p>
     *
     * <p>The two registrations do not have to be established atomically.
     * They guard two INDEPENDENT operations — this controller's INSTANCE
     * closes and the engine's HANDLE closes — and both are standing before
     * {@code action.run()} and withdrawn after it, which is all either guard
     * needs; see {@link #controlPanelBackend} for the residual window
     * between the two stores.</p>
     *
     * @param panelBackend the instance whose panel is being opened;
     *                     registered for the call's duration with BOTH this
     *                     controller and the engine, so neither a concurrent
     *                     provision swap or shutdown closing the INSTANCE in
     *                     {@link #closeProvisionBackends(StreamingProvision,
     *                     StreamingProvision)}, nor an engine-owned close of
     *                     that backend's stream HANDLE, reaches it while the
     *                     dialog is up — see {@link #controlPanelBackend}
     * @param action       the backend's own panel action
     */
    private void runControlPanel(AudioBackend panelBackend, Runnable action) {
        controlPanelLock.lock();
        try {
            registerOpenControlPanel(panelBackend);
            audioEngine.beginControlPanelSession(panelBackend);
            action.run();
        } finally {
            try {
                audioEngine.endControlPanelSession(panelBackend);
            } finally {
                // A field store under the monitor, then the unlock: neither
                // can throw, so nothing after an Error from the drain is
                // stranded.
                releaseOpenControlPanel(panelBackend);
                controlPanelLock.unlock();
            }
        }
    }

    /**
     * Publishes {@code panelBackend} as un-closeable to THIS controller,
     * whose
     * {@link #closeProvisionBackends(StreamingProvision, StreamingProvision)}
     * owns the INSTANCE closes.
     *
     * <p>A field store under the monitor and nothing else: it cannot block,
     * cannot throw, and can never be the reason another thread waits. The
     * engine's half of the same registration —
     * {@link AudioEngine#beginControlPanelSession(AudioBackend)}, which owns
     * the stream HANDLE closes — is published by the caller,
     * {@link #runControlPanel(AudioBackend, Runnable)}, immediately after
     * this returns, and deliberately NOT from in here: keeping the monitor
     * out of the engine calls is what stops the panel path from nesting
     * [monitor &rarr; {@code lifecycleLock}], and the release side of that
     * pair can block for a native teardown. See {@link #controlPanelLock}.</p>
     */
    private synchronized void registerOpenControlPanel(AudioBackend panelBackend) {
        controlPanelBackend = panelBackend;
    }

    /**
     * Withdraws THIS controller's half of the registration, by identity so a
     * late release can never clear a newer one.
     *
     * <p>The engine's half is withdrawn by
     * {@link #runControlPanel(AudioBackend, Runnable)} just BEFORE this
     * runs, and that order is load-bearing: withdrawing the engine's half is
     * what DRAINS the close it deferred, and this field, still standing
     * while that drain runs, is what keeps a concurrent
     * {@link #closeProvisionBackends(StreamingProvision, StreamingProvision)}
     * from freeing {@code panelBackend}'s INSTANCE underneath a drain that
     * is inside {@code close()} on it. Like its register counterpart this is
     * a field store under the monitor and nothing else, so it cannot throw
     * and the caller's {@code finally} can rely on it completing.</p>
     */
    private synchronized void releaseOpenControlPanel(AudioBackend panelBackend) {
        if (controlPanelBackend == panelBackend) {
            controlPanelBackend = null;
        }
    }

    /**
     * Exposes the detector for tests and for engine-internal wiring
     * that records per-buffer timing.
     */
    XrunDetector getXrunDetector() {
        return xrunDetector;
    }

    /**
     * Closes background resources owned by this controller.
     *
     * <p>The provision's backend instances are closed here — the controller
     * owns every backend's lifecycle (story 316) — but only after the engine
     * CONFIRMS the render pump quiesced (story 316 review). A
     * {@link AudioEngine#stopAudioOutput()} whose bounded join timed out
     * deliberately leaves the backend open precisely because the pump may
     * still be inside {@code sink} / {@code awaitSinkCapacity}; closing every
     * provision backend anyway would release native state under that live
     * thread. The stop is retried once — it joins for up to a second and is
     * documented as retryable — and the close is otherwise skipped: see
     * {@link #closeProvisionBackendsOnceQuiesced()}.</p>
     */
    synchronized void shutdown() {
        controllerClosed.set(true);
        tonePlayer.close();
        clearBoundBackendDeviceEvents();
        // Nothing may call back into a shut-down controller, so the engine's
        // stream-open seam is cleared alongside the device-event
        // subscription it drives (story 316 review). bindBackendDeviceEvents
        // already no-ops on controllerClosed, but leaving a dead listener
        // installed on a live engine is not the honest lifecycle.
        audioEngine.setStreamOpenListener(null);
        engineStatePublisher.close();
        deviceEventPublisher.close();
        ScheduledFuture<?> pending = pendingFormatChange.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        formatChangeWorker.shutdownNow();
        monitorEventDrain.shutdownNow();
        XrunDetector detector = this.xrunDetector;
        if (detector != null) {
            detector.close();
        }
        closeProvisionBackendsOnceQuiesced();
    }

    /**
     * Stops any open stream and then closes every provision backend instance
     * to release native resources on exit — but only once the engine reports
     * the render pump CONFIRMED quiesced (story 316 review).
     *
     * <p>{@link AudioEngine#stopAudioOutput()} returns {@code false} for
     * exactly one condition: its bounded join timed out, so a thread may
     * still be inside {@code processBlock} and therefore inside the
     * backend's {@code sink} / {@code awaitSinkCapacity}. That stop
     * intentionally leaves the backend open; closing the provision's
     * instances anyway would free native state under the live
     * {@code engine-render-pump} thread — {@code AsioBackend}'s
     * {@code AsioBufferSwitchShim}, whose shared {@link java.lang.foreign.Arena}
     * backs the {@code bufferSwitch} upcall stub the driver calls into; a
     * Java Sound {@code SourceDataLine}; a PortAudio {@code Pa_} stream
     * handle. The stop is retried ONCE (it joins for up to a second and
     * documents itself as retryable, so this is a real second chance and not
     * an unbounded shutdown hang); if quiescence still cannot be confirmed
     * the instances are deliberately left open and the OS reclaims them at
     * process exit, which is strictly safer than a native use-after-free.</p>
     *
     * <p>A thrown stop counts as NOT quiesced: an exception says nothing
     * about whether the pump exited, and the safe reading of "unknown" here
     * is the one that does not free anything.</p>
     */
    private void closeProvisionBackendsOnceQuiesced() {
        boolean quiesced = stopAudioOutputForShutdown();
        if (!quiesced) {
            quiesced = stopAudioOutputForShutdown();
        }
        if (!quiesced) {
            LOG.severe("Audio shutdown: the render pump did not join in time, so the"
                    + " provision's backend instances are deliberately NOT closed — a"
                    + " close would release native state (an ASIO bufferSwitch upcall"
                    + " arena, a Java Sound SourceDataLine, a PortAudio stream handle)"
                    + " underneath a still-live engine-render-pump thread. The OS"
                    + " reclaims them at process exit, which is strictly safer than a"
                    + " native use-after-free.");
            return;
        }
        // No incoming provision on the shutdown path: every instance goes.
        closeProvisionBackends(audioEngine.getStreamingProvision(), null);
    }

    /**
     * One bounded stop attempt for {@link #closeProvisionBackendsOnceQuiesced()}.
     *
     * @return the engine's own quiescence verdict, or {@code false} when the
     *         stop threw — a failure that leaves pump quiescence unknown
     */
    private boolean stopAudioOutputForShutdown() {
        try {
            return audioEngine.stopAudioOutput();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to stop audio output during shutdown", e);
            return false;
        }
    }

    private static XrunDetector createDetectorFor(AudioFormat format) {
        return new XrunDetector(format.sampleRate(), format.bufferSize());
    }

    /**
     * Installs the blank-name default {@link StreamingProvision} —
     * PortAudio (adapted behind the SDK interface) when available, else
     * Java Sound — exactly the default the retired legacy backend factory
     * produced, now expressed as an explicit ladder on the engine's one
     * streaming slot (story 316).
     * Called by {@code MainController} at startup, before the persisted
     * audio settings refine the provision via
     * {@link #applyConfiguration(Request)}.
     */
    synchronized void installDefaultProvision() {
        installProvision(buildDefaultProvision("", ""));
    }

    /**
     * Builds the {@link StreamingProvision} for the given request (story
     * 316) — the requested backend as the first rung plus the explicit
     * emergency ladder the engine walks when that backend cannot open:
     *
     * <ul>
     *   <li>Requested rung: resolved by name — {@code "PortAudio"} becomes
     *       a {@link CallbackBackendAdapter} over {@link PortAudioBackend};
     *       {@code "Java Sound"} is the SDK {@link JavaxSoundBackend};
     *       every other name routes through the
     *       {@link AudioBackendSelector}. Its {@link DeviceId} is the
     *       configured output device (blank &rarr; the backend's default),
     *       honoured on every open.</li>
     *   <li>Fallback rungs: the PortAudio adapter when available (and not
     *       itself the requested backend), then Java Sound — each on its
     *       own DEFAULT device: falling back is an emergency, the default
     *       device is the honest choice, and the engine's
     *       {@code BackendFallbackEvent} names it.</li>
     * </ul>
     *
     * <p>If the requested backend is unavailable or its streaming path is
     * not available in this build ({@link AudioBackend#supportsStreaming()}
     * {@code == false} — unimplemented as for WASAPI / CoreAudio / JACK
     * today, or an ASIO whose native streaming shim is missing or
     * incomplete), the probe is closed, a
     * single {@link NotificationManager} warning of the form
     * {@code "ASIO not available — falling back to <head rung>"} — naming
     * the fallback ladder's ACTUAL head (PortAudio when available, else
     * Java Sound) — is emitted so the user is never silently routed
     * elsewhere, and the ladder starts at that head. The provision's
     * {@code requestedBackendName} <em>and</em> {@code requestedDevice} both
     * stay the user's request — passed explicitly on that path rather than
     * defaulted from the (fallback) first rung — so the engine's
     * {@code BackendFallbackEvent}s name the true requested endpoint and
     * never pair the requested backend with a device the user never chose.
     * The rejection is additionally carried as a single
     * {@link StreamingProvision#pendingFailedHopCauses() pending failed hop}
     * (story 316 review) naming WHY the gate rejected it — unavailable on
     * this host versus available with an unavailable streaming path — so
     * the engine publishes a {@code BackendFallbackEvent} for it too once
     * the winning rung is known; without that, a fallback head opening on
     * the first try published nothing at all and the substitution was
     * visible only as a transient notification.
     * A blank / {@link #BACKEND_NONE} name yields the default ladder
     * (PortAudio when available, else Java Sound) with no notification and
     * no pending cause. An unknown or unconstructible name (story 316
     * review) is treated exactly like an unavailable backend: the request is
     * preserved, the user is notified, and the rejection rides into the
     * ladder as a pending failed hop — previously it was silently replaced
     * by the default ladder, losing the requested backend/device and
     * publishing nothing.</p>
     *
     * <p>Pure builder: does not install the provision and does not touch
     * the engine — {@link #applyConfiguration(Request)} pairs it with
     * {@link #installProvision(StreamingProvision)}.</p>
     */
    synchronized StreamingProvision buildStreamingProvision(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        String name = request.backendName();
        if (name == null || name.isBlank() || BACKEND_NONE.equals(name)) {
            return buildDefaultProvision(request.inputDeviceName(), request.outputDeviceName());
        }
        AudioBackend requested = createStreamingBackendByName(name, request.inputDeviceName());
        if (requested == null) {
            // Story 316 review: an unknown or unconstructible name used to
            // be quietly replaced by the default provision, which lost both
            // the requested backend/device and any failed-hop cause — a
            // requested != active substitution with neither the fallback
            // event nor the warning the unavailable path promises. It now
            // takes exactly that path. createStreamingBackendByName returns
            // null for two distinct reasons, and the cause names each
            // honestly: "PortAudio" whose adapter construction threw (the
            // host cannot load it), versus a name this build has no backend
            // for at all (a stale persisted setting, a plugin-era name).
            String cause = "PortAudio".equals(name)
                    ? name + " is not available on this host, so it was never offered to"
                            + " the open ladder"
                    : name + " is not a backend this build knows, so it was never offered"
                            + " to the open ladder";
            return rejectedProvision(name, request, cause);
        }
        boolean requestedIsAvailable = requested.isAvailable();
        if (!requestedIsAvailable || !requested.supportsStreaming()) {
            closeQuietly(requested);
            // The cause distinguishes the two genuinely different
            // user-facing facts: a backend the host does not have at all,
            // versus one it has whose streaming path is not available in
            // this build — either unimplemented (WASAPI / CoreAudio / JACK
            // today) or, since the story 316 review, an ASIO whose native
            // asioshim is missing the story-311 streaming symbols, so
            // supportsStreaming() is a live probe rather than a constant.
            String gateCause = requestedIsAvailable
                    ? name + " is available on this host but its streaming path is not"
                            + " available in this build (unimplemented, or its native"
                            + " streaming shim is missing or incomplete), so it was never"
                            + " offered to the open ladder"
                    : name + " is not available on this host, so it was never offered to"
                            + " the open ladder";
            return rejectedProvision(name, request, gateCause);
        }
        List<BackendStreamRung> ladder = new ArrayList<>();
        ladder.add(new BackendStreamRung(requested, deviceId(name, request.outputDeviceName())));
        appendFallbackRungs(ladder, name);
        return new StreamingProvision(name, ladder);
    }

    /**
     * The single rejection path of {@link #buildStreamingProvision(Request)}
     * (story 316 review — extracted so the unavailable, non-streamable,
     * unconstructible and unknown cases cannot drift apart): builds the
     * emergency ladder WITHOUT the requested backend, notifies the user of
     * the switch, and returns a provision that preserves the user's request
     * and carries {@code cause} as a pending failed hop.
     *
     * <p>{@code requestedBackendName} stays the USER'S request even though
     * it cannot stream: the engine stamps it into every published
     * {@code BackendFallbackEvent}, and those must name the true request —
     * {@link #getProvisionedBackendName()} reports the first rung (the
     * backend the user will actually hear) separately. The requested DEVICE
     * is passed explicitly for exactly the same reason: this ladder starts on
     * a FALLBACK rung, so the two-arg convenience constructor would default
     * {@code requestedDevice} to that fallback's device and the engine would
     * stamp a {@code BackendFallbackEvent} pairing the user's requested
     * backend name with a device they never chose.</p>
     *
     * <p>The rejection is ALSO carried forward as a pending failed hop. This
     * path removes the requested backend from the ladder entirely, so the
     * engine's ladder walk records no failed hop for it and — when the
     * fallback head opens first try — published NOTHING on the EventBus
     * seam, making requested != active a silent substitution there. The
     * {@link NotificationManager} message is a different surface, not a
     * substitute.</p>
     */
    private StreamingProvision rejectedProvision(String name, Request request, String cause) {
        List<BackendStreamRung> ladder = new ArrayList<>();
        appendFallbackRungs(ladder, name);
        if (ladder.isEmpty()) {
            ladder.add(new BackendStreamRung(
                    new JavaxSoundBackend(), DeviceId.defaultFor("Java Sound")));
        }
        // Fallback notification: the user explicitly asked for a backend
        // the host can't stream through — surface the switch, naming the
        // ladder's ACTUAL head (PortAudio when available, else Java Sound),
        // instead of silently routing them elsewhere.
        try {
            notifications.notify(name + " not available — falling back to "
                    + ladder.get(0).backend().name());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "NotificationManager rejected fallback message", e);
        }
        return new StreamingProvision(
                name, deviceId(name, request.outputDeviceName()), ladder, List.of(cause));
    }

    /**
     * The blank-name default ladder: PortAudio (adapted) when available —
     * with the configured output device on its rung — else Java Sound.
     */
    private StreamingProvision buildDefaultProvision(
            String inputDeviceName, String outputDeviceName) {
        AudioBackend portAudio = tryCreatePortAudioAdapter(inputDeviceName);
        if (portAudio != null) {
            return new StreamingProvision("PortAudio", List.of(
                    new BackendStreamRung(portAudio, deviceId("PortAudio", outputDeviceName)),
                    new BackendStreamRung(
                            new JavaxSoundBackend(), DeviceId.defaultFor("Java Sound"))));
        }
        return new StreamingProvision("Java Sound", List.of(
                new BackendStreamRung(
                        new JavaxSoundBackend(), deviceId("Java Sound", outputDeviceName))));
    }

    /**
     * Appends the emergency fallback rungs — PortAudio when available, then
     * Java Sound — skipping whichever of them is the requested backend
     * itself. Fallback rungs open their backend's DEFAULT device: the
     * default OUTPUT, and — story 316 review — deliberately the default
     * INPUT too, which is why the PortAudio adapter below is constructed
     * with a BLANK input-device name instead of the one the user configured.
     *
     * <p>There is no configured input name that could legitimately reach a
     * rung built here. The requested backend's input-device name belongs to
     * THAT backend's namespace, and this method only ever appends a
     * PortAudio rung when PortAudio is NOT the requested backend — so the
     * name it would have carried is always foreign, and BOTH ways it can
     * land are wrong for an emergency rung. It may MISS PortAudio's own
     * enumeration entirely. It may also HIT, which is not the remote case it
     * sounds like on Windows: {@link PortAudioBackend}'s class javadoc lists
     * ASIO among the host APIs it reaches there, {@code PortAudioBindings}'
     * host-API lookup reads {@code "ASIO"} back as a driver-supplied label,
     * and {@code AsioBackend.listDevices()} stamps every driver it
     * enumerates with that same {@code "ASIO"} host API — so the two
     * backends can produce the IDENTICAL
     * {@link AudioDeviceInfo#qualifiedName()} for one driver, and a hit
     * would pair a SPECIFIC foreign input device with the DEFAULT output
     * this rung opens ({@link DeviceId#defaultFor(String)}), which is not a
     * pairing the user chose either. That is why the parameter was removed
     * outright rather than left unused: no caller has one to pass.</p>
     *
     * <p>The mismatch was not cosmetic, and the SILENT TAKE it once produced
     * is why the blank name was chosen.
     * {@code CallbackBackendAdapter.resolveInputDevice} matches the
     * configured name against the adapted backend's OWN device snapshot, and
     * a miss used to mean exactly one thing: a logged warning, a
     * {@code null} input device, and a stream that still opened perfectly
     * well for PLAYBACK with capture silently disabled. A session that fell
     * back from ASIO to PortAudio therefore recorded a silent take, and it
     * bit hardest through
     * {@link #rejectedProvision(String, Request, String)}, where this
     * PortAudio rung is appended to an EMPTY ladder and is therefore the
     * ladder HEAD: the backend the user actually hears and records
     * through.</p>
     *
     * <p>What a miss MEANS is now conditional on the caller's
     * {@link com.benesquivelmusic.daw.sdk.audio.CaptureRequirement}, and the
     * silent take above is history rather than the current failure mode on
     * the record path (story 316 review):</p>
     * <ul>
     *   <li>PLAYBACK — {@link AudioEngine#startAudioOutput()} asks for
     *       {@link com.benesquivelmusic.daw.sdk.audio.CaptureRequirement#OPTIONAL}
     *       — is unchanged: a foreign input name still resolves to a miss
     *       that warns and DISABLES CAPTURE for that stream while the rung
     *       opens perfectly well for playback. That is correct — playback
     *       must not die of an input problem — and it is still a stream that
     *       could never record.</li>
     *   <li>RECORDING — {@link AudioEngine#startAudioInputOutput()} asks for
     *       {@link com.benesquivelmusic.daw.sdk.audio.CaptureRequirement#REQUIRED}
     *       — turns that same miss into a REFUSAL: the adapter throws
     *       instead of returning {@code null}, and the engine additionally
     *       refuses any rung whose
     *       {@link AudioBackend#openedInputChannels()} is zero after a
     *       REQUIRED open. A capture-less rung can no longer succeed into a
     *       stream the recording pipeline cannot tell from a duplex
     *       one.</li>
     * </ul>
     *
     * <p>A refusal here does not fail the recording by itself — it is an
     * ORDINARY failed ladder hop. The throw happens inside the adapter's
     * {@code open()} before the delegate stream is opened, so its
     * {@code close()} skips the stream teardown it gates on its own
     * {@code open} flag, still releases the initialized delegate, and
     * reports no deferred release; the engine reads that as a clean
     * handle-back and its ladder walk advances to the NEXT rung — the Java
     * Sound rung this same method appends immediately below, unless Java
     * Sound is itself the requested backend and was therefore skipped.
     * Recording fails outright only when EVERY rung fails, and it then fails
     * LOUDLY — the ladder rethrows its first hop's failure, which for a
     * refusal like this one is the adapter's own
     * {@code AudioBackendException} naming the unresolvable input device —
     * rather than by handing back a stream that can never record.</p>
     *
     * <p>The blank name is what gives this rung a real chance to BE that
     * next successful hop: it takes {@code resolveInputDevice}'s
     * {@code inputDeviceName.isBlank()} branch, which resolves PortAudio's
     * own default input device — the only input selection that belongs to
     * this rung. (A host with no default input device at all still refuses
     * the rung under {@code REQUIRED}, which is the honest answer: there is
     * nothing to record from.)</p>
     *
     * <p>{@link #buildDefaultProvision(String, String)} is the deliberate
     * counter-example: there PortAudio IS the provisioned head for a request
     * that named no backend at all, so the configured input name genuinely
     * belongs to it and is passed through.</p>
     */
    private void appendFallbackRungs(List<BackendStreamRung> ladder, String requestedName) {
        if (!"PortAudio".equals(requestedName)) {
            // Blank on purpose (story 316 review) — see the javadoc above.
            // A foreign backend's input-device name is resolved against
            // PortAudio's OWN snapshot: a miss disables capture on this rung
            // under OPTIONAL (playback), and REFUSES the rung under REQUIRED
            // (recording), costing the ladder the hop. Blank resolves this
            // backend's own default input, the only one that belongs here.
            AudioBackend portAudio = tryCreatePortAudioAdapter("");
            if (portAudio != null) {
                ladder.add(new BackendStreamRung(
                        portAudio, DeviceId.defaultFor("PortAudio")));
            }
        }
        if (!"Java Sound".equals(requestedName)) {
            ladder.add(new BackendStreamRung(
                    new JavaxSoundBackend(), DeviceId.defaultFor("Java Sound")));
        }
    }

    /**
     * Returns an available {@link CallbackBackendAdapter} over a fresh
     * {@link PortAudioBackend}, or {@code null} when PortAudio is not
     * usable on this host.
     */
    private static AudioBackend tryCreatePortAudioAdapter(String inputDeviceName) {
        try {
            CallbackBackendAdapter adapter =
                    new CallbackBackendAdapter(new PortAudioBackend(), inputDeviceName);
            if (adapter.isAvailable()) {
                return adapter;
            }
            closeQuietly(adapter);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "PortAudio adapter unavailable", e);
        }
        return null;
    }

    /**
     * Swaps the engine's streaming provision: <b>installs first</b>, then
     * closes the outgoing backend instances (story 316 review — the order was
     * previously the other way round).
     *
     * <p>{@link AudioEngine#setStreamingProvision(StreamingProvision)} is the
     * only call that quiesces the render pump: when the engine still tracks a
     * stream on an outgoing backend the incoming ladder no longer carries, it
     * joins that pump — {@code abandonStreamOnOutgoingBackend} calls
     * {@code stopPump()} first of all — before it goes anywhere near the
     * handle. What follows that join is no longer unconditionally an
     * abandonment: the handle is given back only when it CAN be, and a close
     * that fails, or one deferred because that backend's native control panel
     * is open, REFUSES the swap instead (see below).
     * Closing the outgoing instances first therefore freed native state —
     * {@code AsioBackend}'s nulled {@code bufferSwitchShim}, a closed
     * {@code SourceDataLine}, a released {@code Pa_} handle — underneath a
     * live {@code engine-render-pump} thread that was still calling
     * {@code sink()} / {@code awaitSinkCapacity()} on it, whenever the engine
     * still tracked a stream (a {@code stopAudioOutput()} whose pump join
     * timed out, or a retained {@code RELEASE_PENDING} handle).</p>
     *
     * <p>The controller still owns every backend instance's lifecycle, so the
     * outgoing instances are closed here — but only the ones the incoming
     * ladder does not reuse: a shared instance is still live on the new
     * provision and must not be closed.</p>
     *
     * <p>That same ownership is why a REFUSED swap closes the INCOMING
     * instances (story 316 review). {@code setStreamingProvision} throws
     * {@link com.benesquivelmusic.daw.sdk.audio.AudioBackendException} on
     * THREE counts — the outgoing stream's pump cannot be confirmed
     * quiesced, the outgoing backend has its native control panel open, or
     * the outgoing handle's close itself failed — and aborts the swap whole
     * in each case; by then {@code buildStreamingProvision} has already
     * CONSTRUCTED the incoming backend instances, and an exception that
     * simply propagated left them neither installed nor closed — a leak of
     * exactly the native handles this ordering exists to protect (a
     * PortAudio {@code Pa_} handle per {@code CallbackBackendAdapter}, a
     * Java Sound {@code SourceDataLine}). They are closed best-effort here,
     * skipping any instance the OUTGOING provision also carries: the engine
     * still points at that provision, so a shared instance is live.</p>
     */
    private void installProvision(StreamingProvision provision) {
        StreamingProvision outgoing = audioEngine.getStreamingProvision();
        try {
            audioEngine.setStreamingProvision(provision);
        } catch (RuntimeException swapRefused) {
            // Roles reversed on purpose: the provision that never got
            // installed is the one whose instances are now orphaned.
            closeProvisionBackends(provision, outgoing);
            throw swapRefused;
        }
        closeProvisionBackends(outgoing, provision);
    }

    /**
     * Best-effort close of every backend instance in {@code outgoing} that
     * {@code incoming} does not also carry. Reuse is decided by instance
     * identity, mirroring {@code AudioEngine.ladderContains} — an instance
     * the incoming ladder still holds is live and closing it would break the
     * next open.
     *
     * <p>An instance whose native driver control panel is OPEN is skipped
     * too (story 316 re-review). That guard is what replaced holding the
     * controller monitor across the modal dialog: closing a backend frees
     * exactly the native state the dialog is running on, so the close is
     * refused and logged rather than performed. This is the same trade
     * {@link #closeProvisionBackendsOnceQuiesced()} already makes for a
     * render pump that would not confirm quiescence &mdash; the OS reclaims
     * the handle at process exit, which is strictly safer than a native
     * use-after-free. This method always runs under the controller monitor,
     * which is what makes the {@link #controlPanelBackend} read sound — but
     * NOT because its own callers are {@code synchronized}: none of the three
     * is. Two of them are in {@link #installProvision(StreamingProvision)}
     * and the third in {@link #closeProvisionBackendsOnceQuiesced()}, both
     * plain private methods. The monitor is held TRANSITIVELY, by the only
     * entry points that reach them — {@link #applyConfiguration(Request)} and
     * {@link #installDefaultProvision()} into the first, {@link #shutdown()}
     * into the second — each of which is {@code synchronized} for its whole
     * duration. That is the property to re-establish if a FOURTH call site is
     * added: it is sound only when EVERY path into it also enters through a
     * {@code synchronized} method of this controller. Being reached from
     * something that merely looks internal proves nothing, and no compiler
     * check enforces it.</p>
     *
     * <p>The guard is no longer scoped to THIS close. The engine consults
     * its own half of the same registration before five of the six handle
     * closes it owns — every one that can run WHILE the dialog is up; the
     * sixth is its own drain, which runs only after the registration has
     * been withdrawn and must not consult it. So an
     * {@link AudioEngine#stopAudioOutput()} or an
     * {@link AudioEngine#setStreamingProvision(StreamingProvision)} that runs
     * ahead of this method RETAINS the tracked stream's handle instead of
     * freeing it under the dialog, and releases it once the panel returns —
     * see {@link #controlPanelBackend}'s javadoc for the full boundary, and
     * for the one window that is still open.</p>
     *
     * @param outgoing the provision being replaced, or {@code null}
     * @param incoming the provision taking its place, or {@code null} when
     *                 the engine is being left with nothing to stream
     */
    private void closeProvisionBackends(
            StreamingProvision outgoing, StreamingProvision incoming) {
        if (outgoing == null) {
            return;
        }
        for (BackendStreamRung rung : outgoing.ladder()) {
            if (carries(incoming, rung.backend())) {
                continue;
            }
            if (rung.backend() == controlPanelBackend) {
                LOG.severe("Backend " + rung.backend().name() + " is deliberately"
                        + " NOT closed: its native driver control panel is open, and"
                        + " the close would free the native state that modal dialog"
                        + " is running on. The OS reclaims the handle at process"
                        + " exit, which is strictly safer than a use-after-free"
                        + " underneath a live driver dialog.");
                continue;
            }
            closeQuietly(rung.backend());
        }
    }

    /**
     * Returns whether {@code provision}'s ladder carries this exact backend
     * <em>instance</em> — identity, not equality, matching
     * {@code AudioEngine.ladderContains}.
     */
    private static boolean carries(StreamingProvision provision, AudioBackend backend) {
        if (provision == null) {
            return false;
        }
        for (BackendStreamRung rung : provision.ladder()) {
            if (rung.backend() == backend) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(AudioBackend backend) {
        try {
            backend.close();
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Resolves a UI-facing backend name to a fresh SDK {@link AudioBackend}
     * instance (story 316: every backend — including the adapted
     * {@code "PortAudio"} — lives behind the SDK interface now). Returns
     * {@code null} for unknown names; the caller owns the instance's
     * lifecycle.
     */
    private AudioBackend createStreamingBackendByName(String name, String inputDeviceName) {
        return switch (name) {
            case "PortAudio" -> {
                try {
                    yield new CallbackBackendAdapter(new PortAudioBackend(), inputDeviceName);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "PortAudio requested but unavailable", e);
                    yield null;
                }
            }
            case "Java Sound" -> new JavaxSoundBackend();
            default -> createSdkBackendByName(name);
        };
    }

    private AudioBackend createSdkBackendByName(String name) {
        if (WasapiBackend.NAME.concat(" (Exclusive)").equals(name)) {
            return new WasapiBackend(true);
        }
        return backendSelector.selectByName(name);
    }

    private <T> T withSdkBackend(
            String backendName, Function<AudioBackend, T> operation, T fallback) {
        if (backendName == null || backendName.isBlank()
                || "PortAudio".equals(backendName) || "Java Sound".equals(backendName)) {
            return fallback;
        }
        AudioBackend provisioned = audioEngine.getBackend();
        if (provisioned != null && backendName.equals(provisioned.name())) {
            return operation.apply(provisioned);
        }
        AudioBackend probe = createSdkBackendByName(backendName);
        if (probe == null) {
            return fallback;
        }
        try (probe) {
            return operation.apply(probe);
        }
    }

    private static DeviceId deviceId(String backendName, String deviceName) {
        String normalizedBackend = WasapiBackend.NAME.concat(" (Exclusive)").equals(backendName)
                ? WasapiBackend.NAME : backendName;
        if (deviceName == null || deviceName.isBlank()) {
            return DeviceId.defaultFor(normalizedBackend);
        }
        return new DeviceId(normalizedBackend, deviceName);
    }
}
