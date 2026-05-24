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
 * An undoable action that removes a track from the project.
 *
 * <p>Executing this action removes the track from the project's track list
 * and its mixer channel from the mixer. Undoing it re-adds the track at
 * its original position.</p>
 */
public final class RemoveTrackAction implements UndoableAction {

    private final DawProject project;
    private final Track track;
    private int originalIndex;

    /**
     * Creates a new remove-track action.
     *
     * @param project the project to remove the track from
     * @param track   the track to remove
     */
    public RemoveTrackAction(DawProject project, Track track) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.track = Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public String description() {
        return "Remove Track";
    }

    @Override
    public void execute() {
        originalIndex = project.getTracks().indexOf(track);
        boolean removed = project.removeTrack(track);
        // Story 283 — mirror DawProject.addTrack's parse/fallback so a
        // non-UUID track id (test fixtures) doesn't throw post-mutation.
        if (removed) {
            EventBusPublisher.publish(new MixerEvent.ChannelRemoved(
                    channelIdFor(track), Instant.now()));
        }
    }

    @Override
    public void undo() {
        project.addTrack(track);
        // Move the re-added track (which is appended) back to its original position
        int currentIndex = project.getTracks().size() - 1;
        if (currentIndex != originalIndex && originalIndex >= 0
                && originalIndex < project.getTracks().size()) {
            project.moveTrack(currentIndex, originalIndex);
        }
        EventBusPublisher.publish(new MixerEvent.ChannelAdded(
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
