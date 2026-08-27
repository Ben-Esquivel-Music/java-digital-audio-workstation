package com.benesquivelmusic.daw.core.metering;

import java.util.Objects;

/**
 * One analysis-lane binding: a subscription's ring, its consumer and the
 * consumer's preallocated scratch block. Drained only by
 * {@link AnalysisThread}; the render thread never sees this class (it sees
 * the ring through {@link LevelTapSlot#rings()}).
 */
final class AnalysisLane {

    private final MeteringTapBus bus;
    private final AnalysisSubscription subscription;
    private final AnalysisConsumer consumer;
    private final float[][] scratch;
    /** Analysis-thread owned: the dropped count last reported to the consumer. */
    private long reportedDropped;

    AnalysisLane(MeteringTapBus bus, AnalysisSubscription subscription, AnalysisConsumer consumer) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.subscription = Objects.requireNonNull(subscription, "subscription must not be null");
        this.consumer = Objects.requireNonNull(consumer, "consumer must not be null");
        this.scratch = new float[SampleBlockRing.MAX_CHANNELS][subscription.ring().blockFrames()];
    }

    MeterTapPoint point() {
        return subscription.point();
    }

    SampleBlockRing ring() {
        return subscription.ring();
    }

    AnalysisSubscription subscription() {
        return subscription;
    }

    /**
     * Drains up to one ring's worth of blocks into the consumer, in order,
     * then reports any new overrun. Analysis thread only; bounded per call so
     * one hot lane cannot starve the others.
     */
    void drain() {
        SampleBlockRing ring = subscription.ring();
        double sampleRate = bus.snapshot().sampleRate();
        int budget = ring.capacity();
        for (int i = 0; i < budget; i++) {
            int numFrames = ring.readInto(scratch);
            if (numFrames < 0) {
                break;
            }
            consumer.onBlock(scratch, ring.lastChannelCount(), numFrames, sampleRate);
        }
        long dropped = ring.droppedBlocks();
        if (dropped != reportedDropped) {
            reportedDropped = dropped;
            consumer.onOverrun(dropped);
        }
    }

    @Override
    public String toString() {
        return "AnalysisLane[" + subscription + "]";
    }
}
