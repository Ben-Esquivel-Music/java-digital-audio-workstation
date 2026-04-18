package com.benesquivelmusic.daw.core.track;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable "render in place" action.
 *
 * <p>Executing this action renders the track's output through its signal
 * chain (clips / virtual instrument → insert effects → gain/pan) and places
 * the resulting {@link com.benesquivelmusic.daw.core.audio.AudioClip} either
 * on the source track (replacing the original clips) or on a new track,
 * depending on the provided {@link RenderInPlaceOptions}.</p>
 *
 * <p>Undoing the action removes the rendered clip and — if the original
 * clips were replaced — restores them on the source track.</p>
 *
 * @see RenderInPlaceService
 */
public final class RenderInPlaceAction implements UndoableAction {

    private final Track track;
    private final MixerChannel channel;
    private final int sampleRate;
    private final double tempo;
    private final int channels;
    private final RenderInPlaceOptions options;

    private RenderInPlaceService.Result result;

    /**
     * Creates a new render-in-place action.
     *
     * @param track      the track to render
     * @param channel    the mixer channel associated with the track
     * @param sampleRate the project sample rate in Hz
     * @param tempo      the project tempo in BPM
     * @param channels   the number of output channels
     * @param options    render options (must not be null)
     */
    public RenderInPlaceAction(Track track, MixerChannel channel,
                               int sampleRate, double tempo, int channels,
                               RenderInPlaceOptions options) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.sampleRate = sampleRate;
        this.tempo = tempo;
        this.channels = channels;
    }

    @Override
    public String description() {
        return "Render in Place";
    }

    @Override
    public void execute() {
        this.result = RenderInPlaceService.render(
                track, channel, sampleRate, tempo, channels, options);
    }

    @Override
    public void undo() {
        if (result != null) {
            RenderInPlaceService.restore(track, result);
        }
    }

    /**
     * Returns the result of the last {@link #execute()} invocation, or
     * {@code null} if the action has not yet been executed or the track had
     * nothing to render.
     */
    public RenderInPlaceService.Result getResult() {
        return result;
    }
}
