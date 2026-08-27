package com.benesquivelmusic.daw.core.metering;

import java.util.Objects;

/**
 * A level-lane token: reads the latest published {@link MeterFrame} of one
 * tap point (book &sect;3.4). Obtained from
 * {@link MeteringTapBus#attachLevel(MeterTapPoint)}.
 *
 * <p>{@link #readInto(MeterFrame)} resolves the slot through the bus's
 * current snapshot on every call (one {@code HashMap.get}, no allocation),
 * so a subscription survives a {@link MeteringTapBus#refreshSlots()} that
 * keeps its channel, and reads {@code false} while its tap point has no
 * slot. For an {@link MeterTapPoint.InsertIo} point this token reads the
 * insert's <em>output</em> half; use
 * {@link MeteringTapBus#attachInsertIo(MeterTapPoint.InsertIo)} for both
 * halves.</p>
 *
 * <p>Consumer side: not {@code @RealTimeSafe}.</p>
 */
public final class LevelSubscription extends TapSubscription {

    LevelSubscription(MeteringTapBus bus, MeterTapPoint point, long epoch) {
        super(bus, point, epoch);
    }

    /**
     * Copies the latest stable frame of this tap point into {@code frame}.
     *
     * @return {@code true} when {@code frame} now holds a coherent frame;
     *         {@code false} (frame untouched) when the token is disposed,
     *         the bus has moved to another epoch, the tap point currently
     *         has no slot, nothing has been published yet, or no stable read
     *         was obtained
     */
    public boolean readInto(MeterFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        if (isDisposed()) {
            return false;
        }
        TapSnapshot snapshot = bus().snapshot();
        if (snapshot.epoch() != epoch()) {
            return false;
        }
        LevelTapSlot slot = snapshot.resolve(point());
        return slot != null && slot.readInto(frame);
    }
}
