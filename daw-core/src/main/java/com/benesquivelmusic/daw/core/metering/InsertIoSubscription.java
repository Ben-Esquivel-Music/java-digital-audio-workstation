package com.benesquivelmusic.daw.core.metering;

import java.util.Objects;

/**
 * A level-lane token for an insert's input/output pair
 * ({@link MeterTapPoint.InsertIo}). Obtained from
 * {@link MeteringTapBus#attachInsertIo(MeterTapPoint.InsertIo)}; the plugin
 * editor footer's IN / OUT meters read both halves from one token.
 *
 * <p>The pair is resolved through the bus's current snapshot on every call
 * (a {@code HashMap.get}, no allocation). An insert that is bypassed is
 * absent from its channel's {@code EffectsChain} and publishes nothing, so
 * both reads return {@code false} and the FX side shows silence.</p>
 *
 * <p>Consumer side: not {@code @RealTimeSafe}.</p>
 */
public final class InsertIoSubscription extends TapSubscription {

    InsertIoSubscription(MeteringTapBus bus, MeterTapPoint.InsertIo point, long epoch) {
        super(bus, point, epoch);
    }

    /** Copies the latest stable frame of the insert's INPUT into {@code frame}. */
    public boolean readInputInto(MeterFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        InsertTapPair pair = resolvePair();
        return pair != null && pair.input().readInto(frame);
    }

    /** Copies the latest stable frame of the insert's OUTPUT into {@code frame}. */
    public boolean readOutputInto(MeterFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        InsertTapPair pair = resolvePair();
        return pair != null && pair.output().readInto(frame);
    }

    private InsertTapPair resolvePair() {
        if (isDisposed()) {
            return null;
        }
        TapSnapshot snapshot = bus().snapshot();
        if (snapshot.epoch() != epoch()) {
            return null;
        }
        return snapshot.resolveInsert(point());
    }
}
