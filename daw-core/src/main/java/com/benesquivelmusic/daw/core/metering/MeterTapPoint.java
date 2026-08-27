package com.benesquivelmusic.daw.core.metering;

import java.util.Objects;
import java.util.UUID;

/**
 * A point on the render graph a meter or analyzer can tap (Audio Engine
 * Wiring Design Book &sect;3.3). Value semantics: two tap points are equal
 * when they name the same graph position, so they serve as registry map keys.
 *
 * <p>Off-RT only. The render thread never resolves a tap point by value; it
 * resolves slots through {@link TapSnapshot} by index and identity.</p>
 *
 * <ul>
 *   <li>{@link ChannelPost} &mdash; per-channel post-insert, post-fader.</li>
 *   <li>{@link ReturnPost} &mdash; per-return-bus post-chain, post-volume.</li>
 *   <li>{@link MasterChain} &mdash; the master sum before the master fader
 *       (what an export measures; story 321 moves it post-mastering-chain).</li>
 *   <li>{@link MasterOut} &mdash; the final output, post master fader/mute
 *       (what the interface receives).</li>
 *   <li>{@link InsertIo} &mdash; a focused insert's input/output pair, keyed
 *       by the slot's stable {@code pluginInstanceId}.</li>
 * </ul>
 */
public sealed interface MeterTapPoint
        permits MeterTapPoint.ChannelPost, MeterTapPoint.ReturnPost, MeterTapPoint.MasterChain,
                MeterTapPoint.MasterOut, MeterTapPoint.InsertIo {

    /** The single master-chain tap point (pre master fader). */
    MasterChain MASTER_CHAIN = new MasterChain();

    /** The single master-output tap point (post master fader / mute). */
    MasterOut MASTER_OUT = new MasterOut();

    /**
     * Per-channel post-fader tap.
     *
     * @param channelId the {@code MixerChannel} id (equals the track id for
     *                  channels created by {@code DawProject.addTrack})
     */
    record ChannelPost(UUID channelId) implements MeterTapPoint {
        public ChannelPost {
            Objects.requireNonNull(channelId, "channelId must not be null");
        }
    }

    /**
     * Per-return-bus post-volume tap.
     *
     * @param busId the return bus's {@code MixerChannel} id
     */
    record ReturnPost(UUID busId) implements MeterTapPoint {
        public ReturnPost {
            Objects.requireNonNull(busId, "busId must not be null");
        }
    }

    /** The master sum before the master fader. Use {@link #MASTER_CHAIN}. */
    record MasterChain() implements MeterTapPoint {
    }

    /** The final output after the master fader and mute. Use {@link #MASTER_OUT}. */
    record MasterOut() implements MeterTapPoint {
    }

    /**
     * A focused insert's input and output pair.
     *
     * @param pluginInstanceId {@code InsertSlot.getPluginInstanceId()} of the
     *                         tapped slot
     */
    record InsertIo(UUID pluginInstanceId) implements MeterTapPoint {
        public InsertIo {
            Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
        }
    }
}
