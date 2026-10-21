package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the missing-assets report: an asset (a file, or a recorded take
 * as a whole), why it is listed, and the clips that hold it (Persistence
 * Integrity book §4.3, §6.2). The asset is the unit the user acts on —
 * locate, skip — so it is counted once however many clips hold it; the clips
 * are detail.
 *
 * <p>A row names <em>every</em> clip that holds the asset, because a relink
 * re-points every one of them; which of them are silent is a separate,
 * possibly smaller list. A clip is silent only when it has no audio: a
 * {@link MissingAssetReason#CHANGED_ON_DISK} row silences none of its
 * holders, an {@link MissingAssetReason#ADM_OBJECT_UNKNOWN} row may silence
 * only the clips whose names select nothing, and a row for a file that is
 * not there silences every holder that has no audio.</p>
 *
 * @param reference     the asset, as its clips hold it
 * @param reason        why the row exists
 * @param detail        a human-readable detail in English (the decoder's
 *                      error, the missing segments, the unresolvable reason,
 *                      which of the holders the reason concerns); may be
 *                      empty, never {@code null}
 * @param holders       every clip that holds the asset, in project order;
 *                      copied, not empty, distinct by clip id
 * @param silentHolders the holders that have no audio because of this row;
 *                      copied, distinct by clip id, each one of
 *                      {@code holders}; empty for a
 *                      {@link MissingAssetReason#CHANGED_ON_DISK} row
 */
public record MissingAsset(AssetReference reference, MissingAssetReason reason, String detail,
                           List<Holder> holders, List<Holder> silentHolders) {

    public MissingAsset {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        holders = distinctByClipId(Objects.requireNonNull(holders, "holders must not be null"));
        if (holders.isEmpty()) {
            throw new IllegalArgumentException("holders must not be empty: a row names the clips that hold it");
        }
        silentHolders = distinctByClipId(Objects.requireNonNull(silentHolders, "silentHolders must not be null"));
        Set<String> held = clipIds(holders);
        for (Holder silent : silentHolders) {
            if (!held.contains(silent.clipId())) {
                throw new IllegalArgumentException("a silent clip is one of the row's holders: " + silent);
            }
        }
        if (!reason.silencesClips() && !silentHolders.isEmpty()) {
            throw new IllegalArgumentException("a " + reason + " row silences no clip: " + silentHolders);
        }
    }

    /**
     * Creates a row whose holders are all silent when its reason
     * {@linkplain MissingAssetReason#silencesClips() silences clips}, and
     * none of them otherwise — the row of a file that is not there, or of a
     * file shorter than its clips.
     *
     * @param reference the asset, as its clips hold it
     * @param reason    why the row exists
     * @param detail    the human-readable detail in English; never {@code null}
     * @param holders   every clip that holds the asset; not empty
     */
    public MissingAsset(AssetReference reference, MissingAssetReason reason, String detail, List<Holder> holders) {
        this(reference, reason, detail, holders, everyHolderIfSilencing(reason, holders));
    }

    /**
     * Returns how many clips hold the asset.
     *
     * @return the number of holders
     */
    public int clipCount() {
        return holders.size();
    }

    /**
     * Returns how many of the holders are silent because of this row — have
     * no audio.
     *
     * @return the number of silent holders; 0 for a
     *         {@link MissingAssetReason#CHANGED_ON_DISK} row
     */
    public int silentClipCount() {
        return silentHolders.size();
    }

    /**
     * Returns this row with the holders, and the silent holders, of
     * {@code later} — a row of the same asset and reason — added (a holder
     * already present, by clip id, is not repeated). The detail is this
     * row's.
     */
    MissingAsset mergedWith(MissingAsset later) {
        List<Holder> mergedHolders = new ArrayList<>(holders);
        mergedHolders.addAll(later.holders());
        List<Holder> mergedSilent = new ArrayList<>(silentHolders);
        mergedSilent.addAll(later.silentHolders());
        return new MissingAsset(reference, reason, detail, mergedHolders, mergedSilent);
    }

    private static List<Holder> everyHolderIfSilencing(MissingAssetReason reason, List<Holder> holders) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(holders, "holders must not be null");
        return reason.silencesClips() ? holders : List.of();
    }

    private static List<Holder> distinctByClipId(List<Holder> holders) {
        Set<String> seen = new LinkedHashSet<>();
        List<Holder> distinct = new ArrayList<>(holders.size());
        for (Holder holder : holders) {
            if (seen.add(Objects.requireNonNull(holder, "holder must not be null").clipId())) {
                distinct.add(holder);
            }
        }
        return List.copyOf(distinct);
    }

    private static Set<String> clipIds(List<Holder> holders) {
        Set<String> ids = new HashSet<>();
        for (Holder holder : holders) {
            ids.add(holder.clipId());
        }
        return ids;
    }

    /**
     * A clip that holds the asset, named for the report.
     *
     * @param clipId    the clip's runtime id ({@link AudioClip#getId()}; not persisted)
     * @param trackName the name of the track the clip is on
     * @param clipName  the clip's name
     */
    public record Holder(String clipId, String trackName, String clipName) {

        public Holder {
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(trackName, "trackName must not be null");
            Objects.requireNonNull(clipName, "clipName must not be null");
        }

        /**
         * Returns the holder for {@code clip} on {@code track}.
         *
         * @param track the track the clip is on
         * @param clip  the clip
         * @return the holder
         */
        public static Holder of(Track track, AudioClip clip) {
            return new Holder(clip.getId(), track.getName(), clip.getName());
        }
    }
}
