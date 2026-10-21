package com.benesquivelmusic.daw.core.persistence;

import java.util.Objects;

/**
 * The one outcome a {@link RehydrationSession} reports for each source it
 * decodes (Persistence Integrity book §4.3): the source {@link Decoded}, or
 * it {@link Failed} for a reason. Produced on a decode thread, handed to the
 * {@link RehydrationListener}, and applied on the owner thread with
 * {@link RehydrationSession#apply(RehydrationOutcome)} — which is the only
 * thing that touches the project. Only a session builds outcomes, and a
 * session applies only its own.
 */
public sealed interface RehydrationOutcome permits RehydrationOutcome.Decoded, RehydrationOutcome.Failed {

    /**
     * Returns the source this outcome is for.
     *
     * @return the decoded or failed asset reference
     */
    AssetReference source();

    /**
     * The source decoded; applying the outcome attaches its audio to every
     * clip that still reads from the source and has no audio.
     */
    final class Decoded implements RehydrationOutcome {

        private final RehydrationSession origin;
        private final AssetReference source;
        private final DecodedAudio audio;

        Decoded(RehydrationSession origin, AssetReference source, DecodedAudio audio) {
            this.origin = origin;
            this.source = Objects.requireNonNull(source, "source must not be null");
            this.audio = Objects.requireNonNull(audio, "audio must not be null");
        }

        @Override
        public AssetReference source() {
            return source;
        }

        RehydrationSession origin() {
            return origin;
        }

        DecodedAudio audio() {
            return audio;
        }

        @Override
        public String toString() {
            return "Decoded[" + source + "]";
        }
    }

    /**
     * The source could not be loaded; applying the outcome names the clips
     * that still read from it in a report row.
     */
    final class Failed implements RehydrationOutcome {

        private final RehydrationSession origin;
        private final AssetReference source;
        private final MissingAssetReason reason;
        private final String detail;

        Failed(RehydrationSession origin, AssetReference source, MissingAssetReason reason, String detail) {
            this.origin = origin;
            this.source = Objects.requireNonNull(source, "source must not be null");
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
            this.detail = Objects.requireNonNull(detail, "detail must not be null");
        }

        @Override
        public AssetReference source() {
            return source;
        }

        /**
         * Returns why the source could not be loaded.
         *
         * @return {@link MissingAssetReason#NOT_FOUND}, {@link MissingAssetReason#UNREADABLE}
         *         or {@link MissingAssetReason#TOO_LARGE}
         */
        public MissingAssetReason reason() {
            return reason;
        }

        /**
         * Returns the human-readable detail (in English).
         *
         * @return the detail; never {@code null}
         */
        public String detail() {
            return detail;
        }

        RehydrationSession origin() {
            return origin;
        }

        @Override
        public String toString() {
            return "Failed[" + source + ", " + reason + ": " + detail + "]";
        }
    }
}
