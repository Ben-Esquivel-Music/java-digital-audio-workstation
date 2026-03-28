package com.benesquivelmusic.daw.sdk.spatial;

import java.util.List;
import java.util.Objects;

/**
 * Immutable speaker layout definition for immersive audio monitoring.
 *
 * <p>Defines a named set of speakers used for object-based and channel-based
 * audio rendering. Predefined layouts cover the standard Dolby Atmos
 * configurations: 7.1.4, 5.1.4, 5.1, and stereo.</p>
 *
 * @param name     the display name for this layout (e.g. "7.1.4")
 * @param speakers the ordered list of speaker labels in this layout
 */
public record SpeakerLayout(String name, List<SpeakerLabel> speakers) {

    /** 7.1.4 — Dolby Atmos standard bed layout (12 channels). */
    public static final SpeakerLayout LAYOUT_7_1_4 = new SpeakerLayout("7.1.4", List.of(
            SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C, SpeakerLabel.LFE,
            SpeakerLabel.LS, SpeakerLabel.RS, SpeakerLabel.LRS, SpeakerLabel.RRS,
            SpeakerLabel.LTF, SpeakerLabel.RTF, SpeakerLabel.LTR, SpeakerLabel.RTR));

    /** 5.1.4 — Dolby Atmos layout without rear surrounds (10 channels). */
    public static final SpeakerLayout LAYOUT_5_1_4 = new SpeakerLayout("5.1.4", List.of(
            SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C, SpeakerLabel.LFE,
            SpeakerLabel.LS, SpeakerLabel.RS,
            SpeakerLabel.LTF, SpeakerLabel.RTF, SpeakerLabel.LTR, SpeakerLabel.RTR));

    /** 5.1 — Standard surround layout (6 channels). */
    public static final SpeakerLayout LAYOUT_5_1 = new SpeakerLayout("5.1", List.of(
            SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C, SpeakerLabel.LFE,
            SpeakerLabel.LS, SpeakerLabel.RS));

    /** Stereo — Standard two-channel layout (2 channels). */
    public static final SpeakerLayout LAYOUT_STEREO = new SpeakerLayout("Stereo", List.of(
            SpeakerLabel.L, SpeakerLabel.R));

    /** Mono — Single-channel layout (1 channel, center). */
    public static final SpeakerLayout LAYOUT_MONO = new SpeakerLayout("Mono", List.of(
            SpeakerLabel.C));

    public SpeakerLayout {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(speakers, "speakers must not be null");
        if (speakers.isEmpty()) {
            throw new IllegalArgumentException("speakers must not be empty");
        }
        speakers = List.copyOf(speakers);
    }

    /** Returns the number of channels in this layout. */
    public int channelCount() {
        return speakers.size();
    }

    /**
     * Returns the index of the given speaker label in this layout,
     * or {@code -1} if the speaker is not present.
     *
     * @param label the speaker label to find
     * @return the zero-based index or {@code -1}
     */
    public int indexOf(SpeakerLabel label) {
        return speakers.indexOf(label);
    }

    /**
     * Returns whether this layout contains the given speaker label.
     *
     * @param label the speaker label to check
     * @return {@code true} if present
     */
    public boolean contains(SpeakerLabel label) {
        return speakers.contains(label);
    }
}
