package com.benesquivelmusic.daw.core.transport;

import com.benesquivelmusic.daw.core.concurrent.ChangeNotifier;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Controls the playback transport of the DAW (play, stop, record, pause).
 *
 * <p>The transport maintains the current playback position and state,
 * coordinating with the audio engine to start and stop audio processing.</p>
 *
 * <p>Tempo and time signature data are managed by an associated
 * {@link TempoMap}, which supports multiple tempo and time signature
 * changes along the timeline.</p>
 *
 * <h2>Threading contract (Audio Engine Wiring Design Book §4.1, §6.1)</h2>
 *
 * <p>The RT audio callback <em>owns the position advance</em>: it is the only
 * production caller of {@link #advancePosition(double)} (from
 * {@code RenderPipeline} at the end of each rendered block). The FX thread
 * owns every other mutator (the control writes) and reaches the RT-owned
 * position only via the defined seam — the <em>seek queue</em>:</p>
 *
 * <ul>
 *   <li>{@code state}, {@code positionInBeats}, the {@link LoopWindow} and the
 *       roll flags are {@code volatile}, so a value written on one thread is
 *       never stale or torn when read on another (JMM safe publication).</li>
 *   <li>The seek queue is armed only while an RT clock has explicitly
 *       {@linkplain #setRealTimeClockActive(boolean) claimed} this transport —
 *       i.e. while a live audio callback really is calling
 *       {@link #advancePosition(double)} on it. Deferral without such a driver
 *       would strand every seek forever (nothing would ever drain the queue),
 *       so an unclaimed transport applies seeks inline exactly as it did
 *       before the queue existed.</li>
 *   <li>{@link #setPositionInBeats(double)} issued while the transport is
 *       {@linkplain TransportState#PLAYING playing} or
 *       {@linkplain TransportState#RECORDING recording} <em>and</em> claimed
 *       does not race the RT read-modify-write: the target is published into a
 *       single-slot, last-writer-wins seek queue and applied by the RT thread
 *       exactly at the next block boundary — a seek is never lost, never
 *       torn.</li>
 *   <li>{@link #advancePosition(double)} is {@code @RealTimeSafe}: it drains
 *       the seek queue commit-before-clear — the target is <em>peeked</em>,
 *       committed into the position, and only then CAS-cleared out of the
 *       slot — then advances via a lock-free {@link VarHandle} CAS loop with a
 *       closed-form loop wrap; no locks, no allocation. The queue is never
 *       empty while its target is uncommitted, so
 *       {@link #getSeekTargetInBeats()} can never return the pre-seek base (a
 *       pop-then-commit drain had exactly that gap, collapsing two relative
 *       seeks that straddled it into one). The drain ignores the claim flag —
 *       a claim released mid-flight must not strand a seek.</li>
 *   <li>{@link #stop()} and {@link #pause()} store their non-rolling state
 *       <em>before</em> touching the queue or the position, and
 *       {@link #advancePosition(double)} re-reads the state <em>after</em> its
 *       seek peek and after its successful CAS, undoing an advance that landed
 *       past the transition. The state store alone is not sufficient: an
 *       advance whose CAS lands before it observes the new state would
 *       otherwise keep its block when the transition writes no position of
 *       its own (see {@link #advancePosition(double)}). The
 *       position-ownership protocol below is what makes that undo sound.</li>
 * </ul>
 *
 * <h2>Position ownership protocol</h2>
 *
 * <p>The RT thread <em>owns the position word</em> for the whole of an
 * advance: {@link #advancePosition(double)} increments
 * {@code advancesInFlight} (an {@link AtomicInteger}, full fence) before it
 * reads the state, peeks the queue, or touches the position, and decrements
 * it only after its CAS — and any compensating CAS — has retired. The two
 * rolling-to-non-rolling transitions, {@link #stop()} and {@link #pause()},
 * <em>acquire</em> the word by storing their new state first and then
 * awaiting {@code advancesInFlight == 0}; only then do they clear or drain the
 * queue and store the position. This is a Dekker pairing: the advance
 * increments before reading the state, the transition stores the state
 * before reading the counter, so either the advance observes the non-rolling
 * state and leaves without a CAS, or the transition observes the counter and
 * waits until that advance has fully retired (committed or compensated).
 * Consequently no transition store can ever interleave between an RT CAS and
 * its compensation, and the compensation's outcome never depends on the
 * position's <em>value</em> bits — the ABA where a stop/pause stored the very
 * bits the RT CAS had just written (a seek onto the anchor, a loop wrap
 * landing on the anchor, a seek that pause() itself commits) and a
 * value-inferred undo then rolled the transition's authoritative position
 * back is impossible by construction (story 315 review). Every other control
 * write (seek while not rolling, loop, tempo, pre-roll) is FX-thread
 * serialized with those transitions, and the RT thread never blocks,
 * allocates, or parks inside the owned window, so the transition's wait is a
 * handful of instructions long. Change listeners fire only <em>outside</em>
 * the owned window, so a listener that calls {@code stop()} or
 * {@code pause()} on the RT thread can never await itself.</p>
 *
 * <h2>Toolkit-neutral change notification</h2>
 *
 * <p>Per the Control Synchronization Design Book (§1.3, §2.5, §3.2) the
 * transport exposes a <em>toolkit-neutral</em> notification seam: observers
 * register a {@link Consumer}&lt;{@link ChangeKind}&gt; via
 * {@link #addChangeListener(Consumer)} and are invoked <strong>after</strong>
 * each field mutation completes, so an observer that re-reads the transport in
 * its callback always sees the post-mutation value. The signal carries only a
 * small {@link ChangeKind} tag describing <em>what</em> slice changed — never a
 * state-bearing delta and, critically, never a {@code javafx.beans.*} type: the
 * core stays JavaFX-free so the view-model adapter
 * ({@code daw-app/.../ui/vm/TransportVM}) is the sole bridge to FX Properties
 * (§9 rejection list). Notification is lock-free and allocation-free (an
 * immutable listener-snapshot array, read once and iterated by index), so
 * {@link #advancePosition(double)} may fire from the {@code @RealTimeSafe} render
 * path; the only sanctioned cross-thread sink is the view-model's lock-free,
 * single-reader buffer (§4.1, §4.6).</p>
 */
public final class Transport {

    private static final double DEFAULT_LOOP_START = 0.0;
    private static final double DEFAULT_LOOP_END = 16.0;

    /**
     * Sentinel meaning "no seek pending" in {@link #pendingSeek}. The canonical
     * NaN bit pattern can never collide with a real seek target because
     * {@link #setPositionInBeats(double)} rejects NaN (and every other
     * non-finite or negative value) before publishing into the queue.
     */
    private static final long NO_SEEK = Double.doubleToRawLongBits(Double.NaN);

    /**
     * Number of busy spins {@link #awaitNoAdvanceInFlight()} performs before
     * falling back to {@link Thread#yield()} per iteration. An in-flight
     * advance retires within a handful of instructions, so the spin phase
     * covers the common case; the yield phase only matters when the RT thread
     * was preempted mid-advance on a loaded single core.
     */
    private static final int SPINS_BEFORE_YIELD = 1_000;

    /**
     * {@link VarHandle} over {@link #positionInBeats} enabling the lock-free
     * compare-and-set advance in {@link #advancePosition(double)}.
     */
    private static final VarHandle POSITION;

    static {
        try {
            POSITION = MethodHandles.lookup()
                    .findVarHandle(Transport.class, "positionInBeats", double.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The kind of transport slice that a change notification refers to.
     *
     * <p>An observer registered via {@link Transport#addChangeListener(Consumer)}
     * re-reads only the affected slice from the transport when it receives the
     * matching tag (Control Synchronization Design Book §3.2, §3.4).</p>
     */
    public enum ChangeKind {
        /** The {@linkplain TransportState transport state} changed (play/stop/pause/record). */
        STATE,
        /** The initial tempo (BPM) changed. */
        TEMPO,
        /** The initial time signature changed. */
        TIME_SIGNATURE,
        /** The loop region or loop-enabled flag changed. */
        LOOP,
        /** The playback position changed (seek, scrub, or per-block advance). */
        POSITION
    }

    /**
     * Immutable snapshot of the loop region — the enabled flag and both
     * boundaries published as <em>one</em> {@code volatile} reference so the
     * RT thread can read the trio consistently in a single load. Three
     * separate fields could tear across a concurrent FX-thread update; a
     * record cannot (story 315, Audio Engine Wiring Design Book §4.1).
     *
     * <p>Every mutator installs a complete window in one store, and
     * {@link Transport#setLoopWindow(boolean, double, double)} is the single
     * call that publishes all three fields together — the way to
     * <em>define</em> a new loop without the RT thread ever observing the
     * enabled flag paired with the previous bounds (story 315 review).</p>
     *
     * @param startInBeats loop start position in beats (finite, &ge; 0)
     * @param endInBeats   loop end position in beats (finite, &gt;
     *                     {@code startInBeats} for any window installed via
     *                     {@link Transport#setLoopWindow(boolean, double, double)}
     *                     or {@link Transport#setLoopRegion(double, double)})
     * @param enabled      whether loop mode is engaged
     */
    public record LoopWindow(boolean enabled, double startInBeats, double endInBeats) {
    }

    /**
     * Backs the toolkit-neutral {@code Consumer<ChangeKind>} change signal — the
     * register / unregister / lock-free notify mechanism lives in the shared
     * {@link ChangeNotifier}. {@link ChangeNotifier#fire} allocates nothing, so it
     * is safe to invoke from the {@code @RealTimeSafe} render path via
     * {@link #advancePosition(double)}.
     */
    private final ChangeNotifier<ChangeKind> changes = new ChangeNotifier<>();

    private volatile TransportState state = TransportState.STOPPED;
    private volatile double positionInBeats = 0.0;
    private final TempoMap tempoMap = new TempoMap();
    private volatile LoopWindow loopWindow =
            new LoopWindow(false, DEFAULT_LOOP_START, DEFAULT_LOOP_END);
    private volatile PunchRegion punchRegion;
    private volatile PreRollPostRoll preRollPostRoll = PreRollPostRoll.DISABLED;
    private volatile boolean inPreRoll = false;
    private volatile boolean inPostRoll = false;

    /**
     * Single-slot, last-writer-wins seek queue: {@code doubleToRawLongBits} of
     * the pending seek target, or {@link #NO_SEEK}. Written by the FX thread
     * ({@link #setPositionInBeats(double)} while rolling <em>and</em> claimed),
     * drained by the RT thread at the next {@link #advancePosition(double)}
     * (and by {@link #pause()}, whose state flip halts the RT advance, and by
     * {@link #setRealTimeClockActive(boolean) releasing the claim}). Every
     * drain commits the target into {@link #positionInBeats} <em>before</em>
     * clearing the slot, and clears via {@code compareAndSet} on the peeked
     * value — so {@link #getSeekTargetInBeats()} never observes an
     * empty-queue-but-uncommitted gap, and a newer target published mid-drain
     * survives for the next drain. {@link #stop()} and {@link #pause()} touch
     * the slot only after {@linkplain #awaitNoAdvanceInFlight() awaiting} the
     * retirement of any in-flight RT drain, so a transition never clears or
     * commits a slot that an RT drain is mid-way through committing. In
     * production all control writes are FX-thread-serialized; the queue exists
     * solely for the FX&harr;RT race.
     */
    private final AtomicLong pendingSeek = new AtomicLong(NO_SEEK);

    /**
     * Number of {@link #advancePosition(double)} calls currently owning the
     * position word (see the class-level position ownership protocol). In
     * production there is a single RT writer, so this is effectively a flag;
     * it is a counter rather than a boolean so that multi-threaded tests that
     * drive several advances at once stay correct. Incremented (full fence)
     * before the advance reads any state and decremented after its CAS and
     * compensation have retired; {@link #stop()} and {@link #pause()} store
     * their state and then {@linkplain #awaitNoAdvanceInFlight() spin} until
     * it reads zero before touching the queue or the position. Increment and
     * decrement are allocation-free and lock-free, so the RT path stays
     * {@code @RealTimeSafe}.
     */
    private final AtomicInteger advancesInFlight = new AtomicInteger();

    /**
     * Whether a real-time clock has claimed this transport — i.e. whether an
     * audio callback is driving {@link #advancePosition(double)} on it, or is
     * about to: the claim is taken immediately before the stream is started,
     * and released when the stream stops, pauses, or fails to start (story
     * 315 review; Audio Engine Wiring Design Book §6.1 thread ownership).
     *
     * <p>This is the seek queue's arming condition. The transport state alone
     * is <em>not</em> a truthful proxy for "something is advancing me":
     * {@code AudioEngine.startAudioOutput()} returns early when no backend is
     * configured ("playback without hardware output"), yet the UI still calls
     * {@link #play()} — the transport is {@link TransportState#PLAYING} with
     * nobody rendering. Deferring seeks in that state queues them behind a
     * drain that never comes, freezing the playhead and the time display.
     * Default {@code false}: an unclaimed transport applies every seek
     * inline.</p>
     */
    private volatile boolean realTimeClockActive = false;

    /**
     * The position at which Play (or Record) last started — the anchor that
     * {@link #stop()} returns the playhead to when
     * {@link #isReturnToStartOnStop()} is set. Resume-from-pause re-anchors at
     * the resume position.
     */
    private volatile double playStartAnchor = 0.0;

    /**
     * Whether {@link #stop()} returns the playhead to {@link #playStartAnchor}.
     * The story-305 settings layer seeds this flag; the core holds only the
     * flag itself (no preference persistence here).
     */
    private volatile boolean returnToStartOnStop = true;

    /**
     * Starts playback from the current position.
     *
     * <p>Records the current position as the play-start anchor that
     * {@link #stop()} returns to. Calling {@code play()} after {@link #pause()}
     * re-anchors at the resume position — Stop then returns to where playback
     * last resumed, matching common DAW convention.</p>
     */
    public void play() {
        playStartAnchor = positionInBeats;
        inPreRoll = false;
        inPostRoll = false;
        state = TransportState.PLAYING;
        notifyChange(ChangeKind.STATE);
    }

    /**
     * Stops playback and returns the playhead to the play-start anchor (when
     * {@link #isReturnToStartOnStop()} is set; otherwise the position is left
     * where it was).
     *
     * <p>Idempotent: calling {@code stop()} while already
     * {@link TransportState#STOPPED} is a no-op and fires no signal. The
     * "second Stop rewinds to zero" behaviour is a <em>gesture-level</em>
     * semantic owned by the UI layer, deliberately not implemented here —
     * internal callers (e.g. {@code RecordingPipeline.stop()}) must be able to
     * stop the transport without a follow-up UI stop yanking the playhead to
     * zero.</p>
     *
     * <p>Ordering: the {@link TransportState#STOPPED} store happens
     * <em>first</em> so a concurrent {@link #advancePosition(double)} sees the
     * stop as early as possible; the stop then
     * {@linkplain #awaitNoAdvanceInFlight() awaits} the retirement of any
     * advance that was already inside its owned window, and only afterwards
     * clears the queue and stores the anchor. The state store is the cheap
     * half of the guarantee, not the whole of it — an advance whose CAS landed
     * before it observed the stop would otherwise keep its block when the stop
     * writes no position of its own (return-to-start disabled), so the advance
     * must re-read the state after its CAS and undo itself; the await is what makes
     * that undo sound, because it guarantees this stop's own position store
     * can never land between the RT CAS and its compensation (see
     * {@link #advancePosition(double)} and the class-level ownership
     * protocol). A queued seek is discarded — a stop supersedes it; otherwise
     * the next Play's first block would apply a stale seek.</p>
     */
    public void stop() {
        if (state == TransportState.STOPPED) {
            return;
        }
        state = TransportState.STOPPED;
        inPreRoll = false;
        inPostRoll = false;
        awaitNoAdvanceInFlight();
        pendingSeek.set(NO_SEEK);
        boolean moved = false;
        if (returnToStartOnStop) {
            double anchor = playStartAnchor;
            moved = positionInBeats != anchor;
            positionInBeats = anchor;
        }
        notifyChange(ChangeKind.STATE);
        if (moved) {
            notifyChange(ChangeKind.POSITION);
        }
    }

    /**
     * Pauses playback at the current position.
     *
     * <p>Drains the seek queue: a seek issued during playback still lands even
     * though the RT advance (which normally applies queued seeks) halts once
     * the state flips to {@link TransportState#PAUSED}.</p>
     *
     * <p>Ordering: the {@link TransportState#PAUSED} store happens
     * <em>first</em>, then the pause
     * {@linkplain #awaitNoAdvanceInFlight() awaits} the retirement of any
     * advance already inside its owned window, and only afterwards peeks,
     * commits and clears the queue. An RT drain that observed the pause after
     * its CAS rolls its commit back and leaves the slot untouched, so this
     * drain always finds the target still queued; an RT drain that committed
     * while still rolling cleared the slot, so this drain finds nothing to do.
     * Either way the seek lands exactly once and is never lost — the await is
     * what rules out the pause's own commit landing between the RT CAS and
     * its compensation (class-level ownership protocol).</p>
     */
    public void pause() {
        if (state == TransportState.PLAYING || state == TransportState.RECORDING) {
            state = TransportState.PAUSED;
            awaitNoAdvanceInFlight();
            // Commit-before-clear (see getSeekTargetInBeats): peek the target,
            // store it, and only then CAS the slot empty, so the target stays
            // observable until the position includes it. A deliberate side
            // effect of the peek: an RT drain that had already popped the
            // queue used to make the seek invisible here and it was silently
            // discarded — now the target stays visible until committed and
            // this drain applies it, exactly as the contract above promises.
            long seek = pendingSeek.get();
            boolean sought = seek != NO_SEEK;
            if (sought) {
                positionInBeats = Double.longBitsToDouble(seek);
                pendingSeek.compareAndSet(seek, NO_SEEK);
            }
            notifyChange(ChangeKind.STATE);
            if (sought) {
                notifyChange(ChangeKind.POSITION);
            }
        }
    }

    /**
     * Blocks the calling control thread until no {@link #advancePosition(double)}
     * owns the position word — the acquire half of the class-level position
     * ownership protocol. Called by {@link #stop()} and {@link #pause()}
     * <em>after</em> their volatile state store (Dekker: the advance
     * increments the counter before reading the state, so an advance that
     * starts after this store sees the non-rolling state and never CASes,
     * while one that started before it is counted here and waited out).
     *
     * <p>The wait is the length of a handful of CAS instructions: the RT
     * thread never blocks, allocates, parks, or runs listeners inside its
     * owned window. It spins with {@link Thread#onSpinWait()} and, after a
     * bounded number of spins, yields per iteration so an RT thread that was
     * preempted on a loaded single core can run and retire. Never called from
     * inside an owned window (by construction — no callbacks fire there), so
     * it cannot self-deadlock.</p>
     */
    private void awaitNoAdvanceInFlight() {
        int spins = 0;
        while (advancesInFlight.get() > 0) {
            if (spins < SPINS_BEFORE_YIELD) {
                spins++;
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
    }

    /**
     * Starts recording from the current position, anchoring the play-start
     * position exactly as {@link #play()} does — {@link #stop()} after a
     * recording pass returns the playhead to where the pass started.
     */
    public void record() {
        playStartAnchor = positionInBeats;
        state = TransportState.RECORDING;
        notifyChange(ChangeKind.STATE);
    }

    /** Returns the current transport state. */
    public TransportState getState() {
        return state;
    }

    /** Returns the current playback position in beats. */
    public double getPositionInBeats() {
        return positionInBeats;
    }

    /**
     * Sets the playback position in beats.
     *
     * <p>The seek is deferred to the RT thread <em>only</em> when the transport
     * is {@linkplain TransportState#PLAYING playing} or
     * {@linkplain TransportState#RECORDING recording} <strong>and</strong> a
     * real-time clock has {@linkplain #setRealTimeClockActive(boolean) claimed}
     * it: the target is then published into the last-writer-wins seek queue and
     * applied by the RT thread at the next block boundary (which fires
     * {@link ChangeKind#POSITION}), and this method returns without firing. In
     * every other case — stopped, paused, or rolling with nothing actually
     * driving {@link #advancePosition(double)} — the position is stored
     * immediately, any stale queued seek is discarded, and
     * {@link ChangeKind#POSITION} fires here.</p>
     *
     * <p>Deferring without a driver would strand the seek forever: only the RT
     * advance and the claim-release drain the queue, so a "playing" transport
     * that no callback is rendering (e.g. the engine started with no audio
     * backend configured) would freeze its playhead and its time display on
     * every ruler click and skip.</p>
     *
     * <p>Relative seeks (skip forward/back, nudges) must compose against
     * {@link #getSeekTargetInBeats()}, not this class's committed position —
     * see that method.</p>
     *
     * @param positionInBeats the target position (must be finite and &ge; 0)
     * @throws IllegalArgumentException if the position is negative, NaN, or
     *                                  infinite
     */
    public void setPositionInBeats(double positionInBeats) {
        if (Double.isNaN(positionInBeats) || Double.isInfinite(positionInBeats)) {
            throw new IllegalArgumentException("position must be finite: " + positionInBeats);
        }
        if (positionInBeats < 0) {
            throw new IllegalArgumentException("position must not be negative: " + positionInBeats);
        }
        TransportState current = state;
        boolean rolling = current == TransportState.PLAYING
                || current == TransportState.RECORDING;
        if (rolling && realTimeClockActive) {
            pendingSeek.set(Double.doubleToRawLongBits(positionInBeats));
            // Re-read the claim after publishing (Dekker-style double check):
            // setRealTimeClockActive(false) stores the flag BEFORE draining, so
            // between the two volatile accesses at least one side observes the
            // other and the seek is applied exactly once — never stranded by a
            // stream that closed microseconds after the gate check passed.
            if (!realTimeClockActive) {
                drainPendingSeekInline();
            }
            return;
        }
        pendingSeek.set(NO_SEEK);
        this.positionInBeats = positionInBeats;
        notifyChange(ChangeKind.POSITION);
    }

    /**
     * Returns the position that a <em>relative</em> seek must be computed
     * from: the pending seek target when one is queued, otherwise
     * {@link #getPositionInBeats()}.
     *
     * <p>{@link #getPositionInBeats()} returns the <em>committed</em> position,
     * which does not move while a seek sits in the queue. Two skip-forward
     * presses inside one audio block would therefore both compute
     * {@code position + jump} from the same base, and the single-slot,
     * last-writer-wins queue would keep only the second — the playhead would
     * land one jump ahead instead of two. Composing against this method makes
     * successive relative seeks accumulate:</p>
     *
     * <pre>{@code
     * transport.setPositionInBeats(
     *         Math.max(0.0, transport.getSeekTargetInBeats() + jumpInBeats));
     * }</pre>
     *
     * <p>Absolute seeks (ruler click, marker jump, go-to-start) must keep using
     * {@link #getPositionInBeats()} semantics — they do not read the base at
     * all.</p>
     *
     * <p>Invariant (story 315 review): every drain commits the queued target
     * into the position <em>before</em> clearing the queue, so at every
     * instant this method returns either the queued target or a committed
     * position that already includes it — never the pre-seek base. Relative
     * seeks composed against it therefore always accumulate, even when they
     * straddle an in-flight RT drain; two skips can never collapse into
     * one.</p>
     *
     * @return the queued seek target if one is pending, else the committed
     *         position
     */
    public double getSeekTargetInBeats() {
        long seek = pendingSeek.get();
        return seek == NO_SEEK ? positionInBeats : Double.longBitsToDouble(seek);
    }

    /**
     * Claims or releases the real-time clock for this transport — the explicit
     * ownership seam behind the seek queue (story 315 review; Audio Engine
     * Wiring Design Book §6.1).
     *
     * <p>Claim ({@code true}) immediately <em>before</em> starting an audio
     * stream whose callback will call {@link #advancePosition(double)} on
     * <em>this</em> transport — the backend may run the first callback block
     * before its start call returns, so claiming afterwards would let that
     * block race an inline seek — and release ({@code false}) when the stream
     * stops, pauses, or fails to start. Seeks issued between the claim and the
     * first callback block are queued and applied by that first block (or by
     * the release, when the start fails). The invariant is "claimed &hArr; a
     * callback is driving, or is about to drive, this transport";
     * {@code AudioEngine} maintains it across its stream lifecycle and hands
     * the claim over on {@code setGraph}. Defaults to {@code false}, so a
     * transport that nothing renders (unit tests, offline export, a playback
     * start that found no audio backend) never defers a seek.</p>
     *
     * <p>Releasing drains any queued seek inline — applying it and firing
     * {@link ChangeKind#POSITION} — so a seek issued microseconds before the
     * stream closed is not silently lost. The flag is stored <em>before</em>
     * the drain so a concurrent {@link #setPositionInBeats(double)} that
     * already passed the gate check either sees the release (and applies
     * inline itself) or lands in the queue in time for this drain.</p>
     *
     * @param active {@code true} to claim, {@code false} to release
     */
    public void setRealTimeClockActive(boolean active) {
        if (active) {
            this.realTimeClockActive = true;
            return;
        }
        this.realTimeClockActive = false;
        drainPendingSeekInline();
    }

    /**
     * Returns whether a real-time clock currently
     * {@linkplain #setRealTimeClockActive(boolean) claims} this transport.
     *
     * @return {@code true} while an audio callback is driving — or, the claim
     *         being taken immediately before the stream is started, is about
     *         to drive — {@link #advancePosition(double)} on this transport
     */
    public boolean isRealTimeClockActive() {
        return realTimeClockActive;
    }

    /**
     * Applies a queued seek on the calling (non-RT) thread and fires
     * {@link ChangeKind#POSITION}; a no-op when the queue is empty. Used by
     * the claim release and by the double check in
     * {@link #setPositionInBeats(double)}.
     */
    private void drainPendingSeekInline() {
        // Commit-before-clear (see getSeekTargetInBeats): peek, store, then
        // CAS the slot empty — a getAndSet pop would open a window where the
        // queue is empty but the position does not yet include the target. The
        // CAS leaves a newer concurrently-published target queued for the next
        // drain (the publisher's own double check in setPositionInBeats
        // guarantees one runs).
        long seek = pendingSeek.get();
        if (seek != NO_SEEK) {
            this.positionInBeats = Double.longBitsToDouble(seek);
            pendingSeek.compareAndSet(seek, NO_SEEK);
            notifyChange(ChangeKind.POSITION);
        }
    }

    /**
     * Returns whether {@link #stop()} returns the playhead to the play-start
     * anchor. Defaults to {@code true} (common DAW convention).
     */
    public boolean isReturnToStartOnStop() {
        return returnToStartOnStop;
    }

    /**
     * Configures whether {@link #stop()} returns the playhead to the
     * play-start anchor ({@code true}, the default) or leaves it where
     * playback halted ({@code false}).
     *
     * <p>The story-305 settings descriptor catalogue seeds this flag from the
     * user preference; the core holds only the flag.</p>
     *
     * @param returnToStartOnStop the new behaviour
     */
    public void setReturnToStartOnStop(boolean returnToStartOnStop) {
        this.returnToStartOnStop = returnToStartOnStop;
    }

    /**
     * Returns the tempo map that manages tempo and time signature changes.
     *
     * @return the tempo map
     */
    public TempoMap getTempoMap() {
        return tempoMap;
    }

    /**
     * Returns the initial (default) tempo in beats per minute (BPM).
     *
     * <p>This is a convenience method equivalent to reading the first tempo
     * change event from the {@link TempoMap}.</p>
     */
    public double getTempo() {
        return tempoMap.getTempoChanges().get(0).bpm();
    }

    /**
     * Sets the initial tempo in BPM by replacing the tempo change event at beat 0.
     *
     * @param tempo BPM value (must be between 20 and 999)
     */
    public void setTempo(double tempo) {
        if (tempo < 20.0 || tempo > 999.0) {
            throw new IllegalArgumentException("tempo must be between 20 and 999 BPM: " + tempo);
        }
        tempoMap.addTempoChange(TempoChangeEvent.instant(0.0, tempo));
        notifyChange(ChangeKind.TEMPO);
    }

    /**
     * Returns the initial time signature numerator.
     *
     * <p>This is a convenience method equivalent to reading the first time
     * signature change event from the {@link TempoMap}.</p>
     */
    public int getTimeSignatureNumerator() {
        return tempoMap.getTimeSignatureChanges().get(0).numerator();
    }

    /**
     * Returns the initial time signature denominator.
     *
     * <p>This is a convenience method equivalent to reading the first time
     * signature change event from the {@link TempoMap}.</p>
     */
    public int getTimeSignatureDenominator() {
        return tempoMap.getTimeSignatureChanges().get(0).denominator();
    }

    /**
     * Sets the initial time signature by replacing the time signature change at beat 0.
     *
     * @param numerator   beats per bar (e.g., 4)
     * @param denominator note value of each beat (e.g., 4 for quarter note)
     */
    public void setTimeSignature(int numerator, int denominator) {
        if (numerator <= 0) {
            throw new IllegalArgumentException("numerator must be positive: " + numerator);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive: " + denominator);
        }
        tempoMap.addTimeSignatureChange(new TimeSignatureChangeEvent(0.0, numerator, denominator));
        notifyChange(ChangeKind.TIME_SIGNATURE);
    }

    /**
     * Returns the current loop region and enabled flag as one immutable,
     * consistently-published snapshot. RT-path readers must use this (a single
     * volatile load) rather than the three individual getters, which could
     * observe a half-updated region across a concurrent control write.
     *
     * <p>Every loop mutator ({@link #setLoopWindow(boolean, double, double)},
     * {@link #setLoopEnabled(boolean)}, {@link #setLoopRegion(double, double)})
     * installs a complete window through one volatile store followed by one
     * {@link ChangeKind#LOOP} notification, so a snapshot taken here — on the
     * RT thread or inside a change listener — is always a window that some
     * single call published in full. Callers that <em>define</em> a new loop
     * must use {@code setLoopWindow} rather than an enable-then-region pair
     * (story 315 review).</p>
     */
    public LoopWindow getLoopWindow() {
        return loopWindow;
    }

    /** Returns {@code true} if loop mode is enabled. */
    public boolean isLoopEnabled() {
        return loopWindow.enabled();
    }

    /**
     * Enables or disables loop mode, keeping the current loop bounds (the loop
     * toggle). To define a new loop — flag <em>and</em> bounds — use
     * {@link #setLoopWindow(boolean, double, double)} instead, which publishes
     * all three together.
     *
     * @param loopEnabled whether loop mode is engaged
     */
    public void setLoopEnabled(boolean loopEnabled) {
        LoopWindow current = this.loopWindow;
        publishLoopWindow(new LoopWindow(loopEnabled, current.startInBeats(), current.endInBeats()));
    }

    /** Returns the loop start position in beats. */
    public double getLoopStartInBeats() {
        return loopWindow.startInBeats();
    }

    /** Returns the loop end position in beats. */
    public double getLoopEndInBeats() {
        return loopWindow.endInBeats();
    }

    /**
     * Sets the loop region boundaries in beats, keeping the current enabled
     * flag (a handle drag). To define a new loop — flag <em>and</em> bounds —
     * use {@link #setLoopWindow(boolean, double, double)} instead, which
     * publishes all three together.
     *
     * @param startInBeats loop start position (must be finite and &ge; 0)
     * @param endInBeats   loop end position (must be finite and greater than
     *                     {@code startInBeats})
     * @throws IllegalArgumentException if either bound is NaN or infinite, or
     *                                  the bounds are otherwise invalid; the
     *                                  current window is left untouched and
     *                                  nothing fires
     */
    public void setLoopRegion(double startInBeats, double endInBeats) {
        requireValidLoopBounds(startInBeats, endInBeats);
        LoopWindow current = this.loopWindow;
        publishLoopWindow(new LoopWindow(current.enabled(), startInBeats, endInBeats));
    }

    /**
     * Defines a loop in one call — enabled flag and both bounds published as a
     * single {@link LoopWindow} through one volatile store and one
     * {@link ChangeKind#LOOP} notification (story 315 review).
     *
     * <p>This is the API to use whenever a new loop is being defined (the
     * ruler's Shift+click, project load). A {@link #setLoopEnabled(boolean)}
     * followed by {@link #setLoopRegion(double, double)} is <em>not</em>
     * atomic: between the two calls the RT thread can observe an enabled loop
     * over the previous bounds and wrap or render one block against the wrong
     * region. Validation matches {@code setLoopRegion} exactly; on rejection
     * the current window is left untouched and nothing fires.</p>
     *
     * @param enabled      whether loop mode is engaged
     * @param startInBeats loop start position (must be finite and &ge; 0)
     * @param endInBeats   loop end position (must be finite and greater than
     *                     {@code startInBeats})
     * @throws IllegalArgumentException if either bound is NaN or infinite, or
     *                                  the bounds are otherwise invalid; the
     *                                  current window is left untouched and
     *                                  nothing fires
     */
    public void setLoopWindow(boolean enabled, double startInBeats, double endInBeats) {
        requireValidLoopBounds(startInBeats, endInBeats);
        publishLoopWindow(new LoopWindow(enabled, startInBeats, endInBeats));
    }

    private static void requireValidLoopBounds(double startInBeats, double endInBeats) {
        // Non-finite bounds must be rejected BEFORE the ordering checks: every
        // comparison with NaN is false (so NaN would sail through), and a
        // finite start with +Infinity end satisfies end > start — yet an
        // enabled window holding either corrupts the wrap arithmetic
        // (Infinity % length is NaN) and the render cursor (story 315 review).
        if (!Double.isFinite(startInBeats) || !Double.isFinite(endInBeats)) {
            throw new IllegalArgumentException(
                    "loop bounds must be finite: start=" + startInBeats + ", end=" + endInBeats);
        }
        if (startInBeats < 0) {
            throw new IllegalArgumentException("loop start must not be negative: " + startInBeats);
        }
        if (endInBeats <= startInBeats) {
            throw new IllegalArgumentException(
                    "loop end must be greater than loop start: start=" + startInBeats + ", end=" + endInBeats);
        }
    }

    /**
     * The single store-and-notify path shared by every loop mutator: one
     * volatile write of the complete window, then exactly one
     * {@link ChangeKind#LOOP} signal. {@link #advancePosition(double)} keeps
     * reading the one volatile snapshot — no lock, no allocation is added on
     * the RT read path.
     */
    private void publishLoopWindow(LoopWindow window) {
        this.loopWindow = window;
        notifyChange(ChangeKind.LOOP);
    }

    /**
     * Advances the playback position by the given number of beats — the RT
     * audio callback's per-block clock tick (sole production caller:
     * {@code RenderPipeline}, at the end of each rendered block).
     *
     * <p>Order of operations:</p>
     * <ol>
     *   <li><b>Take ownership.</b> After validating the delta, the advance
     *       increments {@code advancesInFlight} (a full fence) and holds it
     *       across every state read, queue peek, CAS and compensation below —
     *       the class-level position ownership protocol. {@link #stop()} and
     *       {@link #pause()} store their state and then await this counter
     *       reaching zero before they touch the queue or the position, so no
     *       transition store can ever interleave between a CAS below and its
     *       compensation.</li>
     *   <li><b>Seek drain.</b> A queued UI seek is applied <em>verbatim</em>
     *       (the delta is NOT added: the just-rendered block was rendered from
     *       the pre-seek position, so the seek takes effect exactly at this
     *       block boundary) and {@link ChangeKind#POSITION} fires. The target
     *       is <em>peeked</em>, committed through the same guarded
     *       compare-and-set as the advance, and only then CAS-cleared out of
     *       the queue — commit-before-clear, so
     *       {@link #getSeekTargetInBeats()} never observes an empty queue over
     *       a position that excludes the target (see that method's invariant).
     *       The clear is a {@code compareAndSet} on the peeked value: a newer
     *       seek published mid-drain stays queued for the next block. The
     *       drain ignores the {@linkplain #setRealTimeClockActive(boolean)
     *       claim} — released mid-flight it must not strand a seek — but it
     *       backs off a stop or pause without touching the queue, so a stop
     *       that lands mid-drain supersedes the seek instead of the drain
     *       parking the playhead at a target the user cancelled by
     *       stopping.</li>
     *   <li><b>CAS advance.</b> Otherwise the position advances by
     *       {@code deltaBeats} through a lock-free compare-and-set loop. When
     *       loop mode is enabled and the new position reaches or passes the
     *       loop end, it wraps to
     *       {@code loopStart + ((next - loopEnd) % loopLength)} — the O(1)
     *       closed form of repeated length subtraction, identical in result
     *       and safe even for pathologically tiny loop regions.</li>
     *   <li><b>Stop/pause back-off.</b> The state is checked three times: once
     *       after the seek peek (a stop supersedes a still-queued seek), once
     *       before each CAS attempt (the cheap fast path), and once
     *       <em>after</em> a successful CAS. Every back-off leaves the queue
     *       untouched — the rolling-to-non-rolling transition owns the
     *       clear.</li>
     *   <li><b>Release ownership, then notify.</b> The counter is decremented
     *       in a {@code finally}, and {@link ChangeKind#POSITION} fires only
     *       <em>after</em> that, outside the owned window — so a listener that
     *       calls {@code stop()} or {@code pause()} on the RT thread can never
     *       await its own advance.</li>
     * </ol>
     *
     * <h4>Why the post-CAS re-read is required</h4>
     *
     * <p>The pre-CAS state read alone does <em>not</em> establish "the playhead
     * never moves past a stop". A transition can store its state after that
     * read but before (or while) the CAS lands — and nothing else touches the
     * position word inside the owned window, so the CAS succeeds regardless of
     * what the transition will later write. The transition then awaits this
     * advance's retirement; when it writes no position of its own
     * ({@link #stop()} with {@link #isReturnToStartOnStop()} {@code false},
     * {@link #pause()} with nothing queued) the advanced value would simply
     * stand, leaving the playhead one block past the stop or pause point.</p>
     *
     * <p>The real guarantee is therefore: after a successful CAS the state is
     * re-read, and if the transport is no longer rolling the advance is undone
     * with a compensating {@code compareAndSet(next, current)} and nothing is
     * fired. Because the transition awaits this advance's retirement before it
     * touches the queue or stores the position, the compensation is
     * <em>uncontended by transitions by construction</em>: no stop or pause
     * position store can land between the CAS and the undo, and the undo's
     * outcome never depends on the position's value bits (the ABA where a
     * transition stored the very bits the CAS had just written, and a
     * value-inferred undo rolled that authoritative position back, cannot
     * occur). The undo stays a {@code compareAndSet} purely as a defence
     * against writers outside the protocol — tests driving the word from
     * several threads, and the direct control-thread stores (the inline seek
     * in {@link #setPositionInBeats(double)}, the claim-release and
     * double-check drains, {@link #playWithPreRoll()}'s rewind), which are
     * FX-serialized with the transitions and so never coincide with a
     * compensation in production. Either way an advance
     * that has <em>observed</em> the stop neither keeps its value nor fires
     * {@link ChangeKind#POSITION}. (An advance that completed before the stop
     * became visible to it may still deliver its POSITION signal after the
     * stop's {@link ChangeKind#STATE}; that is a benign late notification —
     * the observer re-reads the position, which the stop has already
     * corrected — not a playhead that moved past the stop.)</p>
     *
     * @param deltaBeats number of beats to advance (must be &ge; 0)
     */
    @RealTimeSafe
    public void advancePosition(double deltaBeats) {
        if (deltaBeats < 0) {
            throw new IllegalArgumentException("deltaBeats must not be negative: " + deltaBeats);
        }
        boolean moved;
        advancesInFlight.incrementAndGet(); // full fence — pairs with the transition's state store
        try {
            moved = advanceOwned(deltaBeats);
        } finally {
            advancesInFlight.decrementAndGet();
        }
        if (moved) {
            notifyChange(ChangeKind.POSITION); // outside the owned window — listeners may stop()/pause()
        }
    }

    /**
     * The body of {@link #advancePosition(double)} that runs while this thread
     * owns the position word ({@code advancesInFlight > 0}): seek drain or CAS
     * advance, with the stop/pause back-off and compensation. Fires nothing —
     * the caller fires {@link ChangeKind#POSITION} after releasing ownership.
     *
     * @return {@code true} when the position was committed (seek drained or
     *         advance landed) and {@link ChangeKind#POSITION} must fire;
     *         {@code false} when the advance backed off or was undone
     */
    @RealTimeSafe
    private boolean advanceOwned(double deltaBeats) {
        // Peek — do NOT pop. The target must stay observable through
        // getSeekTargetInBeats() until the position commit below includes it:
        // a pop-then-commit drain leaves a window where the queue is empty and
        // the position is still the pre-seek base, so a relative seek landing
        // in it composes against the stale base and two skips straddling the
        // drain collapse into one jump.
        long seek = pendingSeek.get();
        if (seek != NO_SEEK) {
            TransportState afterPeek = state;
            if (afterPeek != TransportState.PLAYING
                    && afterPeek != TransportState.RECORDING) {
                // A stop or pause landed after the peek: it supersedes the
                // seek. Return without touching the queue — every
                // rolling-to-non-rolling transition owns the clear (stop()
                // sets NO_SEEK after awaiting our retirement, pause() drains
                // inline after the same await), so nothing is stranded and
                // the target stays visible until that owner commits or
                // discards it.
                return false;
            }
            double previous = positionInBeats;
            double target = Double.longBitsToDouble(seek);
            if (!POSITION.compareAndSet(this, previous, target)) {
                // Not a transition (they await our retirement) — a
                // non-protocol writer moved the word. Return without touching
                // the slot: whoever moved it owns the clear, and a
                // still-queued target is drained next block.
                return false;
            }
            TransportState afterStore = state;
            if (afterStore != TransportState.PLAYING
                    && afterStore != TransportState.RECORDING) {
                // A stop or pause stored its state while our CAS was in
                // flight; it is now awaiting our retirement, so it has not yet
                // touched the position or the queue. Roll our commit back —
                // uncontended by the transition by construction — and leave
                // the slot untouched: the transition owns the clear (stop
                // discards, pause commits).
                POSITION.compareAndSet(this, target, previous);
                return false;
            }
            // Committed while still rolling — only now empty the slot, and
            // only if it still holds the value just applied: a newer seek
            // published mid-drain must stay queued for the next block.
            pendingSeek.compareAndSet(seek, NO_SEEK);
            return true;
        }
        LoopWindow loop = loopWindow; // one consistent read outside the CAS loop
        boolean wraps = loop.enabled() && loop.endInBeats() > loop.startInBeats();
        while (true) {
            double current = positionInBeats;
            TransportState currentState = state;
            if (currentState != TransportState.PLAYING
                    && currentState != TransportState.RECORDING) {
                return false; // stop()/pause() landed — never advance a non-rolling transport
            }
            double next = current + deltaBeats;
            if (wraps && next >= loop.endInBeats()) {
                double loopLength = loop.endInBeats() - loop.startInBeats();
                next = loop.startInBeats() + ((next - loop.endInBeats()) % loopLength);
            }
            if (POSITION.compareAndSet(this, current, next)) {
                TransportState afterCas = state;
                if (afterCas != TransportState.PLAYING
                        && afterCas != TransportState.RECORDING) {
                    // A stop or pause stored its state while this CAS was in
                    // flight and is awaiting our retirement before it touches
                    // the queue or the position. Undo the advance —
                    // uncontended by the transition by construction; the CAS
                    // form only guards against non-protocol writers.
                    POSITION.compareAndSet(this, next, current);
                    return false;
                }
                return true;
            }
        }
    }

    /**
     * Installs a frame-based punch-in/out region on the transport.
     *
     * <p>When the region is {@linkplain PunchRegion#enabled() enabled}, the
     * recording pipeline captures input only within
     * {@code [startFrames, endFrames)} while the transport continues to play
     * back normally outside that range. This enables auto-punch: the record
     * arm can remain pressed across multiple passes and only the punch range
     * is captured each time.</p>
     *
     * @param punchRegion the punch region to install (must not be {@code null};
     *                    use {@link #clearPunchRegion()} to remove)
     * @throws NullPointerException if {@code punchRegion} is {@code null}
     */
    public void setPunchRegion(PunchRegion punchRegion) {
        if (punchRegion == null) {
            throw new NullPointerException("punchRegion must not be null; use clearPunchRegion() to remove");
        }
        this.punchRegion = punchRegion;
    }

    /**
     * Removes any installed punch region. After this call
     * {@link #isPunchEnabled()} returns {@code false} and
     * {@link #getPunchRegion()} returns {@code null}.
     */
    public void clearPunchRegion() {
        this.punchRegion = null;
    }

    /**
     * Returns the currently installed punch region, or {@code null} if none
     * has been set. The region may be present but disabled; use
     * {@link #isPunchEnabled()} to test whether punch recording is active.
     *
     * @return the punch region, or {@code null}
     */
    public PunchRegion getPunchRegion() {
        return punchRegion;
    }

    /**
     * Returns whether punch recording is currently active — i.e. a punch
     * region has been installed <em>and</em> its {@code enabled} flag is
     * {@code true}.
     *
     * @return {@code true} if punch recording should gate input capture
     */
    public boolean isPunchEnabled() {
        PunchRegion current = punchRegion;
        return current != null && current.enabled();
    }

    // ── Pre-roll / Post-roll ───────────────────────────────────────────────

    /**
     * Installs a bar-based pre-roll/post-roll configuration on the transport.
     *
     * <p>When the configuration is {@linkplain PreRollPostRoll#enabled()
     * enabled}, {@link #playWithPreRoll()} seeks the playhead back by
     * {@code preBars} before beginning playback, and {@link #requestStop()}
     * extends playback by {@code postBars} before fully stopping. During
     * pre-roll and post-roll windows the click track keeps sounding but input
     * must not be captured — callers can check {@link #isInputCaptureGated()}
     * to implement that gating.</p>
     *
     * @param preRollPostRoll the configuration (must not be {@code null}; use
     *                        {@link #clearPreRollPostRoll()} to reset)
     * @throws NullPointerException if {@code preRollPostRoll} is {@code null}
     */
    public void setPreRollPostRoll(PreRollPostRoll preRollPostRoll) {
        if (preRollPostRoll == null) {
            throw new NullPointerException(
                    "preRollPostRoll must not be null; use clearPreRollPostRoll() to reset");
        }
        this.preRollPostRoll = preRollPostRoll;
    }

    /**
     * Resets the pre-roll/post-roll configuration to
     * {@link PreRollPostRoll#DISABLED}.
     */
    public void clearPreRollPostRoll() {
        this.preRollPostRoll = PreRollPostRoll.DISABLED;
        this.inPreRoll = false;
        this.inPostRoll = false;
    }

    /**
     * Returns the currently installed pre-roll/post-roll configuration.
     * Never {@code null}; defaults to {@link PreRollPostRoll#DISABLED}.
     *
     * @return the current configuration
     */
    public PreRollPostRoll getPreRollPostRoll() {
        return preRollPostRoll;
    }

    /**
     * Returns {@code true} if a pre-roll/post-roll configuration is installed
     * and its {@code enabled} flag is {@code true}.
     */
    public boolean isPreRollPostRollEnabled() {
        return preRollPostRoll.enabled();
    }

    /**
     * Starts playback with pre-roll applied: before entering the
     * {@link TransportState#PLAYING} state, the position is seeked backward by
     * {@code preBars × beatsPerBar} (clamped to zero). When no pre-roll is
     * configured (or the configuration is disabled or {@code preBars == 0}),
     * this method is equivalent to {@link #play()}.
     *
     * <p>The play-start anchor records the position <em>before</em> the
     * pre-roll rewind: {@link #stop()} returns the playhead to where the user
     * started Play, not to the rewound spot. The rewind itself is a direct
     * volatile store — control writes happen while the transport is not
     * rolling, so no seek queue is needed here.</p>
     *
     * <p>Fires {@link ChangeKind#POSITION} when the rewind actually moved the
     * playhead, then {@link ChangeKind#STATE} — the same signals every other
     * mutator fires, so view-models observe the transition immediately
     * (story 315; previously this transition was silent).</p>
     *
     * <p>The returned value is the number of beats that playback was rewound
     * by, which is useful for tests asserting sample-accurate pre-roll.</p>
     *
     * @return the number of beats by which the playhead was shifted back
     */
    public double playWithPreRoll() {
        double startPosition = positionInBeats;
        playStartAnchor = startPosition;
        double shift = 0.0;
        if (preRollPostRoll.enabled() && preRollPostRoll.preBars() > 0) {
            shift = preRollPostRoll.preRollBeats(getTimeSignatureNumerator());
            double target = startPosition - shift;
            if (target < 0) {
                shift = startPosition; // clamp
                target = 0.0;
            }
            positionInBeats = target;
            inPreRoll = shift > 0;
        } else {
            inPreRoll = false;
        }
        inPostRoll = false;
        state = TransportState.PLAYING;
        if (shift > 0) {
            notifyChange(ChangeKind.POSITION);
        }
        notifyChange(ChangeKind.STATE);
        return shift;
    }

    /**
     * Requests that the transport stop. If a post-roll is configured, the
     * transport enters the post-roll window instead of stopping immediately;
     * the caller is expected to invoke {@link #advancePosition(double)} as
     * normal and call {@link #finishPostRoll()} once the post-roll duration
     * has elapsed. If no post-roll is configured, this method is equivalent
     * to {@link #stop()}.
     *
     * <p>Entering the post-roll window fires {@link ChangeKind#STATE}
     * (story 315; previously this transition was silent).</p>
     *
     * @return {@code true} if the transport entered a post-roll window
     *         (still running), {@code false} if it stopped immediately
     */
    public boolean requestStop() {
        if (preRollPostRoll.enabled() && preRollPostRoll.postBars() > 0
                && (state == TransportState.PLAYING
                        || state == TransportState.RECORDING)) {
            inPostRoll = true;
            inPreRoll = false;
            // Post-roll plays back, not records — drop out of RECORDING.
            state = TransportState.PLAYING;
            notifyChange(ChangeKind.STATE);
            return true;
        }
        stop();
        return false;
    }

    /**
     * Completes a post-roll window started by {@link #requestStop()} — the
     * deferred stop. Delegates to {@link #stop()}: the transport moves to
     * {@link TransportState#STOPPED}, the playhead returns to the play-start
     * anchor per {@link #isReturnToStartOnStop()} (story 315; previously this
     * hard-reset to zero), any queued seek is discarded, and
     * {@link ChangeKind#STATE} (plus {@link ChangeKind#POSITION} when the
     * playhead moved) fires. Safe to call when the transport is not stopping:
     * an already-stopped transport is left untouched (no signal).
     */
    public void finishPostRoll() {
        inPostRoll = false;
        stop();
    }

    /**
     * Marks the transport as having crossed the pre-roll boundary — i.e. the
     * playhead has reached the original starting position and real recording
     * may begin. Safe to call when not in pre-roll (no-op).
     */
    public void finishPreRoll() {
        inPreRoll = false;
    }

    /** Returns {@code true} if the transport is currently inside a pre-roll window. */
    public boolean isInPreRoll() {
        return inPreRoll;
    }

    /** Returns {@code true} if the transport is currently inside a post-roll window. */
    public boolean isInPostRoll() {
        return inPostRoll;
    }

    /**
     * Returns {@code true} if input capture must be suppressed at the current
     * moment — i.e. the transport is inside a pre-roll or post-roll window.
     * The click/metronome is <em>not</em> affected by this flag.
     */
    public boolean isInputCaptureGated() {
        return inPreRoll || inPostRoll;
    }

    // ── Toolkit-neutral change notification ────────────────────────────────

    /**
     * Registers a toolkit-neutral change observer that is invoked
     * <strong>after</strong> each transport field mutation completes, carrying
     * the {@link ChangeKind} tag of the slice that changed.
     *
     * <p>Because the callback fires post-mutation, an observer that re-reads the
     * transport inside its callback always sees the new value (Control
     * Synchronization Design Book §3.2). The observer must do only lock-free,
     * non-blocking work when it may be invoked from the {@code @RealTimeSafe}
     * render path (via {@link #advancePosition(double)}) — the sanctioned sink
     * is the view-model's lock-free, single-reader buffer (§4.1, §4.6).</p>
     *
     * @param listener the observer to register; must not be {@code null}
     * @return a removal token; run it to unregister the observer (mirrors the
     *         {@code DockManager.addListener} convention)
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public Runnable addChangeListener(Consumer<ChangeKind> listener) {
        return changes.add(listener);
    }

    /**
     * Removes a previously {@linkplain #addChangeListener(Consumer) registered}
     * observer. Equivalent to running the token returned by
     * {@code addChangeListener}. Safe to call with an unregistered listener
     * (no-op).
     *
     * @param listener the observer to remove; must not be {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public void removeChangeListener(Consumer<ChangeKind> listener) {
        changes.remove(listener);
    }

    /**
     * Notifies registered observers of a change — delegates to the allocation-free
     * {@link ChangeNotifier#fire}, so it stays safe on the {@code @RealTimeSafe}
     * render path.
     */
    private void notifyChange(ChangeKind kind) {
        changes.fire(kind);
    }
}
