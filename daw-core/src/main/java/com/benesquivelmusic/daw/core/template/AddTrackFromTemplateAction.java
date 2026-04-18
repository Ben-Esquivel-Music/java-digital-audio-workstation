package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that creates a new track from a {@link TrackTemplate} and
 * adds it (along with its configured mixer channel) to the project.
 *
 * <p>Executing the action creates a fresh {@link Track}, adds it to the
 * project, and applies the template's inserts, sends, volume, pan, color, and
 * I/O routing to the track and its associated mixer channel. Undoing removes
 * the track from the project (the original mixer channel is retained in the
 * project's track-channel map so a redo reuses the same channel object).</p>
 */
public final class AddTrackFromTemplateAction implements UndoableAction {

    private final DawProject project;
    private final TrackTemplate template;
    private final String name;
    private Track createdTrack;

    /**
     * Creates a new add-track-from-template action.
     *
     * @param project  the project to add the track to
     * @param template the template to instantiate
     * @param name     the name for the new track, or {@code null} to use the
     *                 template's {@link TrackTemplate#nameHint() nameHint}
     */
    public AddTrackFromTemplateAction(DawProject project, TrackTemplate template, String name) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.name = name;
    }

    /**
     * Creates the action using the template's default name hint.
     *
     * @param project  the project to add the track to
     * @param template the template to instantiate
     */
    public AddTrackFromTemplateAction(DawProject project, TrackTemplate template) {
        this(project, template, null);
    }

    /**
     * Returns the track created by {@link #execute()}, or {@code null} if the
     * action has not been executed yet.
     *
     * @return the created track, or {@code null}
     */
    public Track getCreatedTrack() {
        return createdTrack;
    }

    @Override
    public String description() {
        return "Add Track from Template";
    }

    @Override
    public void execute() {
        if (createdTrack == null) {
            createdTrack = TrackTemplateService.createTrackFromTemplate(template, project, name);
        } else {
            // Redo: re-add the previously created track so the channel-to-track
            // mapping inside DawProject is reused and no duplicate channel is
            // created.
            project.addTrack(createdTrack);
        }
    }

    @Override
    public void undo() {
        if (createdTrack != null) {
            project.removeTrack(createdTrack);
        }
    }
}
