package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;

import java.util.ArrayList;
import java.util.List;

/**
 * Every value a project writer saves for one audio clip — the attributes
 * and children {@code ProjectSerializer.buildClipElement} writes — and
 * nothing else: not the clip's id, its audio array, its source-rate
 * metadata or its comp-output tag, none of which reach the file.
 *
 * <p>Two clips with equal values save identically, so a list of them
 * compares "would a save write the same thing" regardless of identity
 * ({@link #sameSavedClips}). Keep the components in step with
 * {@code buildClipElement}: a field written there but missing here would
 * let an edit to it go unnoticed by the audition dirty rule
 * ({@link TakeComping#compOutputSavesAsSelection}).</p>
 */
record ClipSaveValue(String name,
                     double startBeat,
                     double durationBeats,
                     double sourceOffsetBeats,
                     double gainDb,
                     boolean reversed,
                     boolean locked,
                     double fadeInBeats,
                     double fadeOutBeats,
                     FadeCurveType fadeInCurve,
                     FadeCurveType fadeOutCurve,
                     double timeStretchRatio,
                     double pitchShiftSemitones,
                     StretchQuality stretchQuality,
                     String sourceFilePath,
                     List<String> sourceSegmentPaths,
                     ClipGainEnvelope gainEnvelope) {

    /**
     * Captures the saved values of {@code clip}.
     *
     * @param clip the clip
     * @return its saved values
     */
    static ClipSaveValue of(AudioClip clip) {
        return new ClipSaveValue(
                clip.getName(),
                clip.getStartBeat(),
                clip.getDurationBeats(),
                clip.getSourceOffsetBeats(),
                clip.getGainDb(),
                clip.isReversed(),
                clip.isLocked(),
                clip.getFadeInBeats(),
                clip.getFadeOutBeats(),
                clip.getFadeInCurveType(),
                clip.getFadeOutCurveType(),
                clip.getTimeStretchRatio(),
                clip.getPitchShiftSemitones(),
                clip.getStretchQuality(),
                clip.getSourceFilePath(),
                List.copyOf(clip.getSourceSegmentPaths()),
                clip.getGainEnvelope());
    }

    /**
     * Returns whether the two clip lists save the same: equal sizes, and
     * every clip of one matched by a distinct clip of the other with equal
     * {@linkplain #of saved values}. Order and identity are ignored.
     *
     * @param first  one list of clips
     * @param second the other list of clips
     * @return {@code true} if a save would write the same clips for both
     */
    static boolean sameSavedClips(List<AudioClip> first, List<AudioClip> second) {
        if (first.size() != second.size()) {
            return false;
        }
        List<ClipSaveValue> unmatched = new ArrayList<>(second.size());
        for (AudioClip clip : second) {
            unmatched.add(of(clip));
        }
        for (AudioClip clip : first) {
            if (!unmatched.remove(of(clip))) {
                return false;
            }
        }
        return true;
    }
}
