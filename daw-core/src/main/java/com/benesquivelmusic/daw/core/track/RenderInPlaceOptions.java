package com.benesquivelmusic.daw.core.track;

import java.util.Objects;
import java.util.function.Function;

/**
 * Options for a render-in-place operation.
 *
 * <p>"Render-in-place" (also known as "bounce in place" or "commit") creates
 * a new {@link com.benesquivelmusic.daw.core.audio.AudioClip} containing the
 * fully-processed output of a track — including insert effects, virtual
 * instruments, and (optionally) automation and sends — and optionally
 * replaces the original track content. Unlike track freeze, the result is a
 * permanent, editable audio clip.</p>
 *
 * <p>Use the {@link Builder} to configure options; sensible defaults cover
 * the common "commit this track to audio" workflow.</p>
 */
public final class RenderInPlaceOptions {

    /**
     * Callback that synthesizes audio for a MIDI track.
     *
     * <p>Given the track and the render length in frames, the implementation
     * must return a {@code [channels][frames]} audio buffer containing the
     * synthesized output (for example from a SoundFont renderer), or
     * {@code null} / an empty buffer if the track has nothing to render.</p>
     */
    @FunctionalInterface
    public interface MidiRenderer {
        /**
         * Synthesizes audio for the given MIDI track.
         *
         * @param track      the MIDI track to synthesize
         * @param sampleRate the project sample rate in Hz
         * @param tempo      the project tempo in BPM
         * @param channels   the number of output channels
         * @param totalFrames the total number of frames to render
         * @return synthesized audio as {@code [channel][frame]}, or {@code null}
         */
        float[][] render(Track track, int sampleRate, double tempo,
                         int channels, int totalFrames);
    }

    private final boolean replaceOriginalClips;
    private final boolean createNewTrack;
    private final boolean includeInserts;
    private final boolean includeAutomation;
    private final boolean includeSends;
    private final MidiRenderer midiRenderer;
    private final Function<Track, Track> newTrackFactory;

    private RenderInPlaceOptions(Builder b) {
        this.replaceOriginalClips = b.replaceOriginalClips;
        this.createNewTrack = b.createNewTrack;
        this.includeInserts = b.includeInserts;
        this.includeAutomation = b.includeAutomation;
        this.includeSends = b.includeSends;
        this.midiRenderer = b.midiRenderer;
        this.newTrackFactory = b.newTrackFactory;
    }

    /**
     * Returns options with sensible defaults: include inserts, do not include
     * automation or sends (neither is yet wired through), replace the
     * original clips on the source track.
     */
    public static RenderInPlaceOptions defaults() {
        return new Builder().build();
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether the rendered clip replaces the source clips on the original track. */
    public boolean isReplaceOriginalClips() {
        return replaceOriginalClips;
    }

    /**
     * Whether the rendered clip should be placed on a newly-created audio track.
     *
     * <p>Mutually exclusive with {@link #isReplaceOriginalClips()}.</p>
     */
    public boolean isCreateNewTrack() {
        return createNewTrack;
    }

    /** Whether to apply the mixer channel's insert effects chain during render. */
    public boolean isIncludeInserts() {
        return includeInserts;
    }

    /** Whether to bake track/channel automation into the rendered audio. */
    public boolean isIncludeAutomation() {
        return includeAutomation;
    }

    /** Whether to include pre-fader send contributions in the render. */
    public boolean isIncludeSends() {
        return includeSends;
    }

    /** Optional callback that synthesizes audio for MIDI tracks. */
    public MidiRenderer getMidiRenderer() {
        return midiRenderer;
    }

    /**
     * Optional factory invoked when {@link #isCreateNewTrack()} is set, to
     * produce the audio track that will host the rendered clip. The original
     * source track is passed in so the factory may copy naming/routing.
     */
    public Function<Track, Track> getNewTrackFactory() {
        return newTrackFactory;
    }

    /** Builder for {@link RenderInPlaceOptions}. */
    public static final class Builder {
        private boolean replaceOriginalClips = true;
        private boolean createNewTrack = false;
        private boolean includeInserts = true;
        private boolean includeAutomation = false;
        private boolean includeSends = false;
        private MidiRenderer midiRenderer;
        private Function<Track, Track> newTrackFactory;

        /** Replace the source clips on the original track with the rendered clip. */
        public Builder replaceOriginalClips(boolean value) {
            this.replaceOriginalClips = value;
            if (value) {
                this.createNewTrack = false;
            }
            return this;
        }

        /** Place the rendered clip on a newly-created audio track. */
        public Builder createNewTrack(boolean value) {
            this.createNewTrack = value;
            if (value) {
                this.replaceOriginalClips = false;
            }
            return this;
        }

        /** Apply insert effects during the render (default {@code true}). */
        public Builder includeInserts(boolean value) {
            this.includeInserts = value;
            return this;
        }

        /** Bake automation into the render. Default {@code false}. */
        public Builder includeAutomation(boolean value) {
            this.includeAutomation = value;
            return this;
        }

        /** Include pre-fader send contributions. Default {@code false}. */
        public Builder includeSends(boolean value) {
            this.includeSends = value;
            return this;
        }

        /** Sets the MIDI synthesis callback for MIDI tracks. */
        public Builder midiRenderer(MidiRenderer renderer) {
            this.midiRenderer = renderer;
            return this;
        }

        /** Sets the factory used to create a destination track when {@code createNewTrack} is set. */
        public Builder newTrackFactory(Function<Track, Track> factory) {
            this.newTrackFactory = factory;
            return this;
        }

        public RenderInPlaceOptions build() {
            if (replaceOriginalClips && createNewTrack) {
                throw new IllegalStateException(
                        "replaceOriginalClips and createNewTrack are mutually exclusive");
            }
            // Require a factory when createNewTrack is on.
            if (createNewTrack) {
                Objects.requireNonNull(newTrackFactory,
                        "newTrackFactory must be provided when createNewTrack is enabled");
            }
            return new RenderInPlaceOptions(this);
        }
    }
}
