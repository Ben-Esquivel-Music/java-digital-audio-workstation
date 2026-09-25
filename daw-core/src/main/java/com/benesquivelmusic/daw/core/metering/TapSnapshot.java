package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
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
 * so all tap kinds of one block carry the same stamp.</p>
 */
public final class TapSnapshot {

    private static final MixerChannel[] NO_CHANNELS = new MixerChannel[0];
    private static final LevelTapSlot[] NO_SLOTS = new LevelTapSlot[0];
    private static final InsertSlot[] NO_INSERTS = new InsertSlot[0];
    private static final InsertTapPair[] NO_PAIRS = new InsertTapPair[0];

    private final MeteringTapBus bus;
    private final Mixer mixer;
    private final long epoch;
    private final AudioFormat format;
    private final MixerChannel[] channelSubjects;
    private final LevelTapSlot[] channelSlots;
    private final MixerChannel[] returnSubjects;
    private final LevelTapSlot[] returnSlots;
    private final LevelTapSlot masterChain;
    private final LevelTapSlot masterOut;
    private final LevelTapSlot[] masteringStages;
    private final InsertSlot[] insertSubjects;
    private final MixerChannel[] insertOwners;
    private final InsertTapPair[] insertPairs;
    private final boolean hasAnalysisRings;
    private final int channelSlotCount;
    private final int returnSlotCount;
    private final int masteringStageSlotCount;
    private final Map<MeterTapPoint, LevelTapSlot> slotsByPoint;
    private final Map<MeterTapPoint, InsertTapPair> pairsByPoint;
    private final LevelTapSlot[] publicationSlots;

    /** An unbound snapshot: nothing is tapped. */
    static TapSnapshot empty(MeteringTapBus bus, long epoch) {
        return new TapSnapshot(bus, null, epoch, null, NO_CHANNELS, NO_SLOTS, NO_CHANNELS, NO_SLOTS,
                null, null, NO_SLOTS, NO_INSERTS, NO_CHANNELS, NO_PAIRS);
    }

    /**
     * Builds a snapshot over arrays the bus has just constructed; the
     * snapshot takes ownership of them and never mutates them.
     */
    TapSnapshot(MeteringTapBus bus, Mixer mixer, long epoch, AudioFormat format,
                MixerChannel[] channelSubjects, LevelTapSlot[] channelSlots,
                MixerChannel[] returnSubjects, LevelTapSlot[] returnSlots,
                LevelTapSlot masterChain, LevelTapSlot masterOut, LevelTapSlot[] masteringStages,
                InsertSlot[] insertSubjects, MixerChannel[] insertOwners,
                InsertTapPair[] insertPairs) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.mixer = mixer;
        this.epoch = epoch;
        this.format = format;
        this.channelSubjects = Objects.requireNonNull(channelSubjects);
        this.channelSlots = Objects.requireNonNull(channelSlots);
        this.returnSubjects = Objects.requireNonNull(returnSubjects);
        this.returnSlots = Objects.requireNonNull(returnSlots);
        this.masterChain = masterChain;
        this.masterOut = masterOut;
        this.masteringStages = Objects.requireNonNull(masteringStages);
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
        int channelCount = 0;
        for (LevelTapSlot slot : channelSlots) {
            if (slot == null) {
                continue;
            }
            channelCount++;
            byPoint.put(slot.point(), slot);
            rings |= slot.rings().length > 0;
        }
        int returnCount = 0;
        for (LevelTapSlot slot : returnSlots) {
            if (slot == null) {
                continue;
            }
            returnCount++;
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
        int masteringCount = 0;
        for (LevelTapSlot slot : masteringStages) {
            if (slot == null) {
                continue;
            }
            masteringCount++;
            byPoint.put(slot.point(), slot);
            rings |= slot.rings().length > 0;
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
        var publicationSlots = new java.util.ArrayList<>(byPoint.values());
        for (InsertTapPair pair : insertPairs) {
            publicationSlots.add(pair.input());
        }
        this.publicationSlots = publicationSlots.toArray(NO_SLOTS);
        this.hasAnalysisRings = rings;
        this.channelSlotCount = channelCount;
        this.returnSlotCount = returnCount;
        this.masteringStageSlotCount = masteringCount;
    }

    /** The binding epoch this snapshot was built under. */
    @RealTimeSafe
    public long epoch() {
        return epoch;
    }

    /** A transition between graph and registry publications must skip metering. */
    @RealTimeSafe
    public boolean matches(Mixer mixer, long epoch) {
        return this.mixer == mixer && this.epoch == epoch;
    }

    /** Rejects blocks rendered before the off-RT format notification reaches the registry. */
    @RealTimeSafe
    public boolean matchesFormat(AudioFormat format) {
        return this.format != null && this.format.equals(format);
    }

    /** The engine sample rate in Hz at build time ({@code 0.0} when unbound). */
    @RealTimeSafe
    public double sampleRate() {
        return format == null ? 0.0 : format.sampleRate();
    }

    /** The bus block counter to stamp every frame of the current block with. */
    @RealTimeSafe
    public long blockIndex() {
        return bus.currentBlockIndex();
    }

    /** Holds level and analysis publication until the engine finishes this render attempt. */
    @RealTimeSafe
    public void beginPublication() {
        for (LevelTapSlot slot : publicationSlots) {
            slot.deferPublication();
        }
    }

    /** Replaces every demanded tap, including unvisited inserts, with one silent block. */
    @RealTimeSafe
    public void abortBlock(int channelCount, int numFrames) {
        long stamp = blockIndex();
        for (LevelTapSlot slot : publicationSlots) {
            slot.abortBlock(epoch, stamp, channelCount, numFrames);
        }
    }

    @RealTimeSafe
    void completePublication() {
        for (LevelTapSlot slot : publicationSlots) {
            slot.completePublication();
        }
    }

    /** {@code true} when at least one analysis ring is attached anywhere in this snapshot. */
    @RealTimeSafe
    public boolean hasAnalysisRings() {
        return hasAnalysisRings;
    }

    /** {@code true} when nothing is tapped, including a bound bus with no demand. */
    @RealTimeSafe
    public boolean isEmpty() {
        return channelSlotCount == 0 && returnSlotCount == 0
                && masterChain == null && masterOut == null && insertPairs.length == 0
                && masteringStageSlotCount == 0;
    }

    /**
     * The {@code CHANNEL_POST} slot for the mixer channel at {@code index},
     * or {@code null} when there is no demand, the index is out of range or the channel at that
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

    /** The {@code MASTER_CHAIN} slot, or {@code null} when unbound or unobserved. */
    @RealTimeSafe
    public LevelTapSlot masterChain() {
        return masterChain;
    }

    /** The {@code MASTER_OUT} slot, or {@code null} when unbound or unobserved. */
    @RealTimeSafe
    public LevelTapSlot masterOut() {
        return masterOut;
    }

    /** The indexed mastering-stage slot, or {@code null} when unbound or unobserved. */
    @RealTimeSafe
    public LevelTapSlot masteringStage(int stageIndex) {
        if (stageIndex < 0 || stageIndex >= masteringStages.length) {
            return null;
        }
        return masteringStages[stageIndex];
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
        return channelSlotCount;
    }

    /** Number of {@code RETURN_POST} slots. */
    @RealTimeSafe
    public int returnSlotCount() {
        return returnSlotCount;
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
        return "TapSnapshot[epoch=" + epoch + ", channels=" + channelSlotCount
                + ", returns=" + returnSlotCount + ", master=" + (masterChain != null)
                + ", masteringStages=" + masteringStageSlotCount
                + ", inserts=" + insertPairs.length + ", rings=" + hasAnalysisRings + "]";
    }
}
