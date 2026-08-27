package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable slot set the render thread taps for one block (book
 * &sect;3.5): "the engine sees only a fixed slot array and an immutable ring
 * array, swapped atomically when the registry changes".
 *
 * <p>Obtained once per block from {@link MeteringTapBus#snapshot()} and used
 * for the whole block. Slots are resolved by <em>index and identity</em>:
 * {@link #channelSlot(int, MixerChannel)} returns the slot at {@code index}
 * only if it was derived from that very {@link MixerChannel}, so a mixer whose
 * channel list changed between the snapshot and the block reads {@code null}
 * (untapped) rather than metering the wrong strip. Insert pairs are resolved
 * by a linear identity scan over a tiny array.</p>
 *
 * <p>Every accessor the render thread calls is {@code @RealTimeSafe}: plain
 * field reads, bounds checks and reference compares. {@link #resolve} /
 * {@link #resolveInsert} are the off-RT map lookups behind the subscription
 * tokens. {@link #blockIndex()} reads the bus's single-writer block counter
 * so all four tap kinds of one block carry the same stamp.</p>
 */
public final class TapSnapshot {

    private static final MixerChannel[] NO_CHANNELS = new MixerChannel[0];
    private static final LevelTapSlot[] NO_SLOTS = new LevelTapSlot[0];
    private static final InsertSlot[] NO_INSERTS = new InsertSlot[0];
    private static final InsertTapPair[] NO_PAIRS = new InsertTapPair[0];

    private final MeteringTapBus bus;
    private final long epoch;
    private final double sampleRate;
    private final MixerChannel[] channelSubjects;
    private final LevelTapSlot[] channelSlots;
    private final MixerChannel[] returnSubjects;
    private final LevelTapSlot[] returnSlots;
    private final LevelTapSlot masterChain;
    private final LevelTapSlot masterOut;
    private final InsertSlot[] insertSubjects;
    private final MixerChannel[] insertOwners;
    private final InsertTapPair[] insertPairs;
    private final boolean hasAnalysisRings;
    private final Map<MeterTapPoint, LevelTapSlot> slotsByPoint;
    private final Map<MeterTapPoint, InsertTapPair> pairsByPoint;

    /** An unbound snapshot: nothing is tapped. */
    static TapSnapshot empty(MeteringTapBus bus, long epoch) {
        return new TapSnapshot(bus, epoch, 0.0, NO_CHANNELS, NO_SLOTS, NO_CHANNELS, NO_SLOTS,
                null, null, NO_INSERTS, NO_CHANNELS, NO_PAIRS);
    }

    /**
     * Builds a snapshot over arrays the bus has just constructed; the
     * snapshot takes ownership of them and never mutates them.
     */
    TapSnapshot(MeteringTapBus bus, long epoch, double sampleRate,
                MixerChannel[] channelSubjects, LevelTapSlot[] channelSlots,
                MixerChannel[] returnSubjects, LevelTapSlot[] returnSlots,
                LevelTapSlot masterChain, LevelTapSlot masterOut,
                InsertSlot[] insertSubjects, MixerChannel[] insertOwners,
                InsertTapPair[] insertPairs) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.epoch = epoch;
        this.sampleRate = sampleRate;
        this.channelSubjects = Objects.requireNonNull(channelSubjects);
        this.channelSlots = Objects.requireNonNull(channelSlots);
        this.returnSubjects = Objects.requireNonNull(returnSubjects);
        this.returnSlots = Objects.requireNonNull(returnSlots);
        this.masterChain = masterChain;
        this.masterOut = masterOut;
        this.insertSubjects = Objects.requireNonNull(insertSubjects);
        this.insertOwners = Objects.requireNonNull(insertOwners);
        this.insertPairs = Objects.requireNonNull(insertPairs);
        if (channelSubjects.length != channelSlots.length
                || returnSubjects.length != returnSlots.length
                || insertSubjects.length != insertPairs.length
                || insertSubjects.length != insertOwners.length) {
            throw new IllegalArgumentException("subject / slot arrays must be parallel");
        }
        Map<MeterTapPoint, LevelTapSlot> byPoint = new HashMap<>();
        boolean rings = false;
        for (LevelTapSlot slot : channelSlots) {
            byPoint.put(slot.point(), slot);
            rings |= slot.rings().length > 0;
        }
        for (LevelTapSlot slot : returnSlots) {
            byPoint.put(slot.point(), slot);
            rings |= slot.rings().length > 0;
        }
        if (masterChain != null) {
            byPoint.put(masterChain.point(), masterChain);
            rings |= masterChain.rings().length > 0;
        }
        if (masterOut != null) {
            byPoint.put(masterOut.point(), masterOut);
            rings |= masterOut.rings().length > 0;
        }
        Map<MeterTapPoint, InsertTapPair> pairs = new HashMap<>();
        for (InsertTapPair pair : insertPairs) {
            MeterTapPoint point = pair.output().point();
            pairs.put(point, pair);
            byPoint.put(point, pair.output());
            rings |= pair.output().rings().length > 0 || pair.input().rings().length > 0;
        }
        this.slotsByPoint = byPoint;
        this.pairsByPoint = pairs;
        this.hasAnalysisRings = rings;
    }

    /** The binding epoch this snapshot was built under. */
    @RealTimeSafe
    public long epoch() {
        return epoch;
    }

    /** The engine sample rate in Hz at build time ({@code 0.0} when unbound). */
    @RealTimeSafe
    public double sampleRate() {
        return sampleRate;
    }

    /** The bus block counter to stamp every frame of the current block with. */
    @RealTimeSafe
    public long blockIndex() {
        return bus.currentBlockIndex();
    }

    /** {@code true} when at least one analysis ring is attached anywhere in this snapshot. */
    @RealTimeSafe
    public boolean hasAnalysisRings() {
        return hasAnalysisRings;
    }

    /** {@code true} when nothing is tapped (unbound bus). */
    @RealTimeSafe
    public boolean isEmpty() {
        return channelSlots.length == 0 && returnSlots.length == 0
                && masterChain == null && masterOut == null && insertPairs.length == 0;
    }

    /**
     * The {@code CHANNEL_POST} slot for the mixer channel at {@code index},
     * or {@code null} when the index is out of range or the channel at that
     * index is not the one this snapshot was derived from.
     */
    @RealTimeSafe
    public LevelTapSlot channelSlot(int index, MixerChannel channel) {
        if (index < 0 || index >= channelSlots.length) {
            return null;
        }
        return channelSubjects[index] == channel ? channelSlots[index] : null;
    }

    /** As {@link #channelSlot(int, MixerChannel)} for the {@code RETURN_POST} slots. */
    @RealTimeSafe
    public LevelTapSlot returnSlot(int index, MixerChannel bus) {
        if (index < 0 || index >= returnSlots.length) {
            return null;
        }
        return returnSubjects[index] == bus ? returnSlots[index] : null;
    }

    /** The {@code MASTER_CHAIN} slot, or {@code null} when unbound. */
    @RealTimeSafe
    public LevelTapSlot masterChain() {
        return masterChain;
    }

    /** The {@code MASTER_OUT} slot, or {@code null} when unbound. */
    @RealTimeSafe
    public LevelTapSlot masterOut() {
        return masterOut;
    }

    /** The {@code INSERT_IO} pair for {@code slot} (identity scan), or {@code null} when untapped. */
    @RealTimeSafe
    public InsertTapPair insertTapFor(InsertSlot slot) {
        InsertSlot[] subjects = insertSubjects;
        for (int i = 0; i < subjects.length; i++) {
            if (subjects[i] == slot) {
                return insertPairs[i];
            }
        }
        return null;
    }

    /** {@code true} when any insert of {@code channel} has a tap pair in this snapshot. */
    @RealTimeSafe
    public boolean hasInsertTaps(MixerChannel channel) {
        MixerChannel[] owners = insertOwners;
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == channel) {
                return true;
            }
        }
        return false;
    }

    /** Number of {@code CHANNEL_POST} slots. */
    @RealTimeSafe
    public int channelSlotCount() {
        return channelSlots.length;
    }

    /** Number of {@code RETURN_POST} slots. */
    @RealTimeSafe
    public int returnSlotCount() {
        return returnSlots.length;
    }

    /** Number of {@code INSERT_IO} pairs. */
    @RealTimeSafe
    public int insertTapCount() {
        return insertPairs.length;
    }

    /**
     * Off-RT: the level slot for {@code point} (an {@link MeterTapPoint.InsertIo}
     * resolves to its output half), or {@code null} when untapped.
     */
    LevelTapSlot resolve(MeterTapPoint point) {
        return slotsByPoint.get(point);
    }

    /** Off-RT: the insert pair for {@code point}, or {@code null} when untapped. */
    InsertTapPair resolveInsert(MeterTapPoint point) {
        return pairsByPoint.get(point);
    }

    @Override
    public String toString() {
        return "TapSnapshot[epoch=" + epoch + ", channels=" + channelSlots.length
                + ", returns=" + returnSlots.length + ", master=" + (masterChain != null)
                + ", inserts=" + insertPairs.length + ", rings=" + hasAnalysisRings + "]";
    }
}
