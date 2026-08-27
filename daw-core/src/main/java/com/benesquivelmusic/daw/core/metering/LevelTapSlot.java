package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * The level lane's RT-written, preallocated, latest-wins slot for one tap
 * point (book &sect;3.4).
 *
 * <h2>Writer (render thread, exactly one per block)</h2>
 * <ol>
 *   <li>{@link #beginBlock(long, long, int)} stamps the block and zeroes the
 *       accumulators;</li>
 *   <li>{@link #accumulate(int, float)} (inline-able for use inside an
 *       existing sample loop) or the block helpers fold in samples:
 *       {@code peak = max(peak, |x|)}, {@code sumSquares += x*x};</li>
 *   <li>{@link #publish(int)} computes RMS and the clip flag and publishes
 *       the frame; {@link #publishSilence(long, long, int)} is the one-call
 *       form for a muted / solo-excluded channel.</li>
 * </ol>
 * <h2>How often a slot publishes</h2>
 * <p>Publication is <strong>at most once per block per slot</strong>, and how
 * often that actually happens depends on the tap point:</p>
 * <ul>
 *   <li>{@code MASTER_OUT} publishes on <em>every</em> block, playing or
 *       stopped — it is the render pipeline's unconditional tap, which is what
 *       returns every master meter to floor at stop;</li>
 *   <li>{@code CHANNEL_POST}, {@code RETURN_POST} and {@code MASTER_CHAIN}
 *       publish exactly once per block <em>while the mixer runs</em> (muted and
 *       solo-excluded owners publish silence, so no slot the mix walks is
 *       skipped) — the pipeline calls the mixer only while the transport is
 *       playing or recording, so none of them publishes while stopped;</li>
 *   <li>an {@code INSERT_IO} pair publishes only in the blocks in which its
 *       processor actually runs: a bypassed insert is absent from its
 *       {@code EffectsChain} and publishes nothing, an empty or bypassed chain
 *       publishes nothing, and the master channel's own insert slots are never
 *       walked by the mixer at all.</li>
 * </ul>
 * <p>A reader therefore cannot treat "block index did not advance" as
 * "silence": a non-advancing block index means <em>stale</em>. The
 * consumer-side discriminator is a timeout — see the FX drain's
 * {@code MeterFeed.STALE_NANOS}, which delivers one silent frame when no new
 * block has arrived for its stale window and then stops.</p>
 *
 * <h2>Publication: a seqlock</h2>
 * <p>The single writer bumps {@code sequence} to odd (opaque store followed
 * by a store-store fence), writes the published fields, then bumps it to even
 * with a release store. {@link #readInto(MeterFrame)} acquire-loads the
 * sequence, copies the fields into the frame's staging area, issues a
 * load-load fence and re-reads the sequence; only a stable even sequence is
 * committed into the frame. A read that sees a write in progress retries a
 * bounded number of times and returns {@code false} without touching the
 * frame's live fields, so a torn frame mixing two blocks or two epochs is
 * impossible (the "latest-wins publication" acceptance test).</p>
 *
 * <p>No allocation, no lock and no atomic read-modify-write on any producer
 * ({@code @RealTimeSafe}) path. The consumer-side
 * {@link #readInto(MeterFrame)}, {@link #hasPublished()} and
 * {@link #toString()} are off-RT and unannotated — {@code readInto} spins and
 * {@code toString} builds a String.</p>
 */
public final class LevelTapSlot {

    /** Bounded retry budget for {@link #readInto(MeterFrame)}. */
    public static final int MAX_READ_ATTEMPTS = 8;

    private static final int MAX_CHANNELS = MeterFrame.MAX_CHANNELS;
    private static final SampleBlockRing[] NO_RINGS = new SampleBlockRing[0];
    private static final VarHandle SEQUENCE;

    static {
        try {
            SEQUENCE = MethodHandles.lookup().findVarHandle(
                    LevelTapSlot.class, "sequence", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MeterTapPoint point;

    // Accumulation state — render-thread owned, never read by consumers.
    private final float[] accumulatedPeak = new float[MAX_CHANNELS];
    private final double[] accumulatedSumSquares = new double[MAX_CHANNELS];
    private int accumulatedChannels;
    private long accumulatedEpoch;
    private long accumulatedBlockIndex;

    // Published state — guarded by the seqlock below.
    @SuppressWarnings("unused") // accessed through SEQUENCE only
    private long sequence;
    private final float[] publishedPeak = new float[MAX_CHANNELS];
    private final float[] publishedRms = new float[MAX_CHANNELS];
    private int publishedChannels;
    private boolean publishedClipped;
    private long publishedEpoch;
    private long publishedBlockIndex;

    /**
     * Analysis rings attached to this tap point. Immutable array, swapped
     * wholesale off-RT by the bus; the render thread reads it once per block.
     */
    private volatile SampleBlockRing[] rings = NO_RINGS;

    LevelTapSlot(MeterTapPoint point) {
        this.point = Objects.requireNonNull(point, "point must not be null");
    }

    /** The tap point this slot publishes for. */
    @RealTimeSafe
    public MeterTapPoint point() {
        return point;
    }

    /**
     * Starts a new block: records the stamp and clears the accumulators for
     * {@code channelCount} lanes (clamped to {@code 0..MAX_CHANNELS}).
     */
    @RealTimeSafe
    public void beginBlock(long epoch, long blockIndex, int channelCount) {
        int lanes = clampLanes(channelCount);
        accumulatedChannels = lanes;
        accumulatedEpoch = epoch;
        accumulatedBlockIndex = blockIndex;
        for (int ch = 0; ch < lanes; ch++) {
            accumulatedPeak[ch] = 0f;
            accumulatedSumSquares[ch] = 0.0;
        }
    }

    /**
     * Folds one sample into lane {@code channel}. Lanes outside the block's
     * lane count are ignored, so a caller already inside a sample loop needs
     * no guard of its own.
     */
    @RealTimeSafe
    public void accumulate(int channel, float sample) {
        if (channel < 0 || channel >= accumulatedChannels) {
            return;
        }
        float magnitude = Math.abs(sample);
        if (magnitude > accumulatedPeak[channel]) {
            accumulatedPeak[channel] = magnitude;
        }
        accumulatedSumSquares[channel] += (double) sample * sample;
    }

    /** Folds {@code numFrames} samples of {@code buffer} into lane {@code channel}. */
    @RealTimeSafe
    public void accumulate(int channel, float[] buffer, int numFrames) {
        if (channel < 0 || channel >= accumulatedChannels || buffer == null) {
            return;
        }
        int n = Math.min(numFrames, buffer.length);
        float peak = accumulatedPeak[channel];
        double sumSquares = accumulatedSumSquares[channel];
        for (int f = 0; f < n; f++) {
            float x = buffer[f];
            float magnitude = Math.abs(x);
            if (magnitude > peak) {
                peak = magnitude;
            }
            sumSquares += (double) x * x;
        }
        accumulatedPeak[channel] = peak;
        accumulatedSumSquares[channel] = sumSquares;
    }

    /**
     * Folds {@code numFrames} samples of {@code buffer}, each multiplied by
     * {@code gain}, into lane {@code channel} — the post-fader value the mix
     * sum produces without materialising it.
     */
    @RealTimeSafe
    public void accumulateScaled(int channel, float[] buffer, int numFrames, float gain) {
        if (channel < 0 || channel >= accumulatedChannels || buffer == null) {
            return;
        }
        int n = Math.min(numFrames, buffer.length);
        float peak = accumulatedPeak[channel];
        double sumSquares = accumulatedSumSquares[channel];
        for (int f = 0; f < n; f++) {
            float x = buffer[f] * gain;
            float magnitude = Math.abs(x);
            if (magnitude > peak) {
                peak = magnitude;
            }
            sumSquares += (double) x * x;
        }
        accumulatedPeak[channel] = peak;
        accumulatedSumSquares[channel] = sumSquares;
    }

    /** Folds {@code numFrames} samples of a 64-bit accumulator lane into lane {@code channel}. */
    @RealTimeSafe
    public void accumulate(int channel, double[] buffer, int numFrames) {
        if (channel < 0 || channel >= accumulatedChannels || buffer == null) {
            return;
        }
        int n = Math.min(numFrames, buffer.length);
        double peak = accumulatedPeak[channel];
        double sumSquares = accumulatedSumSquares[channel];
        for (int f = 0; f < n; f++) {
            double x = buffer[f];
            double magnitude = Math.abs(x);
            if (magnitude > peak) {
                peak = magnitude;
            }
            sumSquares += x * x;
        }
        accumulatedPeak[channel] = (float) peak;
        accumulatedSumSquares[channel] = sumSquares;
    }

    /**
     * Publishes the accumulated block: {@code rms = sqrt(sumSquares / numFrames)}
     * per lane, {@code clipped} when any lane's peak reached {@code 1.0f}.
     */
    @RealTimeSafe
    public void publish(int numFrames) {
        int lanes = accumulatedChannels;
        double inverseFrames = numFrames > 0 ? 1.0 / numFrames : 0.0;
        long s = (long) SEQUENCE.getOpaque(this);
        SEQUENCE.setOpaque(this, s + 1L);
        VarHandle.storeStoreFence();
        boolean clipped = false;
        for (int ch = 0; ch < lanes; ch++) {
            float peak = accumulatedPeak[ch];
            publishedPeak[ch] = peak;
            publishedRms[ch] = (float) Math.sqrt(accumulatedSumSquares[ch] * inverseFrames);
            if (peak >= 1.0f) {
                clipped = true;
            }
        }
        for (int ch = lanes; ch < MAX_CHANNELS; ch++) {
            publishedPeak[ch] = 0f;
            publishedRms[ch] = 0f;
        }
        publishedChannels = lanes;
        publishedClipped = clipped;
        publishedEpoch = accumulatedEpoch;
        publishedBlockIndex = accumulatedBlockIndex;
        SEQUENCE.setRelease(this, s + 2L);
    }

    /**
     * Publishes a silent frame with the given stamp — the one-call form for a
     * muted or solo-excluded channel, so the slot still sees one publish per
     * block.
     */
    @RealTimeSafe
    public void publishSilence(long epoch, long blockIndex, int channelCount) {
        int lanes = clampLanes(channelCount);
        long s = (long) SEQUENCE.getOpaque(this);
        SEQUENCE.setOpaque(this, s + 1L);
        VarHandle.storeStoreFence();
        for (int ch = 0; ch < MAX_CHANNELS; ch++) {
            publishedPeak[ch] = 0f;
            publishedRms[ch] = 0f;
        }
        publishedChannels = lanes;
        publishedClipped = false;
        publishedEpoch = epoch;
        publishedBlockIndex = blockIndex;
        SEQUENCE.setRelease(this, s + 2L);
    }

    /**
     * The analysis rings attached to this tap point — an immutable array to
     * be read <em>once</em> per block. Empty when no analyzer is attached, in
     * which case the render thread makes no extra pass over the buffer.
     */
    @RealTimeSafe
    public SampleBlockRing[] rings() {
        return rings;
    }

    /**
     * Copies the latest published frame into {@code frame}. Consumer side —
     * NOT {@code @RealTimeSafe}: it may spin up to {@link #MAX_READ_ATTEMPTS}
     * times against a writer in progress.
     *
     * @return {@code true} when a stable frame was committed into
     *         {@code frame}; {@code false} when nothing has been published
     *         yet or no stable read was obtained (the frame is untouched)
     */
    public boolean readInto(MeterFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        float[] stagePeak = frame.stagePeak();
        float[] stageRms = frame.stageRms();
        for (int attempt = 0; attempt < MAX_READ_ATTEMPTS; attempt++) {
            long before = (long) SEQUENCE.getAcquire(this);
            if (before == 0L) {
                return false; // never published
            }
            if ((before & 1L) != 0L) {
                Thread.onSpinWait();
                continue; // write in progress
            }
            System.arraycopy(publishedPeak, 0, stagePeak, 0, MAX_CHANNELS);
            System.arraycopy(publishedRms, 0, stageRms, 0, MAX_CHANNELS);
            int channels = publishedChannels;
            boolean clipped = publishedClipped;
            long epoch = publishedEpoch;
            long blockIndex = publishedBlockIndex;
            VarHandle.loadLoadFence();
            long after = (long) SEQUENCE.getAcquire(this);
            if (before == after) {
                frame.commitStaged(channels, clipped, epoch, blockIndex);
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    /** {@code true} once at least one frame has been published (test / diagnostic seam). */
    public boolean hasPublished() {
        return (long) SEQUENCE.getAcquire(this) != 0L;
    }

    /** The current seqlock sequence (test seam; even when stable). */
    long sequence() {
        return (long) SEQUENCE.getAcquire(this);
    }

    /** Swaps the ring array (off-RT, bus only). Never mutates the previous array. */
    void setRings(SampleBlockRing[] rings) {
        this.rings = rings == null || rings.length == 0 ? NO_RINGS : rings;
    }

    private static int clampLanes(int channelCount) {
        return channelCount < 0 ? 0 : Math.min(channelCount, MAX_CHANNELS);
    }

    @Override
    public String toString() {
        return "LevelTapSlot[" + point + ", rings=" + rings.length + "]";
    }
}
