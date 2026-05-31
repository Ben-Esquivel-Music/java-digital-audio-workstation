package com.benesquivelmusic.daw.app.ui.marshal;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The single marshalling seam between non-JavaFX threads and the JavaFX
 * Application Thread (Control Synchronization Design Book §2.6, §4.5; story
 * 289, Stage 1 of the §8 migration path).
 *
 * <p>State arrives on the FX thread from at least three other threads: the
 * MIDI receiver thread, the audio/metering thread, and background virtual
 * threads doing I/O (archiving, restoring, autosave). Before this seam the UI
 * layer hopped onto the FX thread from <strong>27 separate files</strong> that
 * each called {@code Platform.runLater} directly — an independent, un-ordered,
 * un-coalesced hop with nothing documenting which signals are allowed to
 * originate off-thread (§1.5). {@code FxDispatcher} replaces all of them: it is
 * the <em>only</em> place {@code Platform.runLater} (or an
 * {@link AnimationTimer}) appears in {@code daw-app} (§4.5), enforced by
 * {@code RunLaterConsolidationTest}.</p>
 *
 * <p>The seam exposes the two §4.5 jobs:</p>
 *
 * <h2>Discrete — {@link #onFx(Runnable)} / {@link #onFx(Object, Runnable)}</h2>
 *
 * <p>{@link #onFx(Runnable)} posts a one-shot runnable to the FX thread. It is
 * {@link java.util.concurrent.Executor}-compatible by design — {@code
 * fxDispatcher::onFx} is the {@code Executor} the production
 * {@link com.benesquivelmusic.daw.sdk.event.EventBus EventBus} uses for
 * {@link com.benesquivelmusic.daw.sdk.event.DispatchMode#ON_UI_THREAD
 * ON_UI_THREAD} delivery
 * ({@code DawApplication}). <strong>It performs an unconditional
 * {@link Platform#runLater(Runnable)} and never coalesces, drops, or defers a
 * post indefinitely.</strong> This is a hard contract: the bus's
 * {@code DefaultEventBus.Sub.runAndAwait} hands the runnable to this executor
 * and then blocks a daemon worker on a {@code CountDownLatch} until the runnable
 * has run on the FX thread; a seam that coalesced or dropped it would deadlock
 * that worker forever. And because {@code Platform.runLater} always enqueues
 * onto a later turn of the FX event loop — even when called from the FX thread
 * itself — {@link #onFx(Runnable)} likewise never runs a post inline. Call sites
 * that want an inline fast-path when they are already on the FX thread test
 * {@link Platform#isFxApplicationThread()} themselves and hand only the
 * off-thread case to the seam (as {@code MixerView} does).
 *
 * <p>{@link #onFx(Object, Runnable)} adds keyed per-frame coalescing: N posts
 * with the same {@code key} within one animation pulse collapse to a single
 * execution of the latest runnable (latest-wins). Use it for a burst of
 * duplicate discrete updates (a flurry of "refresh undo state" requests, say)
 * that need to happen once per frame, not N times.</p>
 *
 * <h2>Continuous — {@link #openContinuous(Consumer)}</h2>
 *
 * <p>A {@link ContinuousChannel} carries a high-frequency value (a meter level,
 * the playhead during playback) written by the producer with a lock-free,
 * wait-free {@link ContinuousChannel#publish(Object) publish} that
 * <strong>never blocks</strong> — safe to call from the {@link RealTimeSafe}
 * audio thread (§4.1, §4.6, §9). The dispatcher drains the channel once per
 * frame, keeps the latest value, and invokes the FX-thread consumer with it.
 * A ~1&nbsp;kHz meter stream therefore becomes ~60 coalesced UI updates/s, and
 * the audio thread never blocks on the UI (§4.5; {@code research-daw} §3
 * lock-free audio thread; {@code javafx-application-design} §6 "coalesce per
 * frame", §11 "the FX thread is sacred — marshal deliberately"). Each channel
 * is backed by a lock-free, wait-free, single-reader latest-value buffer (a
 * depth-1 coalescing mailbox; {@code @RealTimeSafe}). See {@link Channel}.</p>
 *
 * <h2>Coalescing pulse</h2>
 *
 * <p>{@link #start()} creates and starts a single {@link AnimationTimer} (the
 * only one this seam owns); its {@code handle} body is exactly {@link #pulse()}.
 * Each pulse runs every pending keyed runnable once then clears the keyed map,
 * and drains every continuous channel. {@link #onFx(Runnable)} does
 * <em>not</em> depend on the timer — it goes straight to {@code Platform
 * .runLater}; the timer only flushes the keyed-coalesce map and the continuous
 * channels. {@link #dispose()} stops the timer and clears both. Both
 * {@code start()} and {@code dispose()} are idempotent and must be called on the
 * FX thread (the {@code AnimationTimer} lifecycle is FX-thread state).</p>
 *
 * <h2>Stage-1 scope (story 289)</h2>
 *
 * <p>This story builds and fully unit-tests the continuous seam but does
 * <em>not</em> yet reroute the live playhead / meter polls through it: today
 * those read state on the FX thread each frame, and inserting a ring-buffer
 * round-trip would add a frame of latency (a behaviour change), while the real
 * {@code @RealTimeSafe} audio-thread producer cannot be added without giving
 * {@code daw-core} a metering tap (an explicit Non-Goal — {@code daw-core} stays
 * JavaFX-free). The seam exists and is tested; its audio-thread producer is
 * wired in a later stage when {@code daw-core} gains the metering tap (§4.6,
 * §5.8).</p>
 *
 * <h2>App-scoped default</h2>
 *
 * <p>The composition root ({@code DawApplication}) installs one instance via
 * {@link #installDefault(FxDispatcher)} so the awkward minority of call sites
 * that cannot take a constructor dependency — the FXML-instantiated
 * {@code MainController}, the toolkit-free {@code getDefault()} singletons
 * ({@code ThemeManager}, {@code DensityManager}), and short-lived dialogs — can
 * reach the one seam through {@link #getDefault()}. This mirrors the blessed
 * {@code EventBusPublisher} publisher seam (story 283): a single app-scoped
 * holder set once at startup, with constructor injection preferred wherever the
 * construction chain makes it natural. Tests install their own instance so they
 * can drive {@link #onFx(Runnable)} / {@link #pulse()} deterministically.</p>
 *
 * <p>This class is thread-safe: {@link #onFx(Runnable)},
 * {@link #onFx(Object, Runnable)} and {@link ContinuousChannel#publish(Object)}
 * may be called from any thread; {@link #start()}, {@link #pulse()} and
 * {@link #dispose()} run on the FX thread.</p>
 *
 * @see com.benesquivelmusic.daw.core.event.EventBusPublisher
 */
public final class FxDispatcher {

    /**
     * The application-wide dispatcher installed by the composition root.
     * {@code volatile} so a hot-swap via {@link #installDefault(FxDispatcher)}
     * (production startup, or a test installing its own instance) is visible to
     * every reader without tearing — the same discipline as
     * {@link com.benesquivelmusic.daw.core.event.EventBusPublisher}.
     */
    private static volatile FxDispatcher defaultInstance;

    /**
     * Latest runnable per coalescing key, run once on the next {@link #pulse()}.
     * A {@link ConcurrentHashMap} because {@link #onFx(Object, Runnable)} may be
     * called from any thread while the FX thread drains the map.
     */
    private final Map<Object, Runnable> keyedWork = new ConcurrentHashMap<>();

    /** Open continuous channels drained on every {@link #pulse()}. */
    private final List<Channel<?>> channels = new CopyOnWriteArrayList<>();

    /** The single per-frame coalescing timer; non-null only between
     *  {@link #start()} and {@link #dispose()}. */
    private AnimationTimer pulseTimer;

    /** Whether {@link #start()} has run (and {@link #dispose()} has not). */
    private boolean started;

    /** Creates an unstarted dispatcher. Call {@link #start()} on the FX thread. */
    public FxDispatcher() {
        // No FX state is touched until start(): constructable in any thread /
        // a toolkit-free unit context, like EventBusPublisher's bus reference.
    }

    // ── app-scoped default (mirrors EventBusPublisher) ───────────────────────

    /**
     * Installs the application-wide dispatcher. Idempotent; passing
     * {@code null} clears the default (test teardown / app shutdown). The
     * caller owns the instance's {@link #start()} / {@link #dispose()}
     * lifecycle — this only publishes the reference for {@link #getDefault()}.
     *
     * @param dispatcher the dispatcher to publish, or {@code null} to clear
     */
    public static void installDefault(FxDispatcher dispatcher) {
        defaultInstance = dispatcher;
    }

    /**
     * Returns the application-wide dispatcher installed by the composition
     * root, or {@code null} when none is installed (a pure-unit context that
     * never called {@link #installDefault(FxDispatcher)}). Callers that hold a
     * long-lived reference should capture the returned instance once rather
     * than re-read on every use (the "capture swappable singleton reference
     * once" discipline).
     *
     * @return the current default dispatcher, or {@code null}
     */
    public static FxDispatcher getDefault() {
        return defaultInstance;
    }

    /**
     * Static convenience that posts {@code work} to the FX thread through the
     * {@link #getDefault() app-scoped default} dispatcher, or — if none is
     * installed — falls back to a direct {@link Platform#runLater(Runnable)}.
     *
     * <p>This is the drop-in for the call sites that cannot take a constructor
     * dependency on the seam: the {@code getDefault()} singletons
     * ({@code ThemeManager}, {@code DensityManager}), short-lived dialogs, and
     * views constructed deep in a chain. It preserves behaviour exactly — the
     * work always ends in a {@code Platform.runLater} — while keeping
     * {@code Platform.runLater} confined to this one file (enforced by
     * {@code RunLaterConsolidationTest}). The fallback matters for unit tests
     * that start the JavaFX toolkit but not {@code DawApplication}: there the
     * default is unset, yet a {@code runLater} is still both valid and required,
     * just as the bare call these sites used previously was.</p>
     *
     * <p>Callers that already hold an injected {@code FxDispatcher} should call
     * the instance {@link #onFx(Runnable)} directly; this static seam is only
     * for the injection-awkward minority.</p>
     *
     * @param work the runnable to execute on the FX thread; must not be
     *             {@code null}
     */
    public static void runOnFx(Runnable work) {
        runOnFx(null, work);
    }

    /**
     * Posts {@code work} to the FX thread, preferring {@code preferred} when it
     * is non-{@code null} and otherwise falling back to the {@link #getDefault()
     * app-scoped default} (and, with no dispatcher installed anywhere, a direct
     * {@link Platform#runLater(Runnable)}).
     *
     * <p>This is the single home for the "prefer an injected dispatcher, else
     * the app-scoped default" resolution that the injection-aware call sites
     * (e.g. {@code MixerView}, {@code BrowserPanel}, {@code MainController} and
     * the other controllers) previously each re-implemented as a private
     * {@code postFx} helper. Those helpers now delegate here, so the rule lives
     * in one place. Behaviour is identical to the former inlined form: the work
     * always ends in {@link #onFx(Runnable)} (an unconditional {@code
     * Platform.runLater}), or in a bare {@code runLater} when no dispatcher is
     * reachable at all (the pure-unit context).</p>
     *
     * @param preferred the call site's injected dispatcher, or {@code null} to
     *                  use the app-scoped default
     * @param work      the runnable to execute on the FX thread; must not be
     *                  {@code null}
     */
    public static void runOnFx(FxDispatcher preferred, Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        FxDispatcher target = preferred != null ? preferred : defaultInstance;
        if (target != null) {
            target.onFx(work);
        } else {
            Platform.runLater(work);
        }
    }

    // ── discrete ─────────────────────────────────────────────────────────────

    /**
     * Posts {@code work} to the JavaFX Application Thread via an unconditional
     * {@link Platform#runLater(Runnable)}. {@link java.util.concurrent.Executor}-
     * compatible: this is the {@code Executor} the production
     * {@link com.benesquivelmusic.daw.sdk.event.EventBus EventBus} uses for
     * {@link com.benesquivelmusic.daw.sdk.event.DispatchMode#ON_UI_THREAD}
     * delivery.
     *
     * <p>Never coalesces, never drops, and — because {@code Platform.runLater}
     * always enqueues onto a later turn of the FX event loop — never runs inline
     * even when called from the FX thread (see the class Javadoc: the bus blocks
     * a worker until this runnable runs, so the post must not be coalesced or
     * dropped). This is the single permitted {@code Platform.runLater} call site
     * in {@code daw-app}.</p>
     *
     * @param work the runnable to execute on the FX thread; must not be
     *             {@code null}
     */
    public void onFx(Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        Platform.runLater(work);
    }

    /**
     * Posts {@code work} to the FX thread with per-frame coalescing keyed by
     * {@code key}: only the latest runnable stored under a given key survives
     * to the next {@link #pulse()}, where it runs exactly once. N same-key posts
     * within one frame therefore collapse to one execution (latest-wins);
     * distinct keys each run once.
     *
     * <p>Requires {@link #start()} to have been called so the pulse timer is
     * draining the keyed map; until then the work accumulates and runs on the
     * first pulse after start. Use this for a burst of duplicate discrete
     * refreshes that need to happen once per frame, not for work the
     * {@link com.benesquivelmusic.daw.sdk.event.EventBus EventBus}'s blocking
     * UI executor relies on (use {@link #onFx(Runnable)} for that).</p>
     *
     * @param key  the coalescing key; must not be {@code null}
     * @param work the runnable to run once per frame under {@code key}; must not
     *             be {@code null}
     */
    public void onFx(Object key, Runnable work) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(work, "work must not be null");
        keyedWork.put(key, work);
    }

    // ── continuous ───────────────────────────────────────────────────────────

    /**
     * A lock-free, single-reader channel carrying a high-frequency value from
     * a producer thread to an FX-thread consumer, coalesced once per frame.
     *
     * @param <T> the value type
     */
    public interface ContinuousChannel<T> {
        /**
         * Publishes the latest value. Lock-free, wait-free and non-blocking —
         * safe to call from the {@link RealTimeSafe} audio thread. The backing
         * buffer is a depth-1 latest-wins mailbox: a value published before the
         * next frame's drain overwrites the previous unconsumed value, so the
         * newest sample is never lost (an earlier un-drained value is) and the
         * producer never blocks on the UI.
         *
         * @param value the value to publish; must not be {@code null}
         */
        @RealTimeSafe
        void publish(T value);
    }

    /**
     * Opens a continuous channel whose latest published value is delivered to
     * {@code fxConsumer} on the FX thread at most once per frame. On each
     * {@link #pulse()} the channel's latest value is taken (and cleared), and —
     * only if a value was published since the last pulse — {@code fxConsumer} is
     * invoked with it. The channel stays open until {@link #dispose()}.
     *
     * @param fxConsumer the FX-thread consumer of the coalesced latest value;
     *                   must not be {@code null}
     * @param <T>        the value type
     * @return the channel the producer publishes into
     */
    public <T> ContinuousChannel<T> openContinuous(Consumer<T> fxConsumer) {
        Objects.requireNonNull(fxConsumer, "fxConsumer must not be null");
        Channel<T> channel = new Channel<>(fxConsumer);
        channels.add(channel);
        return channel;
    }

    // ── pulse / lifecycle ────────────────────────────────────────────────────

    /**
     * Drains the keyed-coalesce map (running each pending keyed runnable once)
     * then every continuous channel. The owned {@link AnimationTimer}'s {@code
     * handle} body is exactly this call; it runs on the FX thread.
     *
     * <p>Exposed (the enclosing module exports / opens nothing, so this is not a
     * widened public API) so the story's tests — which must live in the {@code
     * …app.ui} package for the JavaFX-toolkit extension to work under JPMS, a
     * different package from this seam — can drive the pulse manually rather
     * than relying on a live timer.</p>
     */
    public void pulse() {
        // Snapshot the keyed entries first, then remove each by key AND value, so
        // work posted while this pulse runs (including a runnable that re-posts
        // under the same key) stays queued for the NEXT pulse. A weakly
        // consistent keySet() iterator could otherwise pick up such concurrent
        // posts and run them in this same pulse nondeterministically, violating
        // the documented per-frame coalescing contract.
        if (!keyedWork.isEmpty()) {
            List<Map.Entry<Object, Runnable>> snapshot =
                    new ArrayList<>(keyedWork.entrySet());
            for (Map.Entry<Object, Runnable> entry : snapshot) {
                if (keyedWork.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().run();
                }
            }
        }
        for (Channel<?> channel : channels) {
            channel.drain();
        }
    }

    /**
     * Creates and starts the single per-frame {@link AnimationTimer} whose
     * {@code handle} body is {@link #pulse()}. Idempotent — a second call while
     * already started is a no-op. Must be called on the FX thread.
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        pulseTimer = new PulseTimer();
        pulseTimer.start();
    }

    /**
     * Stops the pulse timer and clears the keyed map and continuous channels.
     * Idempotent — safe to call when never started or already disposed. Must be
     * called on the FX thread.
     */
    public void dispose() {
        started = false;
        if (pulseTimer != null) {
            pulseTimer.stop();
            pulseTimer = null;
        }
        keyedWork.clear();
        channels.clear();
    }

    /**
     * The one {@link AnimationTimer} this seam owns. Extracted as a named type
     * (rather than an anonymous subclass) so {@code RunLaterConsolidationTest}'s
     * {@code new AnimationTimer} / {@code extends AnimationTimer} scan finds it
     * only inside {@code FxDispatcher} — the seam's own file is excluded from
     * that scan by name, so this timer needs no {@code @FxAnimationTimerAllowed}
     * sentinel (it <em>is</em> the legitimate seam timer §4.5 sanctions).
     */
    private final class PulseTimer extends AnimationTimer {
        @Override
        public void handle(long now) {
            pulse();
        }
    }

    /**
     * One continuous channel: a lock-free, wait-free, single-reader
     * latest-value buffer (a depth-1 coalescing mailbox). The producer
     * {@link #publish(Object) publishes} the newest value; the FX-thread
     * {@link #drain()} once per frame takes whatever is there and clears it.
     *
     * <p>A depth-1 atomic mailbox is the correct realization of §4.5's
     * "lock-free single-reader buffer drained once per frame" for a continuous
     * value whose consumer only ever wants the <em>latest</em>: it coalesces a
     * full frame's worth of writes (a ~1&nbsp;kHz stream → one value per ~60&nbsp;Hz
     * frame) into the freshest sample with no allocation and no loss of the
     * latest. Unlike a bounded ring, it can never deliver a <em>stale</em> value
     * by dropping the newest write on overflow — "latest always wins" holds
     * regardless of how far the producer outruns the drain. {@link AtomicReference}
     * gives single-producer/single-consumer wait-freedom; {@code lazySet} is
     * sufficient and avoids a full fence, and the producer never blocks on the
     * consumer (§4.1, §4.6 — safe for the {@link RealTimeSafe} audio thread).</p>
     *
     * @param <T> the value type
     */
    private static final class Channel<T> implements ContinuousChannel<T> {

        /** Holds the latest published value, or {@code null} when drained / empty. */
        private final AtomicReference<T> latest = new AtomicReference<>();
        private final Consumer<T> fxConsumer;

        Channel(Consumer<T> fxConsumer) {
            this.fxConsumer = fxConsumer;
        }

        @Override
        @RealTimeSafe
        public void publish(T value) {
            Objects.requireNonNull(value, "value must not be null");
            // Wait-free latest-wins overwrite: never blocks, never drops the
            // newest — the audio thread must never block on the UI (§4.1, §4.6).
            latest.lazySet(value);
        }

        /** Takes the latest value (if any) and delivers it once on the FX thread. */
        void drain() {
            T value = latest.getAndSet(null);
            if (value != null) {
                fxConsumer.accept(value);
            }
        }
    }
}
