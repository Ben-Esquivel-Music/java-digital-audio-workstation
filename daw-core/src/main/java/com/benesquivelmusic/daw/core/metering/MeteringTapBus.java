package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine-owned metering registry (book &sect;3.5, &sect;4.3): derives one
 * {@link LevelTapSlot} per tap point from the bound {@link Mixer}, hands out
 * subscription tokens, and publishes the immutable {@link TapSnapshot} the
 * render thread reads once per block.
 *
 * <h2>Off-RT API</h2>
 * <p>Every registry mutation runs under one private {@code registryLock};
 * {@link TapSubscription#onDisposed(Runnable)} callbacks are fired
 * <strong>after</strong> the lock is released, so a callback may re-enter the
 * bus, the binder or the application. Lock order with the engine is
 * {@code bindingLock -> registryLock}, never reversed: the binder calls
 * {@link #rebind} / {@link #unbind} / {@link #refreshSlots} inside its own
 * lock; the bus never calls back into the binder while holding its own.</p>
 * <ul>
 *   <li>{@link #rebind(Mixer, AudioFormat, long)} — the binder's
 *       {@code bind()}: disposes every subscription from an older epoch
 *       (book &sect;6.2), then derives the slots.</li>
 *   <li>{@link #refreshSlots()} — re-derives the slots from the bound mixer
 *       (TRACKS listener, post-reconfigure, and an {@code attach} whose tap
 *       point is not in the current snapshot). Slots for
 *       {@link MixerChannel}s / {@link InsertSlot}s that already have one are
 *       <em>reused</em> (identity map) so an attached token keeps its slot.</li>
 *   <li>{@link #unbind()} — disposes everything and publishes an empty
 *       snapshot: the render thread then taps nothing.</li>
 *   <li>{@link #close()} — engine shutdown: unbind plus stop the analysis
 *       thread; the bus accepts no further attachments.</li>
 * </ul>
 *
 * <h2>RT API</h2>
 * <p>{@link #snapshot()} is one volatile read; {@link #blockCompleted(TapSnapshot)}
 * advances the single-writer block counter and unparks the analysis thread
 * iff the snapshot has rings. Nothing else on this class is for the render
 * thread.</p>
 */
public final class MeteringTapBus {

    /** Ring block size used when an analysis consumer attaches before the bus is bound. */
    public static final int DEFAULT_RING_BLOCK_FRAMES = 512;

    private static final Logger LOG = Logger.getLogger(MeteringTapBus.class.getName());
    private static final AnalysisLane[] NO_LANES = new AnalysisLane[0];
    private static final SampleBlockRing[] NO_RINGS = new SampleBlockRing[0];

    private final Object registryLock = new Object();
    private final LevelTapSlot masterChainSlot = new LevelTapSlot(MeterTapPoint.MASTER_CHAIN);
    private final LevelTapSlot masterOutSlot = new LevelTapSlot(MeterTapPoint.MASTER_OUT);

    private volatile TapSnapshot snapshot = TapSnapshot.empty(this, 0L);
    private volatile long epoch;
    /** Single-writer (render thread) block counter; see {@link #blockCompleted(TapSnapshot)}. */
    private volatile long blockIndex;
    private volatile AnalysisLane[] lanes = NO_LANES;
    private volatile AnalysisThread analysisThread;

    // Guarded by registryLock.
    private Mixer mixer;
    private AudioFormat format;
    private final IdentityHashMap<MixerChannel, LevelTapSlot> slotsByChannel = new IdentityHashMap<>();
    private final IdentityHashMap<InsertSlot, InsertTapPair> pairsByInsert = new IdentityHashMap<>();
    private final List<TapSubscription> subscriptions = new ArrayList<>();
    private final LinkedHashMap<AnalysisSubscription, AnalysisLane> lanesBySubscription = new LinkedHashMap<>();
    private boolean closed;

    /** Creates an unbound bus with an empty snapshot. */
    public MeteringTapBus() {
    }

    // Off-RT: binding lifecycle.

    /**
     * Binds the bus to {@code mixer} under a new (monotonic) epoch: every
     * subscription attached under an older epoch is disposed (its
     * {@code onDisposed} callbacks run after the lock is released), then the
     * slot set is derived from the mixer's channels, return buses, master and
     * every insert slot of each.
     *
     * @throws IllegalArgumentException if {@code epoch} is below the current one
     * @throws IllegalStateException    if the bus is closed
     */
    public void rebind(Mixer mixer, AudioFormat format, long epoch) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        Objects.requireNonNull(format, "format must not be null");
        List<Runnable> callbacks;
        synchronized (registryLock) {
            ensureOpenLocked();
            if (epoch < this.epoch) {
                throw new IllegalArgumentException(
                        "epoch must not go backwards: " + epoch + " < " + this.epoch);
            }
            callbacks = disposeLocked(sub -> sub.epoch() < epoch);
            this.mixer = mixer;
            this.format = format;
            this.epoch = epoch;
            rebuildLocked();
        }
        fire(callbacks);
    }

    /** Disposes every subscription and publishes an empty snapshot. Idempotent. */
    public void unbind() {
        List<Runnable> callbacks;
        synchronized (registryLock) {
            callbacks = disposeLocked(sub -> true);
            mixer = null;
            format = null;
            rebuildLocked();
        }
        fire(callbacks);
    }

    /**
     * Re-derives the slot set from the bound mixer, reusing the slot of every
     * channel / return / insert that already has one. No-op when unbound.
     */
    public void refreshSlots() {
        synchronized (registryLock) {
            if (mixer != null) {
                rebuildLocked();
            }
        }
    }

    /**
     * {@link #refreshSlots()} after an engine format apply: adopts
     * {@code format} (the sample rate the next snapshot reports and the
     * block size new analysis rings are sized from) and re-derives the slot
     * set. No-op when unbound. Existing rings are not resized.
     *
     * @param format the engine's current format
     */
    public void refreshSlots(AudioFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        synchronized (registryLock) {
            if (mixer != null) {
                this.format = format;
                rebuildLocked();
            }
        }
    }

    /**
     * Unbinds and stops the analysis thread (bounded join). The bus accepts no
     * attachments afterwards. Idempotent.
     */
    public void close() {
        List<Runnable> callbacks;
        AnalysisThread thread;
        synchronized (registryLock) {
            if (closed) {
                return;
            }
            closed = true;
            callbacks = disposeLocked(sub -> true);
            mixer = null;
            format = null;
            rebuildLocked();
            thread = analysisThread;
            analysisThread = null;
        }
        fire(callbacks);
        if (thread != null) {
            thread.close();
        }
    }

    // Off-RT: attachment.

    /**
     * Attaches a level-lane consumer to {@code point}. If the point is not in
     * the current snapshot the slots are refreshed first (covers "a meter
     * attaches after a return bus was added"); a point that still has no slot
     * simply reads {@code false} until it does.
     *
     * @throws IllegalStateException if the bus is closed
     */
    public LevelSubscription attachLevel(MeterTapPoint point) {
        Objects.requireNonNull(point, "point must not be null");
        synchronized (registryLock) {
            ensureOpenLocked();
            ensureResolvableLocked(point);
            LevelSubscription subscription = new LevelSubscription(this, point, epoch);
            subscriptions.add(subscription);
            return subscription;
        }
    }

    /**
     * Attaches a level-lane consumer to both halves of an insert's I/O pair.
     *
     * @throws IllegalStateException if the bus is closed
     */
    public InsertIoSubscription attachInsertIo(MeterTapPoint.InsertIo point) {
        Objects.requireNonNull(point, "point must not be null");
        synchronized (registryLock) {
            ensureOpenLocked();
            ensureResolvableLocked(point);
            InsertIoSubscription subscription = new InsertIoSubscription(this, point, epoch);
            subscriptions.add(subscription);
            return subscription;
        }
    }

    /**
     * Attaches an analysis-lane consumer to {@code point}: preallocates a
     * {@link SampleBlockRing} of {@code ringBlocks} blocks sized to the bound
     * format's buffer size ({@link #DEFAULT_RING_BLOCK_FRAMES} when unbound),
     * swaps the point's ring array into a new snapshot, and starts the
     * analysis thread on the first attach. For an {@link MeterTapPoint.InsertIo}
     * point the insert's output is ringed.
     *
     * @throws IllegalArgumentException if {@code ringBlocks} is not positive
     * @throws IllegalStateException    if the bus is closed
     */
    public AnalysisSubscription attachAnalysis(MeterTapPoint point, int ringBlocks,
                                               AnalysisConsumer consumer) {
        Objects.requireNonNull(point, "point must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");
        if (ringBlocks <= 0) {
            throw new IllegalArgumentException("ringBlocks must be positive: " + ringBlocks);
        }
        synchronized (registryLock) {
            ensureOpenLocked();
            ensureResolvableLocked(point);
            int blockFrames = format != null ? format.bufferSize() : DEFAULT_RING_BLOCK_FRAMES;
            SampleBlockRing ring = new SampleBlockRing(ringBlocks, blockFrames);
            AnalysisSubscription subscription = new AnalysisSubscription(this, point, epoch, ring);
            subscriptions.add(subscription);
            lanesBySubscription.put(subscription, new AnalysisLane(this, subscription, consumer));
            rebuildLocked();
            if (analysisThread == null) {
                AnalysisThread thread = new AnalysisThread(() -> lanes);
                thread.start();
                analysisThread = thread;
            }
            return subscription;
        }
    }

    // Off-RT: inspection seams.

    /** The current binding epoch. */
    public long epoch() {
        return epoch;
    }

    /** The block counter (the stamp the next block's frames carry). */
    public long blockIndex() {
        return blockIndex;
    }

    /** {@code true} between {@link #rebind} and {@link #unbind} / {@link #close}. */
    public boolean isBound() {
        synchronized (registryLock) {
            return mixer != null;
        }
    }

    /** {@code true} once {@link #close()} has run. */
    public boolean isClosed() {
        synchronized (registryLock) {
            return closed;
        }
    }

    /** Live level-lane tokens ({@link LevelSubscription} and {@link InsertIoSubscription}). */
    public int levelSubscriptionCount() {
        synchronized (registryLock) {
            int count = 0;
            for (TapSubscription sub : subscriptions) {
                if (!(sub instanceof AnalysisSubscription)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** Live analysis-lane tokens. */
    public int analysisSubscriptionCount() {
        synchronized (registryLock) {
            return lanesBySubscription.size();
        }
    }

    /**
     * The current {@link InsertSlot} to {@link InsertTapPair} map (identity
     * keyed, unmodifiable copy) — an <strong>off-RT inspection seam</strong>
     * for tests and diagnostics. It allocates a copy under the registry lock
     * and must never be called from the render thread: the chain's tag hook
     * resolves a pair through {@link TapSnapshot#insertTapFor(InsertSlot)}
     * (an allocation-free identity scan over the block's snapshot), not
     * through this map.
     */
    public Map<InsertSlot, InsertTapPair> insertTapPairs() {
        synchronized (registryLock) {
            return Collections.unmodifiableMap(new IdentityHashMap<>(pairsByInsert));
        }
    }

    // RT API.

    /** The slot set for this block: one volatile read, then use it for the whole block. */
    @RealTimeSafe
    public TapSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Ends a block: advances the block counter and, iff {@code taps} carries
     * analysis rings, unparks the analysis thread. The render thread's only
     * cross-thread signal.
     */
    @RealTimeSafe
    public void blockCompleted(TapSnapshot taps) {
        blockIndex++;
        if (taps != null && taps.hasAnalysisRings()) {
            AnalysisThread thread = analysisThread;
            if (thread != null) {
                thread.wake();
            }
        }
    }

    @RealTimeSafe
    long currentBlockIndex() {
        return blockIndex;
    }

    // Package-private seams.

    /** Detaches {@code subscription}; callbacks run after the lock is released. Idempotent. */
    void detach(TapSubscription subscription) {
        List<Runnable> callbacks;
        synchronized (registryLock) {
            boolean removed = subscriptions.remove(subscription);
            callbacks = subscription.markDisposed();
            if (removed && subscription instanceof AnalysisSubscription analysis) {
                lanesBySubscription.remove(analysis);
                rebuildLocked();
            }
        }
        fire(callbacks);
    }

    boolean isAnalysisThreadAlive() {
        AnalysisThread thread = analysisThread;
        return thread != null && thread.isAlive();
    }

    AnalysisLane[] lanes() {
        return lanes;
    }

    // Locked helpers.

    private void ensureOpenLocked() {
        if (closed) {
            throw new IllegalStateException("MeteringTapBus is closed");
        }
    }

    private void ensureResolvableLocked(MeterTapPoint point) {
        if (mixer == null) {
            return;
        }
        TapSnapshot current = snapshot;
        if (current.resolve(point) == null && current.resolveInsert(point) == null) {
            rebuildLocked();
        }
    }

    private List<Runnable> disposeLocked(Predicate<TapSubscription> which) {
        List<Runnable> callbacks = new ArrayList<>();
        for (Iterator<TapSubscription> it = subscriptions.iterator(); it.hasNext(); ) {
            TapSubscription sub = it.next();
            if (!which.test(sub)) {
                continue;
            }
            it.remove();
            callbacks.addAll(sub.markDisposed());
            if (sub instanceof AnalysisSubscription analysis) {
                lanesBySubscription.remove(analysis);
            }
        }
        return callbacks;
    }

    /**
     * Derives the slot set from the bound mixer (reusing existing slots by
     * subject identity), assigns each slot its immutable ring array from the
     * live analysis lanes, and publishes a new snapshot plus lane array.
     */
    private void rebuildLocked() {
        AnalysisLane[] laneArray = lanesBySubscription.values().toArray(NO_LANES);
        Mixer bound = mixer;
        if (bound == null) {
            slotsByChannel.clear();
            pairsByInsert.clear();
            masterChainSlot.setRings(NO_RINGS);
            masterOutSlot.setRings(NO_RINGS);
            lanes = laneArray;
            snapshot = TapSnapshot.empty(this, epoch);
            return;
        }

        List<MixerChannel> channels = bound.getChannels();
        List<MixerChannel> returns = bound.getReturnBuses();
        MixerChannel master = bound.getMasterChannel();

        IdentityHashMap<MixerChannel, LevelTapSlot> nextSlots = new IdentityHashMap<>();
        MixerChannel[] channelSubjects = new MixerChannel[channels.size()];
        LevelTapSlot[] channelSlots = new LevelTapSlot[channels.size()];
        for (int i = 0; i < channelSubjects.length; i++) {
            MixerChannel channel = channels.get(i);
            LevelTapSlot slot = slotsByChannel.get(channel);
            if (slot == null) {
                slot = new LevelTapSlot(new MeterTapPoint.ChannelPost(channel.getId()));
            }
            nextSlots.put(channel, slot);
            channelSubjects[i] = channel;
            channelSlots[i] = slot;
        }
        MixerChannel[] returnSubjects = new MixerChannel[returns.size()];
        LevelTapSlot[] returnSlots = new LevelTapSlot[returns.size()];
        for (int i = 0; i < returnSubjects.length; i++) {
            MixerChannel returnBus = returns.get(i);
            LevelTapSlot slot = slotsByChannel.get(returnBus);
            if (slot == null) {
                slot = new LevelTapSlot(new MeterTapPoint.ReturnPost(returnBus.getId()));
            }
            nextSlots.put(returnBus, slot);
            returnSubjects[i] = returnBus;
            returnSlots[i] = slot;
        }

        IdentityHashMap<InsertSlot, InsertTapPair> nextPairs = new IdentityHashMap<>();
        List<InsertSlot> insertSubjects = new ArrayList<>();
        List<MixerChannel> insertOwners = new ArrayList<>();
        List<InsertTapPair> insertPairs = new ArrayList<>();
        List<MixerChannel> owners = new ArrayList<>(channels.size() + returns.size() + 1);
        owners.addAll(channels);
        owners.addAll(returns);
        if (master != null) {
            owners.add(master);
        }
        for (MixerChannel owner : owners) {
            for (InsertSlot insert : owner.getInsertSlots()) {
                InsertTapPair pair = pairsByInsert.get(insert);
                if (pair == null) {
                    MeterTapPoint.InsertIo point =
                            new MeterTapPoint.InsertIo(insert.getPluginInstanceId());
                    pair = new InsertTapPair(insert, new LevelTapSlot(point), new LevelTapSlot(point));
                }
                nextPairs.put(insert, pair);
                insertSubjects.add(insert);
                insertOwners.add(owner);
                insertPairs.add(pair);
            }
        }

        slotsByChannel.clear();
        slotsByChannel.putAll(nextSlots);
        pairsByInsert.clear();
        pairsByInsert.putAll(nextPairs);

        for (LevelTapSlot slot : channelSlots) {
            slot.setRings(ringsFor(slot.point(), laneArray));
        }
        for (LevelTapSlot slot : returnSlots) {
            slot.setRings(ringsFor(slot.point(), laneArray));
        }
        masterChainSlot.setRings(ringsFor(MeterTapPoint.MASTER_CHAIN, laneArray));
        masterOutSlot.setRings(ringsFor(MeterTapPoint.MASTER_OUT, laneArray));
        for (InsertTapPair pair : insertPairs) {
            pair.input().setRings(NO_RINGS);
            pair.output().setRings(ringsFor(pair.output().point(), laneArray));
        }

        lanes = laneArray;
        snapshot = new TapSnapshot(this, epoch, format.sampleRate(),
                channelSubjects, channelSlots, returnSubjects, returnSlots,
                masterChainSlot, masterOutSlot,
                insertSubjects.toArray(new InsertSlot[0]),
                insertOwners.toArray(new MixerChannel[0]),
                insertPairs.toArray(new InsertTapPair[0]));
    }

    private static SampleBlockRing[] ringsFor(MeterTapPoint point, AnalysisLane[] laneArray) {
        int count = 0;
        for (AnalysisLane lane : laneArray) {
            if (lane.point().equals(point)) {
                count++;
            }
        }
        if (count == 0) {
            return NO_RINGS;
        }
        SampleBlockRing[] rings = new SampleBlockRing[count];
        int i = 0;
        for (AnalysisLane lane : laneArray) {
            if (lane.point().equals(point)) {
                rings[i++] = lane.ring();
            }
        }
        return rings;
    }

    /** Runs disposal callbacks outside the registry lock; a failing callback does not stop the rest. */
    private static void fire(List<Runnable> callbacks) {
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "onDisposed callback failed", e);
            }
        }
    }
}
