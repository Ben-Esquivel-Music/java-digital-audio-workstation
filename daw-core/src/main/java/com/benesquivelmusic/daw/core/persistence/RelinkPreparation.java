package com.benesquivelmusic.daw.core.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * What preparing one relink found ({@link AssetRelinker#prepare}): a
 * {@link Candidate} ready to apply, or a {@link Refusal} saying why the
 * located file cannot be used (Persistence Integrity book §4.3; decision
 * D17). Built on a worker thread, read on the owner thread; immutable.
 */
public sealed interface RelinkPreparation permits RelinkPreparation.Candidate, RelinkPreparation.Refusal {

    /**
     * Returns the asset being relinked — the reference its clips hold, as
     * the report row names it.
     *
     * @return the asset
     */
    AssetReference asset();

    /**
     * The located file decoded and passed the checks; applying it
     * ({@link AssetRelinker#apply}) relinks every clip that holds the asset.
     * The decoded audio travels with it, so nothing is decoded twice.
     *
     * <p>It also carries what the one relink confirmation needs (decisions
     * D25, D27, D30): whether the user must agree before it is applied
     * ({@link #needsConfirmation()}), and for each reason the facts the
     * question names — a take found outside its own take folder, an ADM
     * import's file without ADM metadata, a file shorter than its clips.</p>
     */
    final class Candidate implements RelinkPreparation {

        private final AssetRelinker.Request request;
        private final AssetReference replacement;
        private final DecodedAudio audio;
        private final String locatedName;
        private final double heldSeconds;
        private final double usedSeconds;
        private final boolean shorterThanUsed;
        private final TakeFolderMismatch takeFolderMismatch;
        private final MissingAdmMetadata missingAdmMetadata;

        /** {@code takeFolderMismatch} and {@code missingAdmMetadata} are {@code null} when they do not apply. */
        Candidate(AssetRelinker.Request request, AssetReference replacement, DecodedAudio audio,
                  String locatedName, double heldSeconds, double usedSeconds, boolean shorterThanUsed,
                  TakeFolderMismatch takeFolderMismatch, MissingAdmMetadata missingAdmMetadata) {
            this.request = Objects.requireNonNull(request, "request must not be null");
            this.replacement = Objects.requireNonNull(replacement, "replacement must not be null");
            this.audio = Objects.requireNonNull(audio, "audio must not be null");
            this.locatedName = Objects.requireNonNull(locatedName, "locatedName must not be null");
            this.heldSeconds = heldSeconds;
            this.usedSeconds = usedSeconds;
            this.shorterThanUsed = shorterThanUsed;
            this.takeFolderMismatch = takeFolderMismatch;
            this.missingAdmMetadata = missingAdmMetadata;
        }

        @Override
        public AssetReference asset() {
            return request.asset();
        }

        /**
         * Returns the reference the clips will hold: the located file, or
         * for a take its segments in order; every reference absolute.
         *
         * @return the replacement reference
         */
        public AssetReference replacement() {
            return replacement;
        }

        /**
         * Returns the name a confirmation calls the located audio by: the
         * located file's name, or for a take the folder its segments were
         * found in, named by its trailing path elements — as many as the
         * take's own folder reference has, normally two,
         * {@code <stamp>_take-NNNN/<trackId>} — joined with {@code '/'}
         * (decision D38): every take of a track has a folder named by the
         * track's id, so its last element alone would not tell them apart.
         *
         * @return the file name, or the take and track folder names
         */
        public String locatedName() {
            return locatedName;
        }

        /**
         * Returns how many seconds of audio the located file holds — for a
         * take, all its segments at their native rate; for an ADM file, its
         * beds and objects.
         *
         * @return the seconds held
         */
        public double heldSeconds() {
            return heldSeconds;
        }

        /**
         * Returns the longest span among the asset's clips, in seconds, as
         * the length check judges it (decision D37: {@code (sourceOffsetBeats
         * + durationBeats) × 60 / bpm} at the transport's tempo, which the
         * render pipeline plays every audio clip at) — the most audio its
         * clips use. A renderer that follows the tempo map must replace this
         * with one beat-to-source-frame function that it and the check both
         * call.
         *
         * @return the seconds the clips use at most; 0 when no clip held the asset
         */
        public double usedSeconds() {
            return usedSeconds;
        }

        /**
         * Returns whether the located audio is shorter than a clip of the
         * asset spans (the rule of the {@code CHANGED_ON_DISK} row): the user
         * is asked to confirm before it is applied.
         *
         * @return {@code true} if the file is shorter than its clips
         */
        public boolean shorterThanUsed() {
            return shorterThanUsed;
        }

        /**
         * Returns, for a take found by a Locate file in a folder that is not
         * the take's own (decision D25), the picked folder and the take's
         * folder; empty when the take was found in its own folder, when its
         * references name no take folder, and for a file.
         *
         * @return the mismatch, or empty
         */
        public Optional<TakeFolderMismatch> takeFolderMismatch() {
            return Optional.ofNullable(takeFolderMismatch);
        }

        /**
         * Returns, when the asset's clips are an ADM import's
         * ({@link DecodedAudio#isAdmImport}) but the located file has no ADM
         * metadata (decision D30), how many channels the file has — applied,
         * every clip plays the same channels of it instead of its own bed or
         * object; empty otherwise.
         *
         * @return the missing ADM metadata, or empty
         */
        public Optional<MissingAdmMetadata> missingAdmMetadata() {
            return Optional.ofNullable(missingAdmMetadata);
        }

        /**
         * Returns whether the user must agree before this candidate is
         * applied: the take was found outside its own folder, the file lacks
         * the ADM metadata its clips need, or it is shorter than its clips.
         *
         * @return {@code true} if a confirmation is needed
         */
        public boolean needsConfirmation() {
            return takeFolderMismatch != null || missingAdmMetadata != null || shorterThanUsed;
        }

        AssetRelinker.Request request() {
            return request;
        }

        DecodedAudio audio() {
            return audio;
        }

        @Override
        public String toString() {
            return "Candidate[" + request.asset() + " -> " + replacement
                    + (takeFolderMismatch != null ? ", " + takeFolderMismatch : "")
                    + (missingAdmMetadata != null ? ", " + missingAdmMetadata : "")
                    + (shorterThanUsed ? ", shorter than used" : "") + "]";
        }
    }

    /**
     * A take's segments were found in a folder that does not carry the
     * take's own trailing {@code <stamp>_take-NNNN/<trackId>} (decision D25):
     * segment names repeat in every track folder of every take, so the
     * folder may hold another track's or another take's audio.
     *
     * @param pickedFolder the folder the segments were found in, named by its
     *                     trailing path elements — as many as
     *                     {@code takeFolder} has — joined with {@code '/'}
     *                     (decision D38), so another take of the same track
     *                     reads {@code "<stamp>_take-0003/<trackId>"}, not
     *                     the track id the two folders share
     * @param takeFolder   the take's own folder as its references name it,
     *                     {@code "<stamp>_take-NNNN/<trackId>"}
     */
    record TakeFolderMismatch(String pickedFolder, String takeFolder) {
        public TakeFolderMismatch {
            Objects.requireNonNull(pickedFolder, "pickedFolder must not be null");
            Objects.requireNonNull(takeFolder, "takeFolder must not be null");
        }
    }

    /**
     * The asset's clips are an ADM import's, but the located file carries
     * no ADM metadata (decision D30): applied, every clip plays the same
     * channels of the file — as many as its track renders — instead of its
     * own bed or object.
     *
     * @param channels how many channels the file has; at least 1
     */
    record MissingAdmMetadata(int channels) {
        public MissingAdmMetadata {
            if (channels < 1) {
                throw new IllegalArgumentException("channels must be at least 1: " + channels);
            }
        }
    }

    /**
     * The located file cannot be used; nothing was changed.
     *
     * @param asset  the asset being relinked
     * @param reason why, as a report row would say it:
     *               {@link MissingAssetReason#NOT_FOUND} (the file, or a
     *               segment of the take, is not there),
     *               {@link MissingAssetReason#UNREADABLE} (it does not
     *               decode, or the segments disagree on channels or rate) or
     *               {@link MissingAssetReason#TOO_LARGE}
     * @param detail the human-readable detail in English, naming the located
     *               file or folder
     */
    record Refusal(AssetReference asset, MissingAssetReason reason, String detail) implements RelinkPreparation {
        public Refusal {
            Objects.requireNonNull(asset, "asset must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }
}
