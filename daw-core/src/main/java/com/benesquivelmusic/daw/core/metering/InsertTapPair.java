package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Objects;

/**
 * The two level slots of an {@link MeterTapPoint.InsertIo} tap point — the
 * insert's input (what the processor receives) and output (what it returns) —
 * keyed by the {@link InsertSlot} instance so the {@code EffectsChain} hook
 * can resolve it by tag identity ({@link TapSnapshot#insertTapFor(InsertSlot)}).
 *
 * <p>Immutable; both slots are written by the render thread in the block
 * where the insert's processor runs and read off-RT through
 * {@link InsertIoSubscription}.</p>
 */
public final class InsertTapPair {

    private final InsertSlot slot;
    private final LevelTapSlot input;
    private final LevelTapSlot output;

    InsertTapPair(InsertSlot slot, LevelTapSlot input, LevelTapSlot output) {
        this.slot = Objects.requireNonNull(slot, "slot must not be null");
        this.input = Objects.requireNonNull(input, "input must not be null");
        this.output = Objects.requireNonNull(output, "output must not be null");
    }

    /** The tapped insert slot (identity key). */
    @RealTimeSafe
    public InsertSlot slot() {
        return slot;
    }

    /** The level slot for the insert's input. */
    @RealTimeSafe
    public LevelTapSlot input() {
        return input;
    }

    /** The level slot for the insert's output. */
    @RealTimeSafe
    public LevelTapSlot output() {
        return output;
    }

    @Override
    public String toString() {
        return "InsertTapPair[" + slot.getName() + "/" + slot.getPluginInstanceId() + "]";
    }
}
