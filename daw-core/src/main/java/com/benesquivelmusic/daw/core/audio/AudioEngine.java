package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central audio engine responsible for managing the audio processing pipeline.
 *
 * <p>The engine coordinates audio I/O, drives the mixer, and dispatches
 * audio buffers to tracks and plugins.</p>
 *
 * <p>Audio processing is performed in fixed-size blocks via
 * {@link #processBlock(float[][], float[][], int)}. All processing buffers
 * are pre-allocated during {@link #start()} so that the audio callback
 * performs zero heap allocations — making it real-time-safe.</p>
 *
 * <p>When a {@link Transport}, {@link Mixer}, and track list are configured,
 * the engine reads audio data from each track's {@link AudioClip}s at the
 * current transport position, mixes them through the mixer channel strips
 * into the master bus, and advances the transport. Loop playback is supported
 * when the transport's loop mode is enabled. That transport/mixer/tracks
 * configuration is published atomically via
 * {@link #setGraph(Transport, Mixer, List)} as one immutable snapshot record
 * behind a single volatile field, so the audio thread always sees either the
 * entire old graph or the entire new one — never a half-swapped hybrid
 * (story 314 review).</p>
 *
 * <p>Audio I/O streams through ONE slot typed by the SDK
 * {@link AudioBackend} interface (story 316 — the backend consolidation this
 * class's former dual-slot javadoc awaited). The application installs a
 * {@link StreamingProvision} — the requested backend plus an explicit
 * fallback ladder — via {@link #setStreamingProvision(StreamingProvision)};
 * {@link #startAudioOutput()} walks the ladder, opens the first rung that
 * accepts its device and negotiated format, and starts an engine-owned
 * {@link EngineStreamPump} that calls {@link #processBlock} once per buffer
 * period, paced by the backend's device-clock backpressure signal. The
 * reported active backend ({@link #openStreamBackendName()} /
 * {@link #openStreamDevice()}) is by construction the open stream's.</p>
 */
public final class AudioEngine {

    private static final Logger LOG = Logger.getLogger(AudioEngine.class.getName());

    /** Maximum number of tracks supported for pre-allocated buffers. */
    static final int MAX_TRACKS = 64;

    /**
     * The session format the render pipeline, the buffer pool and every
     * ladder open are shaped from.
     *
     * <p>{@code volatile} since the story-316 re-review. It is written by
     * {@link #setFormat(AudioFormat)} on the settings-apply / device-event
     * threads and read inside {@link #lifecycleLock} by {@link #startLocked()},
     * {@link #openLadder}, the {@link EngineStreamPump} constructor and
     * {@link #handOffMixer}. The lock now serializes the write with those
     * readers; the volatile is what makes the write VISIBLE to a reader that
     * takes the lock afterwards on another thread, and — more to the point —
     * keeps a torn/absent publication impossible for the one reader that
     * takes a DIFFERENT lock ({@code handOffMixer}, under {@link #graphLock}).
     * </p>
     */
    private volatile AudioFormat format;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EffectsChain masterChain;
    private AudioBufferPool bufferPool;

    /**
     * The engine's ONE streaming slot (story 316): the requested backend
     * plus its explicit fallback ladder.
     */
    private volatile StreamingProvision streamingProvision;

    /**
     * Backend of the stream the engine currently tracks — the winning
     * ladder rung after a successful open. Non-null while
     * {@link StreamState#RUNNING} or {@link StreamState#PAUSED}, and while
     * {@link StreamState#RELEASE_PENDING} (where it names the backend still
     * holding the unreleasable handle). {@link #processBlock} snapshots this
     * once per block for metronome {@code writeToChannel} routing, so the
     * click side output finally targets an OPEN stream (stories 135/136).
     */
    private volatile AudioBackend openBackend;

    /** Device identity of the tracked stream (the winning rung's device). */
    private volatile DeviceId openDevice;

    /** Negotiated SDK format the tracked stream was actually opened with. */
    private volatile com.benesquivelmusic.daw.sdk.audio.AudioFormat openSdkFormat;

    /** Render pump of the open stream; non-null only while RUNNING. */
    private volatile EngineStreamPump pump;

    /**
     * Notified whenever an open COMPLETES, with the rung that actually won
     * (story 316 review). See {@link StreamOpenListener}; {@code null} when
     * nothing is listening.
     */
    private volatile StreamOpenListener streamOpenListener;

    /**
     * Exact failed stream-start invocations paired with the endpoint and
     * START/RESUME operation captured under {@link #lifecycleLock}. This is
     * lifecycle/reporting state only, never read by the real-time path.
     *
     * <p>The context is scoped to the lifecycle caller's thread and matched by
     * throwable identity: the engine rethrows the backend/publisher's original
     * failure object unchanged, and the app hands that same object back through
     * {@link #takeFailedStreamStart(Throwable)}. Thread confinement prevents
     * concurrent lifecycle callers from overwriting one another; active-frame
     * stacking preserves an outer failure across same-thread announcement
     * re-entry, and consume-on-read plus clearing at each new top-level start
     * prevents a backend that reuses one exception instance from reviving stale
     * attribution.</p>
     */
    private static final int MAX_COMPLETED_STREAM_START_CONTEXTS = 16;

    private final ThreadLocal<FailedStreamStartContexts> failedStreamStartContexts =
            ThreadLocal.withInitial(FailedStreamStartContexts::new);

    /**
     * Lifecycle state of the backend audio stream (story 315 review). One
     * volatile field replaces the former {@code streamOpen}/{@code streamPaused}
     * pair so a stopped-but-unreleasable stream can be told apart from a
     * paused one.
     *
     * <p>Transitions, and what each does to the transport's RT-clock claim
     * ({@link Transport#setRealTimeClockActive(boolean)}):</p>
     * <ul>
     *   <li>{@code CLOSED → RUNNING}: a ladder rung's {@code open} succeeded;
     *       the claim is taken <em>before</em> the render pump starts (the
     *       pump may call {@link #processBlock} before the start call
     *       returns).</li>
     *   <li>{@code RUNNING → CLOSED} / {@code RUNNING → RELEASE_PENDING} on a
     *       start failure: the claim is released (draining any seek queued in
     *       the window), then the handle is closed — {@code CLOSED} if that
     *       close succeeded AND actually gave the handle back,
     *       {@code RELEASE_PENDING} if it failed, if it was DEFERRED because
     *       the backend's control panel is open (see
     *       {@link #beginControlPanelSession(AudioBackend)}), or if the close
     *       RETURNED with the backend still holding the handle
     *       ({@link AudioBackend#isReleasePending()} — see
     *       {@link #releaseDeferredBy(AudioBackend)}).</li>
     *   <li>{@code RUNNING → PAUSED} ({@link #pauseAudioOutput()}): releases
     *       the claim. {@code PAUSED → RUNNING} ({@link #startAudioOutput()}):
     *       claims before the pump start; a failed resume returns to
     *       {@code PAUSED} and releases.</li>
     *   <li>{@code RUNNING|PAUSED|RELEASE_PENDING → CLOSED}
     *       ({@link #stopAudioOutput()}, close succeeded): releases the claim.
     *       {@code RUNNING|PAUSED → RELEASE_PENDING}: releases the claim, in
     *       three cases, all of them past a CONFIRMED render-pump exit — the
     *       close FAILED, the close was DEFERRED because the backend's
     *       control panel is open, or the close RETURNED and the BACKEND
     *       reported the release deferred
     *       ({@link AudioBackend#isReleasePending()}). When the bounded
     *       join does NOT confirm the exit, NO close is attempted at all:
     *       {@link #stopAudioOutputLocked} returns early on
     *       {@code !stopPump()}, ahead of both the clock release and any
     *       {@code close()}, so the state and the claim are preserved
     *       untouched for a later retry.</li>
     *   <li>{@code RUNNING → RELEASE_PENDING} ({@link #stop()}): another
     *       way into that state, and the one that attempts no close either.
     *       {@link #stopLocked(PendingAnnouncements)} quiesces the pump,
     *       releases the claim and leaves the handle with the backend for a
     *       later release — and it does so even when its OWN bounded join
     *       timed out, which is why {@code RELEASE_PENDING} alone is not
     *       proof the pump has exited. See {@link StreamState#RELEASE_PENDING}
     *       and {@link #drainDeferredHandleRelease()}, which re-joins rather
     *       than trusting the state.</li>
     *   <li>{@code RELEASE_PENDING → CLOSED} ({@link #startAudioOutput()}
     *       retrying the close before a fresh open, or
     *       {@link #endControlPanelSession(AudioBackend)} draining a close it
     *       deferred): the claim is already released; nothing changes until
     *       the fresh stream starts.</li>
     *   <li>{@code RUNNING|PAUSED|RELEASE_PENDING → CLOSED}
     *       ({@link #setStreamingProvision(StreamingProvision)} re-pointing
     *       the engine at a provision that no longer carries the tracked
     *       backend instance): reached only when the outgoing backend's handle
     *       actually CLOSES. Four things refuse the whole swap instead, with
     *       an {@link AudioBackendException} — an unconfirmed render-pump
     *       exit (story 316 review: nothing is touched, so the state AND the
     *       claim stay exactly as they were rather than close a backend a
     *       live thread may still be inside), an open control panel on the
     *       outgoing backend, a close that FAILED, and a close that RETURNED
     *       with the release deferred by the backend itself
     *       ({@link AudioBackend#isReleasePending()}). The latter three leave
     *       {@code RELEASE_PENDING} with that backend still tracked, so the
     *       engine keeps its retry path; all four are retryable. The claim
     *       is released the moment the pump's exit is CONFIRMED — so on those
     *       three refusal paths exactly as on the success path, since a
     *       confirmed exit means nothing is driving the transport any
     *       more.</li>
     * </ul>
     */
    private enum StreamState {
        /** No backend stream handle is held. */
        CLOSED,
        /**
         * The stream is open and started (or being started): the callback may
         * call {@link #processBlock(float[][], float[][], int)}, and the RT
         * clock is claimed.
         */
        RUNNING,
        /**
         * The stream is open but stopped via {@link #pauseAudioOutput()};
         * resumable, clock released.
         */
        PAUSED,
        /**
         * The stream is stopped but the backend still owns the handle. Four
         * ways in, and they are worth telling apart because only the first
         * three imply the pump is confirmed gone:
         * <ul>
         *   <li>a close was attempted after a CONFIRMED join and FAILED
         *       ({@link #stopAudioOutputLocked},
         *       {@link #abandonStreamOnOutgoingBackend}), or the pump start
         *       failed and its unwind's close failed
         *       ({@link #startOpenedStream});</li>
         *   <li>that same close was DEFERRED rather than attempted, because
         *       the backend's native control panel is open (see
         *       {@link #beginControlPanelSession(AudioBackend)});</li>
         *   <li>that close WAS attempted, it RETURNED NORMALLY, and the
         *       BACKEND then reported that it had not actually given the
         *       handle back ({@link AudioBackend#isReleasePending()},
         *       consulted through {@link #releaseDeferredBy(AudioBackend)}).
         *       This is the ASIO case: {@code AsioBackend} DEFERS the release
         *       of its driver shim while the asio-control thread is still
         *       executing a call the host abandoned, so the shim keeps its
         *       ownership claim while the Java-side fields the close cleared
         *       let {@code close()} return normally. A close that returns is
         *       therefore NOT proof of a release, and reading it as one would
         *       point the engine's next open at a device the driver may still
         *       be holding (story 316 re-review);</li>
         *   <li>{@link #stopLocked(PendingAnnouncements)} quiesced a
         *       {@code RUNNING} stream on {@link #stop()} and left the handle
         *       for a later release WITHOUT attempting a close — and it does
         *       so even when its own bounded join timed out, so this state
         *       alone is NOT proof that the render pump has exited. Anything
         *       about to close the retained handle must re-confirm that
         *       itself; see {@link #stopPump()}.</li>
         * </ul>
         * The clock is released in all four; the handle must be released by
         * a later close retry before any new stream can be opened. Not
         * resumable, and not reported by {@link #isStreamOpen()} — to
         * callers, output is simply stopped; the retained handle is the
         * engine's own business.
         *
         * <p>{@code openBackend} names the backend still holding it, and that
         * is what makes the retry possible: the engine retries the close
         * before the next open ({@link #releaseRetainedStreamHandle}), on the
         * next {@link #stopAudioOutput()}, and — for a panel-deferred close —
         * from {@link #endControlPanelSession(AudioBackend)}. Every path that
         * would otherwise DROP that tracking is therefore a refusal instead,
         * because a forgotten unreleasable handle means the next open puts a
         * second backend on the same device.</p>
         */
        RELEASE_PENDING
    }

    // Audio output stream state
    private volatile StreamState streamState = StreamState.CLOSED;

    /**
     * The backend whose native control panel is open right now, or
     * {@code null} — the engine-side half of the control-panel guard.
     *
     * <p>Written only inside {@link #lifecycleLock}, by
     * {@link #beginControlPanelSession(AudioBackend)} and
     * {@link #endControlPanelSession(AudioBackend)}; read only inside it too,
     * through {@link #controlPanelOpenOn(AudioBackend)}. {@code volatile}
     * anyway, so a reader added outside the lock cannot observe a stale
     * registration — the field's whole purpose is to be checked before a
     * close, and a missed registration is the failure mode it exists to
     * prevent.</p>
     *
     * <p>At most ONE session at a time: the application controller serializes
     * panel launches against each other with its own lock, so this is a
     * single slot rather than a set.</p>
     */
    private volatile AudioBackend controlPanelBackend;

    // Unified per-block render pipeline shared by live playback and offline
    // export. Owns the pre-allocated mix, per-track, and return-bus buffers.
    private RenderPipeline renderPipeline;

    // MIDI track renderer for SoundFont synthesis (volatile for safe stop() from UI thread)
    private volatile MidiTrackRenderer midiTrackRenderer;

    // The whole transport/mixer/tracks project graph is published to the
    // audio thread as ONE volatile store of an immutable record (story 314
    // review). Three separate volatile fields allowed processBlock to read
    // the OLD transport (playbackActive gate) alongside the NEW tracks and
    // mixer mid-publish — a hybrid graph. One field makes that impossible.
    private volatile EngineGraph graph = EngineGraph.EMPTY;

    // Serializes the graph mutators (setGraph / setTracks) so concurrent
    // read-modify-writes cannot lose an update. The RT read path NEVER
    // touches this lock — processBlock reads the volatile graph field only.
    private final Object graphLock = new Object();

    /**
     * Serializes the backend-stream lifecycle transition (story 316
     * re-review). The {@code streamState} / {@code openBackend} /
     * {@code openDevice} / {@code openSdkFormat} / {@code pump} quintet is a
     * multi-step STATE MACHINE, not five independent facts: an open reads the
     * state, retries a join, releases a retained handle, walks the ladder,
     * stores four fields and starts a thread. {@code volatile} gives each
     * field visibility and gives that sequence no atomicity at all — two
     * callers could both observe {@link StreamState#CLOSED}, open two
     * different rungs, overwrite each other's {@code openBackend} and
     * {@code pump}, and leave one stream orphaned on a device nobody will
     * ever close again. That race is reachable in production: a plain Play
     * calls {@link #startAudioOutput()} straight from the FX thread while the
     * application's audio controller drives the same methods from its
     * device-event and format-change workers.
     *
     * <h2>What it guards</h2>
     * <p>ELEVEN public methods take it, in two shapes.</p>
     * <p>SEVEN read-then-write the quintet and delegate to a {@code …Locked}
     * body that holds the whole transition: {@link #start()}, {@link #stop()},
     * {@link #setStreamingProvision(StreamingProvision)},
     * {@link #startAudioOutput()}, {@link #startAudioInputOutput()},
     * {@link #stopAudioOutput()} and {@link #pauseAudioOutput()}.</p>
     * <p>FOUR take it around an INLINE body and have no {@code …Locked}
     * method of their own — {@link #setFormat(AudioFormat)},
     * {@link #setEngineSettings(AudioEngineSettings)},
     * {@link #beginControlPanelSession(AudioBackend)} and
     * {@link #endControlPanelSession(AudioBackend)}. The first two are here
     * because each is a read-then-write (a running check, then a store) over
     * a field the locked open path consumes; see their own javadoc. The
     * control-panel pair is here for a different reason: the registration it
     * publishes is what FIVE of the six engine-owned closes consult before
     * freeing a handle — the sixth is this class's own drain; see
     * {@link #beginControlPanelSession(AudioBackend)} for the split — so
     * taking it with a bare volatile store would let a close that
     * is ALREADY inside {@code backend.close()} race the registration instead
     * of being waited for. Anything that reasons about this lock from the
     * {@code …Locked} NAMING alone will miss all four, which is why
     * {@code AudioEngineLifecycleLockContractTest} derives its root set from
     * the lock ACQUISITION in the bytecode instead.</p>
     * <p>The control-panel pair also fixes a LOCK ORDER, and it is NOT the
     * only pair reached from inside an application monitor: the controller's
     * {@code synchronized} {@code applyConfiguration}, and the
     * {@code performFormatChangeReopen} it shares its predicate with, both
     * reach {@link #setFormat(AudioFormat)} — and {@code applyConfiguration}
     * also {@link #setEngineSettings(AudioEngineSettings)} — with that
     * monitor
     * held, so [controller&nbsp;monitor → this&nbsp;lock] is an established
     * edge rather than one the panel pair introduces. The panel pair
     * deliberately does NOT take it: the controller calls both from its
     * {@code runControlPanel}, under its own {@code controlPanelLock} and
     * with the monitor released, because
     * {@link #endControlPanelSession(AudioBackend)} drains a close and can
     * therefore hold this lock for as long as a native teardown takes.</p>
     * <p>What makes that edge sound wherever it IS taken is the rule the
     * next section states, and the rule is narrower than "nothing outward
     * runs while this lock is held" — which an earlier revision of this
     * paragraph asserted, and which was never true. The accurate rule is
     * that no APPLICATION CALLBACK runs under this lock: the three seams
     * that reach application-registered code are deferred on a
     * {@link PendingAnnouncements}, while SDK extension points and untimed
     * native downcalls run under it DELIBERATELY, are catalogued below, and
     * are priced in the blocking budget. {@code endControlPanelSession}
     * itself is one of them — it reaches {@link #stopPump()} and
     * {@code close()} on the retained handle — and
     * {@code AudioEngineLifecycleLockContractTest} allow-lists
     * {@link AudioBackend} on its own grounds, which predate this drain:
     * "the SDK backend contract — driver code, not application code.
     * Bounded rather than deferred". A maintainer who
     * "restored" the absolute as written would have to delete that drain,
     * turning the panel deferral back into the leak the drain exists to
     * prevent. What these two methods may never gain is an APPLICATION
     * callback, on pain of closing the cycle.</p>
     * <p>{@link #setGraph(Transport, Mixer, List)} is NOT on this lock; it
     * has its own ({@link #graphLock}), and defers its outward calls with the
     * same {@link PendingAnnouncements} mechanism.</p>
     * <p>The RT path is untouched:
     * {@link #processBlock(float[][], float[][], int)} still reads those
     * fields volatilely and acquires nothing, so this lock can never be
     * contended by — or block — the render thread.</p>
     *
     * <h2>Internal call graph (and why the lock is REENTRANT)</h2>
     * <pre>
     * startAudioOutput      → startAudioOutputLocked
     *                           → requireQuiescedPump  → stopPump
     *                           → resumeAudioOutputLocked → startLocked
     *                           → releaseRetainedStreamHandle
     *                           → startLocked → stopPump
     *                           → openLadder → startOpenedStream
     * startAudioInputOutput → startAudioInputOutputLocked
     *                           → stopAudioOutputLocked → stopPump
     *                           → startAudioOutputLocked (as above)
     * stopAudioOutput       → stopAudioOutputLocked → stopPump
     * pauseAudioOutput      → pauseAudioOutputLocked → stopPump
     * setStreamingProvision → setStreamingProvisionLocked
     *                           → abandonStreamOnOutgoingBackend → stopPump
     * start                 → startLocked → stopPump
     * stop                  → stopLocked  → stopPump
     * beginControlPanelSession → (inline field store only)
     * endControlPanelSession   → drainDeferredHandleRelease → stopPump
     * </pre>
     * <p>No internal path calls a PUBLIC wrapper, so every nesting above sits
     * inside ONE acquisition. {@link ReentrantLock} rather than a bare
     * monitor for two reasons — try/finally makes each critical section's
     * extent explicit at the call site, and
     * {@link ReentrantLock#isHeldByCurrentThread()} lets
     * {@link PendingAnnouncements#deliver()} verify the
     * deliver-outside-the-lock invariant.
     * Re-entrancy is no longer LOAD-BEARING: it used to be, because the
     * RT-clock release ran inline and fired the transport's change listeners
     * on this very thread, so a listener that called back into a lifecycle
     * method would have self-deadlocked on a non-reentrant lock. That release
     * is now deferred with the rest (see below), which removes the re-entry
     * rather than tolerating it.</p>
     *
     * <h2>What must NOT happen while it is held</h2>
     * <ol>
     *   <li><strong>No application callback.</strong> The hazard is always
     *       the same shape: the app layer's audio controller binds device
     *       events from a {@code synchronized} method while that same
     *       monitor's reconfigure path calls this engine's lifecycle methods,
     *       so ANY outward call under this lock gives one thread [lifecycle →
     *       controller] and the other [controller → lifecycle], a textbook
     *       inversion. THREE seams reach APPLICATION-REGISTERED code, and all
     *       three are RECORDED on a {@link PendingAnnouncements} inside the
     *       critical section and delivered by the public wrapper after the
     *       unlock (which also keeps a {@code BLOCK}-strategy event bus from
     *       parking a lifecycle thread here indefinitely). Enumerated, with
     *       the disposition of each:
     *       <ol>
     *         <li>{@link StreamOpenListener} — goes straight into the app
     *             layer. Recorded by
     *             {@link PendingAnnouncements#streamOpened}, delivered by
     *             {@link #notifyStreamOpened}. DEFERRED.</li>
     *         <li>The failed hops' {@link BackendFallbackEvent}s — go to the
     *             {@code EventBus}, whose subscribers are app-layer. Recorded
     *             by {@link PendingAnnouncements#fallbacks}, delivered by
     *             {@link #publishFallbackEvents}. DEFERRED.</li>
     *         <li>The transport's RT-clock RELEASE (story 316 re-review — the
     *             seam this bullet used to deny existed).
     *             {@link Transport#setRealTimeClockActive(boolean)} with
     *             {@code false} drains a queued seek inline and fires the
     *             transport's {@link Transport.ChangeKind#POSITION}
     *             observers, which are arbitrary app-registered consumers.
     *             Recorded by {@link #recordClockRelease}, delivered by
     *             {@link #deliverClockRelease}. DEFERRED.</li>
     *       </ol>
     *       Other calls DO leave this class without being application
     *       callbacks, and stay inside deliberately. The ones known as of
     *       this change are catalogued below with the disposition of each.
     *       Read the catalogue as a RECORD, not as a closure: it is prose, it
     *       has been wrong before in both directions (it has named a seam
     *       that does not exist — the pump's construction, seam (2) — and
     *       omitted one that does — the pump's subscription cancel, seam (7))
     *       and nothing keeps it complete across the next edit. The
     *       MECHANICAL guarantee is narrower and does not rot:
     *       {@code AudioEngineLifecycleLockContractTest} fails the BUILD for
     *       any call this class's own lock-held code makes to a type that is
     *       neither on its allow-list nor named in its {@code EXCEPTIONS}
     *       with a written reason, so a novel outward call written directly
     *       into a critical section is caught whether or not anyone updates
     *       this list. What that sentinel cannot see is a call made ONE FRAME
     *       DEEPER, inside an allow-listed collaborator — which is where
     *       every entry below that is not a bare field store actually lives.
     *       So a maintainer adding a call here owes it the RULES rather than
     *       a lookup: does it reach application-registered code (then DEFER
     *       it on a {@link PendingAnnouncements} and deliver after the
     *       unlock), does it reach an SDK extension point or an untimed
     *       native downcall (then it is unbounded by contract — price it in
     *       the budget below and say so), and does it take another lock (then
     *       name that lock, and show that nothing holding it ever waits for
     *       this one). Absence from this catalogue is not a verdict of
     *       safety:
     *       <ol>
     *         <li>The RT-clock CLAIM ({@code setRealTimeClockActive(true)})
     *             is a bare volatile store that drains nothing and notifies
     *             nobody. INWARD in effect; proven by
     *             {@code AudioEngineLifecycleLockContractTest}, which reads
     *             the literal {@code true} out of
     *             {@link #claimTransportClock()}'s bytecode rather than
     *             trusting this sentence.</li>
     *         <li>The {@link AudioBackend} calls (open / close /
     *             negotiateFormat / supportsStreaming / isReleasePending —
     *             the last added by the story-316 re-review, which reads
     *             every close's verdict through
     *             {@link #releaseDeferredBy(AudioBackend)} — and
     *             {@code name()}, which the lock-held log lines and failure
     *             messages call and which is an SDK call like any other; see
     *             {@link #drainDeferredHandleRelease()}) are SDK DRIVER
     *             code, not application code. They cannot be deferred — the whole
     *             point of the critical section is that the ladder walk and
     *             the field stores are one transition — so they are a
     *             BLOCKING seam rather than a re-entrancy seam; and because
     *             the interface is deliberately not sealed, every one of them
     *             is unbounded by contract. Their cost is priced in the
     *             blocking budget, the next NUMBERED item of the outer list.
     *             Constructing the {@link EngineStreamPump} is NOT one of
     *             them, despite an earlier revision of this entry saying so:
     *             the constructor validates the format pair, allocates the
     *             render buffers and creates an unstarted thread, and touches
     *             the backend only to store the reference. The pump's backend
     *             contact is {@code inputBlocks().subscribe(…)} inside
     *             {@link EngineStreamPump#start()} — seam (6) — and the
     *             matching cancel on the way out — seam (7).</li>
     *         <li>{@link Mixer#prepareForPlayback(int, int)}, from
     *             {@link #startLocked()}, runs THIRD-PARTY code twice over.
     *             Its {@code recalculateDelayCompensation()} reaches
     *             {@code EffectsChain.getTotalLatencySamples()}, which calls
     *             {@code AudioProcessor.getLatencySamples()} on EVERY
     *             processor in every channel and return-bus chain; and its
     *             {@code rebindAllReflectiveParameterBindings()} re-runs
     *             {@code ReflectiveParameterBinder}'s REFLECTIVE
     *             {@code @ProcessorParam} discovery
     *             ({@code Class.getMethods()} per processor) for every
     *             channel. (The buffer allocation itself is inward — it only
     *             sizes arrays.) {@code AudioProcessor} is an SDK extension
     *             point, so a third-party plugin's {@code getLatencySamples}
     *             can block for as long as it likes, on this lock, with
     *             nothing bounding it.
     *             ACCEPTED, not deferred: the mixer must be prepared before
     *             any pump can render through it (an unprepared mixer means
     *             null scratch buffers and an NPE inside the FFM upcall), and
     *             the pump is started inside this same critical section — so
     *             moving the preparation outside it would let a concurrent
     *             open start a pump on an unprepared mixer, which is a
     *             crash rather than a stall. The same call also runs under
     *             {@link #graphLock} from {@link #handOffMixer(Mixer, Mixer)},
     *             for the same reason and with the same disposition.</li>
     *         <li>{@code Mixer.setGraphScheduler} and
     *             {@code Mixer.getGraphScheduler}, from
     *             {@link #startLocked()} and
     *             {@link #tearDownRenderCollaborators()}. INWARD: the mixer
     *             is engine-owned, and these two are a plain reference store
     *             and its identity read-back — no foreign code, no lock, no
     *             allocation, no notification. They are listed not because
     *             this catalogue is closed — it is not; see its preamble —
     *             but because a catalogue that skipped its BORING entries
     *             would teach the next reader to skip them too, and because
     *             {@code Mixer} is also the type that carries seam (3), so
     *             "it is only the mixer" is not by itself a verdict. Both
     *             call sites are already named in
     *             {@code AudioEngineLifecycleLockContractTest}'s
     *             {@code EXCEPTIONS}; this bullet is what that catalogue
     *             points at.</li>
     *         <li>{@link MidiTrackRenderer}'s CONSTRUCTION in
     *             {@link #startLocked()} and its {@code close()} from
     *             {@link #tearDownRenderCollaborators()}. The class is
     *             engine-owned, but its {@code close()} walks every per-track
     *             {@code SoundFontRenderer}, which is an SDK EXTENSION POINT
     *             ({@code com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer}),
     *             and the in-tree implementations reach native teardown:
     *             {@code FluidSynthRenderer.close()} issues
     *             {@code fluid_synth_system_reset}, {@code delete_fluid_synth}
     *             and {@code delete_fluid_settings} as UNTIMED FFM downcalls
     *             and closes the render {@link java.lang.foreign.Arena}, and
     *             {@code JavaSoundRenderer.close()} closes a
     *             {@code javax.sound.midi.Synthesizer}. That is the SAME
     *             CATEGORY as seam (3) — unbounded, third-party-implementable
     *             code running under this lock — and it belongs in the budget
     *             below, where it now is. The construction side is cheaper
     *             but not free: {@code MidiTrackRenderer}'s constructor
     *             builds a {@code FluidSynthBindings}, which LOADS the native
     *             FluidSynth library through {@code NativeLibraryLoader} and
     *             binds its symbols — a filesystem and dynamic-loader hit,
     *             cheap only once the OS has the image cached.
     *             ACCEPTED, not deferred, and the deferral this class already
     *             owns does NOT cover it:
     *             {@link #collaboratorTeardownDeferred} defers the teardown
     *             along a DIFFERENT axis (until the pump's exit is CONFIRMED)
     *             and drains it from {@link #stopPump()}, which is itself
     *             only ever reached with this lock held — so reusing that
     *             path moves the call in TIME, never OUTSIDE the critical
     *             section. Moving it outside would require the teardown to
     *             clear {@code midiTrackRenderer}, {@code graphScheduler} and
     *             {@code workerPool} only when a concurrent
     *             {@link #startLocked()} had not already replaced them —
     *             identity or epoch machinery this class does not have, and
     *             that a documentation pass must not invent.</li>
     *         <li>{@link EngineStreamPump#start()}, from
     *             {@code startOpenedStream} and
     *             {@code resumeAudioOutputLocked}, executes
     *             {@code backend.inputBlocks().subscribe(new InputSubscriber())}.
     *             The subscriber is ENGINE-INTERNAL — no application code is
     *             registered on it — but the subscribe is a SECOND LOCK taken
     *             under this one: the production publishers are
     *             {@link java.util.concurrent.SubmissionPublisher}s (via the
     *             SDK's {@code AudioBackendSupport}), and its
     *             {@code subscribe} acquires the publisher's OWN
     *             {@link ReentrantLock} for the whole registration. What it
     *             does NOT do is run the subscriber's
     *             {@code onSubscribe(Flow.Subscription)} on this thread —
     *             that call is handed to the publisher's executor
     *             ({@code ForkJoinPool.commonPool()} by default) and arrives
     *             asynchronously, which is precisely the race
     *             {@code EngineStreamPump.InputSubscriber.onSubscribe}
     *             defends against. Only the {@code Executor.execute} handoff
     *             happens inline, with both locks held. No inversion exists
     *             today — nothing that holds a publisher's lock ever waits
     *             for this one — but {@link AudioBackend#inputBlocks()} is a
     *             third-party-implementable seam, so a custom
     *             {@link java.util.concurrent.Flow.Publisher} could block, or
     *             take further locks, right here. INWARD BY CONVENTION rather
     *             than inward by proof, which is why it carries an entry of
     *             its own rather than being folded into seam (2): the
     *             subscribe happens in {@code start()}, and the pump's
     *             CONSTRUCTOR makes no backend call at all — it validates the
     *             format pair, allocates the render buffers and creates an
     *             unstarted thread — so nothing else here would cover it.</li>
     *         <li>{@link EngineStreamPump#stop()}, from {@link #stopPump()},
     *             CANCELS that same subscription: its
     *             {@code cancelInputSubscription()} calls
     *             {@link java.util.concurrent.Flow.Subscription#cancel()} on
     *             a subscription object the BACKEND's publisher handed out,
     *             so the code that runs is the publisher's, not the engine's
     *             — the mirror image of seam (6), and unbounded for the same
     *             reason. For the production
     *             {@link java.util.concurrent.SubmissionPublisher} it is the
     *             JDK's own buffered-subscription teardown; for a custom
     *             {@link AudioBackend#inputBlocks()} it is whatever that
     *             implementation makes it. Unlike the subscribe it cannot
     *             even fail loudly: {@code EngineStreamPump.cancelQuietly}
     *             logs and swallows a throwing cancel, because the stop paths
     *             that reach it must never throw. ACCEPTED, not deferred — it
     *             runs BEFORE the bounded join in the same {@code stop()}, so
     *             the upstream stops delivering into a pump that is on its
     *             way out rather than after it is already gone, and a
     *             confirmed join is what lets the caller close that backend.
     *             Only the JOIN is priced as item (1) of the budget below;
     *             the cancel is priced with seam (6)'s publisher contact,
     *             which it is the other half of.</li>
     *       </ol></li>
     *   <li><strong>How long it can block — the honest budget.</strong> Not
     *       "no unbounded wait". The general truth first, because it is the
     *       part that cannot go stale: every call this critical section makes
     *       into an SDK EXTENSION POINT, and every UNTIMED native downcall it
     *       issues, is unbounded BY CONTRACT — the interface says nothing
     *       about how long an implementation may take, so no figure derived
     *       from today's implementations bounds tomorrow's.
     *       {@link AudioBackend} is deliberately NOT sealed (see its class
     *       javadoc: {@code daw-core} contributes adapters and JPMS sealing
     *       would forbid cross-module implementors), so EVERY backend call
     *       seam (2) enumerates — the list is not restated here, because two
     *       copies of it would rot against each other — and seam
     *       (6)'s {@code inputBlocks()} are unbounded for ANY third-party
     *       backend, whatever the in-tree ones happen to cost. That includes
     *       the {@code isReleasePending()} the SDK contract REQUIRES to be
     *       cheap and non-blocking — a requirement nothing enforces, and this
     *       paragraph's whole point is that no property of today's
     *       implementations bounds tomorrow's. The same holds
     *       for {@code AudioProcessor}, {@code SoundFontRenderer} and any
     *       {@link java.util.concurrent.Flow} implementation reached through
     *       them. The figures below are what the IN-TREE implementations cost
     *       TODAY, which is worth knowing for capacity work and for triage
     *       but is not a guarantee. Three of them are unbounded even in-tree
     *       — one native (PortAudio's untimed downcalls) and two through SDK
     *       extension points ({@code AudioProcessor.getLatencySamples},
     *       {@code SoundFontRenderer.close})
     *       — and the bounded ones are bounded PER CALL, not per transition. A
     *       maintainer adding code here should assume the following worst
     *       case, and remember that the application's
     *       {@code TransportController.start()} — the Play intent handler —
     *       calls {@link #startAudioOutput()} straight from the JavaFX
     *       application thread, so every figure below is also a UI freeze:
     *       <ol>
     *         <li>{@link EngineStreamPump#stop()}'s join — 1 s, once per
     *             {@code stopPump()}. A transition can call it more than
     *             once (an open does: {@code requireQuiescedPump} and then
     *             {@code startLocked}).</li>
     *         <li>{@code AudioWorkerPool.close()} — its per-worker join is
     *             500 ms and the joins are SEQUENTIAL, so the bound is
     *             (pool size − 1) × 500 ms, not 500 ms. With the default
     *             sizing of {@code max(1, availableProcessors − 2)} that is
     *             6.5 s on a 16-core host.</li>
     *         <li>ASIO — {@code AsioControlThread}'s 15 s budget is PER
     *             DOWNCALL. One {@code AsioBackend.open()} issues eight or
     *             more of them (list, load, buffer-size, channel counts,
     *             createBuffers, getBufferInfos, install callback, start),
     *             all inside its own {@code DRIVER_LIFECYCLE_LOCK}, and a
     *             failed open adds its rollback's. A driver that is SLOW BUT
     *             RETURNING — every call answering just inside its own
     *             budget — therefore still costs MINUTES on a single ASIO
     *             rung, and the fallback ladder may walk several rungs. A
     *             driver that WEDGES costs ONE budget, not eight: the caller
     *             whose budget expires while the downcall is still executing
     *             marks that operation abandoned, and
     *             {@code AsioControlThread.call} then refuses every further
     *             BOUNDED call on arrival while it remains outstanding
     *             ({@code isQuiesced()}), so the rest of the open and the
     *             whole of its rollback fail instantly instead of queueing
     *             behind it. {@code AsioBackend.open()} refuses on the same
     *             check, so a later rung cannot re-enter the wedge either,
     *             and the one teardown that cannot be retried — the driver
     *             shim's {@code ASIOExit} — is handed to a background
     *             executor rather than waited for here. The bound is real (a
     *             wedged driver cannot stall the application forever) but it
     *             is not a snappy one.</li>
     *         <li>{@code CallbackBackendAdapter.close()} joins its
     *             {@code native-input-drain} thread — 2 s.</li>
     *         <li>{@code JavaxSoundBackend.close()} — its bounded output
     *             drain ({@code CLOSE_DRAIN_TIMEOUT_MILLIS}, 200 ms) and its
     *             capture-thread join ({@code CAPTURE_EXIT_TIMEOUT_MILLIS},
     *             2 s), each at most once per close of the Java Sound rung,
     *             reached for example via {@link #stopAudioOutputLocked}'s
     *             {@code backend.close()} under this lock.</li>
     *         <li>PortAudio — <strong>UNBOUNDED</strong>. There is no
     *             {@code AsioControlThread} equivalent for it:
     *             {@code CallbackBackendAdapter.open} calls
     *             {@code PortAudioBackend.openStream} and
     *             {@code startStream}, which issue {@code Pa_OpenStream} and
     *             {@code Pa_StartStream} as UNTIMED FFM downcalls on the
     *             calling thread — this thread, holding this lock. A device
     *             whose driver wedges in either one stalls every lifecycle
     *             transition in the application (Play, Stop, the
     *             device-event reopen worker, shutdown) for as long as it
     *             wedges, and freezes the UI with them. The same is true of
     *             {@code Pa_StopStream} / {@code Pa_CloseStream} on the way
     *             out. This is a KNOWN, ACCEPTED hole, not an oversight; see
     *             {@code CallbackBackendAdapter}'s class javadoc for what
     *             closing it would take and why it was not done inside a
     *             review round.</li>
     *         <li>{@link Mixer#prepareForPlayback(int, int)} —
     *             <strong>UNBOUNDED</strong>, and previously described in
     *             seam (3) above without ever appearing in this budget. Its
     *             {@code recalculateDelayCompensation()} calls
     *             {@code AudioProcessor.getLatencySamples()} once per
     *             processor across every channel and return-bus chain;
     *             {@code AudioProcessor} is an SDK extension point, so a
     *             third-party plugin may take as long as it likes in a method
     *             the engine calls with this lock held. The reflective
     *             {@code @ProcessorParam} rebind in the same call is bounded
     *             only by the number of processors and the cost of
     *             {@code Class.getMethods()} per processor, which is not a
     *             figure worth quoting but is not free either.</li>
     *         <li>{@link MidiTrackRenderer}{@code .close()} from
     *             {@link #tearDownRenderCollaborators()} —
     *             <strong>UNBOUNDED</strong>. It closes every per-track
     *             {@code SoundFontRenderer}, an SDK extension point:
     *             {@code FluidSynthRenderer.close()} makes untimed FFM
     *             downcalls into FluidSynth teardown and closes the render
     *             arena, and {@code JavaSoundRenderer.close()} closes a
     *             {@code javax.sound.midi.Synthesizer} — neither has an
     *             {@code AsioControlThread}-style budget. A third-party
     *             implementation has none either. Runs on every {@code stop()}
     *             that confirms quiescence, and on the deferred drain inside
     *             {@link #stopPump()}.</li>
     *       </ol>
     *       Joining the pump here is safe because the render loop calls only
     *       {@link #processBlock(float[][], float[][], int)} and
     *       {@link #isRunning()}, neither of which touches this lock — a pump
     *       thread can never be waiting on it while a lifecycle thread waits
     *       on the pump.</li>
     *   <li><strong>No graph lock.</strong> {@link #graphLock} and this lock
     *       are never nested in either direction: the lifecycle paths read
     *       {@code graph} volatilely, and {@link #setGraph(Transport, Mixer,
     *       List)} calls no lifecycle method — it reads {@code streamState}
     *       INSIDE its own monitor, through {@link #callbackIsDriving()}, and
     *       {@code graph} volatilely, and takes nothing further. Should a
     *       future path need both, take this one first.
     *       The rule for fields is one sentence, and it is the part of this
     *       bullet worth memorising: a field WRITTEN while one of the two
     *       locks is held and READ while the other is held gets no
     *       happens-before from either, so it must be {@code volatile} (or
     *       {@code final}). Every field currently in that set satisfies it —
     *       {@link #format} and {@link #graphScheduler}, written under this
     *       lock and read by {@link #handOffMixer(Mixer, Mixer)} under
     *       {@link #graphLock}, and {@code streamState}, written under this
     *       lock and read under {@link #graphLock} by
     *       {@link #setGraph(Transport, Mixer, List)} through
     *       {@link #callbackIsDriving()}. All three are declared
     *       {@code volatile}, so an earlier revision of this bullet naming
     *       only the first two and calling the set TWO was a MISCOUNT in the
     *       prose, not a missing modifier in the code and not a race. To
     *       decide whether a NEW field needs the keyword, ask the two
     *       questions the rule is made of: is it ever stored while this lock
     *       is held, and is it ever loaded while {@code graphLock} is held —
     *       INCLUDING through a helper one of the graph bodies calls, which
     *       is exactly how {@code streamState} hid.
     *       {@link #workerPool} is volatile for a DIFFERENT reason and must
     *       not be cited as evidence for this one: it is only ever touched
     *       under THIS lock, and its cross-thread reader is the lock-free
     *       {@link #getWorkerPool()}, not the graph lock. Both are valid
     *       reasons to be volatile; they are not the same reason.
     *       {@code AudioEngineLifecycleLockContractTest}'s
     *       {@code everyFieldWrittenUnderOneLockAndReadUnderTheOtherIsVolatile}
     *       mechanises PART of that rule, and the part it does not mechanise
     *       is precisely where {@code streamState} slipped through. Its WRITE
     *       side is derived from bytecode over this lock's regions AND their
     *       transitive call closure. Its READ side is not: it collects the
     *       direct {@code GETFIELD}s in three hand-listed bodies
     *       ({@code setGraph}, {@code setTracks}, {@code handOffMixer}) and
     *       does NOT follow the calls those bodies make, so a field reached
     *       through a helper is invisible to it. What the test guarantees is
     *       "no field directly read in a listed {@code graphLock} body and
     *       written under this lock is non-volatile" — real, and strictly
     *       smaller than the rule above. The remainder is a reading
     *       obligation, which is what this bullet exists to hand over.</li>
     * </ol>
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    // Optional callback invoked from processBlock when recording is active
    private volatile RecordingCallback recordingCallback;

    // Optional performance monitor for CPU load and underrun tracking
    private volatile PerformanceMonitor performanceMonitor;

    // Optional per-track CPU budget enforcer for graceful degradation
    private volatile TrackCpuBudgetEnforcer cpuBudgetEnforcer;

    // Process-wide cache of sample-rate-converted clip buffers (story 126).
    // The render pipeline consults this cache before reading each clip's
    // audio data so clips imported at a rate different from the session
    // rate are JIT resampled rather than pitch-shifted.
    private volatile SampleRateConversionCache srcCache = new SampleRateConversionCache();

    // Optional registry of input-level monitors (story 137). When set, the
    // engine taps the raw input signal per armed track ahead of any
    // processing and feeds it through the track's monitor so the mixer's
    // input-meter column and the arrangement-view clip indicator stay live.
    private volatile InputLevelMonitorRegistry inputLevelMonitorRegistry;

    // The engine-owned metering tap bus (story 318, book §3.5 / §4.3): the
    // registry every output meter subscribes to. The render path reads its
    // immutable TapSnapshot once per block (one volatile load hoisted with
    // the graph) and taps nothing when the bus is unbound. Bound / unbound /
    // refreshed by the EngineBinder; closed by shutdown().
    private final MeteringTapBus meteringTapBus = new MeteringTapBus();

    // Metronome click-generation pipeline (story 136). All three are
    // published volatilely so the audio thread sees a consistent view
    // without locking. The router writes its side output via
    // {@link AudioBackend#writeToChannel(int, float[])} so it requires
    // the SDK backend slot above; cue-bus contributions are written to
    // the cue bus's hardware output stereo pair on the same backend.
    private volatile Metronome metronome;
    private volatile MetronomeSideOutputRouter metronomeSideOutputRouter;
    private volatile CueBusManager cueBusManager;

    // Multi-core graph scheduling state (story 125). The pool size is
    // locked at start(); changing it requires a stop/start cycle. The
    // scheduler is also installed on the mixer so its mixDown() path
    // dispatches independent insert chains across the worker pool.
    private volatile AudioEngineSettings engineSettings = AudioEngineSettings.defaults();

    /**
     * The parallel-dispatch pool, and the scheduler that drives it.
     *
     * <p>Both are {@code volatile} since the story-316 re-review, but NOT
     * for the same reason — a distinction that matters to whoever adds the
     * next reader. Both are WRITTEN only under {@link #lifecycleLock}:
     * {@link #startLocked()} creates them, {@link #tearDownRenderCollaborators()}
     * and {@code startLocked}'s abandon branch clear them.</p>
     *
     * <p>{@code graphScheduler} is the CROSS-LOCK one, exactly like
     * {@link #format}: {@link #handOffMixer(Mixer, Mixer)} reads it inside
     * {@link #graphLock}. Two different locks establish no happens-before
     * between the write and the read, so without {@code volatile} an
     * {@link EngineBinder} rebind could read a stale {@code null} scheduler
     * and publish a mixer with no parallel dispatch installed, or read a
     * stale reference to a scheduler whose pool {@code stop()} has already
     * closed and install THAT on the incoming mixer.</p>
     *
     * <p>{@code workerPool} is never read under {@link #graphLock} at all —
     * {@code handOffMixer} does not touch it. It is {@code volatile} for the
     * OTHER valid reason: the lock-free reader {@link #getWorkerPool()},
     * called from threads that hold neither lock. {@link #getGraphScheduler()}
     * and {@link #getActiveThreadCount()} — which the performance monitor's
     * timer thread polls — read {@code graphScheduler} the same lock-free
     * way, so it has both reasons and the pool has one.</p>
     *
     * <p>Neither field is on the RT path:
     * {@link #processBlock(float[][], float[][], int)} reads neither — it
     * reaches the scheduler only through the mixer it was installed on.</p>
     */
    private volatile AudioWorkerPool workerPool;

    /** @see #workerPool */
    private volatile AudioGraphScheduler graphScheduler;

    /**
     * Set by {@link #stop()} when it could not confirm the render pump had
     * exited, meaning the shared render collaborators — the MIDI renderer,
     * the graph scheduler and the worker pool — were RETAINED instead of torn
     * down (story 316 review). A thread already past {@code stop()}'s
     * {@code running} check and inside
     * {@link #processBlock(float[][], float[][], int)} still uses all three,
     * so closing them there would render through a closed worker pool and a
     * closed MIDI renderer.
     *
     * <p>The deferral is DRAINED at the single choke point every lifecycle
     * path funnels through, {@link #stopPump()} — on both of its
     * confirmed-quiescence returns — so the retained state cannot be owed
     * forever. {@link #start()} carries the last-resort backstop.</p>
     */
    private volatile boolean collaboratorTeardownDeferred;

    /**
     * Creates a new audio engine with the specified format.
     *
     * @param format the audio format configuration
     */
    public AudioEngine(AudioFormat format) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.masterChain = new EffectsChain();
    }

    /**
     * Creates a new audio engine with the specified format and engine
     * settings (story 125 — Multi-Core Parallel Audio Graph Processing).
     *
     * <p>The {@link AudioEngineSettings#workerPoolSize() worker-pool size}
     * is locked at {@link #start()} time; changing it later requires
     * stopping and restarting the engine.</p>
     *
     * @param format         the audio format configuration
     * @param engineSettings the engine settings; must not be null
     */
    public AudioEngine(AudioFormat format, AudioEngineSettings engineSettings) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.masterChain = new EffectsChain();
        this.engineSettings = Objects.requireNonNull(
                engineSettings, "engineSettings must not be null");
    }

    /**
     * Starts the audio engine. If already running, this method is a no-op.
     *
     * <p>Pre-allocates all processing buffers so that the audio callback
     * path is allocation-free.</p>
     *
     * <p>Deferred-teardown backstop (story 316 review): a {@link #stop()}
     * that could not confirm the render pump had exited RETAINED the shared
     * render collaborators — MIDI renderer, graph scheduler, worker pool —
     * rather than close them under a thread possibly still inside
     * {@link #processBlock(float[][], float[][], int)}. Restarting over those
     * retained references would allocate fresh ones and leak them, so the
     * join is retried once here (via {@link #stopPump()}, which drains the
     * deferral when it confirms). If quiescence STILL cannot be confirmed the
     * collaborators are ABANDONED: the references are dropped without being
     * closed, and that is logged at {@link Level#SEVERE}. Leaving one worker
     * pool and one MIDI renderer to the GC is strictly safer than calling
     * {@code close()} on them under a live render thread, which would strand
     * the scheduler's in-flight tasks on a closed pool and pull the synth's
     * handle out mid-block. The retry runs BEFORE the running flag flips
     * because the drain is gated on the engine being stopped.</p>
     *
     * @return {@code true} if the engine was started, {@code false} if already running
     */
    public boolean start() {
        lifecycleLock.lock();
        try {
            return startLocked();
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * The {@link #start()} transition, under {@link #lifecycleLock}. Called
     * directly — never through the public wrapper — by
     * {@link #startAudioOutputLocked(PendingAnnouncements, CaptureRequirement)} and
     * {@link #resumeAudioOutputLocked()}, which already hold the lock.
     */
    private boolean startLocked() {
        if (collaboratorTeardownDeferred) {
            stopPump(); // retry join; drains the deferral when it confirms
            if (collaboratorTeardownDeferred) {
                LOG.severe("Engine start: the render pump retained by the previous stop has"
                        + " still not exited, so the retained MIDI renderer, graph scheduler"
                        + " and worker pool are ABANDONED to the GC rather than closed —"
                        + " closing them under a live render thread would strand the"
                        + " scheduler's in-flight tasks on a closed pool and pull the synth"
                        + " handle out from under the block being rendered");
                collaboratorTeardownDeferred = false;
                midiTrackRenderer = null;
                graphScheduler = null;
                workerPool = null;
            }
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        int channels = format.channels();
        int frames = format.bufferSize();

        // Pre-allocate the unified render pipeline (owns mix/track/return buffers)
        renderPipeline = new RenderPipeline(format, MAX_TRACKS, frames);
        renderPipeline.setSampleRateConversionCache(srcCache);
        renderPipeline.setSrcQualityTier(srcQualityTier);

        // Pre-allocate the buffer pool (8 buffers for intermediate processing)
        bufferPool = new AudioBufferPool(8, channels, frames);

        // Pre-allocate intermediate buffers in the master effects chain
        masterChain.allocateIntermediateBuffers(channels, frames);

        // Pre-allocate intermediate buffers for mixer channel insert effects
        Mixer currentMixer = this.graph.mixer();
        if (currentMixer != null) {
            currentMixer.prepareForPlayback(channels, frames);
        }

        // Pre-allocate MIDI track renderer for SoundFont synthesis
        midiTrackRenderer = new MidiTrackRenderer(format.sampleRate(), frames);

        // Story 125: spin up the multi-core graph scheduler and install it
        // on the mixer so independent per-channel insert chains run on the
        // worker pool. A pool size of 1 is treated as a request to disable
        // parallelism — we skip pool/scheduler creation entirely so the
        // mixer falls back to its sequential mixDown path with zero
        // coordination overhead.
        AudioEngineSettings settings = this.engineSettings;
        int poolSize = settings.workerPoolSize();
        if (poolSize > 1) {
            workerPool = new AudioWorkerPool(poolSize);
            graphScheduler = new AudioGraphScheduler(
                    workerPool, MAX_TRACKS, settings.minParallelBlockSize());
            if (currentMixer != null) {
                currentMixer.setGraphScheduler(graphScheduler);
            }
        }

        return true;
    }

    /**
     * Stops the audio engine, first quiescing the render drive: a still
     * {@link StreamState#RUNNING} stream's pump is stopped and joined, the
     * stream is marked {@link StreamState#RELEASE_PENDING}, and the
     * transport's RT-clock claim is released (draining any queued seek).
     * If not running, this method is a no-op.
     *
     * <p>The quiesce happens BEFORE the running flag is cleared — i.e. while
     * {@link #processBlock(float[][], float[][], int)} is still legal — so a
     * pump caught mid-block finishes that block normally instead of throwing
     * on the freshly cleared flag.</p>
     *
     * <p>Why the quiesce exists (story 316 review): when an earlier
     * {@link #stopAudioOutput()} took its {@code !stopPump()} early return
     * (the bounded join timed out), the state stayed {@code RUNNING} with an
     * orphaned pump still alive. Clearing {@code running} without touching
     * that pump killed it on its next {@code processBlock}, while
     * {@link #startAudioOutput()}'s {@code state == RUNNING} early return then
     * refused to ever restart it — permanent silence behind a UI still
     * reporting RUNNING.</p>
     *
     * <p>{@link StreamState#RELEASE_PENDING} — not {@link StreamState#PAUSED}
     * — is the honest post-state: the backend handle is still held and must
     * be released before any new open, {@link #isStreamOpen()} correctly
     * reports {@code false} (output is stopped and not resumable), and
     * {@link #startAudioOutput()} already releases a retained handle and then
     * opens <em>fresh</em> through the ladder — which is exactly what a
     * caller that changed the format while stopped needs. A pause-shaped
     * PAUSED would instead resume a stream opened at the old format.</p>
     *
     * <p>Clearing {@code running} stays unconditional — it is the documented
     * mechanism by which an orphaned pump loop exits ({@code renderLoop}
     * treats an {@link IllegalStateException} raised while
     * {@code !engine.isRunning()} as a lifecycle race and returns quietly),
     * and the {@code RELEASE_PENDING} post-state is what makes the next
     * {@link #startAudioOutput()} fail LOUDLY through
     * {@link #requireQuiescedPump()} instead of wedging into silence. What is
     * NOT unconditional any more is the collaborator teardown (story 316
     * review): a thread already past the {@code running} check and inside
     * {@link #processBlock(float[][], float[][], int)} still uses the MIDI
     * renderer, the graph scheduler and the worker pool, so when the quiesce
     * could not be CONFIRMED those three are retained and
     * {@link #tearDownRenderCollaborators()} is deferred. The deferral is
     * drained at {@link #stopPump()}'s confirmed-quiescence returns — the
     * choke point every lifecycle path already funnels through — and, failing
     * that, {@link #start()} abandons them rather than restart over them.</p>
     *
     * @return {@code true} if the engine was stopped, {@code false} if already stopped
     */
    public boolean stop() {
        PendingAnnouncements announcements = new PendingAnnouncements();
        lifecycleLock.lock();
        try {
            return stopLocked(announcements);
        } finally {
            lifecycleLock.unlock();
            announcements.deliver();
        }
    }

    /**
     * End of the engine's life (story 318): {@linkplain #stop() stops} the
     * engine if it is running, then closes the {@linkplain #meteringTapBus()
     * metering tap bus} — every subscription is disposed and the analysis
     * thread is joined with a bounded timeout. Unlike {@link #stop()} this
     * is terminal for the tap bus: it accepts no attachments afterwards, so
     * an engine that has been shut down must not be started again. Runs
     * outside {@code lifecycleLock}; the bus close itself takes only the
     * bus's own registry lock. Idempotent.
     *
     * <p>The bus close is in a {@code finally}: {@link #stop()} joins the
     * render pump, tears down the render collaborators and delivers
     * announcement callbacks into app code, so a failure there must not
     * strand the {@code daw-metering-analysis} thread — the one resource
     * only this method releases.</p>
     */
    public void shutdown() {
        try {
            stop();
        } finally {
            meteringTapBus.close();
        }
    }

    /**
     * The {@link #stop()} transition, under {@link #lifecycleLock} — the
     * quiesce, the state store, the clock release and the collaborator
     * teardown are ONE transition, so a concurrent open can neither observe
     * a half-stopped engine nor start a second pump over this one's join.
     *
     * @param announcements collects the RT-clock release this stop owes;
     *                      delivered by the caller after the unlock
     */
    private boolean stopLocked(PendingAnnouncements announcements) {
        if (!running.get()) {
            return false;
        }
        // Honest quiescence: with no pump reference held, no thread can be
        // inside processBlock on our behalf; otherwise only a CONFIRMED join
        // proves it.
        boolean pumpQuiesced = this.pump == null;
        if (this.openBackend != null && streamState == StreamState.RUNNING) {
            // Quiesce while processBlock is still legal (see the javadoc).
            boolean joined = stopPump();
            pumpQuiesced = joined;
            streamState = StreamState.RELEASE_PENDING;
            recordClockRelease(announcements);
            if (!joined) {
                LOG.severe("Engine stop: the render pump did not join in time and may"
                        + " still be inside processBlock — the pump reference is kept so"
                        + " the next open re-joins it instead of starting a second render"
                        + " thread; the backend handle is retained for release");
            }
        }
        if (!running.compareAndSet(true, false)) {
            return false;
        }
        if (pumpQuiesced) {
            tearDownRenderCollaborators();
        } else {
            collaboratorTeardownDeferred = true;
            LOG.severe("Engine stop: the MIDI renderer, graph scheduler and worker pool are"
                    + " RETAINED because the render pump's join was not confirmed — a thread"
                    + " already past the running check and inside processBlock would"
                    + " otherwise render through a closed worker pool and a closed MIDI"
                    + " renderer; the teardown is drained by the next confirmed stopPump()");
        }
        return true;
    }

    /**
     * Closes and forgets the render collaborators shared by every
     * {@link #processBlock(float[][], float[][], int)} call — the MIDI
     * renderer and, since story 125, the multi-core graph scheduling state.
     * The scheduler is detached from the mixer FIRST so subsequent
     * {@code mixDown} calls (e.g. via offline export) revert to the
     * single-threaded path even if the mixer reference outlives the engine
     * restart.
     *
     * <p>Extracted from {@link #stop()} (story 316 review) precisely because
     * it must be skippable there: run only once no render thread can still be
     * using these objects — inline when {@code stop()} confirmed the pump's
     * exit, else from {@link #stopPump()} when a later lifecycle call finally
     * confirms it.</p>
     */
    private void tearDownRenderCollaborators() {
        if (midiTrackRenderer != null) {
            midiTrackRenderer.close();
            midiTrackRenderer = null;
        }
        Mixer currentMixer = this.graph.mixer();
        if (currentMixer != null && currentMixer.getGraphScheduler() == graphScheduler) {
            currentMixer.setGraphScheduler(null);
        }
        graphScheduler = null;
        if (workerPool != null) {
            workerPool.close();
            workerPool = null;
        }
    }

    /**
     * Runs a teardown {@link #stop()} deferred, now that {@link #stopPump()}
     * has confirmed no thread can be inside
     * {@link #processBlock(float[][], float[][], int)} any more.
     *
     * <p>Gated on the engine being stopped so the inline call from within
     * {@code stop()} — which runs while {@code running} is still {@code true}
     * — cannot double-fire the teardown, and so a drain can never close the
     * collaborators of a RESTARTED engine.</p>
     */
    private void drainDeferredCollaboratorTeardown() {
        if (collaboratorTeardownDeferred && !running.get()) {
            collaboratorTeardownDeferred = false;
            tearDownRenderCollaborators();
        }
    }

    /**
     * Returns whether the audio engine is currently running.
     *
     * @return {@code true} if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the audio format used by this engine.
     *
     * @return the audio format
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Replaces the audio format. Must only be called while the engine is
     * stopped; a subsequent {@link #start()} will re-allocate buffers to
     * match the new format's channel count and buffer size.
     *
     * <p>Runs under {@link #lifecycleLock} (story 316 re-review). The body is
     * a read-then-write — the running check, the sample-rate comparison
     * against the OLD format, and the store — over a field the whole locked
     * open path consumes: {@link #startLocked()} sizes the render pipeline
     * and buffer pool from it, {@link #openLadder} builds the requested SDK
     * format from it, and the {@link EngineStreamPump} constructor shapes its
     * planes from it. Unlocked, a settings apply could store a new format
     * BETWEEN a concurrent open's ladder walk and its pump construction, and
     * the engine would render a pipeline of one shape into a stream opened
     * for another. The {@code IllegalStateException} contract is unchanged —
     * but it is now CHECKED under the lock rather than racing the
     * {@link #start()} that would invalidate it.</p>
     *
     * <p>Callers that already hold the lock: none today. The lock is
     * reentrant, so a future {@code …Locked} body may call this safely; a
     * PUBLIC caller must not, since re-entering a public wrapper would defer
     * announcements while the lock is still held (which
     * {@link PendingAnnouncements#deliver()} logs at
     * {@link Level#SEVERE}).</p>
     *
     * @param format the new audio format (must not be {@code null})
     * @throws IllegalStateException if the engine is currently running
     */
    public void setFormat(AudioFormat format) {
        lifecycleLock.lock();
        try {
            if (running.get()) {
                throw new IllegalStateException(
                        "Cannot change format while engine is running");
            }
            Objects.requireNonNull(format, "format must not be null");
            // Drop every cached SRC entry when the session sample rate
            // changes — otherwise stale conversions targeting the old rate
            // would be replayed at the new rate (story 126).
            if (this.format == null || this.format.sampleRate() != format.sampleRate()) {
                srcCache.invalidateAll();
            }
            this.format = format;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns the master effects chain applied to the final mix output.
     *
     * @return the master effects chain
     */
    public EffectsChain getMasterChain() {
        return masterChain;
    }

    /**
     * Returns the MIDI track renderer used for SoundFont synthesis, or
     * {@code null} if the engine has not been started.
     *
     * @return the MIDI track renderer
     */
    MidiTrackRenderer getMidiTrackRenderer() {
        return midiTrackRenderer;
    }

    /**
     * Replaces the MIDI track renderer. Package-private for testing — allows
     * tests to inject a custom renderer without reflection.
     *
     * @param renderer the MIDI track renderer to use, or {@code null}
     */
    void setMidiTrackRenderer(MidiTrackRenderer renderer) {
        this.midiTrackRenderer = renderer;
    }

    /**
     * Returns the pre-allocated buffer pool, or {@code null} if the engine
     * has not been started.
     *
     * @return the buffer pool
     */
    public AudioBufferPool getBufferPool() {
        return bufferPool;
    }

    /**
     * Replaces the engine's streaming provision — the requested backend plus
     * its explicit fallback ladder (story 316). Must be called while the
     * engine is stopped.
     *
     * <p>Story 315 review, carried forward — a stream handle the engine
     * still tracks (running, paused, or RETAINED: a close that failed, a
     * close DEFERRED for an open control panel, a close the BACKEND returned
     * from without releasing, or a {@link #stop()} that
     * attempted none at all — see
     * {@link StreamState#RELEASE_PENDING}) belongs to a backend of the
     * <em>outgoing</em> provision. When the incoming provision no longer
     * carries that backend instance, this method first hands that stream
     * back: it joins the render pump, releases the transport's RT clock
     * (which drains any queued seek) and closes the handle, marking the
     * stream {@link StreamState#CLOSED} only when that close SUCCEEDED. A
     * provision that still carries the tracked instance leaves the stream
     * state untouched: a paused stream stays paused and resumable.</p>
     *
     * <p>That hand-back is refused OUTRIGHT — and with it the whole swap —
     * in four cases, all of them an {@link AudioBackendException} and all of
     * them retryable; see
     * {@link #abandonStreamOnOutgoingBackend(AudioBackend, PendingAnnouncements)}
     * for each. The render pump's exit cannot be confirmed (story 316
     * review): closing the outgoing backend under a thread possibly still
     * inside its {@code sink} / {@code awaitSinkCapacity} would race native
     * state. The backend's native control panel is open: closing its handle
     * would free the native state that modal dialog is running on. The
     * close was attempted and FAILED: an unreleasable handle is exactly the
     * case in which re-pointing the engine at another provision would let the
     * next open put a second backend on the same device, so the engine keeps
     * tracking the handle ({@link StreamState#RELEASE_PENDING}) and retries
     * the release on the next start or stop instead of abandoning it. Or the
     * close RETURNED and the backend reported the release DEFERRED
     * ({@link AudioBackend#isReleasePending()}): the handle is just as
     * retained, and reading a quiet return as a release is how an
     * {@code AsioBackend} whose driver shim queued its teardown would slip
     * past the previous case.</p>
     *
     * <p>The swap then fails WHOLE: the incoming provision is never stored,
     * the engine stays pointed at the outgoing provision, and — because the
     * controller installs the provision BEFORE closing the outgoing instances
     * — those instances stay alive too.</p>
     *
     * <p>Closing the outgoing provision's backend <em>instances</em> is the
     * caller's job — the controller owns backend lifecycles.</p>
     *
     * @param provision the new provision, or {@code null} to stream nothing
     *                  (engine-only mode)
     * @throws IllegalStateException  if the engine is currently running
     * @throws AudioBackendException  if a tracked stream must be handed back
     *                                but the render pump has not exited yet,
     *                                the outgoing backend's control panel is
     *                                open, its handle could not be
     *                                released, or its close returned with the
     *                                release still deferred; the whole swap is
     *                                aborted, unapplied and retryable
     */
    public void setStreamingProvision(StreamingProvision provision) {
        PendingAnnouncements announcements = new PendingAnnouncements();
        lifecycleLock.lock();
        try {
            setStreamingProvisionLocked(provision, announcements);
        } finally {
            lifecycleLock.unlock();
            announcements.deliver();
        }
    }

    /**
     * The provision swap, under {@link #lifecycleLock} (story 316
     * re-review): the hand-back of a tracked stream — join, release the
     * clock, close, forget — and the store of the incoming provision must be
     * ONE transition, or a concurrent open could walk the incoming ladder
     * while the outgoing handle is still being closed, putting two backends
     * on one device.
     *
     * <p>That is also why the store is the LAST statement and is not
     * guarded: an {@link AudioBackendException} out of
     * {@link #abandonStreamOnOutgoingBackend(AudioBackend, PendingAnnouncements)}
     * — the pump is not confirmed gone, the outgoing backend's control panel
     * is open, its handle could not be released, or its close returned with
     * the release deferred — propagates straight
     * through this body, so {@code streamingProvision} is never assigned and
     * the engine stays pointed at the outgoing provision. The swap is refused
     * whole rather than applied over an unreleased handle.</p>
     *
     * @param announcements collects the RT-clock release a hand-back owes;
     *                      delivered by the caller after the unlock, on the
     *                      throwing path too
     */
    private void setStreamingProvisionLocked(StreamingProvision provision,
                                             PendingAnnouncements announcements) {
        if (running.get()) {
            throw new IllegalStateException(
                    "Cannot change streaming provision while engine is running");
        }
        AudioBackend outgoing = this.openBackend;
        if (streamState != StreamState.CLOSED && outgoing != null
                && !ladderContains(provision, outgoing)) {
            abandonStreamOnOutgoingBackend(outgoing, announcements);
        }
        this.streamingProvision = provision;
    }

    /**
     * Returns the currently installed streaming provision, or {@code null}
     * when the engine streams nothing.
     */
    public StreamingProvision getStreamingProvision() {
        return streamingProvision;
    }

    private static boolean ladderContains(StreamingProvision provision, AudioBackend backend) {
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

    /**
     * Hands back the stream the engine still tracks on a backend that the
     * incoming provision no longer carries: joins the pump, releases the RT
     * clock, and closes the handle. Only a close that actually SUCCEEDS —
     * and that the backend does not then report as a DEFERRED release —
     * reaches {@link StreamState#CLOSED} and forgets the backend; every
     * other outcome REFUSES the swap and leaves the engine tracking the
     * outgoing handle so it can retry the release later.
     *
     * <p>Four refusals, in the order they are checked, all of them
     * {@link AudioBackendException} out of
     * {@link #setStreamingProvisionLocked(StreamingProvision, PendingAnnouncements)}
     * — which is what aborts the swap WHOLE, since the incoming provision is
     * stored only after this method returns normally:</p>
     * <ol>
     *   <li><strong>The render pump's exit cannot be confirmed</strong>
     *       (story 316 review). Nothing is closed and the state is untouched.
     *       A pump whose bounded join timed out may still be executing
     *       {@link AudioBackend#sink(AudioBlock)} or
     *       {@link AudioBackend#awaitSinkCapacity(long)} on {@code outgoing},
     *       and this method's next act would be to close exactly that
     *       backend: releasing an ASIO upcall arena and its
     *       {@code bufferSwitch} shim, a Java Sound {@code SourceDataLine},
     *       or a PortAudio stream handle underneath a live thread already
     *       inside the native call is a use-after-free, not a logged
     *       inconvenience. Aborting instead leaves the outgoing handle open
     *       and the engine still pointed at it, so a later retry can join
     *       first — the pump reference is deliberately kept (exactly what
     *       {@link #stopPump()} does on a failed join), and the abandoned
     *       loop exits on the cleared {@code running} flag, which is already
     *       clear here because
     *       {@link #setStreamingProvision(StreamingProvision)} refuses to run
     *       while the engine is running.</li>
     *   <li><strong>The backend's native control panel is open</strong>
     *       ({@link #beginControlPanelSession(AudioBackend)}). The close is
     *       DEFERRED, not attempted: it would free the native state the modal
     *       dialog is running on. The state becomes
     *       {@link StreamState#RELEASE_PENDING} with {@code outgoing} still
     *       tracked, and {@link #endControlPanelSession(AudioBackend)} drains
     *       the deferred close when the dialog returns.</li>
     *   <li><strong>The close itself failed.</strong> The state becomes
     *       {@link StreamState#RELEASE_PENDING} with {@code outgoing} still
     *       tracked, and the engine retries the release on the next start or
     *       stop. Abandoning the handle here instead — which is what this
     *       method used to do, on the premise that "no later retry could
     *       reach this backend once the engine points elsewhere" — discarded
     *       the engine's ONLY retry path and then let the next open put a
     *       second backend on the same device.</li>
     *   <li><strong>The close RETURNED, but the backend deferred the actual
     *       release</strong> ({@link AudioBackend#isReleasePending()}, read
     *       through {@link #releaseDeferredBy(AudioBackend)} — story 316
     *       re-review). Indistinguishable from the previous case in every way
     *       that matters here: the handle is still out with {@code outgoing},
     *       so the state becomes {@link StreamState#RELEASE_PENDING} with it
     *       still tracked and the engine retries the release on the next
     *       start or stop. It is reached where the case above is not — an
     *       {@code AsioBackend} whose driver shim queued its teardown behind
     *       an over-budget downcall clears its Java-side fields and returns
     *       from {@code close()} without complaint, so treating a normal
     *       return as a release would let this swap succeed while the ASIO
     *       driver was still holding the device.</li>
     * </ol>
     *
     * <p>The RT-clock release is recorded immediately after the CONFIRMED
     * join — so before every refusal that can follow one, though necessarily
     * after the join refusal itself, which is the one path that touches
     * nothing — exactly as
     * {@link #stopAudioOutputLocked(PendingAnnouncements)} orders it: the
     * pump is provably gone at that point, so the claim is owed on those
     * refusal paths just as much as on the success path — recording it only
     * after a successful close would strand the claim, and any seek queued
     * behind it, whenever the close is deferred or refused. The public
     * wrapper delivers it from a {@code finally}, so it still goes out when
     * this method throws.</p>
     *
     * @param outgoing      the backend whose tracked handle is handed back
     * @param announcements collects the RT-clock release; delivered by the
     *                      caller after the unlock, on the throwing path too
     * @throws AudioBackendException if the render pump has not exited yet, if
     *                               the backend's control panel is open, if
     *                               the handle could not be released, or if
     *                               the close returned with the backend still
     *                               holding it; the swap is refused whole and
     *                               is retryable
     */
    private void abandonStreamOnOutgoingBackend(AudioBackend outgoing,
                                                PendingAnnouncements announcements) {
        if (!stopPump()) {
            throw new AudioBackendException(
                    "Cannot replace the streaming provision: the render pump has not exited"
                            + " yet and may still be inside sink/awaitSinkCapacity on "
                            + outgoing.name() + ", whose handle this swap would close;"
                            + " retry the swap once the pump unblocks");
        }
        // The join is CONFIRMED, so the claim is owed whatever happens to the
        // handle below — including on the three refusal paths, which throw.
        recordClockRelease(announcements);
        if (controlPanelOpenOn(outgoing)) {
            streamState = StreamState.RELEASE_PENDING;
            throw new AudioBackendException(
                    "Cannot replace the streaming provision: " + outgoing.name()
                            + " has its native control panel open, and closing its stream"
                            + " handle would free the native state that modal dialog is"
                            + " running on. The engine retains the handle and releases it"
                            + " when the panel closes; retry the swap then");
        }
        try {
            outgoing.close();
        } catch (RuntimeException closeFailure) {
            // NOT cleared: the engine must keep tracking `outgoing`, because
            // the retained handle's only release path is the engine's own
            // retry on the next start or stop.
            streamState = StreamState.RELEASE_PENDING;
            throw new AudioBackendException(
                    "Cannot replace the streaming provision: the audio stream handle held by "
                            + outgoing.name() + " could not be released. The engine retains"
                            + " the handle and retries the release on the next start or"
                            + " stop; the swap is refused rather than point the engine at a"
                            + " provision it could open a second backend from, beside a"
                            + " backend that may still hold the device",
                    closeFailure);
        }
        if (releaseDeferredBy(outgoing)) {
            // The close RETURNED, and the handle still did not come back:
            // the backend deferred its own release (story 316 re-review).
            // Identical treatment to a close that threw, for an identical
            // reason — `outgoing` may still hold the device, and pointing the
            // engine at a provision it could open a second backend from is
            // precisely what this refusal exists to prevent. NOT cleared,
            // for the same reason as above.
            streamState = StreamState.RELEASE_PENDING;
            throw new AudioBackendException(
                    "Cannot replace the streaming provision: " + outgoing.name()
                            + " returned from close() without releasing the audio stream"
                            + " handle — the release is DEFERRED, so it may still hold the"
                            + " device. The engine retains the handle and retries the"
                            + " release on the next start or stop; the swap is refused"
                            + " rather than point the engine at a provision it could open a"
                            + " second backend from, beside a backend that has not let go");
        }
        streamState = StreamState.CLOSED;
        clearOpenStream();
    }

    /**
     * Returns the PROVISIONED backend — the engine's answer to "which
     * backend should be queried or configured next": the OPEN stream's
     * backend while a stream is open (running or paused), else the
     * provision's first rung's backend (the one the next open will try
     * first — the requested backend when it passed the app layer's gate),
     * else {@code null}.
     *
     * <p>This is deliberately NOT the "active backend" fact (story 316
     * review). Book &sect;2.4 / &sect;5.2's "reported state equals the open
     * stream" governs what a surface may LABEL as active, and the honest
     * query for that is {@link #openStreamBackendName()}, which is empty
     * whenever nothing streams. The else-branch here answers from the
     * installed provision instead, precisely because capability queries,
     * device enumeration and metronome routing must still resolve a backend
     * while the transport is stopped — book &sect;3.2's provisioned row.
     * While a stream IS open the two coincide, so routing that reaches the
     * hardware never addresses a backend other than the streaming one.</p>
     *
     * @return the open stream's backend, the first rung's backend, or
     *         {@code null}
     */
    public AudioBackend getBackend() {
        AudioBackend backend = this.openBackend;
        if (backend != null && isStreamOpen()) {
            return backend;
        }
        StreamingProvision provision = this.streamingProvision;
        return provision != null ? provision.firstRung().backend() : null;
    }

    /**
     * Returns the name of the backend carrying the OPEN stream, or empty
     * when no stream is open (a retained {@link StreamState#RELEASE_PENDING}
     * handle is not an open stream). This is the truth query behind honest
     * "active backend" reporting: it can never name a backend that holds no
     * device.
     *
     * <p>Open means {@link StreamState#RUNNING} <em>or</em>
     * {@link StreamState#PAUSED} — see {@link #isStreamOpen()}. A PAUSED
     * stream renders nothing, but it still owns the device handle and still
     * holds the driver, so it is deliberately named: answering empty there
     * would tell a surface the device is free while the engine is still
     * holding it, which is the opposite of the honesty this query exists
     * for. "Nothing is audible" is a TRANSPORT question, not a backend one.
     * </p>
     */
    public Optional<String> openStreamBackendName() {
        AudioBackend backend = this.openBackend;
        return backend != null && isStreamOpen()
                ? Optional.of(backend.name())
                : Optional.empty();
    }

    /**
     * Returns the device identity of the OPEN stream, or empty when no
     * stream is open. The identity is the winning ladder rung's stable
     * {@link DeviceId}, resolved by the backend on every open (&sect;3.2).
     */
    public Optional<DeviceId> openStreamDevice() {
        DeviceId device = this.openDevice;
        return device != null && isStreamOpen()
                ? Optional.of(device)
                : Optional.empty();
    }

    /**
     * Takes the complete attribution bound to one failed stream-start
     * invocation, consuming that one-shot context.
     *
     * <p>The endpoint and START/RESUME operation were captured under
     * {@link #lifecycleLock}; neither is reconstructed from live stream or
     * provision state here. Matching the exact throwable and confining the
     * context to this lifecycle caller's thread keeps a previous, reentrant,
     * or concurrent failure from being attributed to this caller. All
     * completed contexts on the thread are removed after the lookup, even
     * when the throwable does not match, so a reused exception instance can
     * never revive stale attribution. This method is for immediate
     * lifecycle/UI reporting only and never participates in audio processing.
     * </p>
     *
     * @param failure the exact throwable returned by {@link #startAudioOutput()}
     *                or {@link #startAudioInputOutput()}
     * @return the failure-bound endpoint and operation, or empty when no
     *         endpoint was configured, the throwable belongs to another
     *         caller, or the context was already consumed
     */
    public Optional<StreamStartFailure> takeFailedStreamStart(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        FailedStreamStartContexts contexts = failedStreamStartContexts.get();
        FailedStreamStartContext match = null;
        for (FailedStreamStartContext context : contexts.completed()) {
            if (context.failure() == failure) {
                match = context;
                break;
            }
        }
        contexts.completed().clear();
        if (contexts.active().isEmpty()) {
            failedStreamStartContexts.remove();
        }
        return match != null
                ? Optional.of(new StreamStartFailure(
                        new StreamStartEndpoint(
                                match.attempt().backendName(), match.attempt().device()),
                        match.attempt().operation()))
                : Optional.empty();
    }

    /** Forgets the tracked stream's backend, device and negotiated format. */
    private void clearOpenStream() {
        this.openBackend = null;
        this.openDevice = null;
        this.openSdkFormat = null;
    }

    /**
     * Whether a {@code close()} that has just returned NORMALLY actually gave
     * the stream handle back, or only promised to (story 316 re-review). This
     * is the ONE definition of "that close was not a release" that all SIX
     * engine-owned closes share, so the outcome cannot mean one thing on the
     * ladder walk and another on a stop.
     *
     * <p>A normal return from {@link AudioBackend#close()} is not proof of a
     * release. {@code AsioBackend} can DEFER the release of its driver shim:
     * while the asio-control thread is still executing a call the host
     * abandoned, the shim deliberately keeps its ownership claim and the
     * teardown is queued until that call returns, while the Java-side fields
     * the close already cleared let {@code close()} itself
     * return normally. {@link AudioBackend#isReleasePending()} is how a
     * backend says so. Read that outcome as a release and the engine's very
     * next act — advancing the ladder onto a fallback rung, forgetting the
     * tracked backend, opening a fresh stream — lands beside a driver that
     * may still be acquiring or holding the device: the
     * two-backends-on-one-device outcome every retained-handle guard in this
     * class exists to prevent. A retained handle is a non-release however it
     * came about, so every caller answers a {@code true} here exactly as it
     * answers a close that THREW.</p>
     *
     * <p>Call this only AFTER a {@code close()} that returned normally. A
     * close that threw has already answered the question, and asking a
     * backend that has just failed a teardown to introspect its own state
     * buys nothing.</p>
     *
     * <p>The condition is self-clearing by COMPLETION, not by time — the
     * backend answers {@code false} again once the deferred release actually
     * completes, which is what makes every caller's response a RETRY rather
     * than a permanent refusal; until then it answers {@code true}, and
     * {@code AsioBackend} keeps it {@code true} for as long as its driver has
     * not returned — for the life of the backend once its wait can no longer
     * be re-queued, in which case the retry never succeeds and the handle
     * stays RETAINED. Wherever the engine is holding a TRACKED handle the
     * retry is its own: the handle stays in {@link StreamState#RELEASE_PENDING},
     * and the next start, the next stop or a control-panel drain closes it
     * again and finds it released — or still retained, and stays put.
     * {@link #closeFailedHop(BackendStreamRung, Throwable)} is
     * the one caller for which that is NOT the shape — a failed hop happens
     * mid-open, before {@code openBackend} is assigned, so the engine tracks
     * nothing and there is no {@code RELEASE_PENDING} to drain; the retry
     * there is simply the next open, which walks the ladder again (see
     * {@link #drainDeferredHandleRelease()}, which says the same about the
     * panel-deferred variant of that hop).</p>
     *
     * <h2>Why it GUARDS, and why only against {@link RuntimeException}</h2>
     * <p>It guards because this helper must not become a new way for an SDK
     * call to break a lifecycle path, and the call sites are not all equally
     * exposed. Story 316 re-review, checked site by site:</p>
     * <ul>
     *   <li>{@link #abandonStreamOnOutgoingBackend(AudioBackend,
     *       PendingAnnouncements)} and
     *       {@link #releaseRetainedStreamHandle(AudioBackend)} ask the
     *       question AFTER their own {@code catch} has closed. This is where
     *       the guard actually earns its keep: unguarded, a throw from the
     *       query would leave a swap or an open half-done and escape as
     *       neither an {@link AudioBackendException} nor a release
     *       verdict.</li>
     *   <li>{@link #closeFailedHop(BackendStreamRung, Throwable)},
     *       {@link #startOpenedStream},
     *       {@link #stopAudioOutputLocked(PendingAnnouncements)} and
     *       {@link #drainDeferredHandleRelease()} ask it from INSIDE a
     *       {@code catch} region of their own, which would absorb an
     *       unguarded throw and treat it as a failed close. The explicit
     *       promises some of them carry — that a teardown seam never MASKS
     *       the hop failure it is unwinding, the same of the start failure,
     *       and that no {@code RuntimeException} reaches
     *       {@link #endControlPanelSession(AudioBackend)}'s {@code finally}
     *       caller — would therefore survive without this guard. Here it is
     *       belt and braces, and it buys honest reporting rather than
     *       correctness: a query that failed is logged as a query that
     *       failed, instead of being suppressed onto an unrelated failure as
     *       though the {@code close()} had thrown.</li>
     * </ul>
     * <p>Since the interface is deliberately not sealed, a third-party
     * backend really can throw out of what the contract calls a cheap,
     * non-blocking read, so neither shape is hypothetical.</p>
     *
     * <p>A throw is reported as {@code true} — NOT released — because that is
     * the conservative answer to the only question being asked: a backend
     * that cannot say whether it gave the handle back has not shown that it
     * did. {@code true} keeps the handle treated as RETAINED and the release
     * retryable, which costs one deferred close; {@code false} would let the engine
     * forget a handle a driver may still hold, and nothing would ever come
     * back for it. The throwable is LOGGED rather than attached to anything:
     * this helper has no in-flight failure to suppress it onto, and its catch
     * deliberately makes no further SDK call — not even
     * {@link AudioBackend#name()} — because the backend that has just thrown
     * is the last thing to trust for a log message, and whatever it chose to
     * say travels on the throwable. That is
     * {@link #drainDeferredHandleRelease()}'s own rule, applied here so that
     * method keeps it while calling this one.</p>
     *
     * <p>{@link Error} is deliberately NOT caught, which is the established
     * policy rather than a new one: {@link #stopAudioOutputLocked},
     * {@link #releaseRetainedStreamHandle} and
     * {@link #drainDeferredHandleRelease} all catch {@link RuntimeException}
     * only and let an {@code Error} propagate. The two callers an escaping
     * {@code Error} could actually mask — the ladder hop's unwind and the
     * pump start's — call this from INSIDE their existing
     * {@code catch (RuntimeException | Error)} region, so an {@code Error}
     * raised by the query unwinds there exactly as an {@code Error} raised by
     * the close does: suppressed onto the in-flight failure, logged, and
     * treated as a non-release.</p>
     *
     * @param backend the backend whose {@code close()} has just returned
     *                normally
     * @return {@code true} when the handle was NOT given back — the backend
     *         deferred the release, or could not say — so the caller must
     *         treat this close exactly as it treats one that failed;
     *         {@code false} when the handle really is released
     */
    private static boolean releaseDeferredBy(AudioBackend backend) {
        try {
            return backend.isReleasePending();
        } catch (RuntimeException queryFailure) {
            LOG.log(Level.WARNING,
                    "A backend could not say whether its close released the audio stream"
                            + " handle, so the handle is treated as RETAINED: the engine"
                            + " keeps tracking it and retries the release rather than open"
                            + " a second backend on a device this one may still hold",
                    queryFailure);
            return true;
        }
    }

    // ── Audio output stream lifecycle ────────────────────────────────────────
    //
    // The backend stream's lifecycle is one volatile StreamState (see the enum
    // javadoc for the state machine and for which transitions claim or release
    // the transport's RT clock). Lifecycle methods run on non-RT threads only
    // (controller / UI thread, device-event thread) — never from the audio
    // callback or the render pump.

    /**
     * Starts audio output: walks the installed {@link StreamingProvision}'s
     * fallback ladder, opens the first rung whose backend accepts its device
     * and negotiated format, and starts the engine-owned render pump that
     * drives {@link #processBlock} at the device's buffer rate.
     *
     * <p>Per rung, the engine calls
     * {@link AudioBackend#negotiateFormat(com.benesquivelmusic.daw.sdk.audio.AudioFormat)}
     * and passes the negotiated format to
     * {@link AudioBackend#open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int, CaptureRequirement)}
     * under {@link CaptureRequirement#OPTIONAL} (story 316 review): a
     * playback open that produces no capture at all is a SUCCESS, which is
     * the historical contract and the right one — a playback-only interface
     * has no inputs to offer and must still open. Only
     * {@link #startAudioInputOutput()} asks for
     * {@link CaptureRequirement#REQUIRED}, so nothing on this path changed
     * when that requirement was introduced.
     * Only the BIT DEPTH may be renegotiated: a rung that returns a different
     * sample rate or channel count is treated as a failed hop (story 316
     * review — see {@link #requireRenderableNegotiation}), because the engine
     * renders through one pipeline shaped by its own format.
     * Every FAILED hop publishes a {@link BackendFallbackEvent} on the
     * {@link com.benesquivelmusic.daw.core.event.EventBusPublisher EventBus}
     * (non-RT, on this caller's thread) so requested &ne; active is always a
     * visible fact; when every rung fails, the FIRST rung's exception — the
     * requested backend's failure, the actionable one — is rethrown.</p>
     *
     * <p>The walk does not always reach the last rung. A rung that reached
     * {@link AudioBackend#open(DeviceId,
     * com.benesquivelmusic.daw.sdk.audio.AudioFormat, int, CaptureRequirement)}
     * and could not then give its handle back ABANDONS the walk (see
     * {@link #openLadder}): it may still hold the device, and a fallback
     * rung opened beside it would be the second backend on one device
     * this ladder exists to prevent. Its hops are still published, naming
     * {@code "none"} active, and the failure that propagates is a new
     * {@link AudioBackendException} carrying the hop failure as its cause.
     * A rung refused BEFORE {@code open} — the ASIO rung on a host whose
     * {@code asioshim} lacks the story-311 streaming symbols, say — holds
     * no device and still falls through to the next rung.</p>
     *
     * <p>Ordering of the open (story 316 review): the ladder walk opens the
     * winning rung's handle, {@link #startOpenedStream} then starts the
     * render pump on it, and only AFTER that start returns are the failed
     * hops' events published naming the winner as active — an event must
     * never name as active a stream whose pump never started. When the pump
     * start fails, {@link #startOpenedStream} has already given the handle
     * back, so the same causes are published naming {@code "none"} before
     * the failure is rethrown: the fallbacks still happened, and requested
     * &ne; active must always leave a published event. The
     * {@link StreamOpenListener} fires last of all.</p>
     *
     * <p>If the stream was previously paused via {@link #pauseAudioOutput()},
     * this resumes it (a fresh pump on the same open backend) without
     * re-opening the stream. If an earlier stop left a backend holding an
     * unreleasable stream handle (see {@link StreamState#RELEASE_PENDING}),
     * that close is retried first and the method fails — without opening a
     * second stream — when the handle still cannot be released.</p>
     *
     * <p>If no provision is installed, the open is refused before the engine
     * starts. Playback without a hardware callback has no clock and therefore
     * cannot be represented honestly as PLAYING; recording additionally has
     * no capture source. Both callers receive an actionable
     * {@link AudioBackendException} instead of a normally-returning silent
     * engine (story 317).</p>
     *
     * <p>The whole transition runs under {@link #lifecycleLock} (story 316
     * re-review), so two callers — a Play from the FX thread and a reopen
     * from a device-event worker, say — can never both observe
     * {@link StreamState#CLOSED}, open two rungs and orphan one of them. The
     * {@link StreamOpenListener} and the failed hops' events are delivered
     * only AFTER that lock is released; see {@link PendingAnnouncements}.</p>
     *
     * @throws AudioBackendException if no streaming provision is installed,
     *                               every ladder rung failed to open (the
     *                               first rung's failure), or a rung that had
     *                               reached {@code open} could not give its
     *                               handle back and the walk was abandoned,
     *                               or a previously retained stream handle
     *                               still cannot be released — including
     *                               because that backend's native control
     *                               panel is open (see
     *                               {@link #beginControlPanelSession(AudioBackend)})
     *                               and including because its close RETURNED
     *                               with the backend still holding the handle
     *                               ({@link AudioBackend#isReleasePending()}),
     *                               or a previous render pump has
     *                               not exited yet (see
     *                               {@link #requireQuiescedPump()} — this
     *                               open would otherwise put a SECOND thread
     *                               into the single shared render pipeline)
    */
    public void startAudioOutput() {
        beginStreamStartInvocation();
        try {
            PendingAnnouncements announcements = new PendingAnnouncements();
            lifecycleLock.lock();
            try {
                try {
                    startAudioOutputLocked(announcements, CaptureRequirement.OPTIONAL);
                } catch (RuntimeException | Error failure) {
                    bindFailedStreamStart(failure);
                    throw failure;
                }
            } finally {
                lifecycleLock.unlock();
                // Outside the lock, on EVERY path — a failed open owes its
                // fallback events just as a successful one owes the listener.
                announcements.deliver();
            }
        } finally {
            // AFTER announcement delivery: those callbacks may re-enter this
            // method on the same thread, and that nested invocation needs its
            // own frame without clearing the outer failure context.
            finishStreamStartInvocation();
        }
    }

    /**
     * The whole open transition, under {@link #lifecycleLock} (story 316
     * re-review): the state read, the join retry, the retained-handle
     * release, the ladder walk, the four field stores and the pump start are
     * ONE critical section, so two callers can never both see
     * {@link StreamState#CLOSED} and open two rungs onto one device.
     *
     * <p>Nothing here calls out to the application. The failed hops'
     * {@link BackendFallbackEvent}s and the {@link StreamOpenListener} are
     * RECORDED on {@code announcements} at exactly the points the old code
     * published them, and delivered by {@link #startAudioOutput()} once the
     * lock is released — see {@link PendingAnnouncements}.</p>
     *
     * <p>The ONE seam both entry points share (story 316 review):
     * {@link #startAudioOutput()} asks for
     * {@link CaptureRequirement#OPTIONAL} and
     * {@link #startAudioInputOutputLocked(PendingAnnouncements)} asks for
     * {@link CaptureRequirement#REQUIRED}. Everything below behaves
     * identically except in each rung's post-{@code open} verification inside
     * {@link #openLadder(StreamingProvision, PendingAnnouncements,
     * CaptureRequirement)}, where {@code REQUIRED} fails instead of
     * succeeding into a stream that could never record. No provision is a
     * failure for both directives: neither playback nor recording may claim a
     * rolling state without a callback. The two
     * shortcuts above them — the {@link StreamState#RUNNING} early return and
     * the {@link StreamState#PAUSED} resume — open nothing, so there is
     * nothing for them to verify; they are unreachable under
     * {@code REQUIRED} anyway, because
     * {@link #startAudioInputOutputLocked(PendingAnnouncements)} closes the
     * previous stream first and refuses to continue unless the state really
     * became {@link StreamState#CLOSED}.</p>
     *
     * @param announcements collects the facts this open owes the outside
     *                      world; delivered by the caller after the unlock
     * @param capture       whether an opened rung must provide capture
     *                      ({@link CaptureRequirement#REQUIRED}) or may be
     *                      playback-only ({@link CaptureRequirement#OPTIONAL})
     */
    private void startAudioOutputLocked(PendingAnnouncements announcements,
                                        CaptureRequirement capture) {
        StreamState state = this.streamState;
        if (state == StreamState.RUNNING) {
            return; // already running
        }
        StreamingProvision provision = null;
        if (state == StreamState.PAUSED) {
            AudioBackend pausedBackend = this.openBackend;
            DeviceId pausedDevice = this.openDevice;
            if (pausedBackend != null && pausedDevice != null) {
                rememberStreamStartAttempt(pausedBackend.name(), pausedDevice,
                        StreamStartFailure.Operation.RESUME);
            }
        } else {
            // This is the one provision snapshot the invocation uses. Bind its
            // requested endpoint before any quiescence/release/engine/open
            // failure can escape, so a later reprovision cannot rewrite the
            // diagnostic history of this call.
            provision = this.streamingProvision;
            rememberRequestedStreamStartAttempt(provision);
        }
        requireQuiescedPump();
        if (state == StreamState.PAUSED) {
            resumeAudioOutputLocked(announcements);
            return;
        }

        AudioBackend retained = this.openBackend;
        if (state == StreamState.RELEASE_PENDING && retained != null) {
            // Backends refuse open() while they still hold a handle, so the
            // retained one must go before a fresh open.
            releaseRetainedStreamHandle(retained);
        }

        if (provision == null) {
            String purpose = capture == CaptureRequirement.REQUIRED
                    ? "recording" : "playback";
            throw new AudioBackendException(
                    "Cannot open a " + purpose + " stream: no audio backend is configured,"
                            + " so no device can be opened. Select an audio device in Audio"
                            + " Settings before " + purpose);
        }

        // Pre-allocate render collaborators before opening the device. If
        // this call started the engine, every failed open owns the matching
        // rollback; a previously running engine is deliberately preserved.
        boolean startedHere = startEngineForStream(announcements);
        OpenedRung opened;
        try {
            opened = openLadder(provision, announcements, capture);
        } catch (RuntimeException | Error openFailure) {
            rollbackEngineStart(startedHere, announcements, openFailure);
            throw openFailure;
        }
        // An opened fallback is now the endpoint whose pump is about to
        // start. It supersedes the requested endpoint for a pump-start
        // failure, even though unwind may immediately clear live stream state.
        rememberStreamStartAttempt(opened.backendName(), opened.rung().device(),
                StreamStartFailure.Operation.START);
        this.openBackend = opened.rung().backend();
        this.openDevice = opened.rung().device();
        this.openSdkFormat = opened.negotiatedFormat();
        try {
            startOpenedStream(opened.rung().backend(), opened.negotiatedFormat(),
                    announcements);
        } catch (RuntimeException | Error startFailure) {
            // The rung's handle is already unwound (or RELEASE_PENDING); no
            // stream became active, so the carried fallbacks name "none"
            // rather than the rung that never started (story 316 review).
            announcements.fallbacks(provision, opened.failedHopCauses(), "none", "none");
            rollbackEngineStart(startedHere, announcements, startFailure);
            throw startFailure;
        }
        // Only now is the winner the active stream, so only now may the
        // failed hops' events name it (story 316 review).
        announcements.fallbacks(provision, opened.failedHopCauses(),
                opened.rung().backend().name(), opened.rung().device().name());

        LOG.info("Audio output started via " + opened.rung().backend().name()
                + " (device: " + opened.rung().device().name() + ")");

        // Announce the WINNER, not the requested rung (story 316 review).
        // Last, because only a stream that actually started is an open.
        announcements.streamOpened(opened.rung().backend(), opened.rung().device());
    }

    /** Starts stream-owned engine state and rolls back a partial preparation failure. */
    private boolean startEngineForStream(PendingAnnouncements announcements) {
        boolean wasRunning = running.get();
        try {
            return startLocked();
        } catch (RuntimeException | Error startFailure) {
            // startLocked publishes running=true before it allocates and
            // prepares every render collaborator. If a later preparation
            // throws, ownership is visible from the before/after state even
            // though startLocked never returned its boolean result.
            boolean startedBeforeFailure = !wasRunning && running.get();
            rollbackEngineStart(startedBeforeFailure, announcements, startFailure);
            throw startFailure;
        }
    }

    /** Stops only the engine allocation owned by a failed open or resume. */
    private void rollbackEngineStart(boolean startedHere,
                                     PendingAnnouncements announcements,
                                     Throwable failure) {
        if (!startedHere) {
            return;
        }
        try {
            stopLocked(announcements);
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            LOG.log(Level.WARNING,
                    "Audio stream failed and the engine start it triggered could not be"
                            + " rolled back cleanly",
                    rollbackFailure);
        }
    }

    /**
     * Notified when {@link #startAudioOutput()} completes an open, carrying
     * the ladder rung that actually WON.
     *
     * <p>Story 316 review — the app layer subscribes to a backend's
     * {@code deviceEvents()} and remembers one active {@link DeviceId} to
     * drive hot-unplug handling, channel queries and latency overrides. It
     * could only bind that subscription where IT drove the open, but a plain
     * Play calls {@link #startAudioOutput()} straight from the transport
     * controller: when ASIO then failed and PortAudio opened, the app layer
     * stayed subscribed to ASIO and still named the requested device, so
     * every one of those three consumers targeted a backend/device that was
     * not the open stream. This seam moves the binding to the one place that
     * knows the winner.</p>
     */
    @FunctionalInterface
    public interface StreamOpenListener {

        /**
         * Called on the thread that completed the open, after the render
         * pump has started, with the winning rung's backend and device.
         *
         * @param backend the backend now carrying the stream
         * @param device  the device it actually opened
         */
        void streamOpened(AudioBackend backend, DeviceId device);
    }

    /**
     * Installs the listener notified whenever {@link #startAudioOutput()}
     * completes an open (story 316 review). At most one listener; passing
     * {@code null} clears it, which is what a shutting-down app layer does
     * so a dead subscriber is never called back.
     *
     * <p>Deliberately NOT fired by a resume ({@link #startAudioOutput()} on
     * a {@link StreamState#PAUSED} stream): a resume
     * puts a fresh pump on a stream that is still OPEN on the same backend
     * and device, so nothing a listener tracks has changed — firing there
     * would re-subscribe the app layer's device-event consumer for no
     * reason.</p>
     *
     * @param listener the listener, or {@code null} to clear
     */
    public void setStreamOpenListener(StreamOpenListener listener) {
        this.streamOpenListener = listener;
    }

    /**
     * Notifies the {@link StreamOpenListener}, if any. A listener failure is
     * logged and swallowed: the stream IS open and rendering by now, so
     * letting an app-layer callback throw would fail an open that actually
     * succeeded — and unwind nothing, because the pump is already running.
     *
     * <p>Called ONLY from {@link PendingAnnouncements#deliver()}, i.e. after
     * {@link #lifecycleLock} has been released (story 316 re-review). The
     * application's listener re-enters the app layer's own monitor — the
     * audio controller's {@code bindBackendDeviceEvents} is
     * {@code synchronized} — while that same monitor's reconfigure path
     * calls this engine's lifecycle methods, so notifying under the
     * lifecycle lock would close a two-lock cycle and hang the pair. Never
     * call this from inside a {@code …Locked} body.</p>
     */
    private void notifyStreamOpened(AudioBackend backend, DeviceId device) {
        StreamOpenListener listener = this.streamOpenListener;
        if (listener == null) {
            return;
        }
        try {
            listener.streamOpened(backend, device);
        } catch (RuntimeException listenerFailure) {
            LOG.log(Level.WARNING,
                    "Stream-open listener failed after " + backend.name()
                            + " opened device '" + device.name()
                            + "'; the stream is open and rendering regardless",
                    listenerFailure);
        }
    }

    /**
     * What a critical section owes the OUTSIDE WORLD, collected while a lock
     * is held and delivered after it is released (story 316 re-review).
     *
     * <p>Used by BOTH of the engine's locks. The lifecycle transitions under
     * {@link #lifecycleLock} record all three of the seams that leave the
     * engine's own code — the failed hops' {@link BackendFallbackEvent}s go
     * to the {@code EventBus}; the {@link StreamOpenListener} goes straight
     * into the application layer; and the transport's RT-clock release drains
     * a queued seek inline, firing {@link Transport.ChangeKind#POSITION} on
     * observers the application registered. {@link #setGraph(Transport,
     * Mixer, List)}, under {@link #graphLock}, records the third one twice
     * (the outgoing transport's release and, when nothing is driving, the
     * incoming one's). The application's audio controller binds device events
     * from a {@code synchronized} method and drives this engine from that
     * same monitor, so calling ANY of them under either lock gives one thread
     * [engine&nbsp;lock → controller&nbsp;monitor] while the other holds
     * [controller&nbsp;monitor → engine&nbsp;lock]: a guaranteed deadlock.
     * Recording them here and delivering afterwards keeps the transition
     * atomic AND the callbacks lock-free, and it also stops a
     * {@code BLOCK}-strategy event bus with a full buffer from parking a
     * lifecycle thread inside the critical section.</p>
     *
     * <p>Delivery is outside the ENGINE's locks; it is not outside a CALLER's.
     * {@link EngineBinder#bind} and {@link EngineBinder#unbind} call
     * {@code setGraph} while holding their own {@code bindingLock}, so the
     * observers a rebind's release fires still run under that monitor. The
     * engine cannot fix that from here — a caller that wraps a publish in its
     * own lock owns the ordering — and it is materially safer than the
     * inversion this class removes, because {@code bindingLock} is private to
     * {@code EngineBinder} and is never taken by a lifecycle path.</p>
     *
     * <p>The clock release is the one recorded fact that can be SUPERSEDED
     * before it is delivered — a concurrent start may claim the clock in the
     * window — so its delivery re-checks; see
     * {@link AudioEngine#deliverClockRelease(Transport)}.</p>
     *
     * <p>Not thread-safe by design and not required to be: an instance is
     * created, filled and delivered by ONE call on ONE thread.</p>
     */
    private final class PendingAnnouncements {

        private StreamingProvision provision;
        private List<String> failedHopCauses = List.of();
        private String activeBackendName = "none";
        private String activeDeviceName = "none";
        private AudioBackend openedBackend;
        private DeviceId openedDevice;

        /**
         * The transports whose RT-clock release this transition owes, in the
         * order the pre-deferral code performed them.
         *
         * <p>A LIST rather than one slot because {@link #setGraph(Transport,
         * Mixer, List)} can owe two in a single critical section: the
         * outgoing transport's release (handing the claim back) and, when no
         * stream is driving, the incoming transport's (making sure the graph
         * that arrives is not left holding a claim nothing honours). Every
         * lifecycle transition still records at most one.</p>
         */
        private final List<Transport> clockReleases = new ArrayList<>(2);

        /**
         * Records the RT-clock release this transition owes, to be performed
         * once the lock is released — see
         * {@link AudioEngine#recordClockRelease(PendingAnnouncements)}.
         *
         * <p>De-duplicating: the same transport recorded twice would drain
         * twice, and the second drain would fire a {@code POSITION} the first
         * already delivered.</p>
         */
        void clockReleased(Transport transport) {
            if (!clockReleases.contains(transport)) {
                clockReleases.add(transport);
            }
        }

        /**
         * Records the failed hops to publish once the lock is released, with
         * the active endpoint they may honestly name. Recorded at exactly
         * the points the pre-lock code published them, so ordering and
         * content are unchanged.
         */
        void fallbacks(StreamingProvision provision, List<String> causes,
                       String activeBackendName, String activeDeviceName) {
            this.provision = provision;
            this.failedHopCauses = causes;
            this.activeBackendName = activeBackendName;
            this.activeDeviceName = activeDeviceName;
        }

        /** Records the winning rung, whose listener callback fires last. */
        void streamOpened(AudioBackend backend, DeviceId device) {
            this.openedBackend = backend;
            this.openedDevice = device;
        }

        /**
         * Delivers everything recorded, in the order the pre-lock code used:
         * the RT-clock release first (it happened at the transition point,
         * ahead of any event the transition went on to publish), then the
         * fallback events, then the stream-open listener. Never throws — it
         * runs in a {@code finally}, where an escaping exception would
         * REPLACE the lifecycle failure that is already unwinding, hiding the
         * actionable one.
         */
        void deliver() {
            if (lifecycleLock.isHeldByCurrentThread()) {
                // A programming error, not a race: some internal path called
                // a PUBLIC lifecycle wrapper from inside the critical
                // section, so this delivery is about to re-enter the app
                // layer under the lock. Deliver anyway — the facts are owed
                // — but say so loudly.
                LOG.severe("Stream announcements are being delivered while the audio"
                        + " engine's lifecycle lock is still held by this thread; a"
                        + " lifecycle path must call the …Locked methods, never the"
                        + " public wrappers");
            }
            if (Thread.holdsLock(graphLock)) {
                // The graphLock half of the same programming error: a graph
                // mutator delivered from INSIDE its own monitor. Same
                // treatment — deliver the owed facts, but say so loudly.
                LOG.severe("Stream announcements are being delivered while the audio"
                        + " engine's graph lock is still held by this thread; the"
                        + " delivery must sit outside the synchronized block, in the"
                        + " mutator's finally");
            }
            while (!clockReleases.isEmpty()) {
                // Removed BEFORE the call, not after: an observer that throws
                // must not leave the entry behind for a later deliver() on
                // this same record to repeat.
                Transport releasing = clockReleases.remove(0);
                try {
                    deliverClockRelease(releasing);
                } catch (RuntimeException releaseFailure) {
                    LOG.log(Level.WARNING,
                            "The transport's real-time clock claim could not be released;"
                                    + " the stream lifecycle transition itself already"
                                    + " completed",
                            releaseFailure);
                }
            }
            if (provision != null) {
                try {
                    publishFallbackEvents(provision, failedHopCauses,
                            activeBackendName, activeDeviceName);
                } catch (RuntimeException publishFailure) {
                    LOG.log(Level.WARNING,
                            "Backend fallback events could not be published; the stream"
                                    + " lifecycle transition itself already completed",
                            publishFailure);
                }
            }
            if (openedBackend != null && openedDevice != null) {
                notifyStreamOpened(openedBackend, openedDevice);
            }
        }
    }

    /**
     * Guarantees no render pump can still be inside {@link #processBlock}
     * before this open creates another one (story 316 review). Retries the
     * bounded join of a pump whose previous stop timed out — the one case
     * where {@link #stopPump()} deliberately KEEPS the reference — and fails
     * the open outright when it still has not exited.
     *
     * <p>Two live pumps would both call {@link #processBlock}, which renders
     * through the SINGLE pre-allocated {@link RenderPipeline} allocated once
     * in {@link #start()} — one set of mix, per-track and return-bus buffers
     * shared by both threads. The result is interleaved garbage, not a
     * doubled signal, so this must be a hard failure rather than a warning.
     * The retry-join also covers the {@link StreamState#RELEASE_PENDING}
     * release that follows it: the backend is never closed out from under a
     * live pump thread.</p>
     *
     * @throws AudioBackendException if the previous pump has not exited yet
     */
    private void requireQuiescedPump() {
        if (!stopPump()) {
            throw new AudioBackendException(
                    "Cannot start audio output: the previous render pump has not exited yet"
                            + " and may still be inside processBlock; retry once it unblocks");
        }
    }

    /**
     * A ladder rung that opened, with the format it actually opened at and
     * the causes of every hop that failed before it (gate rejections first,
     * then this walk's own, in chronological order).
     *
     * <p>The causes ride along instead of being published inside
     * {@link #openLadder(StreamingProvision, PendingAnnouncements,
     * CaptureRequirement)} (story 316 review): a
     * {@link BackendFallbackEvent} names its active backend, and the rung
     * that opened is not the active stream until
     * {@link #startOpenedStream(AudioBackend,
     * com.benesquivelmusic.daw.sdk.audio.AudioFormat)} has started the render
     * pump on it. Carrying the causes lets {@link #startAudioOutput()}
     * publish only once it knows whether that pump started — naming the rung
     * when it did, or {@code "none"} when the start failed and the handle
     * was given back.</p>
     */
    private record OpenedRung(
            BackendStreamRung rung,
            com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiatedFormat,
            List<String> failedHopCauses,
            String backendName) {

        private OpenedRung {
            failedHopCauses = List.copyOf(failedHopCauses);
            Objects.requireNonNull(backendName, "backendName must not be null");
        }
    }

    /** Raw failure-attribution snapshot safe to create while lifecycleLock is held. */
    private record StreamStartAttempt(
            String backendName,
            DeviceId device,
            StreamStartFailure.Operation operation) {

        private StreamStartAttempt {
            Objects.requireNonNull(backendName, "backendName must not be null");
            Objects.requireNonNull(device, "device must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
        }
    }

    /** Exact-identity diagnostic association for a failed start invocation. */
    private record FailedStreamStartContext(
            Throwable failure, StreamStartAttempt attempt) {

        private FailedStreamStartContext {
            Objects.requireNonNull(failure, "failure must not be null");
            Objects.requireNonNull(attempt, "attempt must not be null");
        }
    }

    /** One active public start invocation, including reentrant starts. */
    private static final class FailedStreamStartFrame {
        private StreamStartAttempt attempt;
        private FailedStreamStartContext context;
    }

    /** Thread-confined active frames plus bounded completed contexts. */
    private static final class FailedStreamStartContexts {
        private final Deque<FailedStreamStartFrame> active = new ArrayDeque<>();
        private final Deque<FailedStreamStartContext> completed = new ArrayDeque<>();

        Deque<FailedStreamStartFrame> active() {
            return active;
        }

        Deque<FailedStreamStartContext> completed() {
            return completed;
        }
    }

    /** Opens an invocation frame without invalidating an active outer call. */
    private void beginStreamStartInvocation() {
        FailedStreamStartContexts contexts = failedStreamStartContexts.get();
        if (contexts.active().isEmpty()) {
            // A new top-level lifecycle occurrence invalidates any failure a
            // previous caller chose not to consume.
            contexts.completed().clear();
        }
        contexts.active().addFirst(new FailedStreamStartFrame());
    }

    /** Completes the current frame after its post-unlock announcements. */
    private void finishStreamStartInvocation() {
        FailedStreamStartContexts contexts = failedStreamStartContexts.get();
        FailedStreamStartFrame frame = contexts.active().pollFirst();
        if (frame == null) {
            failedStreamStartContexts.remove();
            throw new IllegalStateException("no audio stream-start invocation is active");
        }
        if (frame.context != null) {
            contexts.completed().addFirst(frame.context);
            while (contexts.completed().size() > MAX_COMPLETED_STREAM_START_CONTEXTS) {
                contexts.completed().removeLast();
            }
        }
        if (contexts.active().isEmpty() && contexts.completed().isEmpty()) {
            failedStreamStartContexts.remove();
        }
    }

    /** Records the provision endpoint read by a fresh start invocation. */
    private void rememberRequestedStreamStartAttempt(StreamingProvision provision) {
        if (provision != null) {
            rememberStreamStartAttempt(
                    provision.requestedBackendName(), provision.requestedDevice(),
                    StreamStartFailure.Operation.START);
        }
    }

    /** Replaces the active invocation's raw attempt snapshot under lifecycleLock. */
    private void rememberStreamStartAttempt(
            String backendName,
            DeviceId device,
            StreamStartFailure.Operation operation) {
        FailedStreamStartFrame frame =
                failedStreamStartContexts.get().active().peekFirst();
        if (frame == null) {
            throw new IllegalStateException("no audio stream-start invocation is active");
        }
        frame.attempt = new StreamStartAttempt(backendName, device, operation);
    }

    /** Records the concrete ladder endpoint whose start failure may propagate. */
    private void rememberStreamStartAttempt(BackendStreamRung rung) {
        rememberStreamStartAttempt(
                rung.backend().name(), rung.device(), StreamStartFailure.Operation.START);
    }

    /** Binds the exact propagated object to the active attempt while locked. */
    private void bindFailedStreamStart(Throwable failure) {
        FailedStreamStartFrame frame =
                failedStreamStartContexts.get().active().peekFirst();
        if (frame == null) {
            throw new IllegalStateException("no audio stream-start invocation is active");
        }
        if (frame.attempt != null) {
            frame.context = new FailedStreamStartContext(failure, frame.attempt);
        }
    }

    /**
     * Walks the provision's ladder in order. First successful open wins and
     * is returned WITHOUT publishing: the failed-hop causes travel on the
     * {@link OpenedRung} so {@link #startAudioOutput()} can publish them
     * after the pump start settles and the events can honestly name the rung
     * that ended up carrying the stream (story 316 review — an event must
     * never name as active a stream whose pump never started). Only the
     * all-rungs-failed path RECORDS its events here, with the literal
     * {@code "none"}: no stream became active, so those events are already
     * truthful. Recording rather than publishing is what lets the caller
     * deliver them once {@link #lifecycleLock} is released (story 316
     * re-review) — nothing outward-facing may be called from inside this
     * walk.
     *
     * <p>The hop list is SEEDED with
     * {@link StreamingProvision#pendingFailedHopCauses()} (story 316 review):
     * a request the app layer's availability/streaming gate rejected never
     * reaches the ladder, so this walk would record no failed hop for it and
     * a fallback head that opens first try would make {@code requested !=
     * active} a silent substitution on the EventBus seam. Seeding — rather
     * than publishing at gate time — is what lets those events name the rung
     * that actually won, which is only known to the caller.</p>
     *
     * <p>Those pending causes are re-seeded on EVERY open, not consumed by
     * the first one: {@link StreamingProvision} is immutable and nothing
     * clears the list, so a gate-refused request republishes its
     * {@link BackendFallbackEvent} on every Play for the life of that
     * provision — until a reconfigure installs a new one. That is deliberate
     * (the substitution is still true on the tenth open as on the first),
     * but it means the stream is REPETITIVE rather than one-shot. Today the
     * only consumer is {@code JournalEventRecorder}, so the cost is
     * crash-journal noise; anyone adding a UI subscriber must de-duplicate
     * or it will toast on every Play.</p>
     *
     * <p>Each rung is first asked {@link AudioBackend#supportsStreaming()}
     * (story 316 review, engine-side guard): the app layer's availability
     * gate keeps non-streaming backends off the ladder in production, but
     * the engine is the authority that actually opens a stream, and a
     * backend whose streaming path is unavailable on this host — ASIO with
     * an {@code asioshim} that lacks the story-311 symbols, say — would
     * otherwise open an honest-looking handle whose {@code sink} discards
     * every block: silence with nothing in the log. Refusing it inside the
     * per-rung {@code try} makes it an ordinary failed hop — a published
     * {@link BackendFallbackEvent} and a fall-through to the next rung —
     * rather than a silent stream.</p>
     *
     * <p>A rung that FAILED has its handle given back before the walk
     * advances (story 316 re-review). {@link AudioBackend#open(DeviceId,
     * com.benesquivelmusic.daw.sdk.audio.AudioFormat, int)} promises no
     * rollback of a partial acquisition, so a rung that took a native handle
     * and then threw would keep holding the device while the next rung opens
     * it: two backends on one device, which is precisely the
     * no-parallel-stream invariant this ladder exists to protect. See
     * {@link #closeFailedHop(BackendStreamRung, Throwable)} for why that
     * close can never mask — nor be mistaken for — the hop failure.</p>
     *
     * <p>When that release does NOT succeed, the walk is ABANDONED rather
     * than continued. "Does not succeed" is
     * {@link #closeFailedHop(BackendStreamRung, Throwable)} answering
     * {@code false}, and it covers THREE outcomes: the close threw, the close
     * was skipped because that backend's control panel is open, or the close
     * RETURNED and the backend reported that it had not actually given the
     * handle back ({@link AudioBackend#isReleasePending()} — story 316
     * re-review). The third is why a quiet return from {@code close()} is not
     * enough to justify advancing: {@code AsioBackend.open()} can fail while
     * DEFERRING its driver-shim release, and its {@code close()} then returns
     * normally over a shim that still holds its ownership claim. The walk
     * stops in all three — but only for a rung that actually reached
     * {@code open()}, which is what the {@code openAttempted} local tracks.
     * The distinction is the whole point, and it is load-bearing in
     * production:</p>
     * <ul>
     *   <li>A rung whose {@code open()} was ATTEMPTED may hold the device
     *       whatever happened next, so an unreleasable handle on it means a
     *       fallback rung would open BESIDE it. That is the two-streams-on-
     *       one-device bug this ladder exists to prevent, so the walk stops
     *       and this method throws a new {@link AudioBackendException} naming
     *       the rung, with the hop failure as its cause — so when a close was
     *       attempted and threw, that close failure travels too, suppressed
     *       on the cause. A close that RETURNED with the release deferred
     *       carries no exception of its own, so nothing is suppressed and the
     *       cause is simply the hop failure; the log line
     *       {@link #closeFailedHop(BackendStreamRung, Throwable)} writes is
     *       what tells the two apart. The failed hops recorded so far, this
     *       one included, are still recorded for publication naming
     *       {@code "none"} active: nothing became active.</li>
     *   <li>A rung refused BEFORE {@code open()} —
     *       {@link #requireStreamingSupport(BackendStreamRung)} and
     *       {@link #requireRenderableNegotiation} both throw ahead of it —
     *       holds no device, so a NON-release on it is spurious and the
     *       walk falls through to the next rung exactly as it always has.
     *       That is true whichever of the three shapes the non-release takes,
     *       a deferred release included: the rung was never asked for the
     *       device, so whatever its backend is still holding is not this
     *       walk's to wait for. This is the ASIO path: when {@code asioshim}
     *       lacks the story-311
     *       streaming symbols, {@code requireStreamingSupport} refuses that
     *       rung, and that refusal must still reach PortAudio or Java
     *       Sound.</li>
     * </ul>
     *
     * <p>A rung whose {@code open} SUCCEEDS is still refused when the caller
     * asked for {@link CaptureRequirement#REQUIRED} and the stream it opened
     * has no capture channels (story 316 review). That check —
     * {@link #requireCaptureOpened(BackendStreamRung, CaptureRequirement)} —
     * is the ONLY guard that runs after {@code open} rather than before it,
     * because the count is not knowable until the driver has answered, and it
     * is deliberately inside the per-rung {@code try} so a capture-less rung
     * is an ORDINARY failed hop: its handle is given back, a
     * {@link BackendFallbackEvent} carries the reason, and the walk falls
     * through to the next rung. An ASIO head that exposes no inputs should
     * let PortAudio take the recording. Being after {@code open} also means
     * {@code openAttempted} is already {@code true}, so a capture-less rung
     * whose CLOSE fails abandons the walk under the same rule as any other
     * hop that reached {@code open} — it may still hold the device.</p>
     *
     * @param provision     the ladder to walk
     * @param announcements collects the failed hops' events when every rung
     *                      failed, and when a {@link RuntimeException} hop's
     *                      unreleasable handle abandons the walk; an
     *                      {@link Error} hop abandons the walk WITHOUT
     *                      collecting any; delivered by the caller after the
     *                      unlock
     * @param capture       passed to every rung's
     *                      {@link AudioBackend#open(DeviceId,
     *                      com.benesquivelmusic.daw.sdk.audio.AudioFormat,
     *                      int, CaptureRequirement)} as a directive, AND
     *                      verified afterwards against
     *                      {@link AudioBackend#openedInputChannels()} —
     *                      the directive's default body ignores it, so the
     *                      verification is what makes the guarantee hold
     * @return the rung that opened, with its negotiated format and the causes
     *         of every hop that failed before it
     * @throws AudioBackendException a NEW exception, caused by the hop
     *                          failure, when a rung that had reached
     *                          {@code open()} could not give its handle back
     *                          — the walk is ABANDONED rather than continued
     *                          onto a fallback rung
     * @throws RuntimeException the FIRST rung's failure when every rung failed
     *                          — which, under
     *                          {@link CaptureRequirement#REQUIRED}, includes
     *                          every rung that opened without capture
     * @throws Error            a hop's own {@code Error}, after that rung's
     *                          handle has been given back OR the attempt to
     *                          give it back has been reported as failed —
     *                          {@link #closeFailedHop(BackendStreamRung,
     *                          Throwable)} answers {@code false} when the
     *                          close threw, when a control panel made it
     *                          unsafe to attempt, and when it returned with
     *                          the backend still holding the handle, and the
     *                          log records which of the three happened. The
     *                          walk is ABANDONED in every case, which is what
     *                          makes the unreleased case safe to report
     *                          rather than act on; see the catch
     */
    private OpenedRung openLadder(StreamingProvision provision,
                                  PendingAnnouncements announcements,
                                  CaptureRequirement capture) {
        com.benesquivelmusic.daw.sdk.audio.AudioFormat requested =
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(
                        format.sampleRate(), format.channels(), format.bitDepth());
        RuntimeException firstFailure = null;
        // Gate rejections FIRST: they happened before the ladder existed, so
        // publishing them ahead of this walk's own failures keeps the event
        // order the chronological order of the fallbacks.
        List<String> failedHopCauses = new ArrayList<>(provision.pendingFailedHopCauses());
        for (BackendStreamRung rung : provision.ladder()) {
            // Whether this rung's backend was ever asked for the device. The
            // two guards below throw BEFORE open(), so a rung they refuse
            // holds nothing and a failed close on it is spurious; a rung that
            // reached open() may hold the device whatever open() did next.
            boolean openAttempted = false;
            try {
                requireStreamingSupport(rung);
                com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiated =
                        rung.backend().negotiateFormat(requested);
                requireRenderableNegotiation(rung, requested, negotiated);
                openAttempted = true;
                rung.backend().open(rung.device(), negotiated, format.bufferSize(), capture);
                // AFTER the open, inside the same try: the only guard that
                // cannot run earlier, because the capture channel count is
                // not knowable until the driver has answered (story 316
                // review). A refusal here is an ordinary failed hop — the
                // handle goes back through closeFailedHop and the walk
                // continues onto a rung that may have inputs.
                requireCaptureOpened(rung, capture);
                return new OpenedRung(
                        rung,
                        negotiated,
                        failedHopCauses,
                        rung.backend().name());
            } catch (RuntimeException hopFailure) {
                // FIRST, before any bookkeeping: give the rung's handle back
                // so no partial acquisition outlives the hop (story 316
                // re-review).
                boolean released = closeFailedHop(rung, hopFailure);
                if (!released && openAttempted) {
                    // This new abandonment exception is the object that
                    // propagates, so its attribution belongs to THIS terminal
                    // rung even when an earlier ordinary hop failure was
                    // already remembered.
                    rememberStreamStartAttempt(rung);
                    // The rung reached open() and its handle could NOT be
                    // given back, so it may still hold the device. Walking on
                    // would open a fallback rung beside it — two backends on
                    // one device, the exact invariant this ladder exists to
                    // protect. The record is completed first so the published
                    // events still describe every hop that failed.
                    failedHopCauses.add(causeMessage(hopFailure));
                    announcements.fallbacks(provision, failedHopCauses, "none", "none");
                    LOG.log(Level.SEVERE,
                            "Backend ladder hop failed and its handle could not be released: "
                                    + rung.backend().name() + " may still hold device '"
                                    + rung.device().name() + "'. The ladder walk is ABANDONED"
                                    + " rather than continued onto a fallback rung",
                            hopFailure);
                    throw new AudioBackendException(
                            "Cannot open an audio stream: " + rung.backend().name()
                                    + " failed to open device '" + rung.device().name()
                                    + "' and its handle could not be released, so it may"
                                    + " still hold that device. Opening a fallback rung"
                                    + " beside it would put two backends on one device, so"
                                    + " the ladder walk is abandoned; retry once the handle"
                                    + " is released",
                            hopFailure);
                }
                if (firstFailure == null) {
                    firstFailure = hopFailure;
                    // All released hops may fail, in which case this exact
                    // object is rethrown below. The requested endpoint can be
                    // absent from the ladder after the availability gate, so
                    // bind the first concrete rung alongside its exception.
                    rememberStreamStartAttempt(rung);
                }
                failedHopCauses.add(causeMessage(hopFailure));
                LOG.log(Level.WARNING,
                        "Backend ladder hop failed: " + rung.backend().name()
                                + " could not open device '" + rung.device().name() + "'",
                        hopFailure);
            } catch (Error hopError) {
                // An Error leaks a partial acquisition exactly like a
                // RuntimeException does, so the handle is given back the same
                // way — or the failure to give it back is REPORTED, which is
                // the only honest thing to log when closeFailedHop answers
                // false. The walk STOPS here either way (story 316
                // re-review, E4).
                //
                // Closing and rethrowing, rather than closing and walking on,
                // is the deliberate half-measure: the leak this unwind exists
                // for is fixed either way, while continuing would allocate a
                // second rung's native buffers and render pipeline after an
                // OutOfMemoryError, and would bury an InternalError under a
                // fallback that "worked". The ladder's purpose is to route
                // around a DEVICE refusal, and the SDK contract says a
                // backend signals those with AudioBackendException — not with
                // an Error. A rung whose native binding is broken on this
                // host is not an Error case either: the shims capture their
                // own linkage failures and report
                // AudioBackend#supportsStreaming() false, which this walk
                // already treats as an ordinary failed hop.
                //
                // The verdict is KEPT rather than discarded: closeFailedHop
                // answers false when the close threw, when a control panel
                // made it unsafe to attempt, and when the close RETURNED
                // with the backend still holding the handle (all three the
                // two-backends-on-one-device case), and a maintainer reading
                // this SEVERE line is reading it to find out whether the
                // device is free.
                // This exact Error is propagated immediately, so its concrete
                // rung supersedes the requested endpoint and any earlier
                // recoverable hop.
                rememberStreamStartAttempt(rung);
                boolean released = closeFailedHop(rung, hopError);
                LOG.log(Level.SEVERE,
                        "Backend ladder hop failed with an Error: " + rung.backend().name()
                                + " on device '" + rung.device().name() + "'. "
                                + (released
                                        ? "The rung's handle was released"
                                        : "The rung's handle could NOT be released, so it"
                                                + " may still hold that device")
                                + ", and the ladder walk is ABANDONED"
                                + " rather than continued onto a fallback rung",
                        hopError);
                throw hopError;
            }
        }
        // Recorded, not published: the caller delivers it once the lifecycle
        // lock is released (story 316 re-review).
        announcements.fallbacks(provision, failedHopCauses, "none", "none");
        throw firstFailure;
    }

    /**
     * Gives a FAILED ladder rung's handle back before the walk advances, and
     * REPORTS whether that succeeded (story 316 re-review). A backend's
     * {@code open} may acquire a native handle and then throw — the SDK
     * contract promises no rollback — and a rung left holding one would still
     * own the device while the fallback rung opens and renders through it:
     * two live streams on one device. Closing here bounds a partial
     * acquisition to the hop that made it; the {@code boolean} is what lets
     * {@link #openLadder} refuse to advance when the bound could not be
     * placed.
     *
     * <p>The rung may also have failed BEFORE {@code open} was ever called —
     * {@link #requireStreamingSupport(BackendStreamRung)} and
     * {@link #requireRenderableNegotiation} both throw ahead of it — so this
     * closes backends that were never opened. That is safe: every in-tree
     * backend's {@code close()} is guarded and idempotent
     * ({@code AsioBackend} null-guards each shim under its driver lock,
     * {@code JavaxSoundBackend} null-guards its lines,
     * {@code CallbackBackendAdapter} gates the stream teardown on its own
     * {@code open} flag, {@code MockAudioBackend} closes idempotent
     * publishers) — and a rung that only negotiated may still have
     * INITIALIZED its driver, which this is the one chance to give back.
     * A {@code false} from such a rung is SPURIOUS, and this method cannot
     * tell the two apart: it does not know whether {@code open} was reached.
     * {@link #openLadder} does, and pairs this verdict with its own
     * {@code openAttempted} flag before deciding to abandon the walk — which
     * is what keeps a streaming-refused ASIO rung falling through to
     * PortAudio or Java Sound.
     *
     * <p>A close is not even ATTEMPTED while that backend's native control
     * panel is open ({@link #beginControlPanelSession(AudioBackend)}): it
     * would free the native state the modal dialog is running on. The verdict
     * is then {@code false} — the handle really was not released — which is
     * the honest answer and the one that stops the walk from opening a
     * fallback rung beside it.
     *
     * <p>A close that RETURNS is not automatically a release either (story
     * 316 re-review). {@link #releaseDeferredBy(AudioBackend)} asks the
     * backend, and a {@code true} there is reported as the same
     * {@code false} verdict, for the same reason. This is the case the
     * REPORTED finding was about, and it is reachable in production exactly
     * where it hurts most: {@code AsioBackend.open()} is where a driver-shim
     * release gets deferred, so the rung whose {@code open} just failed is
     * the likeliest one to be holding a queued teardown. Its {@code close()}
     * then returns normally — the Java-side fields were already cleared — and
     * before this check the walk read that as an ordinary released hop and
     * opened PortAudio or Java Sound over a device the ASIO driver may still
     * have been acquiring. Nothing is attached to {@code hopFailure} on this
     * path: there is no exception to attach, and the recorded cause must keep
     * describing the OPEN failure.
     *
     * <p>A close that itself fails must never replace the hop failure: it is
     * attached as a {@linkplain Throwable#addSuppressed suppressed}
     * exception on {@code hopFailure} — the very exception the caller may
     * rethrow as {@code firstFailure} or wrap as the abandonment's cause, and
     * whose message {@link #causeMessage(RuntimeException)} records — so the
     * recorded cause and the published {@link BackendFallbackEvent} keep
     * describing the OPEN failure, the actionable one, with the close failure
     * carried alongside rather than in place of it. That property is
     * unchanged; what it no longer implies is that the walk CONTINUES. Not
     * masking the hop failure and not advancing past an unreleased handle are
     * two different guarantees, and this helper now serves both — reporting
     * the release, never rewriting the failure.
     *
     * <p>Both catches are widened to {@code Error} (story 316 re-review, E4).
     * The OUTER one because the leak an {@code Error} leaves behind is the
     * same partial native acquisition a {@code RuntimeException} leaves — see
     * {@link #openLadder} for why that hop still aborts the walk. The INNER
     * one because "a close failure never masks the hop failure" has to hold
     * for every throwable a {@code close()} can raise: an escaping
     * {@code Error} here would REPLACE the actionable open failure with a
     * teardown failure. It is reported as a non-release exactly like a
     * {@code RuntimeException}, because an unreleased handle is unreleased
     * whatever the close threw.</p>
     *
     * @param rung       the rung whose hop failed
     * @param hopFailure the hop failure being recorded; a close failure is
     *                   suppressed onto it
     * @return {@code true} when the rung's handle was released, {@code false}
     *         in three cases — the close threw, it was skipped for an open
     *         control panel, or it returned with the backend still holding
     *         the handle
     */
    private boolean closeFailedHop(BackendStreamRung rung,
                                   Throwable hopFailure) {
        if (controlPanelOpenOn(rung.backend())) {
            LOG.severe("Backend ladder hop failed and its handle is deliberately RETAINED: "
                    + rung.backend().name() + " has its native control panel open, and"
                    + " closing it would free the native state that modal dialog is running"
                    + " on. The rung may still hold device '" + rung.device().name() + "'");
            return false;
        }
        try {
            rung.backend().close();
            if (releaseDeferredBy(rung.backend())) {
                // The close RETURNED and the handle still did not come back.
                // Nothing is attached to hopFailure: there is no exception to
                // attach, and the recorded cause must keep describing the
                // OPEN failure.
                LOG.severe("Backend ladder hop failed and its handle is deliberately"
                        + " RETAINED: " + rung.backend().name() + " returned from close()"
                        + " with the release DEFERRED — its driver teardown is queued"
                        + " behind a downcall that has not returned. The rung may still"
                        + " hold device '" + rung.device().name() + "'");
                return false;
            }
            return true;
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != hopFailure) {
                // addSuppressed(self) throws, which would REPLACE the hop
                // failure with an IllegalArgumentException from this unwind.
                hopFailure.addSuppressed(closeFailure);
            }
            LOG.log(Level.WARNING,
                    "Backend ladder hop failed and its handle could not be released: "
                            + rung.backend().name() + " may still hold device '"
                            + rung.device().name() + "'",
                    closeFailure);
            return false;
        }
    }

    /**
     * Refuses a rung whose backend cannot stream on this host (story 316
     * review). {@link AudioBackend#supportsStreaming()} is the backend's own
     * word that {@code sink} reaches the device — for ASIO it is now a live
     * probe of the native shim's streaming symbols rather than a constant —
     * so the engine, which is the authority that opens streams, asks it
     * here rather than trusting the app layer's gate to have asked first.
     *
     * <p>Called inside {@link #openLadder(StreamingProvision,
     * PendingAnnouncements, CaptureRequirement)}'s per-rung
     * {@code try} BEFORE negotiation, so the refusal costs the rung nothing
     * and fails like any refused open: the ladder falls through and the hop
     * publishes a {@link BackendFallbackEvent} carrying this message.</p>
     *
     * @throws AudioBackendException if the backend reports no streaming path
     */
    private static void requireStreamingSupport(BackendStreamRung rung) {
        if (rung.backend().supportsStreaming()) {
            return;
        }
        throw new AudioBackendException(
                rung.backend().name() + " cannot stream on this host: its streaming"
                        + " path is unavailable, so the ladder skipped it");
    }

    /**
     * Rejects a negotiated format the engine cannot actually render (story
     * 316 review). Only the BIT DEPTH may be renegotiated today: the engine
     * renders through a SINGLE {@link RenderPipeline} allocated in
     * {@link #start()} for {@code format.channels()} planes at
     * {@code format.sampleRate()}, and {@link EngineStreamPump} interleaves
     * exactly that shape. A negotiated format with a different channel count
     * would therefore make every {@link AudioBackend#sink(AudioBlock)} reject
     * the block, and a different sample rate would merely RELABEL
     * un-resampled audio — audible as a pitch shift, with nothing in the log.
     * Bit depth is safe because it is the backend's own encoding concern:
     * the pump always hands over normalized floats.
     *
     * <p>Called inside {@link #openLadder(StreamingProvision,
     * PendingAnnouncements, CaptureRequirement)}'s per-rung
     * {@code try} on purpose, so a violating rung fails like any refused
     * open: the ladder falls through to the next rung and the hop publishes a
     * {@link BackendFallbackEvent} carrying this message as its cause, which
     * makes the mis-negotiation a visible fact rather than silent breakage.
     * Story 317 broadens the backend's concrete encoding negotiation, but
     * render-shape conversion through resampling or re-planing remains
     * deliberately unsupported.</p>
     *
     * @throws AudioBackendException if the sample rate or channel count differs
     */
    private static void requireRenderableNegotiation(
            BackendStreamRung rung,
            com.benesquivelmusic.daw.sdk.audio.AudioFormat requested,
            com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiated) {
        boolean channelsDiffer = negotiated.channels() != requested.channels();
        boolean rateDiffers =
                Double.compare(negotiated.sampleRate(), requested.sampleRate()) != 0;
        if (!channelsDiffer && !rateDiffers) {
            return;
        }
        String differed = channelsDiffer && rateDiffers
                ? "the channel count and the sample rate differ"
                : channelsDiffer ? "the channel count differs" : "the sample rate differs";
        throw new AudioBackendException(
                "Backend " + rung.backend().name() + " negotiated a format the engine"
                        + " cannot render: requested " + requested + " but negotiated "
                        + negotiated + " — " + differed
                        + "; only the bit depth may be renegotiated today");
    }

    /**
     * Refuses a rung whose {@code open} SUCCEEDED but produced no capture
     * channels, when the caller asked for {@link CaptureRequirement#REQUIRED}
     * (story 316 review). The third engine-side per-rung guard, and the one
     * that closes the silent take.
     *
     * <h2>Why it runs AFTER open, not before</h2>
     * <p>Its two siblings —
     * {@link #requireStreamingSupport(BackendStreamRung)} and
     * {@link #requireRenderableNegotiation} — can answer from what the
     * backend already knows, so they run ahead of {@code open()} and cost the
     * rung nothing. This one cannot: the number of capture channels a stream
     * really opened with is not knowable until the driver has answered.
     * {@link AudioDeviceInfo#maxInputChannels()} is an ENUMERATION capability
     * (and may legitimately be {@link AudioDeviceInfo#CHANNEL_COUNT_UNKNOWN},
     * "offered, but not knowable until the driver is loaded"), and the
     * requested channel count is a wish; only
     * {@link AudioBackend#openedInputChannels()} is the outcome. So the rung
     * is opened, asked, and — when the answer is zero — closed again. Paying
     * for one open-and-close on a capture-less head is the price of never
     * shipping a silent take.</p>
     *
     * <h2>Why the ENGINE checks rather than the backend</h2>
     * <p>{@link CaptureRequirement} is a DIRECTIVE, and
     * {@link AudioBackend#open(DeviceId,
     * com.benesquivelmusic.daw.sdk.audio.AudioFormat, int, CaptureRequirement)}
     * ships a {@code default} body that ignores it entirely and delegates to
     * the three-argument {@code open}. A backend that never overrides it
     * therefore honours nothing, and the interface is deliberately unsealed,
     * so out-of-tree backends exist that this tree has never seen. A REQUIRED
     * open returning normally proves nothing on its own; the engine is the
     * authority that actually opens streams, so the engine verifies the
     * outcome. Backends that CAN detect the degradation still override the
     * directive, because failing early attaches the precise native cause and
     * never grabs the device output-only just to have it rejected a moment
     * later — but that is an optimisation on top of this check, never a
     * substitute for it.</p>
     *
     * <h2>Why a throw, and what the throw buys</h2>
     * <p>Called inside {@link #openLadder(StreamingProvision,
     * PendingAnnouncements, CaptureRequirement)}'s per-rung {@code try},
     * immediately after {@code open} returns, so the existing machinery does
     * the rest for free:
     * {@link #closeFailedHop(BackendStreamRung, Throwable)} gives the rung's
     * handle back before the walk advances, so the device is released rather
     * than held by a stream nobody wants; {@code openAttempted} is already
     * {@code true} at this point, so a rung whose CLOSE then fails abandons
     * the walk instead of opening a fallback beside a backend that may still
     * hold the device; and otherwise the walk simply falls through to the
     * next rung. That fall-through is the desired behaviour, not a
     * consolation: an ASIO head whose driver exposes no inputs should let
     * PortAudio take the recording, and the refusal is published as an
     * ordinary {@link BackendFallbackEvent} carrying this message.</p>
     *
     * <p>Under {@link CaptureRequirement#OPTIONAL} this is a no-op, which is
     * what keeps the playback contract byte-for-byte unchanged: a
     * playback-only interface, or an ASIO4ALL with only speakers enabled,
     * must still open.</p>
     *
     * @param rung    the rung whose {@code open} has just returned
     * @param capture what the caller asked for; {@link
     *                CaptureRequirement#OPTIONAL} makes this a no-op
     * @throws AudioBackendException if {@code capture} is
     *                               {@link CaptureRequirement#REQUIRED} and
     *                               the backend reports no capture channels
     *                               on the stream it just opened
     */
    private static void requireCaptureOpened(BackendStreamRung rung,
                                             CaptureRequirement capture) {
        if (capture != CaptureRequirement.REQUIRED) {
            return;
        }
        int openedInputChannels = rung.backend().openedInputChannels();
        if (openedInputChannels > 0) {
            return;
        }
        throw new AudioBackendException(
                "Backend " + rung.backend().name() + " opened device '"
                        + rung.device().name() + "' with no capture channels ("
                        + openedInputChannels + "), so this stream could never record."
                        + " A recording open must not degrade to output-only — that is"
                        + " what produces a silent take — so the rung is refused and its"
                        + " handle given back");
    }

    /**
     * Publishes one {@link BackendFallbackEvent} per failed ladder hop.
     * Non-RT context — runs on the lifecycle caller's thread.
     */
    private static void publishFallbackEvents(StreamingProvision provision,
                                              List<String> failedHopCauses,
                                              String activeBackend,
                                              String activeDevice) {
        for (String cause : failedHopCauses) {
            EventBusPublisher.publish(new BackendFallbackEvent(
                    provision.requestedBackendName(),
                    // The provision's carried requested device, NOT the first
                    // rung's: when the app layer's availability/streaming gate
                    // rejected the requested backend, the ladder already starts
                    // on a FALLBACK rung, so the first rung's device belongs to
                    // that fallback and would be paired with the user's
                    // requested backend name here (story 316 review).
                    provision.requestedDevice().name(),
                    activeBackend,
                    activeDevice,
                    cause));
        }
    }

    private static String causeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null ? message : failure.toString();
    }

    /**
     * Starts the render pump on the backend a ladder rung has just opened,
     * claiming the transport's RT clock <em>before</em> the pump start and
     * unwinding both the claim and the handle when the start fails.
     *
     * <p>Story 315 review, carried onto the pump — the pump may call
     * {@link #processBlock} before its start call returns, so claiming
     * afterwards would leave a window in which the render thread advanced
     * the transport while UI seeks still applied inline. Claiming first
     * closes that window: a seek issued in it is queued and applied by the
     * first rendered block. Should the start then fail, the claim is
     * released first — which drains that queued seek inline instead of
     * stranding it — and the handle is given back best-effort: a close that
     * also fails leaves the stream {@link StreamState#RELEASE_PENDING} and
     * is attached as a suppressed exception, never masking the start
     * failure. The close is SKIPPED entirely, leaving the same
     * {@code RELEASE_PENDING} with the backend still tracked, while that
     * backend's native control panel is open
     * ({@link #beginControlPanelSession(AudioBackend)}) — closing the handle
     * would free the native state the modal dialog is running on, and
     * {@link #endControlPanelSession(AudioBackend)} drains it instead. A
     * close that RETURNS is not read as a release either (story 316
     * re-review): when {@link #releaseDeferredBy(AudioBackend)} reports the
     * backend still holding the handle, the unwind leaves that same
     * {@code RELEASE_PENDING} with the backend TRACKED rather than forgetting
     * it, because a handle nobody tracks is a handle nobody retries. The
     * start failure is rethrown unchanged on every one of those paths. The
     * unwind covers <em>every</em> throwable from the start
     * call — a start that escaped it would leave the engine {@code RUNNING}
     * with the clock claimed and nothing driving, a permanent wedge.</p>
     *
     * @param backend          the backend whose handle the ladder just opened
     * @param negotiatedFormat the format that rung actually opened at
     * @param announcements    collects the RT-clock release a failed start
     *                         owes; delivered by the caller after the unlock
     * @throws RuntimeException the pump-start failure, rethrown as-is after
     *                          the unwind (an {@link Error} unwinds the same
     *                          way)
     */
    private void startOpenedStream(AudioBackend backend,
                                   com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiatedFormat,
                                   PendingAnnouncements announcements) {
        streamState = StreamState.RUNNING;
        claimTransportClock();
        try {
            EngineStreamPump newPump =
                    new EngineStreamPump(backend, this, format, negotiatedFormat);
            newPump.start();
            this.pump = newPump;
        } catch (RuntimeException | Error startFailure) {
            // The pump is not running, and the backend still holds the
            // handle — until the close below both RUNS and actually gives it
            // back. Every other outcome (skipped for an open panel, thrown,
            // or returned with the release deferred) leaves this state
            // standing and the engine tracking the handle for a retry.
            streamState = StreamState.RELEASE_PENDING;
            recordClockRelease(announcements);
            if (controlPanelOpenOn(backend)) {
                // Skip the close entirely: it would free the native state the
                // modal control panel is running on. RELEASE_PENDING is
                // already set above, so the engine keeps tracking the handle
                // and endControlPanelSession drains the close.
                LOG.severe("Audio stream failed to start and its handle is deliberately"
                        + " RETAINED: " + backend.name() + " has its native control panel"
                        + " open and closing the handle would free the native state that"
                        + " modal dialog is running on");
            } else {
                try {
                    backend.close();
                    if (releaseDeferredBy(backend)) {
                        // The close RETURNED and the handle still did not
                        // come back. RELEASE_PENDING is already set above and
                        // the backend stays TRACKED — deliberately no
                        // clearOpenStream() — so the retry path survives,
                        // exactly as when the close throws.
                        LOG.severe("Audio stream failed to start and its handle was NOT"
                                + " released: " + backend.name() + " returned from close()"
                                + " with the release DEFERRED, so it may still hold the"
                                + " device. The engine retains the handle; the release is"
                                + " retried by the next start or stop");
                    } else {
                        streamState = StreamState.CLOSED;
                        clearOpenStream();
                    }
                } catch (RuntimeException | Error closeFailure) {
                    // Widened with closeFailedHop's inner catch (story 316
                    // re-review, E4): this method's javadoc promises the close
                    // failure is "attached as a suppressed exception, never
                    // masking the start failure", and that promise has to hold
                    // for an Error too — otherwise a teardown failure REPLACES
                    // the actionable start failure on its way out.
                    startFailure.addSuppressed(closeFailure);
                    LOG.log(Level.WARNING,
                            "Audio stream failed to start and its handle could not be"
                                    + " released; the release is retried by the next start"
                                    + " or stop",
                            closeFailure);
                }
            }
            throw startFailure;
        }
    }

    /**
     * Retries the close a previous stop could not complete — or deliberately
     * deferred — so the backend no longer owns the stale handle and a fresh
     * stream can be opened. Only called in
     * {@link StreamState#RELEASE_PENDING}, where the pump is known stopped
     * and the clock is already released.
     *
     * <p>The close is not even ATTEMPTED while that backend's native control
     * panel is open ({@link #beginControlPanelSession(AudioBackend)}): the
     * open is refused instead, because closing the handle would free the
     * native state the modal dialog is running on, and opening a fresh stream
     * without closing it would put a second backend on the same device. The
     * state stays {@code RELEASE_PENDING} with the backend still tracked, and
     * {@link #endControlPanelSession(AudioBackend)} drains the close when the
     * dialog returns.</p>
     *
     * <p>A close that RETURNS refuses the open just as firmly when the
     * backend then reports that it kept the handle
     * ({@link #releaseDeferredBy(AudioBackend)} — story 316 re-review).
     * Proceeding on a quiet return would be the whole point of this method
     * undone: the fresh ladder open would be the second backend on a device
     * the previous one has not let go of. The state stays
     * {@code RELEASE_PENDING} with the backend tracked there too, so the next
     * start retries — and, the condition being self-clearing, succeeds once
     * the driver's queued teardown completes.</p>
     *
     * @throws AudioBackendException if the backend's control panel is open,
     *                               if the handle still cannot be released,
     *                               or if the close returned with the release
     *                               deferred; the state stays
     *                               {@code RELEASE_PENDING} in all three
     */
    private void releaseRetainedStreamHandle(AudioBackend backend) {
        if (controlPanelOpenOn(backend)) {
            throw new AudioBackendException(
                    "Cannot open a new audio stream: the previous stream's handle is still"
                            + " held by " + backend.name() + ", whose native control panel"
                            + " is open — closing the handle would free the native state"
                            + " that modal dialog is running on, and opening without closing"
                            + " it would put a second backend on the same device. Retry once"
                            + " the panel closes");
        }
        try {
            backend.close();
        } catch (RuntimeException closeFailure) {
            throw new AudioBackendException(
                    "Cannot open a new audio stream: the previous stream's handle is still"
                            + " held by " + backend.name()
                            + " and could not be released",
                    closeFailure);
        }
        if (releaseDeferredBy(backend)) {
            // The close RETURNED and the handle still did not come back, so
            // the open is refused exactly as it is when the close throws.
            // Neither the state nor the tracking is touched: this method is
            // only ever called in RELEASE_PENDING with `backend` tracked, and
            // leaving both standing is what keeps the retry reachable.
            throw new AudioBackendException(
                    "Cannot open a new audio stream: the previous stream's handle is still"
                            + " held by " + backend.name() + ", which returned from close()"
                            + " with the release DEFERRED — its driver teardown is queued"
                            + " behind a downcall that has not returned, and opening"
                            + " without releasing it would put a second backend on the same"
                            + " device. Retry once the release completes");
        }
        streamState = StreamState.CLOSED;
        clearOpenStream();
    }

    /**
     * Stops and joins the render pump, if one is running. Never throws.
     *
     * <p>This is also where a {@link #stop()} deferred by an unconfirmed join
     * is DRAINED (story 316 review): every lifecycle path that can quiesce
     * the pump — SEVEN call sites: {@link #stop()}, {@link #stopAudioOutput()},
     * {@link #pauseAudioOutput()}, {@link #requireQuiescedPump()},
     * {@link #abandonStreamOnOutgoingBackend(AudioBackend, PendingAnnouncements)},
     * {@link #start()} (whose {@code startLocked} retries the join to drain
     * the deferred collaborator teardown)
     * and, since the control-panel guard gained its drain,
     * {@link #drainDeferredHandleRelease()}
     * — funnels through
     * this one method, so hanging the drain off its confirmed-quiescence
     * returns is what makes the deferral legitimate rather than a leak with a
     * comment.</p>
     *
     * <p>The two RETAINED-handle closes reach a backend without being
     * textually preceded by a call to this method, and both are nonetheless
     * gated on one. {@link StreamState#RELEASE_PENDING} on its own does NOT
     * prove the pump has exited —
     * {@link #stopLocked(PendingAnnouncements)} enters that state on every
     * {@link #stop()} of a {@code RUNNING} stream, including when its own
     * join timed out — so neither may simply trust the state:
     * {@link #releaseRetainedStreamHandle(AudioBackend)} is reached only past
     * {@link #requireQuiescedPump()}, which re-joins and THROWS the open when
     * the exit is unconfirmed, and {@link #drainDeferredHandleRelease()}
     * calls this method itself and leaves the deferral in place when it
     * returns {@code false}.</p>
     *
     * @return {@code true} when no pump thread can call {@link #processBlock}
     *         any more (no pump existed, or the join was confirmed); on a
     *         timed-out join the pump reference is KEPT so a later stop can
     *         retry the join once the abandoned loop exits on its cleared
     *         running flag
     */
    private boolean stopPump() {
        EngineStreamPump current = this.pump;
        if (current == null) {
            drainDeferredCollaboratorTeardown();
            return true;
        }
        boolean joined = current.stop();
        if (joined) {
            this.pump = null;
            drainDeferredCollaboratorTeardown();
        }
        return joined;
    }

    /**
     * Stops the audio output stream and closes it. Best-effort: backend
     * failures are logged, never thrown.
     *
     * <p>Story 316 — the engine owns the render drive: the pump is stopped
     * and joined first. Only a <em>confirmed</em> join proves nothing can
     * call {@link #processBlock} any more; when the bounded join times out
     * (the pump thread may still be mid-{@code processBlock}), the story-315
     * conservative-preserve rule applies — the transport's RT-clock claim is
     * kept, the backend stays open, and the state stays
     * {@link StreamState#RUNNING} so a later stop retries once the abandoned
     * loop exits on its cleared running flag. After a confirmed join the
     * claim is released (draining any seek queued microseconds earlier
     * rather than stranding it — the story-315 bug class) and the backend
     * close is attempted best-effort: success is {@link StreamState#CLOSED};
     * a refused close is {@link StreamState#RELEASE_PENDING} with the
     * backend retained, and the close is retried by the next stop or before
     * the next open.</p>
     *
     * <p>One case DEFERS that close rather than attempting it: the backend's
     * native control panel is open
     * ({@link #beginControlPanelSession(AudioBackend)}). Closing the handle
     * would free the native state that modal dialog is running on, so the
     * state becomes {@link StreamState#RELEASE_PENDING} with the backend
     * retained and nothing is closed. Output really is stopped — the pump has
     * joined and the claim is released — so this returns {@code true}; see
     * the return-value paragraph below.
     * {@link #endControlPanelSession(AudioBackend)} drains that close when
     * the panel returns.</p>
     *
     * <p>The BACKEND can defer it too, and that one is the close's own
     * verdict rather than the engine's (story 316 re-review). An
     * {@code AsioBackend} whose driver shim has queued its teardown behind an
     * over-budget downcall returns from {@code close()} normally and then
     * reports {@link AudioBackend#isReleasePending()} — so a close that
     * returned is read through {@link #releaseDeferredBy(AudioBackend)}
     * before it is believed. A deferred release lands in exactly the state a
     * FAILED close does: {@link StreamState#RELEASE_PENDING} with the backend
     * retained, the release retried by the next start or stop, and this
     * method still returning {@code true} — the pump has joined either
     * way.</p>
     *
     * <p>A subsequent call to {@link #startAudioOutput()} will open a fresh
     * stream once the handle is released.</p>
     *
     * <p>The return value reports QUIESCENCE, not success (story 316 review):
     * {@code false} means only that a thread may still be inside
     * {@link #processBlock(float[][], float[][], int)}, which is the one
     * condition under which a caller must not tear anything shared down or
     * open anything new. No retained handle is reported as {@code false} —
     * not a failed close, not a panel-deferred one, and not one the BACKEND
     * deferred: in all three the pump is confirmed gone
     * and the engine merely retains a handle it will release itself (on the
     * next start or stop, or from
     * {@link #endControlPanelSession(AudioBackend)}), and conflating that
     * with a live render thread would make callers defer work that is
     * perfectly safe to do.</p>
     *
     * <p>The result is therefore only interesting to a caller that is about
     * to do one of those two things. A caller that merely stops the transport
     * and refreshes its own UI state — {@code TransportController}'s stop and
     * post-roll paths, say — owns nothing shared, releases nothing and opens
     * nothing, so DISCARDING the result is correct there rather than a
     * dropped error: a deferred stop retries on its own (the next stop, or
     * before the next open), and there is nothing for such a caller to do
     * differently in the meantime.</p>
     *
     * <p>The two paths that DO tear down or reopen are each protected
     * differently, and only one of them reads this value:</p>
     * <ul>
     *   <li><strong>Application shutdown</strong> BRANCHES on it. It is the
     *       one caller that frees backend instances outright, and nothing
     *       downstream can re-check quiescence for it, so
     *       {@code DefaultAudioEngineController.closeProvisionBackendsOnceQuiesced()}
     *       retries the stop and skips the closes entirely when the verdict
     *       stays {@code false}.</li>
     *   <li><strong>The settings-apply reconfigure</strong> BRANCHES on it
     *       too (story 316 re-review — this bullet used to claim the
     *       opposite, and the claim was false). The engine's own refusal
     *       SITES — FOUR of them now — cover only PART
     *       of a reconfigure: {@link #setStreamingProvision(StreamingProvision)}
     *       refuses the whole swap whenever the outgoing handle cannot be
     *       given back — an unconfirmed pump exit, an open control panel, a
     *       close that failed, or a close that returned with the backend
     *       still holding the handle, every one of them throwing
     *       {@code AudioBackendException} out of
     *       {@link #abandonStreamOnOutgoingBackend(AudioBackend, PendingAnnouncements)}
     *       — {@link #requireQuiescedPump()} refuses the reopen inside
     *       {@link #startAudioOutput()},
     *       {@link #releaseRetainedStreamHandle(AudioBackend)} refuses that
     *       same reopen when the previous handle is still held, and
     *       {@link #openLadder} abandons the walk when a hop that reached
     *       {@code open()} could not give its handle back. NONE of the four
     *       runs on a FORMAT-ONLY
     *       change: no provision is rebuilt when the requested endpoint is
     *       unchanged, and nothing is reopened while output was not open —
     *       which also keeps the last two, both of which live on the open
     *       path, out of it.
     *       That path's only gate is {@link #setFormat(AudioFormat)}, which
     *       refuses solely while {@link #isRunning()} — and the reconfigure
     *       has just cleared that flag via {@link #stop()}. A pump whose
     *       bounded join timed out could therefore still be inside
     *       {@link #processBlock(float[][], float[][], int)}, rendering
     *       through a {@link RenderPipeline} shaped by the OLD format, while
     *       the new format is stored under it. So
     *       {@code DefaultAudioEngineController.applyConfiguration()} keeps
     *       this verdict, gives {@link #stop()}'s own retry-join a chance,
     *       re-checks once — and refuses the whole reconfigure rather than
     *       mutate the format over a possibly live render. Its sibling, the
     *       device-driven {@code performFormatChangeReopen}, BRANCHES on this
     *       verdict too, through the same shared predicate
     *       ({@code stopAndConfirmPumpQuiesced()}) and refusing through
     *       {@code refuseFormatChangeReopen()} before it reaches its own
     *       {@link #setFormat(AudioFormat)} — so both format-mutating paths
     *       are covered by the same rule. The fixed drain sleep that path
     *       used to stand on instead is gone; a timed sleep never proved the
     *       pump had left {@link #processBlock(float[][], float[][], int)},
     *       and a confirmed join does.</li>
     * </ul>
     *
     * @return {@code true} when no render thread can call
     *         {@link #processBlock(float[][], float[][], int)} any more —
     *         including the no-op early return (no stream, hence no pump of
     *         ours), the path where the close FAILED and left
     *         {@link StreamState#RELEASE_PENDING}, the path where the
     *         close was DEFERRED for an open control panel and left the same
     *         state, and the path where the close RETURNED but the backend
     *         deferred the release and left the same state again;
     *         {@code false} only when the pump's bounded join timed
     *         out and the whole stop was deferred for a retry
     */
    public boolean stopAudioOutput() {
        PendingAnnouncements announcements = new PendingAnnouncements();
        lifecycleLock.lock();
        try {
            return stopAudioOutputLocked(announcements);
        } finally {
            lifecycleLock.unlock();
            announcements.deliver();
        }
    }

    /**
     * The stop transition, under {@link #lifecycleLock}. Called directly —
     * never through the public wrapper — by
     * {@link #startAudioInputOutputLocked(PendingAnnouncements)}, which
     * already holds the lock and must not let another caller open a stream
     * between its close and its reopen.
     *
     * @param announcements collects the RT-clock release this stop owes;
     *                      delivered by the outermost caller after the unlock
     */
    private boolean stopAudioOutputLocked(PendingAnnouncements announcements) {
        AudioBackend backend = this.openBackend;
        if (backend == null || streamState == StreamState.CLOSED) {
            return true;
        }
        if (!stopPump()) {
            LOG.severe("Audio output stop deferred: the render pump did not join in time"
                    + " and may still be inside processBlock — the RT-clock claim and the"
                    + " backend handle are preserved; retry the stop");
            return false;
        }
        recordClockRelease(announcements);
        if (controlPanelOpenOn(backend)) {
            // Deliberately NOT closed. The pump is gone and the clock is
            // released, so output really is stopped — but the handle stays
            // with the backend, because closing it frees exactly the native
            // state the modal control panel is running on.
            streamState = StreamState.RELEASE_PENDING;
            LOG.severe("Audio output stopped, but the stream handle is deliberately"
                    + " RETAINED: " + backend.name() + " has its native control panel open"
                    + " and closing the handle would free the native state that modal"
                    + " dialog is running on. endControlPanelSession drains this close when"
                    + " the panel returns");
            return true;
        }
        try {
            backend.close();
            if (releaseDeferredBy(backend)) {
                // The close RETURNED and the handle still did not come back:
                // the backend deferred its own release. Identical handling to
                // the failed close below — the engine keeps tracking the
                // handle so a later stop, the next start, or a control-panel
                // drain retries it.
                streamState = StreamState.RELEASE_PENDING;
                LOG.severe("Audio output stopped, but the stream handle was NOT released: "
                        + backend.name() + " returned from close() with the release"
                        + " DEFERRED, so it may still hold the device. The engine retains"
                        + " the handle and retries the release on the next start or stop");
            } else {
                streamState = StreamState.CLOSED;
                clearOpenStream();
            }
        } catch (RuntimeException closeFailure) {
            // The backend kept the handle; the engine keeps tracking it so a
            // later stop — or the next start — retries the close.
            streamState = StreamState.RELEASE_PENDING;
            LOG.log(Level.WARNING,
                    "Error closing audio output stream; the backend retains the handle"
                            + " and the close is retried by the next start or stop",
                    closeFailure);
        }
        // Quiescence was confirmed above; a retained handle is a release
        // failure, a deliberate panel deferral, or a release the BACKEND
        // deferred over a driver teardown it has queued — never a reason to
        // tell the caller a thread may still render.
        return true;
    }

    /**
     * Starts audio I/O for recording. Story 316 — the SDK
     * {@link AudioBackend#open(DeviceId, com.benesquivelmusic.daw.sdk.audio.AudioFormat, int, CaptureRequirement)}
     * seam is inherently duplex (backends open capture when the provisioned
     * device has it, publishing blocks on
     * {@link AudioBackend#inputBlocks()}), so this runs the same ladder,
     * device identity and pump as {@link #startAudioOutput()}. The record
     * path therefore opens the <em>provisioned</em> device — no index
     * parameter exists any more. Per-track input CHANNEL routing stays on
     * {@code Track.getInputRouting()}; multi-device capture routing is story
     * 326, which is why this method keeps its own name and seam.
     *
     * <p>Unlike {@code startAudioOutput()}, a stream that is already open
     * (running or paused) is <em>closed first</em> and reopened: the open
     * stream may predate the input device — or have been opened by a
     * backend/rung without capture — and record must never silently proceed
     * with zero input channels. When that close does not release the
     * backend's handle, this method fails instead of attempting a second
     * open that the production backends reject (the pre-316
     * {@code startAudioInputOutput(int)} contract).</p>
     *
     * <h2>"Never silently proceed with zero input channels" is now
     * ENFORCED (story 316 review)</h2>
     * <p>That sentence used to describe an INTENTION. The close-and-reopen
     * above is only the first half of it: it guarantees the record path
     * walks a fresh ladder rather than inheriting a stream that predates the
     * input device, and then the walk was the ordinary PLAYBACK walk, whose
     * whole contract is that capture degrades quietly. Three degradations
     * rode straight through it and each one produced an output-only stream
     * the caller could not tell from a duplex one — {@code JavaxSoundBackend}
     * swallowing a refused capture line, {@code CallbackBackendAdapter}
     * resolving no input device or retrying a refused duplex open
     * output-only. The recording pipeline then subscribed to
     * {@link AudioBackend#inputBlocks()}, no block ever arrived, and the take
     * was SILENT with nothing in the log.</p>
     *
     * <p>So this entry point now walks the ladder under
     * {@link CaptureRequirement#REQUIRED} while {@link #startAudioOutput()}
     * keeps {@link CaptureRequirement#OPTIONAL}, and the engine VERIFIES the
     * outcome rather than trusting the directive: after each rung's
     * {@code open} returns, {@link #requireCaptureOpened(BackendStreamRung,
     * CaptureRequirement)} reads {@link AudioBackend#openedInputChannels()}
     * and turns a zero into an ordinary failed hop. A rung with no capture is
     * therefore CLOSED and skipped, not opened — an ASIO head whose driver
     * exposes no inputs lets PortAudio take the recording — and only when
     * every rung fails that way does the open fail. Playback is untouched:
     * the same capture-less provision still opens through
     * {@link #startAudioOutput()}.</p>
     *
     * @throws AudioBackendException if every ladder rung failed to open —
     *                               INCLUDING a rung refused because its open
     *                               produced no capture channels, which is
     *                               where a recording open on hardware with
     *                               no usable input now lands — or a rung
     *                               that had reached {@code open} could
     *                               not give its handle back and the walk was
     *                               abandoned, or NO STREAMING PROVISION is
     *                               configured at all (both playback and
     *                               recording refuse that configuration;
     *                               recording additionally requires a usable
     *                               capture device), or the previous stream
     *                               could
     *                               not be closed first, or a previously
     *                               retained stream handle still cannot be
     *                               released — including because that
     *                               backend's native control panel is open
     *                               (see
     *                               {@link #beginControlPanelSession(AudioBackend)})
     *                               and including because its close RETURNED
     *                               with the release still deferred
     *                               ({@link AudioBackend#isReleasePending()});
     *                               either of those is also what makes the
     *                               close-first step above fail
     */
    public void startAudioInputOutput() {
        beginStreamStartInvocation();
        try {
            PendingAnnouncements announcements = new PendingAnnouncements();
            lifecycleLock.lock();
            try {
                try {
                    startAudioInputOutputLocked(announcements);
                } catch (RuntimeException | Error failure) {
                    bindFailedStreamStart(failure);
                    throw failure;
                }
            } finally {
                lifecycleLock.unlock();
                announcements.deliver();
            }
        } finally {
            finishStreamStartInvocation();
        }
    }

    /**
     * The close-then-reopen duplex transition, under {@link #lifecycleLock}
     * (story 316 re-review) — ONE critical section covering both halves, so
     * no other caller can slip an open in between the close and the reopen
     * and take the very device the record path is about to ask for.
     *
     * <p>The reopen asks for {@link CaptureRequirement#REQUIRED} (story 316
     * review). That is the ONLY difference from
     * {@link #startAudioOutput()}'s call into the same method, and it is what
     * makes the close above worth doing: closing a capture-less stream just
     * to reopen one under the playback contract would have reopened another
     * capture-less stream. The close-first step also means the shared method
     * is always entered from {@link StreamState#CLOSED} on this path — a
     * non-CLOSED state throws just above — so a REQUIRED walk can never take
     * the RUNNING early return or the PAUSED resume shortcut, neither of
     * which opens anything to verify.</p>
     *
     * @param announcements collects the reopen's outward-facing facts;
     *                      delivered by the caller after the unlock
     */
    private void startAudioInputOutputLocked(PendingAnnouncements announcements) {
        // A close-first refusal still belongs to this recording start, so bind
        // the provision this invocation read before touching the old stream.
        rememberRequestedStreamStartAttempt(this.streamingProvision);
        if (streamState != StreamState.CLOSED) {
            stopAudioOutputLocked(announcements);
            if (streamState != StreamState.CLOSED) {
                throw new AudioBackendException(
                        "Cannot open a full-duplex audio stream: the previous stream's"
                                + " handle is still held by the backend and could not be"
                                + " released");
            }
        }
        startAudioOutputLocked(announcements, CaptureRequirement.REQUIRED);
    }

    /**
     * Pauses audio output by stopping the render pump without closing the
     * backend stream, allowing a fast resume via {@link #startAudioOutput()}
     * (a fresh pump on the same open backend). Only a
     * {@link StreamState#RUNNING} stream can be paused; in every other state
     * this is a no-op.
     *
     * <p>Like {@link #stopAudioOutput()}, a pump whose bounded join timed
     * out (possibly still inside {@link #processBlock}) preserves the
     * RT-clock claim and the {@code RUNNING} state — a later pause or stop
     * retries once the abandoned loop has exited.</p>
     */
    public void pauseAudioOutput() {
        PendingAnnouncements announcements = new PendingAnnouncements();
        lifecycleLock.lock();
        try {
            pauseAudioOutputLocked(announcements);
        } finally {
            lifecycleLock.unlock();
            announcements.deliver();
        }
    }

    /**
     * The pause transition, under {@link #lifecycleLock}.
     *
     * @param announcements collects the RT-clock release this pause owes;
     *                      delivered by the caller after the unlock
     */
    private void pauseAudioOutputLocked(PendingAnnouncements announcements) {
        if (this.openBackend != null && streamState == StreamState.RUNNING) {
            if (!stopPump()) {
                LOG.severe("Audio output pause deferred: the render pump did not join in"
                        + " time and may still be inside processBlock — the RT-clock claim"
                        + " is preserved; retry the pause or stop");
                return;
            }
            streamState = StreamState.PAUSED;
            recordClockRelease(announcements);
        }
    }

    /**
     * Returns whether audio output is live or resumable — the stream is
     * {@link StreamState#RUNNING} or {@link StreamState#PAUSED}. This is what
     * the settings-apply path reads to decide whether to restart output after
     * a reconfigure.
     *
     * <p>A stream whose close failed — or was deferred for an open control
     * panel — and whose backend handle the engine still retains
     * ({@link StreamState#RELEASE_PENDING}) is <em>not</em> reported
     * open: output is stopped and not resumable, so a caller that restarted
     * output on this answer would start hardware against a stopped transport.
     * The engine releases that retained handle itself — on the next
     * {@link #stopAudioOutput()}, before the next open, or from
     * {@link #endControlPanelSession(AudioBackend)}.</p>
     *
     * @return {@code true} only in {@link StreamState#RUNNING} or
     *         {@link StreamState#PAUSED}
     */
    public boolean isStreamOpen() {
        StreamState state = this.streamState;
        return state == StreamState.RUNNING || state == StreamState.PAUSED;
    }

    /**
     * Returns whether the audio output stream is paused via
     * {@link #pauseAudioOutput()} and resumable via {@link #startAudioOutput()}.
     * A RETAINED handle ({@link StreamState#RELEASE_PENDING}, however it got
     * there) is <em>not</em> paused: it is stopped, but not resumable.
     *
     * @return {@code true} only in {@link StreamState#PAUSED}
     */
    public boolean isStreamPaused() {
        return streamState == StreamState.PAUSED;
    }

    // ── Native control-panel sessions (story 316 re-review) ──────────────

    /**
     * Registers {@code backend} as running its native, modal control panel,
     * so no engine-owned close reaches its stream handle until
     * {@link #endControlPanelSession(AudioBackend)} withdraws the
     * registration.
     *
     * <p>The application controller already refuses to close the backend
     * INSTANCES it owns while a panel is up, but that guard cannot see the
     * engine's own handle closes — {@link #stopAudioOutput()},
     * {@link #setStreamingProvision(StreamingProvision)}, the retained-handle
     * release before a fresh open, a failed pump start's unwind, and a failed
     * ladder hop's unwind. Those FIVE now consult this registration and
     * REFUSE or DEFER instead, because a driver's control panel runs on the
     * very native state the close frees: a Stop, a shutdown or a concurrent
     * endpoint reconfigure would otherwise tear the backend down underneath
     * a live modal dialog. They are five of the SIX places this class calls
     * {@link AudioBackend#close()}; the sixth is
     * {@link #drainDeferredHandleRelease()}, which deliberately does not
     * consult it — it runs only after
     * {@link #endControlPanelSession(AudioBackend)} has cleared the
     * registration, so the guard no longer applies and a check there would
     * be dead code: {@link #controlPanelOpenOn(AudioBackend)} already
     * answers {@code false} by then.</p>
     *
     * <p>The registration is taken under {@link #lifecycleLock} rather than
     * published with a bare volatile store, and that is the point of the
     * method: a close already INSIDE {@code backend.close()} holds the lock,
     * so this call waits for it to finish rather than racing it. On return,
     * every subsequent engine-owned close of this backend sees the
     * registration.</p>
     *
     * <h2>Lock order — read this before calling</h2>
     * <p>The controller calls this and its release from
     * {@code runControlPanel}, under that class's own
     * {@code controlPanelLock} and with its monitor RELEASED, giving that
     * thread [controlPanelLock → {@code lifecycleLock}] and never
     * [controller&nbsp;monitor → {@code lifecycleLock}] on this path. The
     * monitor is deliberately kept out because
     * {@link #endControlPanelSession(AudioBackend)} drains a close and can
     * hold this lock for a native teardown; a caller that reinstates the
     * monitor around either call reinstates that stall. Whichever way it is
     * called, the direction is safe only because the engine never calls the
     * APPLICATION while holding {@code lifecycleLock} — see the lock's own
     * javadoc. Neither of these two methods may ever gain an application
     * callback, or the cycle closes.</p>
     *
     * @param backend the backend whose control panel is being opened
     * @throws NullPointerException if {@code backend} is {@code null}
     */
    public void beginControlPanelSession(AudioBackend backend) {
        Objects.requireNonNull(backend, "backend");
        lifecycleLock.lock();
        try {
            controlPanelBackend = backend;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Withdraws {@code backend}'s control-panel registration and DRAINS the
     * close that registration deferred.
     *
     * <p>The drain is what makes the deferral legitimate rather than a leak
     * with a comment — the same reasoning as
     * {@link #drainDeferredCollaboratorTeardown()}, which hangs a deferred
     * teardown off the one method that can confirm it is safe. A stop that
     * ran while the panel was up left {@link StreamState#RELEASE_PENDING}
     * with the handle still tracked; this retries that close while still
     * holding {@link #lifecycleLock}, reaching {@link StreamState#CLOSED} on
     * success and staying {@code RELEASE_PENDING} when the backend still
     * refuses (logged at WARNING), when the backend RETURNS from the close
     * with its own release still deferred (logged at SEVERE), or when the
     * render pump's exit still
     * cannot be confirmed (logged at SEVERE — see
     * {@link #drainDeferredHandleRelease()}, which re-joins rather than
     * trusting the state). Without it the only owner of that deferral would
     * be "the next start or stop, maybe".</p>
     *
     * <p>That drain is why this method is NOT cheap and why the controller
     * calls it with its monitor released: {@link #stopPump()}'s bounded join
     * and then an {@link AudioBackend#close()} run under
     * {@link #lifecycleLock}, and the close is priced in this lock's own
     * blocking budget as UNBOUNDED for PortAudio and as minutes for a slow
     * ASIO driver.</p>
     *
     * <p>Compares by IDENTITY and clears only its own registration, so a late
     * release cannot clear a newer one. Throws no
     * {@link RuntimeException}: the controller calls it from a
     * {@code finally}, where an escaping exception would replace whatever the
     * panel action was already failing with, so the whole drain — including
     * the {@link AudioBackend#name()} calls its log messages make — sits
     * inside {@link #drainDeferredHandleRelease()}'s guarded region. An
     * {@link Error} is deliberately
     * left to propagate, matching {@link #releaseRetainedStreamHandle} and
     * {@link #stopAudioOutputLocked}; the controller's {@code finally} is
     * nested so that one cannot strand its half of the guard.</p>
     *
     * <p>Same lock-order constraint as
     * {@link #beginControlPanelSession(AudioBackend)}; a {@code null}
     * argument is ignored rather than rejected, so a {@code finally} can call
     * it unconditionally.</p>
     *
     * @param backend the backend whose control panel has closed
     */
    public void endControlPanelSession(AudioBackend backend) {
        lifecycleLock.lock();
        try {
            if (backend != null && controlPanelBackend == backend) {
                controlPanelBackend = null;
                drainDeferredHandleRelease();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Whether {@code backend}'s native control panel is open — the predicate
     * consulted by every engine-owned close that can run WHILE a panel is up.
     *
     * <p>Called only with {@link #lifecycleLock} held, from exactly five
     * sites: {@link #stopAudioOutputLocked(PendingAnnouncements)},
     * {@link #releaseRetainedStreamHandle(AudioBackend)},
     * {@link #abandonStreamOnOutgoingBackend(AudioBackend, PendingAnnouncements)},
     * {@link #startOpenedStream}'s failure unwind and
     * {@link #closeFailedHop(BackendStreamRung, Throwable)}. The sixth
     * {@link AudioBackend#close()} in this class,
     * {@link #drainDeferredHandleRelease()}, is deliberately not one of
     * them: it runs after the registration has been cleared, so a check
     * there would answer {@code false} anyway — dead code, not a
     * refusal.</p>
     */
    private boolean controlPanelOpenOn(AudioBackend backend) {
        return backend != null && this.controlPanelBackend == backend;
    }

    /**
     * Releases the handle the engine still tracks, now that a control-panel
     * session has ended — normally the close that session's registration
     * deferred.
     *
     * <p>Normally, but not by identity: the gate is the TRACKED handle, not
     * the backend whose panel just closed. So a handle left
     * {@link StreamState#RELEASE_PENDING} by an ordinary failed close, or by
     * a {@link #stop()} that attempted none, is released here too. That is a
     * strictly earlier retry than "the next start or stop", never a wrong
     * one — but it is also why the log messages below say only what is
     * actually known, "a control-panel session ended and the engine still
     * holds this handle", and never attribute the PANEL to that handle's
     * backend. With no stream open {@link #getBackend()} answers with
     * the provision's HEAD rung, so a panel opened while stopped can easily
     * belong to a different backend from the one holding the retained
     * handle.</p>
     *
     * <p>Gated on {@link StreamState#RELEASE_PENDING} with a tracked backend,
     * which is the only state a deferred close of the TRACKED handle can
     * have left behind: a {@code RUNNING} or {@code PAUSED} stream was never
     * closed at all (a panel does not stop the transport), and
     * {@code CLOSED} means the handle is already gone. A close deferred by
     * {@link #closeFailedHop(BackendStreamRung, Throwable)} is outside that
     * gate and is NOT drained here: a failed hop happens mid-open, before
     * {@code openBackend} is assigned, so it leaves that rung's handle
     * unreleased while the engine tracks nothing — the controller's own
     * INSTANCE close is what eventually reclaims that one. Runs
     * with {@link #lifecycleLock} held, so it cannot race a concurrent open
     * that is about to take the same device.</p>
     *
     * <p>Gated a SECOND time on {@link #stopPump()}, and that gate is not
     * redundant: {@code RELEASE_PENDING} does NOT by itself imply the render
     * pump has exited. {@link #stopLocked(PendingAnnouncements)} enters this
     * state on every {@link #stop()} of a {@code RUNNING} stream — including
     * when its own bounded join TIMED OUT, where it deliberately keeps the
     * pump reference for a later retry. Closing the handle there would
     * release native state a thread still inside
     * {@link AudioBackend#sink(AudioBlock)} is using. So this re-joins first
     * and simply leaves the deferral in place (logged at SEVERE) when the
     * exit still cannot be confirmed — the next start or stop retries it,
     * exactly as it would have without a control panel in the picture. This
     * is the same protection {@link #releaseRetainedStreamHandle} gets from
     * {@link #requireQuiescedPump()} on the open path.</p>
     *
     * <p>A close that RETURNS is not taken for a release here either (story
     * 316 re-review): {@link #releaseDeferredBy(AudioBackend)} is consulted,
     * and a backend that reports the release still DEFERRED leaves the state
     * {@code RELEASE_PENDING} with the handle still tracked, logged at
     * SEVERE. Nothing about that outcome is special to a control panel —
     * this is simply the earliest of the engine's retries meeting a handle
     * that is not ready yet, and the next start or stop retries again.</p>
     *
     * <p>Catches {@link RuntimeException} only, and the guarded region is
     * the WHOLE body rather than just the close: {@link AudioBackend#name()}
     * is an SDK call like any other — as is the
     * {@link AudioBackend#isReleasePending()} behind
     * {@link #releaseDeferredBy(AudioBackend)}, which is why that check sits
     * inside this {@code try} and not after it — and
     * {@link #endControlPanelSession(AudioBackend)} promises the
     * controller's {@code finally} that no {@code RuntimeException} escapes,
     * so neither building a log message nor asking a backend how its close
     * went may be the one seam that breaks it. (The helper guards itself as
     * well, for the callers that have no such {@code try}; the two guards are
     * belt and braces, not redundancy to remove — deleting this one would
     * still leave {@code name()} unguarded.) An
     * {@link Error} still propagates, exactly as it does out of
     * {@link #releaseRetainedStreamHandle} and
     * {@link #stopAudioOutputLocked}. {@link #stopPump()} never throws.</p>
     */
    private void drainDeferredHandleRelease() {
        AudioBackend retained = this.openBackend;
        if (streamState != StreamState.RELEASE_PENDING || retained == null) {
            return;
        }
        try {
            if (!stopPump()) {
                LOG.severe("A control-panel session ended while the engine still held the"
                        + " audio stream handle of " + retained.name() + ", and it is NOT"
                        + " being released: the render pump did not join in time and may"
                        + " still be inside processBlock on that backend. The engine keeps"
                        + " tracking the handle; the next start or stop retries the"
                        + " release");
                return;
            }
            retained.close();
            if (releaseDeferredBy(retained)) {
                // The close RETURNED and the handle still did not come back:
                // the backend deferred its own release. The state stays
                // RELEASE_PENDING with `retained` still tracked — no
                // clearOpenStream() — so the next start or stop retries it,
                // exactly as when the close throws. The query lives INSIDE
                // this guarded region because it is an SDK call like any
                // other, and this method promises no RuntimeException
                // escapes.
                LOG.severe("A control-panel session ended and the audio stream handle the"
                        + " engine still holds was NOT released: " + retained.name()
                        + " returned from close() with the release DEFERRED, so it may"
                        + " still hold the device. The engine keeps tracking the handle;"
                        + " the next start or stop retries the release");
                return;
            }
            streamState = StreamState.CLOSED;
            clearOpenStream();
        } catch (RuntimeException drainFailure) {
            // No name() here: this catch is the last line of the
            // no-RuntimeException promise, so it must not make an SDK call
            // of its own — including the one that threw. Whatever the
            // backend chose to say travels on the throwable.
            LOG.log(Level.WARNING,
                    "A control-panel session ended and the audio stream handle the engine"
                            + " still holds could not be released; it keeps tracking the"
                            + " handle and retries on the next start or stop",
                    drainFailure);
        }
    }

    /**
     * Restarts a paused stream: claims the RT clock, then starts a fresh
     * render pump on the SAME still-open backend (see
     * {@link #startOpenedStream(AudioBackend, com.benesquivelmusic.daw.sdk.audio.AudioFormat)}
     * for why the claim precedes the start). On a start failure of any kind
     * the stream stays open and paused (the resume is retryable) and the
     * claim is released again.
     *
     * <p>The engine itself is (re)started first (story 316 review): a stream
     * can be {@link StreamState#PAUSED} across an {@link #stop()}, because
     * {@link #pauseAudioOutput()} leaves the engine running and {@code stop()}
     * only quiesces a {@link StreamState#RUNNING} stream. Resuming against a
     * stopped engine would start a pump whose every {@link #processBlock}
     * throws and whose loop then exits — silence. {@link #start()} is a no-op
     * when the engine is already running, and re-allocates the render buffers
     * when it is not.</p>
     *
     * @param announcements collects the RT-clock release a failed resume
     *                      owes; delivered by the caller after the unlock
     * @throws RuntimeException an engine-preparation or pump-start failure,
     *                          rethrown as-is (an {@link Error} unwinds the
     *                          same way)
     */
    private void resumeAudioOutputLocked(PendingAnnouncements announcements) {
        AudioBackend backend = this.openBackend;
        com.benesquivelmusic.daw.sdk.audio.AudioFormat negotiated = this.openSdkFormat;
        if (backend != null && negotiated != null && streamState == StreamState.PAUSED) {
            // No-op when already running; re-allocates the render pipeline
            // when this resume follows a stop() (see the javadoc). A failure
            // rolls back only an engine start performed by this attempt; the
            // still-open stream remains PAUSED and retryable.
            boolean startedHere = startEngineForStream(announcements);
            streamState = StreamState.RUNNING;
            claimTransportClock();
            try {
                EngineStreamPump newPump =
                        new EngineStreamPump(backend, this, format, negotiated);
                newPump.start();
                this.pump = newPump;
            } catch (RuntimeException | Error startFailure) {
                streamState = StreamState.PAUSED;
                recordClockRelease(announcements);
                rollbackEngineStart(startedHere, announcements, startFailure);
                throw startFailure;
            }
        }
    }

    // ── Real-time clock ownership (story 315 review) ─────────────────────

    /**
     * Returns {@code true} while a backend stream is {@link StreamState#RUNNING},
     * i.e. while {@link #processBlock(float[][], float[][], int)} is (or is
     * about to be) called by the backend and therefore driving
     * {@link Transport#advancePosition(double)}.
     *
     * <p>This — not {@link Transport#getState()} — is the truth condition
     * behind {@link Transport#setRealTimeClockActive(boolean)}.
     * {@link #startAudioOutput()} never returns successfully without a
     * RUNNING stream, so callers can refuse their transport transition when
     * the callback cannot take this clock (story 317).</p>
     */
    private boolean callbackIsDriving() {
        return streamState == StreamState.RUNNING;
    }

    /**
     * Claims the RT clock on the currently published transport. Called only
     * from the paths that are about to start a backend stream registered on
     * {@link #processBlock(float[][], float[][], int)} — immediately before
     * the start call, so the very first callback block cannot race an inline
     * seek. The same paths release the claim again should the start fail.
     *
     * <p>Claiming is safe INSIDE {@link #lifecycleLock}:
     * {@link Transport#setRealTimeClockActive(boolean)} with {@code true} is
     * a bare volatile store — it drains nothing and notifies nobody, so no
     * application code can run on this thread. Only the RELEASE is
     * outward-facing; see {@link #recordClockRelease(PendingAnnouncements)}.
     * </p>
     *
     * <p>A claim taken AFTER a release was recorded in the same critical
     * section — {@link #startAudioInputOutputLocked(PendingAnnouncements)}
     * closes and reopens inside ONE — needs no bookkeeping here: the recorded
     * release is dropped by {@link #deliverClockRelease(Transport)}'s
     * pre-check, which sees the fresh {@link StreamState#RUNNING} and leaves
     * the claim (and any seek the new pump now owns) alone.</p>
     */
    private void claimTransportClock() {
        Transport transport = this.graph.transport();
        if (transport != null) {
            transport.setRealTimeClockActive(true);
        }
    }

    /**
     * RECORDS the RT-clock release a stop, a pause or a failed start owes,
     * for delivery once {@link #lifecycleLock} has been released (story 316
     * re-review).
     *
     * <p>This is the THIRD outward seam, and the one the lock's javadoc used
     * to deny existed. {@link Transport#setRealTimeClockActive(boolean)} with
     * {@code false} drains any queued seek INLINE, and that drain fires the
     * transport's {@link Transport.ChangeKind#POSITION} observers — arbitrary
     * {@link java.util.function.Consumer}s registered through
     * {@link Transport#addChangeListener(java.util.function.Consumer)} by the
     * application layer, whose audio controller binds device events from a
     * {@code synchronized} method and drives this engine's lifecycle from
     * that same monitor. Running it under the lifecycle lock gives one thread
     * [lifecycle&nbsp;lock → controller&nbsp;monitor] while the other holds
     * [controller&nbsp;monitor → lifecycle&nbsp;lock]: the same inversion the
     * {@link StreamOpenListener} is deferred for.</p>
     *
     * <p>Deferring costs only that the claim stays set for the handful of
     * statements between the unlock and the delivery. A seek issued in that
     * window is QUEUED rather than applied inline, and the delivery's own
     * drain applies it — nothing is lost, which is the whole point of
     * draining on release.</p>
     *
     * @param announcements the current call's deferred-announcement record
     */
    private void recordClockRelease(PendingAnnouncements announcements) {
        Transport transport = this.graph.transport();
        if (transport != null) {
            announcements.clockReleased(transport);
        }
    }

    /**
     * Performs a recorded RT-clock release, OUTSIDE {@link #lifecycleLock}.
     *
     * <p>Deferral opens one window the inline call did not have: another
     * thread may take the lock and START a stream between this call's unlock
     * and this delivery, claiming the very clock the delivery is about to
     * release. The pre-check skips a release such a restart has already
     * superseded; the post-check REPAIRS the case the pre-check cannot see.
     * </p>
     *
     * <h2>What the repair guarantees, and what it does not</h2>
     * <p>The CLAIM FLAG is repaired exactly. {@link #startOpenedStream} and
     * {@link #resumeAudioOutputLocked} both store
     * {@code streamState = RUNNING} <em>before</em> they claim, and every
     * access here is volatile, so the two outcomes are total-order
     * exhaustive: if the post-check does not observe {@code RUNNING} then
     * this release precedes the competing {@code RUNNING} store, which
     * precedes the competing claim, so that claim lands last and stands; if
     * it does observe {@code RUNNING}, this re-assert restores the claim. The
     * re-assert calls nothing outward — a {@code true} claim is a bare
     * volatile store (see {@link #claimTransportClock()}).</p>
     *
     * <p>The release's SIDE EFFECT is NOT repaired, and this is
     * check-then-act, not atomic. A start that claims between the pre-check
     * and the {@code false} store makes that store run against a transport
     * whose pump is already live, and the store's inline
     * {@code drainPendingSeekInline()} then applies one queued seek on THIS
     * thread and fires {@link Transport.ChangeKind#POSITION} for it. The
     * re-assert puts the flag back; it cannot un-apply the seek. The residual
     * exposure is therefore exactly: at most one queued seek applied by a
     * lifecycle thread instead of by the render pump, in a window a few
     * statements wide, on a transport a competing start claimed inside it.
     * The seek's VALUE is the same either way (the pump's drain would have
     * applied the same queued target), and {@code POSITION} observers already
     * receive that event from several threads, so the consequence is an
     * out-of-order position notification rather than a lost or wrong seek.
     * </p>
     *
     * <p>Closing it properly would mean holding {@link #lifecycleLock} across
     * the release — which is the deadlock this deferral exists to remove — or
     * giving {@link Transport} a compare-and-release primitive that tests the
     * engine's {@code streamState} atomically with its own flag, i.e. moving
     * the engine's state machine into the transport. Neither is worth the
     * cost of the window described above, so the window is documented rather
     * than eliminated. Do not restate this method as exact.</p>
     *
     * @param transport the transport whose claim this release owes
     */
    private void deliverClockRelease(Transport transport) {
        if (claimSupersededBy(transport)) {
            // Not merely an optimisation: releasing here would DRAIN a queued
            // seek inline while the superseding pump is already advancing the
            // same transport — the very interleaving the claim exists to
            // prevent. The reachable case is not even a race:
            // startAudioInputOutput closes and reopens inside ONE critical
            // section, so its stop's recorded release arrives with the
            // reopen's pump already running.
            return;
        }
        transport.setRealTimeClockActive(false);
        if (claimSupersededBy(transport)) {
            transport.setRealTimeClockActive(true);
        }
    }

    /**
     * Whether a stream is driving {@code transport} right now — i.e. whether
     * a concurrent (re)start has claimed the clock a deferred release still
     * owes. Reads {@code streamState} and the published graph volatilely and
     * takes no lock, so it is safe to call outside {@link #lifecycleLock}.
     */
    private boolean claimSupersededBy(Transport transport) {
        return callbackIsDriving() && this.graph.transport() == transport;
    }

    // ── Project graph publication (story 314) ────────────────────────────

    /**
     * Atomically publishes the whole project graph — transport, mixer, and
     * track list — to the audio thread as a single volatile store of an
     * immutable snapshot record.
     *
     * <p>This is the {@link EngineBinder}'s publish seam. Because all three
     * components land in one store, the next
     * {@link #processBlock(float[][], float[][], int)} observes either the
     * entire previous graph or the entire new one — never a hybrid such as
     * the old (still playing) transport gating playback over the new tracks
     * and mixer. The same source-scan conformance sentinel that restricts
     * {@link #setTracks(List)} applies to this method: only
     * {@code EngineBinder} may call it from production code.</p>
     *
     * <p>Non-RT preparation runs before the store (see
     * {@link #handOffMixer(Mixer, Mixer)}): the graph scheduler is detached
     * from the outgoing mixer (story 125) and, when the engine is running,
     * the incoming mixer is prepared for playback and the scheduler
     * re-installed (story 314 review) so the audio thread never renders
     * through an unprepared mixer.</p>
     *
     * <p>The RT-clock claim (story 315 review) is handed over with the graph:
     * the outgoing transport is released — draining any seek it had queued —
     * and the incoming one is claimed only when a backend stream is actually
     * running, so an {@link EngineBinder} rebind mid-playback leaves exactly
     * one claimed transport, and a rebind with no stream open leaves none.</p>
     *
     * <p>Both RELEASES are DEFERRED past the monitor (story 316 re-review).
     * {@link Transport#setRealTimeClockActive(boolean)} with {@code false}
     * drains a queued seek inline and fires
     * {@link Transport.ChangeKind#POSITION} on arbitrary app-registered
     * consumers — the same [engine lock → app observer] inversion the
     * lifecycle lock defers its three seams for, and one this monitor is no
     * more entitled to than that lock is. Only the CLAIM ({@code true}) stays
     * inside, because it is a bare volatile store that drains nothing and
     * notifies nobody.</p>
     *
     * <p>Deferring does NOT weaken the atomic publish. Both releases target
     * the clock flag, never the {@code graph} field: the RT thread reads only
     * {@code graph}, still sees exactly one store, and by the time it renders
     * the new graph the outgoing transport is out of it whether its claim has
     * been handed back yet or not. What the deferral does open is a window of
     * a few statements, on this thread only, in which BOTH transports look
     * claimed (the outgoing one still holds the claim it is about to give up,
     * the incoming one has already taken its own). Nothing is lost in it: a
     * seek issued against a still-claimed transport is QUEUED, and the
     * delivery's own drain applies it. The window closes before this method
     * returns — {@code deliver()} runs in the {@code finally} — so no caller
     * can observe it except from inside an observer callback the delivery
     * itself is running.</p>
     *
     * <p>Lifecycle-thread only — never call from the audio callback.</p>
     *
     * @param transport the transport, or {@code null} to disable playback rendering
     * @param mixer     the mixer, or {@code null} to disable playback rendering
     * @param tracks    an immutable track-list snapshot, or {@code null} to
     *                  disable playback rendering
     */
    public void setGraph(Transport transport, Mixer mixer, List<Track> tracks) {
        PendingAnnouncements announcements = new PendingAnnouncements();
        try {
            synchronized (graphLock) {
                handOffMixer(this.graph.mixer(), mixer);
                Transport outgoing = this.graph.transport();
                if (outgoing != null && outgoing != transport) {
                    announcements.clockReleased(outgoing);
                }
                this.graph = new EngineGraph(transport, mixer, tracks);
                if (transport != null) {
                    if (callbackIsDriving()) {
                        transport.setRealTimeClockActive(true);
                    } else {
                        announcements.clockReleased(transport);
                    }
                }
            }
        } finally {
            announcements.deliver();
        }
    }

    /**
     * Returns the transport from the current graph snapshot, or {@code null}.
     *
     * @return the transport
     */
    public Transport getTransport() {
        return graph.transport();
    }

    /**
     * Returns the mixer from the current graph snapshot, or {@code null}.
     *
     * @return the mixer
     */
    public Mixer getMixer() {
        return graph.mixer();
    }

    /**
     * Non-RT mixer hand-off run before a graph store (factored from the
     * pre-story-314-review {@code setMixer}). Detaches the graph scheduler
     * from the outgoing mixer (story 125) so stale references do not
     * attempt parallel dispatch after the pool is closed in {@link #stop()}
     * — safe even when the old mixer is null or no scheduler is installed.
     * When the engine is running, the INCOMING mixer is prepared BEFORE the
     * caller's volatile store: publishing first would let the RT thread
     * mixDown an unprepared mixer (null scratch buffers → NPE inside the
     * FFM upcall) for the whole multi-ms prepare window, and the scheduler
     * is re-installed so parallel insert dispatch keeps working after a
     * hot mixer swap.
     *
     * <p>{@code prepareForPlayback} runs THIRD-PARTY code — one
     * {@code AudioProcessor.getLatencySamples()} per insert and a reflective
     * {@code @ProcessorParam} rebind per channel — and it runs here inside
     * {@link #graphLock}, exactly as it does inside {@link #lifecycleLock}
     * from {@link #startLocked()}. Same disposition, same reason: an
     * unprepared mixer must never be published to the render thread, so the
     * preparation cannot move outside the critical section that publishes it.
     * It is enumerated in {@link #lifecycleLock}'s "No application callback"
     * bullet, among the calls that leave this class without being application
     * callbacks.</p>
     */
    private void handOffMixer(Mixer previousMixer, Mixer incomingMixer) {
        if (previousMixer != null && graphScheduler != null
                && previousMixer.getGraphScheduler() == graphScheduler) {
            previousMixer.setGraphScheduler(null);
        }
        if (incomingMixer != null && running.get()) {
            incomingMixer.prepareForPlayback(format.channels(), format.bufferSize());
            if (graphScheduler != null) {
                incomingMixer.setGraphScheduler(graphScheduler);
            }
        }
    }

    /**
     * Returns the total system latency introduced by plugin delay
     * compensation, in samples.
     *
     * <p>This is the maximum insert-chain latency across all mixer channels
     * and return buses. The transport can use this value to offset the
     * playback start position so that the first audible sample aligns
     * with beat 1.</p>
     *
     * @return the system latency in sample frames, or 0 if no mixer is configured
     */
    public int getSystemLatencySamples() {
        Mixer currentMixer = this.graph.mixer();
        return currentMixer != null ? currentMixer.getSystemLatencySamples() : 0;
    }

    /**
     * Replaces only the track list in the published graph, keeping the
     * current transport and mixer — a read-modify-write that still lands as
     * one volatile store of a fresh immutable snapshot. This is the
     * {@link EngineBinder}'s structural-change refresh seam (track
     * add/remove/move/duplicate); the source-scan conformance sentinel
     * restricts production callers to {@code EngineBinder}.
     *
     * <p>To avoid concurrent-modification issues, pass an immutable or
     * snapshot list. Lifecycle-thread only — never call from the audio
     * callback.</p>
     *
     * @param tracks the track list, or {@code null} to disable playback rendering
     */
    public void setTracks(List<Track> tracks) {
        synchronized (graphLock) {
            EngineGraph current = this.graph;
            this.graph = new EngineGraph(current.transport(), current.mixer(), tracks);
        }
    }

    /**
     * Returns the track list from the current graph snapshot, or {@code null}.
     *
     * @return the track list
     */
    public List<Track> getTracks() {
        return graph.tracks();
    }

    /**
     * Sets the callback invoked from {@link #processBlock(float[][], float[][], int)}
     * to capture input audio data during recording.
     *
     * @param callback the recording callback, or {@code null} to disable recording capture
     */
    public void setRecordingCallback(RecordingCallback callback) {
        this.recordingCallback = callback;
    }

    /**
     * Returns the currently configured recording callback, or {@code null}.
     *
     * @return the recording callback
     */
    public RecordingCallback getRecordingCallback() {
        return recordingCallback;
    }

    /**
     * Sets the performance monitor used to track CPU load and buffer underruns.
     *
     * <p>When set, the engine measures the time taken by each
     * {@link #processBlock(float[][], float[][], int)} call and reports it
     * to the monitor.</p>
     *
     * @param monitor the performance monitor, or {@code null} to disable monitoring
     */
    public void setPerformanceMonitor(PerformanceMonitor monitor) {
        this.performanceMonitor = monitor;
    }

    /**
     * Returns the currently configured performance monitor, or {@code null}.
     *
     * @return the performance monitor
     */
    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    /**
     * Sets the per-track CPU budget enforcer for graceful degradation.
     *
     * <p>When set, the engine measures the time taken by each track's mixer
     * processing and feeds the measurements to the enforcer. The enforcer
     * evaluates per-track and master budgets each block. When a track
     * persistently exceeds its budget, the enforcer applies the configured
     * {@link com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy}
     * and publishes events for the UI.</p>
     *
     * @param enforcer the CPU budget enforcer, or {@code null} to disable
     */
    public void setCpuBudgetEnforcer(TrackCpuBudgetEnforcer enforcer) {
        this.cpuBudgetEnforcer = enforcer;
    }

    /**
     * Returns the currently configured per-track CPU budget enforcer, or
     * {@code null}.
     *
     * @return the CPU budget enforcer
     */
    public TrackCpuBudgetEnforcer getCpuBudgetEnforcer() {
        return cpuBudgetEnforcer;
    }

    /**
     * Returns the process-wide {@link SampleRateConversionCache} used
     * by the render pipeline to memoize JIT sample-rate conversions of
     * clips whose native rate differs from the session rate. Story 126.
     *
     * @return the cache, never {@code null}
     */
    public SampleRateConversionCache getSampleRateConversionCache() {
        return srcCache;
    }

    /**
     * Sets the SRC quality tier ({@code LOW} / {@code MEDIUM} /
     * {@code HIGH}) used by the cache when materializing a new
     * conversion. Story 126.
     *
     * @param tier quality tier (must not be {@code null})
     */
    public void setSrcQualityTier(
            com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier tier) {
        if (renderPipeline != null) {
            renderPipeline.setSrcQualityTier(tier);
        }
        this.srcQualityTier = Objects.requireNonNull(tier, "tier must not be null");
    }

    /** Returns the SRC quality tier currently in effect. */
    public com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier getSrcQualityTier() {
        return srcQualityTier;
    }

    /** Cached tier reapplied to the render pipeline on each {@link #start()}. */
    private volatile com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier srcQualityTier
            = com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier.MEDIUM;

    /**
     * Binds an {@link InputLevelMonitorRegistry} to the engine so that the
     * raw input signal for each armed track is tapped ahead of any
     * processing and fed through a per-track monitor (user story 137).
     *
     * <p>When set, every {@link #processBlock(float[][], float[][], int)}
     * call iterates the armed tracks and forwards the configured input-
     * channel slice to {@link InputLevelMonitor#processInputChannels} on
     * the matching monitor. The mixer and arrangement-view UI read the
     * resulting snapshots to drive the input-meter column and the clip
     * LED.</p>
     *
     * <p>Pass {@code null} to disable the tap (the engine then reverts to
     * pre-story-137 behavior — zero per-block overhead).</p>
     *
     * @param registry the registry, or {@code null} to disable
     */
    public void setInputLevelMonitorRegistry(InputLevelMonitorRegistry registry) {
        this.inputLevelMonitorRegistry = registry;
    }

    /**
     * Returns the currently bound input-level monitor registry, or {@code null}.
     */
    public InputLevelMonitorRegistry getInputLevelMonitorRegistry() {
        return inputLevelMonitorRegistry;
    }

    /**
     * Story 318 — the engine-owned metering tap bus every output meter
     * subscribes to (book §3.5). The {@link EngineBinder} binds it to the
     * live project's mixer under the binding epoch, refreshes its slots on
     * structural change, and unbinds it with the project; the render path
     * reads its snapshot once per block. Never {@code null}.
     *
     * @return the metering tap bus
     */
    public MeteringTapBus meteringTapBus() {
        return meteringTapBus;
    }

    /**
     * Story 136 — sets the {@link Metronome} the audio callback uses to
     * generate a click on each scheduled beat (and subdivision) that
     * lands inside the current buffer.
     *
     * <p>Pass {@code null} to disable click generation entirely. The
     * field is {@code volatile} so the audio thread observes the
     * latest reference without locking.</p>
     *
     * @param metronome the metronome, or {@code null}
     */
    public void setMetronome(Metronome metronome) {
        this.metronome = metronome;
    }

    /**
     * Returns the currently bound metronome, or {@code null}.
     */
    public Metronome getMetronome() {
        return metronome;
    }

    /**
     * Story 136 — sets the {@link MetronomeSideOutputRouter} that gates
     * each generated click into (a) the main mix, (b) a direct hardware
     * channel via {@link AudioBackend#writeToChannel(int, float[])}, and
     * (c) any cue-bus contributions configured by the user.
     *
     * <p>The router is thread-safe: its internal cue-bus level map is
     * published via {@link java.util.concurrent.atomic.AtomicReference}
     * using copy-on-write semantics, so the UI thread can mutate levels
     * while the audio callback reads a consistent immutable snapshot
     * without locking and without risking
     * {@link java.util.ConcurrentModificationException}.</p>
     *
     * <p>Pass {@code null} to bypass routing — no click is summed into
     * the main mix and no side-output is written.</p>
     *
     * @param router the side-output router, or {@code null}
     */
    public void setMetronomeSideOutputRouter(MetronomeSideOutputRouter router) {
        this.metronomeSideOutputRouter = router;
    }

    /**
     * Returns the currently bound side-output router, or {@code null}.
     */
    public MetronomeSideOutputRouter getMetronomeSideOutputRouter() {
        return metronomeSideOutputRouter;
    }

    /**
     * Story 136 — sets the {@link CueBusManager} the audio callback consults
     * when summing cue-bus click contributions to each bus's hardware
     * output stereo pair. Pass {@code null} to skip cue-bus routing.
     *
     * @param cueBusManager the cue bus manager, or {@code null}
     */
    public void setCueBusManager(CueBusManager cueBusManager) {
        this.cueBusManager = cueBusManager;
    }

    /**
     * Returns the currently bound cue bus manager, or {@code null}.
     */
    public CueBusManager getCueBusManager() {
        return cueBusManager;
    }

    /**
     * Processes a single block of audio by delegating to the unified
     * {@link RenderPipeline}.
     *
     * <p>When a transport, mixer, and track list are configured and the
     * transport is in {@link TransportState#PLAYING} or
     * {@link TransportState#RECORDING} state, the pipeline renders clip
     * audio, applies automation, mixes through the mixer, and applies the
     * master effects chain. Otherwise, {@code inputBuffer} is passed
     * through the master effects chain (the original passthrough
     * behavior).</p>
     *
     * <p>This method is designed to be called from the audio callback
     * thread. The core track-mixdown/master-chain path performs zero
     * allocations and zero lock acquisitions — all buffers are
     * pre-allocated during {@link #start()}, and the
     * {@link RenderPipeline} reads from one atomic volatile snapshot of
     * the immutable transport/mixer/tracks graph (published whole via
     * {@link #setGraph(Transport, Mixer, List)}) plus volatile snapshots
     * of the MIDI renderer and other collaborators. When an enforcer
     * is present, per-track CPU timing occurs and the enforcer acquires
     * an internal lock for each measurement; the enforcer pre-allocates
     * its own buffers to minimize GC pressure on the audio thread.</p>
     *
     * <p><b>Allocation note (story 136):</b> when a metronome and
     * side-output router are attached and clicks are scheduled in this
     * block, bounded short-lived allocations occur for click generation
     * and routing buffers (see
     * {@link RenderPipeline#renderBlock(float[][], float[][], int,
     * Transport, Mixer, java.util.List, MidiTrackRenderer, EffectsChain,
     * RecordingCallback, PerformanceMonitor, TrackCpuBudgetEnforcer,
     * Metronome, MetronomeSideOutputRouter, CueBusManager,
     * AudioBackend)}).</p>
     *
     * @param inputBuffer  the input audio data {@code [channel][frame]}
     * @param outputBuffer the output audio data {@code [channel][frame]}
     * @param numFrames    the number of sample frames to process
     * @throws IllegalStateException if the engine is not running
     */
    @RealTimeSafe
    public void processBlock(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (!running.get()) {
            throw new IllegalStateException("Engine is not running");
        }

        // Snapshot volatile references once for this block so that the
        // UI thread cannot tear the configuration mid-render. The whole
        // transport/mixer/tracks graph arrives as ONE volatile load of an
        // immutable record (story 314 review), so a concurrent EngineBinder
        // publish can never expose a half-swapped hybrid graph.
        EngineGraph currentGraph = this.graph;
        Transport currentTransport = currentGraph.transport();
        Mixer currentMixer = currentGraph.mixer();
        List<Track> currentTracks = currentGraph.tracks();
        MidiTrackRenderer currentMidiRenderer = this.midiTrackRenderer;
        RecordingCallback cb = this.recordingCallback;
        PerformanceMonitor monitor = this.performanceMonitor;
        TrackCpuBudgetEnforcer enforcer = this.cpuBudgetEnforcer;
        InputLevelMonitorRegistry inputRegistry = this.inputLevelMonitorRegistry;
        Metronome currentMetronome = this.metronome;
        MetronomeSideOutputRouter currentRouter = this.metronomeSideOutputRouter;
        CueBusManager currentCueBusManager = this.cueBusManager;
        // The OPEN stream's backend (story 316): metronome writeToChannel
        // routing finally targets the stream that is actually playing.
        AudioBackend currentBackend = this.openBackend;
        // Story 318: the metering slot set for this block — one volatile
        // load, used for the whole block, so a registry swap mid-render can
        // never mix two snapshots.
        TapSnapshot taps = meteringTapBus.snapshot();

        // Story 137: tap the raw input signal per armed track BEFORE any
        // processing so the mixer's input-meter column and the clip LED
        // always reflect the converter-side signal (not post-gain / post-
        // inserts). No-op when no registry is bound or no track is armed.
        if (inputRegistry != null && inputBuffer != null && currentTracks != null) {
            tapArmedTrackInputs(inputRegistry, inputBuffer, numFrames, currentTracks);
        }

        renderPipeline.renderBlock(inputBuffer, outputBuffer, numFrames,
                currentTransport, currentMixer, currentTracks,
                currentMidiRenderer, masterChain, cb, monitor,
                enforcer,
                currentMetronome, currentRouter,
                currentCueBusManager, currentBackend, taps);

        // Story 318: stamp the next block and wake the analysis lane (only
        // when a ring exists) — the render thread's sole cross-thread signal.
        meteringTapBus.blockCompleted(taps);
    }

    /**
     * Iterates the armed tracks and forwards the raw input-channel slice
     * for each one to its {@link InputLevelMonitor}.
     *
     * <p>Allocation-free hot path: the only per-block state is the snapshot
     * volatile field read at the top of {@link
     * #processBlock(float[][], float[][], int)}. Monitors are looked up by
     * track id via {@link InputLevelMonitorRegistry#getOrCreate(String)},
     * which synchronizes internally but allocates only the first time a
     * given track is armed.</p>
     */
    @RealTimeSafe
    private static void tapArmedTrackInputs(InputLevelMonitorRegistry registry,
                                            float[][] inputBuffer,
                                            int numFrames,
                                            List<Track> currentTracks) {
        int numInputChannels = inputBuffer.length;
        if (numInputChannels == 0) {
            return;
        }
        for (int i = 0, n = currentTracks.size(); i < n; i++) {
            Track track = currentTracks.get(i);
            if (track == null || !track.isArmed()) {
                continue;
            }
            InputRouting routing = track.getInputRouting();
            if (routing == null || routing.isNone()) {
                continue;
            }
            int first = routing.firstChannel();
            int count = routing.channelCount();
            if (first < 0 || count <= 0 || first + count > numInputChannels) {
                // Routing points off the end of the actual input buffer —
                // e.g., user selected "Input 5-6" on a 2-in interface.
                // Skip silently so metering never throws from the audio
                // thread.
                continue;
            }
            InputLevelMonitor monitor = registry.getOrCreate(track.getId());
            monitor.processInputChannels(inputBuffer, first, count, numFrames);
        }
    }

    /**
     * Returns the unified render pipeline used by this engine. Package
     * private so that offline export callers (e.g., master rendering,
     * stem export, track bouncing) can construct their own pipeline or
     * reuse the engine’s to render with identical semantics.
     *
     * @return the render pipeline, or {@code null} if the engine has not
     *         been started
     */
    RenderPipeline getRenderPipeline() {
        return renderPipeline;
    }

    // ── Multi-core graph scheduling (story 125) ─────────────────────────

    /**
     * Returns the current multi-core engine settings (worker-pool size and
     * minimum parallel block size). Story 125.
     *
     * @return the engine settings, never {@code null}
     */
    public AudioEngineSettings getEngineSettings() {
        return engineSettings;
    }

    /**
     * Replaces the multi-core engine settings. Must only be called while
     * the engine is stopped — the worker-pool size locks at
     * {@link #start()} time so changing it requires a stop/start cycle.
     * Story 125.
     *
     * <p>Under {@link #lifecycleLock} for the same reason as
     * {@link #setFormat(AudioFormat)} (story 316 re-review): the running
     * check and the store are a read-then-write over a field
     * {@link #startLocked()} consumes inside the lock to size the worker pool
     * and the graph scheduler. Unlocked, a settings apply could pass the
     * check against a stopped engine and land its store after a concurrent
     * {@link #startAudioOutput()} had already read the OLD settings — a pool
     * sized from settings the caller believes were superseded, with nothing
     * in the log. The field stays {@code volatile} because
     * {@link #getWorkerPoolSize()} reads it lock-free.</p>
     *
     * @param settings the new settings; must not be null
     * @throws IllegalStateException if the engine is currently running
     */
    public void setEngineSettings(AudioEngineSettings settings) {
        lifecycleLock.lock();
        try {
            if (running.get()) {
                throw new IllegalStateException(
                        "Cannot change engine settings while engine is running");
            }
            this.engineSettings =
                    Objects.requireNonNull(settings, "settings must not be null");
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns the {@link AudioWorkerPool} currently driving parallel insert
     * dispatch, or {@code null} if the engine is not running or
     * parallelism is disabled (worker-pool size = 1). Story 125.
     *
     * @return the worker pool, or {@code null}
     */
    public AudioWorkerPool getWorkerPool() {
        return workerPool;
    }

    /**
     * Returns the {@link AudioGraphScheduler} currently installed on the
     * mixer, or {@code null} if the engine is not running or parallelism
     * is disabled. Story 125.
     *
     * @return the graph scheduler, or {@code null}
     */
    public AudioGraphScheduler getGraphScheduler() {
        return graphScheduler;
    }

    /**
     * Returns the configured worker-pool size (including the audio
     * callback as the coordinator). Reflects {@link AudioEngineSettings}
     * even when the engine is stopped. A return value of {@code 1}
     * indicates parallelism is disabled.
     *
     * @return the worker-pool size; {@code >= 1}
     */
    public int getWorkerPoolSize() {
        return engineSettings.workerPoolSize();
    }

    /**
     * Returns the number of parallel insert tasks dispatched during the
     * most recent block — the live "threads in use" reading shown in the
     * UI performance area (story 125). Returns {@code 0} when parallelism
     * is disabled, the engine is not running, or the previous block fell
     * back to sequential execution.
     *
     * @return the dispatched task count for the previous block; {@code >= 0}
     */
    public int getActiveThreadCount() {
        AudioGraphScheduler scheduler = this.graphScheduler;
        return scheduler == null ? 0 : scheduler.getLastDispatchedTaskCount();
    }


    /**
     * Immutable whole-project graph snapshot (story 314 review). Published
     * with a single volatile store so the RT callback can never observe a
     * half-swapped graph. Every component is nullable — {@code null} means
     * playback rendering is disabled for that aspect. In particular,
     * {@code tracks} is deliberately NOT normalized to an empty list:
     * {@link #getTracks()} must keep returning {@code null} when unset.
     */
    private record EngineGraph(Transport transport, Mixer mixer, List<Track> tracks) {
        static final EngineGraph EMPTY = new EngineGraph(null, null, null);
    }

    /**
     * Callback interface invoked from the audio thread to capture input audio
     * data during recording.
     */
    @FunctionalInterface
    public interface RecordingCallback {

        /**
         * Called from the audio thread with captured input audio data.
         *
         * @param inputBuffer the input audio data {@code [channel][frame]}
         * @param numFrames   the number of sample frames captured
         */
        void onAudioCaptured(float[][] inputBuffer, int numFrames);
    }
}
