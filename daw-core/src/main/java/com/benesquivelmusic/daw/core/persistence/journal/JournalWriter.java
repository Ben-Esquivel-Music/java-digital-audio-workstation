package com.benesquivelmusic.daw.core.persistence.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Story 298 — the write-ahead journal engine.
 *
 * <p>{@code JournalWriter} absorbs every state mutation instantly on the
 * caller's thread via a non-blocking {@link #offer}, and a single writer
 * <em>virtual</em> thread (JEP 444) drains the buffered records to an
 * append-only {@link JournalSegment}, fsyncing once per drained group for
 * write-ahead durability.</p>
 *
 * <h2>Buffering — never block, never drop</h2>
 * <p>{@link #offer} first tries a bounded {@link ArrayBlockingQueue}
 * (non-blocking {@code offer}). If the queue is full — i.e. the disk is not
 * keeping up — the record spills to an overflow {@link ArrayDeque} guarded
 * by a {@link ReentrantLock} (NOT {@code synchronized} — the writer thread
 * does blocking I/O on a virtual thread and pinning its carrier would
 * throttle Loom). The producer therefore <strong>never blocks</strong> and
 * the journal <strong>never drops</strong> a record, regardless of disk
 * state; the only observable effect of a slow disk is a rising
 * {@link Backpressure} level reported through the {@link JournalListener}.</p>
 *
 * <h2>Threading discipline</h2>
 * <ul>
 *   <li>The writer thread parks on a {@link Condition} (via the lock's
 *       {@code await}) rather than {@code synchronized.wait}, so it never
 *       pins a carrier thread.</li>
 *   <li>All blocking I/O (segment append + fsync) happens on the writer
 *       virtual thread, outside any {@code synchronized} block.</li>
 *   <li>{@link #close()} flushes and fsyncs every remaining record and
 *       closes the segment <em>before</em> it flips the terminal
 *       {@code running} flag — side effects are published before terminal
 *       state.</li>
 * </ul>
 */
public final class JournalWriter implements AutoCloseable {

    private static final JournalListener NO_OP = new JournalListener() { };

    private final ProjectContext ctx;
    private final JournalConfig config;
    private final boolean enabled;
    private final JournalListener listener;
    private final JournalSegment.Syncer syncer;

    // ---- buffering --------------------------------------------------------
    private final ArrayBlockingQueue<JournalRecord> queue;
    private final ArrayDeque<JournalRecord> overflow = new ArrayDeque<>();
    private final ReentrantLock overflowLock = new ReentrantLock();
    private long overflowBytes;

    // ---- writer-thread wake-up (Condition, never synchronized) ------------
    private final ReentrantLock workLock = new ReentrantLock();
    private final Condition workAvailable = workLock.newCondition();

    // Serialises ownership of drained records from dequeue through durable
    // append. Without this lock, forceFlush() can observe both buffers empty
    // while the writer thread holds a dequeued batch that has not yet acquired
    // appendLock. Producers never acquire this lock, so offer() remains
    // independent of blocking disk I/O.
    private final ReentrantLock drainLock = new ReentrantLock(true);

    // Serialises segment append/rotate so the writer thread and an ad-hoc
    // forceFlush() thread never touch the (non-thread-safe) segment at once.
    // ReentrantLock, NOT synchronized: the section spans blocking fsync I/O on
    // a virtual thread, so a monitor would pin the carrier and throttle Loom.
    private final ReentrantLock appendLock = new ReentrantLock();

    // ---- counters / flags -------------------------------------------------
    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicInteger queuedSinceCheckpoint = new AtomicInteger(0);
    private final AtomicBoolean bufferingSignalled = new AtomicBoolean(false);
    private final AtomicBoolean escalatedSignalled = new AtomicBoolean(false);
    private final AtomicBoolean rotateRequested = new AtomicBoolean(false);
    private final AtomicBoolean startGuard = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile boolean closed;
    /**
     * Set the instant {@link #close()} begins, before it joins the writer
     * thread. Lets {@link #appendWithRetry} give up its retry loop on a
     * persistently failing disk during shutdown instead of spinning forever
     * (which would make {@code close()}'s {@code join()} — and the whole app
     * shutdown — hang).
     */
    private volatile boolean stopping;

    // ---- segment state (writer-thread-confined) ---------------------------
    private JournalSegment segment;
    private int segmentIndex;
    private final List<String> segmentNames = new ArrayList<>();
    private final ReentrantLock segmentNamesLock = new ReentrantLock();

    private Thread writerThread;

    /**
     * Creates a journal writer for {@code ctx}.
     *
     * @param ctx      the project context (provides the {@code journal/} dir)
     * @param config   tuning configuration
     * @param enabled  whether journaling is enabled (the "Use journaled
     *                 persistence" preference). When {@code false} this
     *                 writer creates no directory, starts no thread, and
     *                 every {@link #offer} returns {@link OfferResult#DISABLED}.
     * @param listener the telemetry callback, or {@code null} for a no-op
     */
    public JournalWriter(ProjectContext ctx, JournalConfig config, boolean enabled,
                         JournalListener listener) {
        this(ctx, config, enabled, listener, JournalSegment.Syncer.REAL);
    }

    /**
     * Test-visible constructor that injects the fsync seam so a test can
     * count {@code force(true)} calls or stall the writer's I/O.
     *
     * @param ctx      the project context
     * @param config   tuning configuration
     * @param enabled  whether journaling is enabled
     * @param listener the telemetry callback, or {@code null} for a no-op
     * @param syncer   the fsync seam
     */
    JournalWriter(ProjectContext ctx, JournalConfig config, boolean enabled,
                  JournalListener listener, JournalSegment.Syncer syncer) {
        this.ctx = Objects.requireNonNull(ctx, "ctx must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.enabled = enabled;
        this.listener = listener == null ? NO_OP : listener;
        this.syncer = Objects.requireNonNull(syncer, "syncer must not be null");
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
    }

    // ---- lifecycle --------------------------------------------------------

    /**
     * Starts the writer thread and opens {@code segment-000.bin}. A no-op if
     * journaling is disabled (no {@code journal/} directory is created) or if
     * already started.
     *
     * @throws IOException if the journal directory or first segment cannot be
     *                     created
     */
    public void start() throws IOException {
        if (!enabled) {
            return;
        }
        if (!startGuard.compareAndSet(false, true)) {
            return;
        }
        Files.createDirectories(ctx.journalDirectory());
        openSegment(0);
        running = true;
        writerThread = Thread.ofVirtual()
                .name("daw-journal-writer")
                .unstarted(this::writerLoop);
        writerThread.start();
    }

    /** {@return {@code true} if journaling is enabled for this writer} */
    public boolean isEnabled() {
        return enabled;
    }

    /** {@return the records journaled since the last successful checkpoint} */
    public int queuedSinceCheckpoint() {
        return queuedSinceCheckpoint.get();
    }

    /**
     * {@return the plain file names of every segment created so far}, for
     * daw-app session sealing ({@code WorkingSession.withJournalSegment}).
     */
    public List<String> segmentFileNames() {
        segmentNamesLock.lock();
        try {
            return List.copyOf(segmentNames);
        } finally {
            segmentNamesLock.unlock();
        }
    }

    // ---- the FX-side proxy entry point ------------------------------------

    /**
     * Convenience offer that stamps the next sequence number and builds the
     * record for the caller. Non-blocking; never drops.
     *
     * @param type     compact mutation tag (e.g. {@code "MixerEvent.MuteChanged"})
     * @param entityId affected entity id, or {@code null}
     * @param payload  opaque extra bytes (may be {@code null}/empty)
     * @param ts       wall-clock instant of the mutation
     * @return the outcome (never a drop)
     */
    public OfferResult offer(String type, UUID entityId, byte[] payload, Instant ts) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(ts, "ts must not be null");
        if (!enabled) {
            return OfferResult.DISABLED;
        }
        long seq = sequence.getAndIncrement();
        return offer(JournalRecord.ofMutation(seq, ts, type, entityId, payload));
    }

    /**
     * The non-blocking journal entry point. <strong>Never blocks the caller
     * and never drops the record</strong>, whatever the disk is doing.
     *
     * <p>If the bounded queue accepts the record it returns
     * {@link OfferResult#QUEUED}. Otherwise the record is appended to the
     * overflow deque under {@link #overflowLock} (a short, non-blocking
     * critical section — no I/O): the first spill fires
     * {@link Backpressure#BUFFERING}; crossing
     * {@link JournalConfig#overflowMaxBytes()} fires
     * {@link Backpressure#UNRESPONSIVE} once. Either way the record is
     * buffered and the writer thread is signalled.</p>
     *
     * @param record the record to journal (its {@code sequence} is used as-is;
     *               prefer {@link #offer(String, UUID, byte[], Instant)} which
     *               stamps a fresh sequence)
     * @return the outcome (never a drop)
     */
    public OfferResult offer(JournalRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        if (!enabled) {
            return OfferResult.DISABLED;
        }
        if (queue.offer(record)) {
            signalWork();
            return OfferResult.QUEUED;
        }
        // Queue full — spill to overflow without blocking and without dropping.
        boolean fireBuffering = false;
        boolean fireEscalated = false;
        overflowLock.lock();
        try {
            overflow.addLast(record);
            overflowBytes += record.serializedSize();
            // Flip the one-shot back-pressure flags under the same lock that
            // maybeClearBackpressure() clears them under, so a concurrent clear
            // racing this spill can never lose its BUFFERING/UNRESPONSIVE signal.
            if (bufferingSignalled.compareAndSet(false, true)) {
                fireBuffering = true;
            }
            if (overflowBytes >= config.overflowMaxBytes()
                    && escalatedSignalled.compareAndSet(false, true)) {
                fireEscalated = true;
            }
        } finally {
            overflowLock.unlock();
        }
        signalWork();

        if (fireBuffering) {
            safeBackpressure(Backpressure.BUFFERING);
        }
        if (fireEscalated) {
            safeBackpressure(Backpressure.UNRESPONSIVE);
            return OfferResult.ESCALATED;
        }
        return escalatedSignalled.get() ? OfferResult.ESCALATED : OfferResult.OVERFLOW;
    }

    // ---- checkpoint coordination ------------------------------------------

    /**
     * Signals that an external checkpoint succeeded: the journal rotates to a
     * fresh segment (the new checkpoint subsumes the prior segment) and resets
     * the since-checkpoint counter to 0. The actual segment swap happens on
     * the writer thread once the in-flight batch is durable.
     */
    public void onCheckpointSucceeded() {
        if (!enabled) {
            return;
        }
        rotateRequested.set(true);
        queuedSinceCheckpoint.set(0);
        safeQueuedCount(0);
        signalWork();
    }

    // ---- force flush escape hatch -----------------------------------------

    /**
     * Synchronously drains <em>everything</em> currently buffered (queue +
     * overflow) to disk on a dedicated background <em>virtual</em> thread —
     * never the caller's thread, and never the FX thread — then joins that
     * thread and returns it.
     *
     * <p>After this returns, count-in equals count-on-disk for all records
     * offered before the call. Intended as the user-facing "disk is
     * unresponsive — flush now" escape hatch.</p>
     *
     * @return the (terminated) virtual thread that performed the flush, so a
     *         test can assert it {@link Thread#isVirtual()} and differs from
     *         the caller
     */
    public Thread forceFlush() {
        Thread flusher = Thread.ofVirtual()
                .name("daw-journal-force-flush")
                .unstarted(() -> {
                    try {
                        drainUntilEmpty();
                    } catch (IOException e) {
                        // Surface as unchecked so the joining caller sees it;
                        // a flush failure is exceptional, not routine.
                        throw new RuntimeJournalIoException(e);
                    }
                });
        flusher.start();
        try {
            flusher.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return flusher;
    }

    // ---- writer thread ----------------------------------------------------

    private void writerLoop() {
        try {
            while (running || hasBufferedWork()) {
                if (!appendNextBatch()) {
                    awaitWork();
                }
            }
        } finally {
            // Drain anything that slipped in during shutdown before exiting.
            try {
                drainUntilEmpty();
            } catch (IOException ignored) {
                // best-effort final drain
            }
        }
    }

    /**
     * Claims and appends one writer batch under the same ownership lock used
     * by {@link #forceFlush()}, so an empty buffer always means that no writer
     * batch can still be waiting to append.
     */
    private boolean appendNextBatch() {
        drainLock.lock();
        try {
            List<JournalRecord> batch = takeBatch();
            if (batch.isEmpty()) {
                return false;
            }
            appendWithRetry(batch);
            return true;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Drains up to {@code drainBatchSize} records: first from the bounded
     * queue, then (if the queue ran dry) from the overflow deque under the
     * lock. Globally-sequenced records mean on-disk order need not be strict;
     * the replayer sorts by {@code sequence}.
     */
    private List<JournalRecord> takeBatch() {
        int max = config.drainBatchSize();
        List<JournalRecord> batch = new ArrayList<>(Math.min(max, 64));
        queue.drainTo(batch, max);
        if (batch.size() < max) {
            overflowLock.lock();
            try {
                while (batch.size() < max && !overflow.isEmpty()) {
                    JournalRecord r = overflow.pollFirst();
                    overflowBytes -= r.serializedSize();
                    batch.add(r);
                }
                if (overflow.isEmpty()) {
                    overflowBytes = 0L;     // guard against rounding drift
                }
            } finally {
                overflowLock.unlock();
            }
        }
        return batch;
    }

    private void appendWithRetry(List<JournalRecord> batch) {
        long backoffMillis = 1L;
        while (true) {
            try {
                appendBatchAndMaybeRotate(batch);
                return;
            } catch (IOException e) {
                // Never lose the batch — back off and retry. But once close()
                // has begun (stopping), give up so the writer thread can exit
                // and close()'s join() does not hang on a dead disk.
                if (stopping) {
                    return;
                }
                sleepQuietly(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2L, 250L);
            }
        }
    }

    /**
     * Appends {@code batch} to the current segment, updates counters, and
     * rotates if the segment is full or a checkpoint requested it — all under
     * {@link #appendLock} so the writer thread and a {@link #forceFlush}
     * thread never touch the non-thread-safe segment concurrently.
     */
    private void appendBatchAndMaybeRotate(List<JournalRecord> batch) throws IOException {
        appendLock.lock();
        try {
            segment.appendGroup(batch);
            int total = queuedSinceCheckpoint.addAndGet(batch.size());
            safeQueuedCount(total);
            maybeClearBackpressure();

            boolean sizeRotate = segment.sizeBytes() >= config.segmentMaxBytes();
            boolean checkpointRotate = rotateRequested.getAndSet(false);
            if (sizeRotate || checkpointRotate) {
                rotateSegment();
            }
        } finally {
            appendLock.unlock();
        }
    }

    /** Rotates to the next segment. Caller must hold {@link #appendLock}. */
    private void rotateSegment() {
        JournalSegment old = this.segment;
        try {
            // Open the new segment BEFORE closing the old one: if the open
            // fails we keep writing to the still-open current segment (no wedge)
            // and retry the rotation on the next trigger.
            openSegment(segmentIndex + 1);
        } catch (IOException e) {
            rotateRequested.set(true);
            return;
        }
        old.close();
    }

    /**
     * Synchronously drains queue + overflow until both are empty, fsyncing
     * each group. Runs on the force-flush thread or at end-of-close — never
     * the FX thread.
     */
    private void drainUntilEmpty() throws IOException {
        drainLock.lock();
        try {
            while (true) {
                List<JournalRecord> batch = takeBatch();
                if (batch.isEmpty()) {
                    return;
                }
                appendBatchAndMaybeRotate(batch);
            }
        } finally {
            drainLock.unlock();
        }
    }

    private boolean hasBufferedWork() {
        if (!queue.isEmpty()) {
            return true;
        }
        overflowLock.lock();
        try {
            return !overflow.isEmpty();
        } finally {
            overflowLock.unlock();
        }
    }

    private void maybeClearBackpressure() {
        if (!bufferingSignalled.get() && !escalatedSignalled.get()) {
            return;
        }
        // Check emptiness and clear the flags under the one lock, so a producer
        // spilling concurrently (which sets the flags under the same lock) is
        // never clobbered into a lost signal.
        boolean cleared = false;
        overflowLock.lock();
        try {
            if (overflow.isEmpty()) {
                bufferingSignalled.set(false);
                escalatedSignalled.set(false);
                cleared = true;
            }
        } finally {
            overflowLock.unlock();
        }
        if (cleared) {
            safeBackpressure(Backpressure.NORMAL);
        }
    }

    // ---- segment helpers --------------------------------------------------

    private void openSegment(int index) throws IOException {
        String name = String.format("segment-%03d.bin", index);
        // Open first; only publish the new segment + index once the open
        // succeeds, so a failed open leaves the writer pointing at the still-open
        // current segment rather than a half-rotated/closed one.
        JournalSegment opened = JournalSegment.open(ctx.journalDirectory().resolve(name), syncer);
        this.segment = opened;
        this.segmentIndex = index;
        segmentNamesLock.lock();
        try {
            if (!segmentNames.contains(name)) {
                segmentNames.add(name);
            }
        } finally {
            segmentNamesLock.unlock();
        }
    }

    // ---- wake-up plumbing (Condition, not synchronized) -------------------

    private void signalWork() {
        workLock.lock();
        try {
            workAvailable.signalAll();
        } finally {
            workLock.unlock();
        }
    }

    private void awaitWork() {
        workLock.lock();
        try {
            if (running && !hasBufferedWork()) {
                workAvailable.await(50, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workLock.unlock();
        }
    }

    // ---- close ------------------------------------------------------------

    /**
     * Flushes and fsyncs every remaining record and closes the current
     * segment <strong>before</strong> stopping the writer thread and flipping
     * the terminal {@code closed} flag — so side effects are durable before
     * any observer that gates on terminal state can run. Idempotent; a no-op
     * if journaling is disabled.
     */
    @Override
    public void close() {
        if (!enabled) {
            return;
        }
        if (closed) {
            return;
        }
        // Stop accepting new background work, but keep the segment open so the
        // final drain can publish its side effects first. `stopping` is set
        // before the join so a writer thread wedged in appendWithRetry on a
        // failing disk gives up rather than hanging the join.
        stopping = true;
        running = false;
        signalWork();

        Thread t = this.writerThread;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Publish side effects (flush + fsync) BEFORE terminal state.
        try {
            drainUntilEmpty();
        } catch (IOException ignored) {
            // best-effort final drain
        }
        if (segment != null) {
            segment.close();
        }

        // Only now flip the terminal flag.
        closed = true;
    }

    // ---- small utilities --------------------------------------------------

    private void safeBackpressure(Backpressure level) {
        try {
            listener.onBackpressure(level);
        } catch (RuntimeException ignored) {
            // listener faults must never break the journal
        }
    }

    private void safeQueuedCount(int count) {
        try {
            listener.onQueuedCountChanged(count);
        } catch (RuntimeException ignored) {
            // listener faults must never break the journal
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Unchecked wrapper so {@link #forceFlush} can surface an I/O fault to its joiner. */
    static final class RuntimeJournalIoException extends RuntimeException {
        RuntimeJournalIoException(IOException cause) {
            super(cause);
        }
    }
}
