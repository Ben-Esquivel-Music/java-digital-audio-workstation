package com.benesquivelmusic.daw.sdk.spatial;

import java.util.Objects;

/**
 * The choice of decoder used to render an Ambisonic B-format signal to
 * the session's monitoring output.
 *
 * <p>An Ambisonic recording is independent of any particular speaker
 * layout; a decoder selects the mathematical matrix that transforms the
 * spherical-harmonic channels into the audible target format. Each
 * permitted variant of this sealed interface represents one such target
 * and carries the parameters needed to construct the matrix:</p>
 *
 * <ul>
 *   <li>{@link StereoUhj} — UHJ matrix decode to a stereo-compatible
 *       2-channel feed (also playable on mono systems).</li>
 *   <li>{@link Binaural5} — five virtual-loudspeaker binaural decode for
 *       headphone monitoring without a custom HRTF.</li>
 *   <li>{@link BinauralHrtf} — binaural decode using a specific
 *       {@link HrtfProfile}.</li>
 *   <li>{@link LoudspeakerRig} — direct decode to a multi-channel
 *       speaker {@link SpeakerLayout} (5.1, 7.1.4, 9.1.6, …).</li>
 * </ul>
 *
 * <p>This is an algebraic data type; consumers are expected to use
 * exhaustive pattern-matching {@code switch} expressions over the
 * permitted variants.</p>
 *
 * @see AmbisonicTrack
 * @see AmbisonicOrder
 */
public sealed interface AmbisonicDecoder
        permits AmbisonicDecoder.StereoUhj,
                AmbisonicDecoder.Binaural5,
                AmbisonicDecoder.BinauralHrtf,
                AmbisonicDecoder.LoudspeakerRig {

    /**
     * Returns the number of audio channels produced by this decoder.
     *
     * @return the output channel count
     */
    int outputChannelCount();

    /**
     * Returns a short, human-readable name suitable for display in the
     * mixer's "output decoder" selector.
     *
     * @return the display name
     */
    String displayName();

    /**
     * UHJ stereo decoder — produces a 2-channel feed from B-format that
     * is compatible with stereo (and mono) playback systems.
     */
    record StereoUhj() implements AmbisonicDecoder {
        @Override
        public int outputChannelCount() {
            return 2;
        }

        @Override
        public String displayName() {
            return "Stereo (UHJ)";
        }
    }

    /**
     * Five-virtual-loudspeaker binaural decoder. Renders B-format
     * through five virtual speakers (L, R, C, LS, RS) summed via a
     * generic head model into a 2-channel headphone feed. Use
     * {@link BinauralHrtf} when a specific HRTF profile is required.
     */
    record Binaural5() implements AmbisonicDecoder {
        @Override
        public int outputChannelCount() {
            return 2;
        }

        @Override
        public String displayName() {
            return "Binaural (5-virtual)";
        }
    }

    /**
     * Binaural decoder driven by a specific HRTF profile, suitable for
     * headphone monitoring of immersive content.
     *
     * @param profile the HRTF profile to use; must not be {@code null}
     */
    record BinauralHrtf(HrtfProfile profile) implements AmbisonicDecoder {
        public BinauralHrtf {
            Objects.requireNonNull(profile, "profile must not be null");
        }

        @Override
        public int outputChannelCount() {
            return 2;
        }

        @Override
        public String displayName() {
            return "Binaural (HRTF: " + profile.displayName() + ")";
        }
    }

    /**
     * Loudspeaker-rig decoder — decodes B-format directly to the
     * channels of the given {@link SpeakerLayout} (e.g. 5.1, 7.1.4,
     * 9.1.6).
     *
     * @param layout the destination speaker layout; must not be
     *               {@code null}
     */
    record LoudspeakerRig(SpeakerLayout layout) implements AmbisonicDecoder {
        public LoudspeakerRig {
            Objects.requireNonNull(layout, "layout must not be null");
        }

        @Override
        public int outputChannelCount() {
            return layout.channelCount();
        }

        @Override
        public String displayName() {
            return "Loudspeaker " + layout.name();
        }
    }
}
