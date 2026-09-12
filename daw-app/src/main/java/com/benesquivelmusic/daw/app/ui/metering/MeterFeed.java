package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.metering.InsertIoSubscription;
import com.benesquivelmusic.daw.core.metering.LevelSubscription;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import com.benesquivelmusic.daw.core.metering.MeteringTapBus;
import com.benesquivelmusic.daw.core.metering.TapSubscription;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The FX-pulse drain of the metering tap bus (story 318; Audio Engine Wiring
 * Design Book &sect;4.3): one {@link FxDispatcher} pulse participant that,
 * on every frame, reads the latest RT-published {@link MeterFrame} of each
 * <em>visible</em> subscription and hands it to the surface's sink.
 *
 * <h2>Per pulse, per subscription</h2>
 * <ol>
 *   <li>{@code visible.getAsBoolean()} is {@code false} &rarr; detach the engine
 *       token until shown. A hidden meter has no render demand, slot read or sink call (book
 *       &sect;3.5 "a meter that is not on screen costs zero").</li>
 *   <li>The engine token was disposed by a rebind / unbind &rarr; deliver
 *       one silent frame, then re-attach under the bus's current epoch (the
 *       subscription is a lasting intent; see {@link MeterSubscription}).</li>
 *   <li>{@code readInto(frame)} &rarr; deliver only when the frame is
 *       coherent, its epoch equals the token's and its block index advanced
 *       since the last delivery, or a new clipping occurrence is pending.
 *       Clipping survives quiet blocks between pulses and is acknowledged
 *       independently by each surface after its sink succeeds. If a ring
 *       replacement has no stable levels yet, its inherited clip occurrence
 *       is delivered with the surface's last coherent levels (initially
 *       silence), without waiting for another render.</li>
 *   <li><strong>Honest idle:</strong> no new block for {@link #STALE_NANOS}
 *       (measured with the injected clock on the FX thread) &rarr; deliver
 *       exactly one silent frame ({@link MeterFrame#markSilent}), then
 *       nothing until frames resume. Meters fall to floor at stop instead of
 *       freezing on the last value.</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #subscribe} / {@link #subscribeInsertIo} return a
 * {@link MeterSubscription}; an equal {@link MeterKey} replaces (and
 * disposes) the earlier one. {@link #dispose()} disposes every subscription
 * and removes the pulse participant; the owner ({@code MainController})
 * calls it from the primary stage's {@code setOnHidden}. Everything here runs
 * on the JavaFX Application Thread — the feed touches JavaFX only through
 * the sinks, which run on the pulse. The pulse checks the current engine
 * token's volatile disposal state, so delayed disposal of an older token
 * cannot invalidate its replacement.</p>
 */
public final class MeterFeed {

    /** No new block for this long &rarr; one silent frame ("honest idle"). */
    public static final long STALE_NANOS = 200_000_000L;

    /** Lane count of a silent frame delivered before any real frame was seen. */
    static final int DEFAULT_SILENT_CHANNELS = 2;

    private static final Entry[] NO_ENTRIES = new Entry[0];

    private final MeteringTapBus bus;
    private final LongSupplier clock;
    private final Runnable participantRemover;
    /** Insertion-ordered so pulses deliver in subscription order (FX thread only). */
    private final Map<MeterKey, Entry> entries = new LinkedHashMap<>();
    /** Snapshot iterated by {@link #pulse()} so a sink may subscribe / dispose re-entrantly. */
    private Entry[] entryArray = NO_ENTRIES;
    private long readAttempts;
    private boolean disposed;

    /**
     * Creates a feed over {@code bus} and registers it as a pulse participant
     * of {@code dispatcher}. Wall-clock stale detection via {@link System#nanoTime()}.
     */
    public MeterFeed(MeteringTapBus bus, FxDispatcher dispatcher) {
        this(bus, dispatcher, System::nanoTime);
    }

    /** Test seam: a deterministic clock for the stale window. */
    MeterFeed(MeteringTapBus bus, FxDispatcher dispatcher, LongSupplier clock) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.participantRemover = dispatcher.addPulseParticipant(this::pulse);
    }

    /**
     * Subscribes {@code sink} to the level lane of {@code point} for
     * {@code surface}. An equal {@link MeterKey} replaces the earlier
     * subscription, which is disposed.
     *
     * @param point   the tap point
     * @param surface the consuming surface (coalescing identity)
     * @param visible consulted on subscription and first on every pulse;
     *                {@code false} suspends the engine subscription
     * @param sink    the FX-thread consumer
     * @return the subscription token
     * @throws IllegalStateException if the feed is disposed
     */
    public MeterSubscription subscribe(MeterTapPoint point, Object surface,
                                       BooleanSupplier visible, MeterSink sink) {
        Objects.requireNonNull(point, "point must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(visible, "visible must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        ensureOpen();
        LevelEntry entry = new LevelEntry(this, new MeterKey(point, surface), visible, sink);
        install(entry);
        return entry;
    }

    /**
     * Subscribes {@code sink} to the input / output pair of the insert whose
     * {@code InsertSlot.getPluginInstanceId()} is {@code pluginInstanceId}.
     *
     * @throws IllegalStateException if the feed is disposed
     */
    public MeterSubscription subscribeInsertIo(UUID pluginInstanceId, Object surface,
                                               BooleanSupplier visible, InsertIoSink sink) {
        Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(visible, "visible must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        ensureOpen();
        MeterTapPoint.InsertIo point = new MeterTapPoint.InsertIo(pluginInstanceId);
        InsertEntry entry = new InsertEntry(this, new MeterKey(point, surface), visible, sink);
        install(entry);
        return entry;
    }

    /** Live subscriptions (test seam). */
    public int subscriptionCount() {
        return entries.size();
    }

    /** {@code true} once {@link #dispose()} ran. */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Disposes every subscription (their engine tokens with them) and removes
     * the pulse participant. Idempotent. FX thread.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        participantRemover.run();
        Entry[] all = entryArray;
        entries.clear();
        entryArray = NO_ENTRIES;
        for (Entry entry : all) {
            entry.detach();
        }
    }

    /** Number of slot reads attempted so far (test seam: a hidden meter performs none). */
    long readAttempts() {
        return readAttempts;
    }

    /** The pulse-participant body: one pass over the subscription snapshot. */
    void pulse() {
        Entry[] all = entryArray;
        if (all.length == 0) {
            return;
        }
        long now = 0L;
        boolean clockRead = false;
        RuntimeException failure = null;
        for (Entry entry : all) {
            try {
                if (entry.disposed) {
                    continue;
                }
                if (!entry.visible.getAsBoolean()) {
                    entry.suspend();
                    continue;
                }
                if (entry.disposed) {
                    continue;
                }
                if (!clockRead) {
                    now = clock.getAsLong();
                    clockRead = true;
                }
                entry.pulse(now);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IllegalStateException("Meter pulse failed", exception);
                } else if (failure.getCause() != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (disposed) {
            throw new IllegalStateException("MeterFeed is disposed");
        }
    }

    private void install(Entry entry) {
        boolean visible = entry.visible.getAsBoolean();
        ensureOpen();
        Entry previous = entries.put(entry.key, entry);
        if (previous != null) {
            previous.detach();
        }
        entry.lastDeliveryNanos = clock.getAsLong();
        entry.tokenEpoch = bus.epoch();
        if (visible) {
            entry.attach();
        }
        rebuildArray();
    }

    private void remove(Entry entry) {
        if (entries.get(entry.key) == entry) {
            entries.remove(entry.key);
            rebuildArray();
        }
        entry.detach();
    }

    private void rebuildArray() {
        entryArray = entries.values().toArray(NO_ENTRIES);
    }

    /**
     * One subscription: the shared pulse state machine over an abstract
     * engine token. Package-private (not private) only so the sealed
     * {@link MeterSubscription} can name it; never constructed outside the feed.
     */
    abstract static sealed class Entry implements MeterSubscription permits LevelEntry, InsertEntry {

        final MeterFeed feed;
        final MeterKey key;
        final BooleanSupplier visible;
        private TapSubscription engineToken;
        private boolean resumeSilence;
        long tokenEpoch;
        long lastDeliveryNanos;
        long lastBlockIndex = -1L;
        int lastChannels = DEFAULT_SILENT_CHANNELS;
        boolean silentDelivered;
        boolean disposed;

        Entry(MeterFeed feed, MeterKey key, BooleanSupplier visible) {
            this.feed = feed;
            this.key = key;
            this.visible = visible;
        }

        @Override
        public final MeterKey key() {
            return key;
        }

        @Override
        public final long epoch() {
            return tokenEpoch;
        }

        @Override
        public final boolean isDisposed() {
            return disposed;
        }

        @Override
        public final void dispose() {
            if (!disposed) {
                feed.remove(this);
            }
        }

        final void pulse(long now) {
            if (resumeSilence || (engineToken != null && engineToken.isDisposed())) {
                resumeSilence = false;
                try {
                    deliverSilence(now);
                } finally {
                    lastBlockIndex = -1L;
                    if (!disposed) {
                        attach();
                    }
                }
                return;
            }
            if (engineToken == null) {
                attach();
                if (disposed) {
                    return;
                }
                lastDeliveryNanos = now;
            }
            feed.readAttempts++;
            if (readFresh()) {
                lastBlockIndex = frameBlockIndex();
                lastChannels = frameChannels();
                lastDeliveryNanos = now;
                silentDelivered = false;
                deliverFrame();
            } else if (!silentDelivered && now - lastDeliveryNanos >= STALE_NANOS) {
                deliverSilence(now);
            }
        }

        private void deliverSilence(long now) {
            silentDelivered = true;
            lastDeliveryNanos = now;
            deliverSilentFrame(tokenEpoch, Math.max(lastBlockIndex, 0L), lastChannels);
        }

        /**
         * Attaches a fresh engine token under the bus's current epoch. A
         * closed bus ends the subscription instead (the engine is shutting
         * down; nothing will ever publish again).
         */
        final void attach() {
            try {
                TapSubscription token = attachToken();
                engineToken = token;
                tokenEpoch = token.epoch();
            } catch (IllegalStateException closed) {
                feed.remove(this);
            }
        }

        /** Releases render demand while retaining the UI intent and last displayed state. */
        final void suspend() {
            if (engineToken != null) {
                engineToken.dispose();
                engineToken = null;
                resumeSilence = true;
            }
        }

        /** Marks disposed and releases the engine token. */
        final void detach() {
            disposed = true;
            suspend();
        }

        abstract TapSubscription attachToken();

        /**
         * Reads the newest frame(s); {@code true} only when coherent, of the
         * token's epoch, and of a block not yet delivered.
         */
        abstract boolean readFresh();

        abstract long frameBlockIndex();

        abstract int frameChannels();

        abstract void deliverFrame();

        abstract void deliverSilentFrame(long epoch, long blockIndex, int channels);

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[" + key + ", epoch=" + tokenEpoch
                    + (disposed ? ", disposed" : "") + "]";
        }
    }

    /** A level-lane subscription over a {@link LevelSubscription} token. */
    static final class LevelEntry extends Entry {

        private final MeterSink sink;
        private final MeterFrame frame = new MeterFrame();
        private final ClipDelivery clipping = new ClipDelivery();
        private LevelSubscription token;

        LevelEntry(MeterFeed feed, MeterKey key, BooleanSupplier visible, MeterSink sink) {
            super(feed, key, visible);
            this.sink = sink;
        }

        @Override
        TapSubscription attachToken() {
            token = feed.bus.attachLevel(key.point());
            clipping.startObserving(token.epoch(), token.lastClippedBlockIndex());
            return token;
        }

        @Override
        boolean readFresh() {
            LevelSubscription current = token;
            if (current == null) {
                return false;
            }
            boolean levelsRead = current.readInto(frame) && frame.epoch() == tokenEpoch;
            long clipBlockIndex = current.lastClippedBlockIndex();
            if (!levelsRead && !clipping.hasUnreportedClip(tokenEpoch, clipBlockIndex)) {
                return false;
            }
            if (frame.epoch() != tokenEpoch) {
                frame.markSilent(tokenEpoch, Math.max(lastBlockIndex, 0L), lastChannels);
            }
            frame.retainClipOccurrence(clipBlockIndex);
            return (levelsRead && frame.blockIndex() != lastBlockIndex) || clipping.hasUnreportedClip(frame);
        }

        @Override
        long frameBlockIndex() {
            return frame.blockIndex();
        }

        @Override
        int frameChannels() {
            return frame.channelCount();
        }

        @Override
        void deliverFrame() {
            clipping.prepare(frame);
            sink.accept(frame);
            clipping.acknowledge(frame);
        }

        @Override
        void deliverSilentFrame(long epoch, long blockIndex, int channels) {
            frame.markSilent(epoch, blockIndex, channels);
            sink.accept(frame);
        }
    }

    /** An {@code INSERT_IO} subscription over an {@link InsertIoSubscription} token. */
    static final class InsertEntry extends Entry {

        private final InsertIoSink sink;
        private final MeterFrame input = new MeterFrame();
        private final MeterFrame output = new MeterFrame();
        private final ClipDelivery inputClipping = new ClipDelivery();
        private final ClipDelivery outputClipping = new ClipDelivery();
        private InsertIoSubscription token;

        InsertEntry(MeterFeed feed, MeterKey key, BooleanSupplier visible, InsertIoSink sink) {
            super(feed, key, visible);
            this.sink = sink;
        }

        @Override
        TapSubscription attachToken() {
            token = feed.bus.attachInsertIo((MeterTapPoint.InsertIo) key.point());
            inputClipping.startObserving(token.epoch(), token.lastInputClippedBlockIndex());
            outputClipping.startObserving(token.epoch(), token.lastOutputClippedBlockIndex());
            return token;
        }

        /**
         * The output half is published last on the render thread, so a
         * coherent, advanced output frame is the block gate; the input half
         * (published first) is then read.
         *
         * <p>The two halves are independent seqlocks, so a render thread that
         * starts the NEXT block between the two reads can hand back an input
         * frame from block N+1 beside an output frame from block N. That pair
         * would read as a nonsense I/O relationship in an editor footer, so a
         * mismatched input is replaced with silence for that delivery rather
         * than shown: one dark IN reading beats a wrong one, and the next
         * pulse is 16 ms away. A failed input read is treated the same way.</p>
         */
        @Override
        boolean readFresh() {
            InsertIoSubscription current = token;
            if (current == null) {
                return false;
            }
            boolean outputRead = current.readOutputInto(output) && output.epoch() == tokenEpoch;
            long outputClip = current.lastOutputClippedBlockIndex();
            long inputClip = current.lastInputClippedBlockIndex();
            if ((!outputRead || output.blockIndex() == lastBlockIndex)
                    && !outputClipping.hasUnreportedClip(tokenEpoch, outputClip)
                    && !inputClipping.hasUnreportedClip(tokenEpoch, inputClip)) {
                return false;
            }
            if (output.epoch() != tokenEpoch) {
                output.markSilent(tokenEpoch, Math.max(lastBlockIndex, 0L), lastChannels);
            }
            output.retainClipOccurrence(outputClip);
            if (!current.readInputInto(input)
                    || input.epoch() != output.epoch()
                    || input.blockIndex() != output.blockIndex()) {
                input.markSilent(output.epoch(), output.blockIndex(), output.channelCount());
            }
            input.retainClipOccurrence(inputClip);
            return true;
        }

        @Override
        long frameBlockIndex() {
            return output.blockIndex();
        }

        @Override
        int frameChannels() {
            return output.channelCount();
        }

        @Override
        void deliverFrame() {
            inputClipping.prepare(input);
            outputClipping.prepare(output);
            sink.accept(input, output);
            inputClipping.acknowledge(input);
            outputClipping.acknowledge(output);
        }

        @Override
        void deliverSilentFrame(long epoch, long blockIndex, int channels) {
            input.markSilent(epoch, blockIndex, channels);
            output.markSilent(epoch, blockIndex, channels);
            sink.accept(input, output);
        }
    }

    /** Occurrences are acknowledged per surface, independently of its displayed clip latch. */
    private static final class ClipDelivery {
        private long epoch;
        private long lastClippedBlockIndex = -1L;

        void startObserving(long epoch, long lastClippedBlockIndex) {
            this.epoch = epoch;
            this.lastClippedBlockIndex = lastClippedBlockIndex;
        }

        boolean hasUnreportedClip(MeterFrame frame) {
            return hasUnreportedClip(frame.epoch(), frame.lastClippedBlockIndex());
        }

        boolean hasUnreportedClip(long frameEpoch, long clipBlockIndex) {
            return clipBlockIndex > acknowledgedBlock(frameEpoch);
        }

        void prepare(MeterFrame frame) {
            frame.reportClippingAfter(acknowledgedBlock(frame.epoch()));
        }

        void acknowledge(MeterFrame frame) {
            long acknowledged = acknowledgedBlock(frame.epoch());
            epoch = frame.epoch();
            lastClippedBlockIndex = Math.max(acknowledged, frame.lastClippedBlockIndex());
        }

        private long acknowledgedBlock(long frameEpoch) {
            return epoch == frameEpoch ? lastClippedBlockIndex : -1L;
        }
    }
}
