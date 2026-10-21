package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The one length check a reloaded or relinked source allows (decisions D6,
 * D24): nothing persisted records a clip's channel count or frame count, so
 * the only thing that can be judged is whether the source holds as much
 * audio as the clip spans. The clip rehydrator raises a
 * {@link MissingAssetReason#CHANGED_ON_DISK} row ("Shorter than the clip")
 * from it, and relink asks the user to confirm a located file that fails it.
 *
 * <p>A clip spans {@code (sourceOffsetBeats + durationBeats) × 60 / bpm}
 * seconds at the transport's tempo ({@link #playbackTempo}, decision D37):
 * the render pipeline plays every audio clip at that tempo — the tempo at
 * beat 0, whatever the tempo map holds later — so that is how much of its
 * source a clip really plays. A renderer that follows the tempo map must
 * replace this rule with one beat-to-source-frame function that it and this
 * check both call. A source is short only when it holds more than
 * {@link #TOLERANCE_SECONDS} less than that; a longer source is normal.</p>
 *
 * <p>What the check cannot tell is why a source is short. A clip's length
 * in beats is fixed at the tempo it was imported or recorded at, and nothing
 * persisted records that tempo: slowing the whole song after the import
 * makes the clip span more seconds than its file holds, exactly as a file
 * that was replaced by a shorter one does. So the check raises a row in both
 * cases, and the row claims only that the source is shorter than the clip —
 * never that the file changed.</p>
 */
final class SourceShortfall {

    /** A source this much shorter than a clip uses is short (D6): 10 ms. */
    static final double TOLERANCE_SECONDS = 0.010;

    /** The seconds pattern every message uses (two decimals, D24), as MessageFormat's {@code number,0.00}. */
    private static final String SECONDS_PATTERN = "0.00";

    private SourceShortfall() {
    }

    /**
     * Returns the tempo a clip is judged at (decision D37): the transport's
     * tempo, {@link com.benesquivelmusic.daw.core.transport.Transport#getTempo()},
     * which the render pipeline plays every audio clip at. Owner thread: it
     * reads the live tempo map.
     *
     * @param project the project
     * @return the tempo, in BPM
     */
    static double playbackTempo(DawProject project) {
        return project.getTransport().getTempo();
    }

    /**
     * Returns how many seconds of source audio {@code clip} uses at
     * {@code bpm}. Owner thread: it reads the live clip.
     *
     * @param clip the clip
     * @param bpm  the tempo to judge at ({@link #playbackTempo})
     * @return the seconds the clip uses
     */
    static double usedSeconds(AudioClip clip, double bpm) {
        return (clip.getSourceOffsetBeats() + clip.getDurationBeats()) * 60.0 / bpm;
    }

    /**
     * Returns whether a source holding {@code heldSeconds} is shorter than
     * the {@code usedSeconds} a clip uses, beyond the tolerance.
     *
     * @param heldSeconds the seconds of audio the source holds
     * @param usedSeconds the seconds the clip uses
     * @return {@code true} if the source is short
     */
    static boolean isShort(double heldSeconds, double usedSeconds) {
        return heldSeconds < usedSeconds - TOLERANCE_SECONDS;
    }

    /**
     * The detail of a {@link MissingAssetReason#CHANGED_ON_DISK} row
     * (decisions D24, D26): {@code "holds 4.00 s; the clip spans 4.80 s at
     * the current tempo; loaded as it is"}, starting with how many of the
     * row's clips run past the source's end when that is not all of them —
     * {@code "4 of them run past its end; "}.
     *
     * @param shortClips   how many holders run past the end; at least 1
     * @param holders      how many clips hold the source
     * @param heldSeconds  the seconds the source holds
     * @param spanSeconds  the longest span among the clips that run past it ({@link #usedSeconds})
     * @return the detail
     */
    static String shorterDetail(int shortClips, int holders, double heldSeconds, double spanSeconds) {
        String subset = shortClips >= holders ? ""
                : shortClips + " of them " + (shortClips == 1 ? "runs" : "run") + " past its end; ";
        return subset + "holds " + formatSeconds(heldSeconds) + " s; the clip spans " + formatSeconds(spanSeconds)
                + " s at the current tempo; loaded as it is";
    }

    /**
     * Formats seconds with two decimals in the default format locale — as
     * the application's {@code {n,number,0.00}} messages do, so a row's
     * detail and a relink confirmation print the same figure.
     *
     * @param seconds the seconds
     * @return the formatted seconds, without the unit
     */
    static String formatSeconds(double seconds) {
        return new DecimalFormat(SECONDS_PATTERN,
                DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT))).format(seconds);
    }
}
