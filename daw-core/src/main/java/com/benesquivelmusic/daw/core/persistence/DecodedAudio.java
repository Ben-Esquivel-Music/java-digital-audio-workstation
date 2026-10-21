package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audioimport.AdmClipAudio;
import com.benesquivelmusic.daw.sdk.audio.SourceRateMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What decoding one {@link AssetReference} produced, before it is attached
 * to clips (Persistence Integrity book §4.3; decisions D1, D3). Built on a
 * decode thread, read on the owner thread; the arrays are filled before the
 * value is built and never written afterwards. The clip rehydrator and
 * relink attach it the same way: {@link #forClip} picks what one clip gets,
 * and {@link ClipAudio#attachTo} attaches it.
 */
sealed interface DecodedAudio permits DecodedAudio.FileAudio, DecodedAudio.TakeAudio, DecodedAudio.AdmAudio {

    /** The " (split)" suffix {@code AudioClip.splitAt} appends to the second half's name. */
    String SPLIT_SUFFIX = " (split)";

    /**
     * Returns the audio a clip named {@code clipName} gets from this source:
     * all of it for a file or a take; for an ADM file, the bed or object the
     * name selects ({@link AdmAudio#audioFor}), or empty when it selects
     * none. Every clip of one source (of one bed or object) gets the same
     * array instance.
     *
     * @param clipName the clip's current name
     * @return the clip's audio, or empty
     */
    Optional<ClipAudio> forClip(String clipName);

    /**
     * Returns how many seconds of audio the source holds: all of it for a
     * file or a take; for an ADM file, its longest bed or object (they all
     * span the file).
     *
     * @return the seconds held
     */
    double seconds();

    /**
     * Whether clips named {@code clipNames} — every clip that holds one
     * source — are the clips of an ADM BWF import (decision D30): with every
     * trailing {@value #SPLIT_SUFFIX} removed, at least two distinct names
     * have the form the import gives a bed or object clip
     * ({@link AdmClipAudio#hasImportNameForm}). Clips with other names do
     * not disqualify the source: a DAWproject Exchange import keeps the clip
     * names its file gives, which may have been changed outside this
     * application. One distinct name is a plain file: a user may import a
     * plain {@code Object 1.wav}. The one rule the clip rehydrator and relink
     * apply before they give such clips a file without ADM metadata, which
     * would play the same channels on every clip.
     *
     * @param clipNames the current names of the clips that hold the source
     * @return {@code true} if the clips are an ADM import's
     */
    static boolean isAdmImport(Collection<String> clipNames) {
        Objects.requireNonNull(clipNames, "clipNames must not be null");
        Set<String> distinct = new HashSet<>();
        for (String name : clipNames) {
            String selector = stripSplitSuffixes(name);
            if (AdmClipAudio.hasImportNameForm(selector)) {
                distinct.add(selector);
            }
        }
        return distinct.size() >= 2;
    }

    /**
     * A single file, decoded and converted to the project rate — what its
     * import held in memory. No {@link SourceRateMetadata} goes with it.
     *
     * @param audio      the audio at {@code sampleRate}
     * @param sampleRate the project rate, in Hz
     */
    record FileAudio(float[][] audio, int sampleRate) implements DecodedAudio {
        public FileAudio {
            Objects.requireNonNull(audio, "audio must not be null");
        }

        @Override
        public Optional<ClipAudio> forClip(String clipName) {
            return Optional.of(new ClipAudio(audio, sampleRate, null));
        }

        @Override
        public double seconds() {
            return secondsOf(audio, sampleRate);
        }

        /**
         * Returns how many channels the file has.
         *
         * @return the channel count
         */
        int channels() {
            return audio.length;
        }

        /**
         * The detail of the row an ADM import's file without ADM metadata
         * raises (decisions D30, D43): every clip would play the same
         * channels of it — as many as its track renders — instead of its own
         * bed or object.
         *
         * @return the detail
         */
        String noAdmMetadataDetail() {
            return "the file has no ADM metadata; each clip would play the same channels instead of its own"
                    + " bed or object";
        }
    }

    /**
     * A recorded take read at its native capture rate, with the
     * {@link SourceRateMetadata} the capture path attaches (rate and channels
     * from the segment header, frames from the read).
     *
     * @param audio    the audio at {@code metadata.nativeRateHz()}
     * @param metadata the take's native-rate metadata
     */
    record TakeAudio(float[][] audio, SourceRateMetadata metadata) implements DecodedAudio {
        public TakeAudio {
            Objects.requireNonNull(audio, "audio must not be null");
            Objects.requireNonNull(metadata, "metadata must not be null");
        }

        @Override
        public Optional<ClipAudio> forClip(String clipName) {
            return Optional.of(new ClipAudio(audio, metadata.nativeRateHz(), metadata));
        }

        @Override
        public double seconds() {
            return secondsOf(audio, metadata.nativeRateHz());
        }
    }

    /**
     * An ADM BWF file: the audio of every clip its import creates, at the
     * project rate, under the import's clip names.
     *
     * @param clips      the bed and object clip audio
     * @param sampleRate the project rate, in Hz
     */
    record AdmAudio(List<AdmClipAudio> clips, int sampleRate) implements DecodedAudio {
        public AdmAudio {
            clips = List.copyOf(Objects.requireNonNull(clips, "clips must not be null"));
        }

        /**
         * Returns the audio of the bed or object a clip named
         * {@code clipName} plays: the name, with every trailing
         * {@value DecodedAudio#SPLIT_SUFFIX} removed, must equal an import
         * clip name exactly. Empty when it does not — nothing is guessed.
         *
         * @param clipName the clip's current name
         * @return the selected audio, or empty
         */
        Optional<float[][]> audioFor(String clipName) {
            String selector = stripSplitSuffixes(clipName);
            for (AdmClipAudio clip : clips) {
                if (clip.clipName().equals(selector)) {
                    return Optional.of(clip.audio());
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<ClipAudio> forClip(String clipName) {
            return audioFor(clipName).map(audio -> new ClipAudio(audio, sampleRate, null));
        }

        @Override
        public double seconds() {
            double longest = 0.0;
            for (AdmClipAudio clip : clips) {
                longest = Math.max(longest, secondsOf(clip.audio(), sampleRate));
            }
            return longest;
        }

        /**
         * Returns the import's clip names, bed first, joined for a row's
         * detail: {@code "Bed (Stereo), Object 1, Object 2"}.
         *
         * @return the names a clip may carry to select a bed or object
         */
        String importNames() {
            List<String> names = new ArrayList<>(clips.size());
            for (AdmClipAudio clip : clips) {
                names.add(clip.clipName());
            }
            return String.join(", ", names);
        }

        /**
         * The detail of the row for the clips of this file whose names select
         * no bed or object (decisions D26, D43), naming what the file has:
         * {@code "the clip name selects no bed or object of the file (it
         * has: …)"} when every holder is such a clip, and when only some are,
         * how many in the same one clause — {@code "2 of them select no bed
         * or object of the file (it has: …)"}.
         *
         * @param unknown how many holders select nothing; at least 1
         * @param holders how many clips hold the file
         * @return the detail
         */
        String unknownNamesDetail(int unknown, int holders) {
            String subject = unknown >= holders ? "the clip name selects"
                    : unknown + " of them " + (unknown == 1 ? "selects" : "select");
            return subject + " no bed or object of the file (it has: " + importNames() + ")";
        }
    }

    /**
     * The audio one clip gets from a decoded source, with the native-rate
     * metadata that goes with it — {@code null} for a file, whose audio is
     * at the project rate (D1).
     */
    final class ClipAudio {

        private final float[][] audio;
        private final int sampleRate;
        private final SourceRateMetadata metadata;

        private ClipAudio(float[][] audio, int sampleRate, SourceRateMetadata metadata) {
            this.audio = audio;
            this.sampleRate = sampleRate;
            this.metadata = metadata;
        }

        /**
         * Attaches this audio to {@code clip}, replacing what it holds:
         * the metadata first, then the array — the volatile array write
         * publishes both to the audio thread, which reads the array first
         * (D1). Owner thread.
         *
         * @param clip the clip
         */
        void attachTo(AudioClip clip) {
            clip.setSourceRateMetadata(metadata);
            clip.setAudioData(audio);
        }

        /**
         * Returns how many seconds of audio the clip gets: frames over the
         * rate they are at (the project rate for a file, the header rate for
         * a take).
         *
         * @return the seconds held
         */
        double seconds() {
            return secondsOf(audio, sampleRate);
        }
    }

    /** Frames over the rate they are at; 0 for no channels. */
    private static double secondsOf(float[][] audio, int sampleRate) {
        return audio.length == 0 ? 0.0 : (double) audio[0].length / sampleRate;
    }

    /** Removes every trailing {@value #SPLIT_SUFFIX} from {@code name}. */
    static String stripSplitSuffixes(String name) {
        String stripped = Objects.requireNonNull(name, "name must not be null");
        while (stripped.endsWith(SPLIT_SUFFIX)) {
            stripped = stripped.substring(0, stripped.length() - SPLIT_SUFFIX.length());
        }
        return stripped;
    }
}
