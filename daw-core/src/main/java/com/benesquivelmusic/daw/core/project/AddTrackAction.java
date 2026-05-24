package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that adds an existing track to the project.
 *
 * <p>Executing this action adds the track and its corresponding mixer channel.
 * Undoing it removes the track from the project.</p>
 */
public final class AddTrackAction implements UndoableAction {

    private final DawProject project;
    private final Track track;

    /**
     * Creates a new add-track action.
     *
     * @param project the project to add the track to
     * @param track   the track to add
     */
    public AddTrackAction(DawProject project, Track track) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.track = Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public String description() {
        return "Add Track";
    }

    @Override
    public void execute() {
        project.addTrack(track);
        // Story 283 — channelId == trackId UUID invariant (see
        // DawProject.addTrack), so the channel-added event also acts as
        // a track-added signal for Workshop S3 invalidation. Test
        // fixtures with non-UUID track ids fall back to the mapped
        // channel id (mirrors DawProject.addTrack's parse/fallback) so
        // event publication never throws after a successful mutation.
        EventBusPublisher.publish(new MixerEvent.ChannelAdded(
                channelIdFor(track), Instant.now()));
    }

    @Override
    public void undo() {
        project.removeTrack(track);
        EventBusPublisher.publish(new MixerEvent.ChannelRemoved(
                channelIdFor(track), Instant.now()));
    }

    private UUID channelIdFor(Track track) {
        try {
            return UUID.fromString(track.getId());
        } catch (IllegalArgumentException e) {
            MixerChannel channel = project.getMixerChannelForTrack(track);
            return channel != null ? channel.getId() : UUID.randomUUID();
        }
    }
}
