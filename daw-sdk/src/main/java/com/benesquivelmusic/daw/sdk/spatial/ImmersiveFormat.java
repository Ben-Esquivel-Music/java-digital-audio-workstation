package com.benesquivelmusic.daw.sdk.spatial;

import java.util.List;
import java.util.Objects;

/**
 * Standard immersive audio bed formats used in Dolby Atmos production.
 *
 * <p>An {@code ImmersiveFormat} pairs a human-readable name (such as
 * {@code "7.1.4"}) with the underlying {@link SpeakerLayout}. The layout
 * determines the channel count and channel order for any
 * {@link com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel speaker label}
 * based bed bus.</p>
 *
 * <p>The four standard bed formats supported by Atmos music workflows are
 * 5.1, 5.1.4, 7.1.4 (the most common bed format) and 9.1.6 (the widest
 * channel-based bed permitted by the Atmos renderer).</p>
 */
public enum ImmersiveFormat {

    /** 5.1 — six-channel surround bed. */
    FORMAT_5_1(SpeakerLayout.LAYOUT_5_1),

    /** 5.1.4 — six-channel surround bed with four height speakers. */
    FORMAT_5_1_4(SpeakerLayout.LAYOUT_5_1_4),

    /** 7.1.4 — eight-channel surround bed with four height speakers (default Atmos bed). */
    FORMAT_7_1_4(SpeakerLayout.LAYOUT_7_1_4),

    /** 9.1.6 — ten-channel surround bed (incl. wides) with six height speakers. */
    FORMAT_9_1_6(SpeakerLayout.LAYOUT_9_1_6);

    private final SpeakerLayout layout;

    ImmersiveFormat(SpeakerLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
    }

    /** Returns the underlying speaker layout. */
    public SpeakerLayout layout() {
        return layout;
    }

    /** Returns the channel count for this format. */
    public int channelCount() {
        return layout.channelCount();
    }

    /** Returns the ordered list of speaker labels for this format. */
    public List<SpeakerLabel> speakers() {
        return layout.speakers();
    }

    /** Returns the display name of this format (e.g. {@code "7.1.4"}). */
    public String displayName() {
        return layout.name();
    }

    /**
     * Returns the {@link ImmersiveFormat} whose layout matches the given name
     * (e.g. {@code "7.1.4"}), or {@link #FORMAT_7_1_4} if no match is found.
     *
     * @param displayName the format display name
     * @return the matching format, or {@link #FORMAT_7_1_4} as a fallback
     */
    public static ImmersiveFormat fromDisplayName(String displayName) {
        for (ImmersiveFormat format : values()) {
            if (format.displayName().equals(displayName)) {
                return format;
            }
        }
        return FORMAT_7_1_4;
    }
}
