package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mixer-channel event hierarchy.
 *
 * <p>Emitted when a {@link com.benesquivelmusic.daw.sdk.model.MixerChannel
 * MixerChannel} is added, removed, or has one of its top-line attributes
 * changed (gain, pan, mute, solo). Consumers read the post-change state
 * from the current project snapshot &mdash; the records below carry only
 * the channel id and a wall-clock timestamp.</p>
 */
public sealed interface MixerEvent extends DawEvent
        permits MixerEvent.ChannelAdded,
                MixerEvent.ChannelRemoved,
                MixerEvent.GainChanged,
                MixerEvent.PanChanged,
                MixerEvent.MuteChanged,
                MixerEvent.SoloChanged {

    /** Returns the id of the affected mixer channel. */
    UUID channelId();

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when a new mixer channel is added to the project.
     *
     * @param channelId id of the newly-added channel
     * @param timestamp wall-clock instant of the event
     */
    record ChannelAdded(UUID channelId, Instant timestamp) implements MixerEvent {
        public ChannelAdded {
            Objects.requireNonNull(channelId, "channelId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a mixer channel is removed from the project.
     *
     * @param channelId id of the removed channel
     * @param timestamp wall-clock instant of the event
     */
    record ChannelRemoved(UUID channelId, Instant timestamp) implements MixerEvent {
        public ChannelRemoved {
            Objects.requireNonNull(channelId, "channelId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a mixer channel's gain (linear, in [0.0, 1.0]) changes.
     *
     * @param channelId id of the affected channel
     * @param timestamp wall-clock instant of the event
     */
    record GainChanged(UUID channelId, Instant timestamp) implements MixerEvent {
        public GainChanged {
            Objects.requireNonNull(channelId, "channelId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a mixer channel's pan position changes.
     *
     * @param channelId id of the affected channel
     * @param timestamp wall-clock instant of the event
     */
    record PanChanged(UUID channelId, Instant timestamp) implements MixerEvent {
        public PanChanged {
            Objects.requireNonNull(channelId, "channelId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a mixer channel's mute flag changes.
     *
     * @param channelId id of the affected channel
     * @param muted     the new mute state
     * @param timestamp wall-clock instant of the event
     */
    record MuteChanged(UUID channelId, boolean muted, Instant timestamp) implements MixerEvent {
        public MuteChanged {
            Objects.requireNonNull(channelId, "channelId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a mixer channel's solo flag changes.
     *
     * @param channelId id of the affected channel
     * @param soloed    the new solo state
     * @param timestamp wall-clock instant of the event
     */
    record SoloChanged(UUID channelId, boolean soloed, Instant timestamp) implements MixerEvent {
        public SoloChanged {
            Objects.requireNonNull(channelId, "channelId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
