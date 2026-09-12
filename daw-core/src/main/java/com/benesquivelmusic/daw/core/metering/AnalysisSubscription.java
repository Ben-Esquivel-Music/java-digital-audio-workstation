package com.benesquivelmusic.daw.core.metering;

import java.util.Objects;

/**
 * An analysis-lane token (book &sect;3.4): owns the bounded
 * {@link SampleBlockRing} the render thread copies this tap point's blocks
 * into, which the {@code daw-metering-analysis} thread drains into the
 * consumer given at attach. Obtained from
 * {@link MeteringTapBus#attachAnalysis(MeterTapPoint, int, AnalysisConsumer)}.
 *
 * <p>Disposing the token swaps the ring out of the RT-visible array; the
 * consumer receives no further blocks after {@link #dispose()} returns
 * except a drain already in flight on the analysis thread.</p>
 */
public final class AnalysisSubscription extends TapSubscription {

    private final SampleBlockRing ring;

    AnalysisSubscription(MeteringTapBus bus, MeterTapPoint point, long epoch, SampleBlockRing ring) {
        super(bus, point, epoch);
        this.ring = Objects.requireNonNull(ring, "ring must not be null");
    }

    /** Cumulative blocks dropped because the consumer fell behind (drop-oldest). */
    public long droppedBlocks() {
        return ring.droppedBlocks();
    }

    /** The ring's slot count (the requested block count rounded up to a power of two). */
    public int ringCapacity() {
        return ring.capacity();
    }

    /** Frames per block the ring was sized for at attach. */
    public int ringBlockFrames() {
        return ring.blockFrames();
    }

    SampleBlockRing ring() {
        return ring;
    }
}
