package com.benesquivelmusic.daw.core.audio.health;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * One render thread's share of an {@link AudioHealthRecord} (story 337,
 * rulings E18–E22): per-category failure counters, a last-failure reference
 * per category, a heartbeat, and a 16-slot ring of failure descriptors.
 *
 * <h2>One writer</h2>
 * <p>Exactly one thread — the {@link RenderThread} the lane was claimed for —
 * calls {@link #record} and {@link #beat}. That is what lets every write be a
 * plain load followed by a release store ({@code lazySet}) or a
 * {@code volatile} store, never a CAS loop, {@code getAndIncrement} or
 * {@code LongAdder} (story 337, ruling E19): with one writer no update can be
 * lost, the instruction count is bounded, nothing allocates and nothing
 * blocks. Readers on other threads see monotonic values.</p>
 *
 * <h2>The descriptor ring</h2>
 * <p>A single-producer/single-consumer ring of {@value #SLOTS} preallocated
 * {@link RecordSlot}s (story 337, ruling E21). The owner writes a slot's plain
 * fields and publishes it with a release store of {@code tail}; a full ring
 * drops the NEWEST descriptor and bumps {@link #lostRecords()} — overwriting
 * the oldest would not be single-producer safe, and dropping the newest keeps
 * the first occurrence, the one the drain surfaces at once. The counters stay
 * exact either way: the ring only samples the thrown objects. The drain
 * ({@link #poll}) copies a slot, nulls its references and only then releases
 * it through {@code head} — the {@code InterleavedBlockRing} order, never a
 * release-before-read.</p>
 *
 * <p>The streaming categories may fill at most {@value #STREAMING_SLOTS} of
 * the slots; {@code audio.automation}, {@code audio.config} and
 * {@code audio.render.halted} may use all {@value #SLOTS}, so a fault storm
 * never takes the room a one-shot or a halt record needs to keep its subject
 * (story 337, ruling E80).</p>
 *
 * <h2>The count is the commit</h2>
 * <p>{@link #record} publishes the descriptor (or its loss) and the last
 * failure first and the category's count last (story 337, ruling E79). A
 * reader that sees a count therefore already sees the descriptor it announces,
 * or the lost-record count that replaced it: the drain reads every count
 * first and polls the rings after, so it is never handed a count whose sample
 * is still to come.</p>
 *
 * <p>A descriptor holds the already-thrown {@link Throwable} reference: a
 * reference store allocates nothing (story 337, ruling E22).</p>
 */
public final class AudioHealthLane {

    /** Descriptor slots per lane (story 337, ruling E21); a power of two. */
    static final int SLOTS = 16;

    /** Index mask for {@link #SLOTS}. */
    static final int MASK = SLOTS - 1;

    /**
     * The undrained descriptors a streaming category may leave in the ring
     * (story 337, ruling E80): every category but {@code audio.automation},
     * {@code audio.config} and {@code audio.render.halted}, which keep the
     * last {@code SLOTS - STREAMING_SLOTS} slots for themselves.
     */
    static final int STREAMING_SLOTS = 12;

    private final AudioHealthRecord owner;
    private final LaneRole role;
    private final AtomicLongArray counts = new AtomicLongArray(AudioFailureCodes.COUNT);
    private final AtomicReferenceArray<Throwable> lastFailures =
            new AtomicReferenceArray<>(AudioFailureCodes.COUNT);
    private final RecordSlot[] slots = new RecordSlot[SLOTS];
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();
    private volatile long beats;
    private volatile long lostRecords;
    private volatile boolean retired;

    AudioHealthLane(AudioHealthRecord owner, LaneRole role) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new RecordSlot();
        }
    }

    /**
     * Records one failure. Owner thread only.
     *
     * <p>Samples a descriptor into the ring unless its category has no room
     * left (then the lost-record count grows instead: a streaming category
     * past {@link #STREAMING_SLOTS} undrained descriptors, any category in a
     * full ring; story 337, ruling E80), keeps {@code failure} as the
     * category's last failure when present, and counts the category last — the
     * count is the commit (story 337, ruling E79). An out-of-range category is
     * ignored rather than thrown on the real-time path. Allocation-free,
     * lock-free, CAS-free and log-free; the category test is an identity
     * {@code if} chain on {@code int} constants, never an enum
     * {@code switch}.</p>
     *
     * @param category an {@link AudioFailureCodes} constant
     * @param failure  the thrown object, or {@code null} when the site has
     *                 none (the real-time write API takes nullable references:
     *                 an {@code Optional} would allocate)
     * @param subject  the site's subject, or {@code null}
     * @param detail   a site-specific number
     */
    @RealTimeSafe
    public void record(int category, Throwable failure, Object subject, int detail) {
        if (category < 0 || category >= AudioFailureCodes.COUNT) {
            return;
        }
        long t = tail.get();
        long undrained = t - head.get();
        if (undrained >= SLOTS || undrained >= STREAMING_SLOTS && category != AudioFailureCodes.AUTOMATION
                && category != AudioFailureCodes.CONFIG && category != AudioFailureCodes.RENDER_HALTED) {
            lostRecords++;
        } else {
            RecordSlot slot = slots[(int) (t & MASK)];
            slot.category = category;
            slot.failure = failure;
            slot.subject = subject;
            slot.detail = detail;
            slot.stamp = beats;
            tail.lazySet(t + 1L);
        }
        if (failure != null) {
            lastFailures.lazySet(category, failure);
        }
        // The count is the commit (story 337, ruling E79): a reader that sees
        // it already sees what it announces. Single writer: a load plus a
        // release store loses nothing (ruling E19).
        counts.lazySet(category, counts.get(category) + 1L);
    }

    /**
     * Bumps this lane's heartbeat by one. Owner thread only. The render drive
     * calls it once per block, after the backend accepted the block (story
     * 337, ruling E5).
     */
    @RealTimeSafe
    public void beat() {
        beats++;
    }

    /**
     * Returns how many failures of {@code category} this lane has recorded.
     * Any non-real-time reader.
     *
     * @param category the category
     * @return the count, monotonic
     */
    public long count(AudioFailureCategory category) {
        return counts.get(Objects.requireNonNull(category, "category must not be null").code());
    }

    /**
     * Returns this lane's heartbeat count. Any non-real-time reader.
     *
     * @return the heartbeat count, monotonic
     */
    public long beats() {
        return beats;
    }

    /**
     * Returns how many descriptors were dropped because the ring was full.
     * Any non-real-time reader.
     *
     * @return the lost-descriptor count, monotonic
     */
    public long lostRecords() {
        return lostRecords;
    }

    /**
     * Returns the role of the thread that owns this lane.
     *
     * @return the role; never {@code null}
     */
    public LaneRole role() {
        return role;
    }

    /** The record this lane belongs to. */
    AudioHealthRecord owner() {
        return owner;
    }

    /** The count of one category by code, for the record's folds and totals. */
    long countOf(int code) {
        return counts.get(code);
    }

    /** The last failure of one category by code, or {@code null}. */
    Throwable lastFailureOf(int code) {
        return lastFailures.get(code);
    }

    /**
     * Drains one descriptor to {@code sink}. The drain's thread only — the
     * ring has exactly one consumer, live or retired.
     *
     * @return {@code true} when a descriptor was delivered
     */
    boolean poll(Consumer<AudioFailureSample> sink) {
        long h = head.get();
        if (h == tail.get()) {
            return false;
        }
        RecordSlot slot = slots[(int) (h & MASK)];
        AudioFailureSample sample = new AudioFailureSample(
                AudioFailureCategory.ofCode(slot.category),
                Optional.ofNullable(slot.failure),
                Optional.ofNullable(slot.subject),
                slot.detail,
                slot.stamp,
                role);
        // Copy, null, then advance (ruling E21): the producer may reuse the
        // slot only after the release store below.
        slot.failure = null;
        slot.subject = null;
        head.lazySet(h + 1L);
        sink.accept(sample);
        return true;
    }

    /**
     * Marks this lane retired; returns {@code false} when it already was.
     * Called by the record under its registry lock.
     */
    boolean markRetired() {
        if (retired) {
            return false;
        }
        retired = true;
        return true;
    }

    /**
     * How many published descriptors the drain has not taken yet. The
     * record's lifecycle thread reads it to decide whether a retired lane is
     * still owed to the drain (story 337, ruling E81). A stale lower
     * {@code head} only keeps a drained lane one lifecycle event longer:
     * {@link #poll} releases {@code head} only after its copy, so
     * {@code head == tail} proves every descriptor was taken.
     *
     * @return {@code tail - head}, never negative
     */
    long undrained() {
        return tail.get() - head.get();
    }

    /**
     * Releases every reference the ring still holds. It runs only when the
     * record evicts a retired lane the drain never emptied, beyond the bound
     * of retired lanes it keeps readable (story 337, rulings E21 and E81),
     * so only after the owner thread has exited or never started: no
     * producer writes a slot concurrently. The indices are left alone, so a
     * drain still iterating a snapshot that contained this lane reads
     * emptied slots, never out-of-order ones. An eviction can race a drain
     * that is polling this lane: the drain may then be handed a descriptor
     * whose throwable or subject is already cleared, and that descriptor is
     * also counted lost. That is accepted because it needs more than 32
     * undrained retired lanes (story 337, ruling E81).
     */
    void clearSlots() {
        for (RecordSlot slot : slots) {
            slot.failure = null;
            slot.subject = null;
        }
    }
}
