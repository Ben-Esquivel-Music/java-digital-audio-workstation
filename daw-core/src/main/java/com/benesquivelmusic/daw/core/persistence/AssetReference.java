package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What an {@link AudioClip} reads its audio from, as the clip holds it in
 * memory: one file ({@link SingleFile}) or a recorded take's whole, ordered
 * segment list ({@link Take}) — Persistence Integrity book §4.3.
 *
 * <p>It is both the asset a missing-assets row names and the source key the
 * clip rehydrator decodes once per project open: every clip whose current
 * reference is equal shares one decoded array — split halves, duplicates,
 * glued clips (a glue compares buffers by identity) — and a take is one
 * asset, never a set of independent segments.</p>
 *
 * <p>The strings are the clip's own ({@link AudioClip#getSourceFilePath()},
 * {@link AudioClip#getSourceSegmentPaths()}), compared as strings, so a
 * report row and a relink match exactly the clips that hold that reference
 * — including an {@linkplain ProjectPaths#unresolvable unresolvable} one,
 * which is not an absolute path. Only an {@linkplain #isOpenable() openable}
 * reference — every string an absolute path on this platform
 * ({@link ProjectPaths#isAbsoluteReference}) — names files.</p>
 */
public sealed interface AssetReference permits AssetReference.SingleFile, AssetReference.Take {

    /**
     * Returns the reference {@code clip} currently reads from: its segment
     * list when it has one (the head invariant makes its source file the
     * first segment), otherwise its source file; empty for a clip with
     * neither (an in-memory buffer, a render-in-place result).
     *
     * @param clip the clip
     * @return the clip's current asset reference, or empty
     */
    static Optional<AssetReference> of(AudioClip clip) {
        Objects.requireNonNull(clip, "clip must not be null");
        List<String> segments = clip.getSourceSegmentPaths();
        if (!segments.isEmpty()) {
            return Optional.of(new Take(segments));
        }
        String file = clip.getSourceFilePath();
        if (file == null || file.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SingleFile(file));
    }

    /**
     * The reference strings, in order: the one file, or every segment.
     *
     * @return the strings this reference is made of; never empty
     */
    List<String> references();

    /**
     * Whether {@code clip} currently reads from exactly this reference.
     *
     * @param clip the clip
     * @return {@code true} if {@link #of(AudioClip)} of the clip equals this
     */
    default boolean isHeldBy(AudioClip clip) {
        return of(clip).filter(this::equals).isPresent();
    }

    /**
     * Whether every string of this reference names a file: is an absolute
     * path on this platform ({@link ProjectPaths#isAbsoluteReference}).
     *
     * @return {@code true} if the reference can be opened
     */
    default boolean isOpenable() {
        for (String reference : references()) {
            if (!ProjectPaths.isAbsoluteReference(reference)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The files this reference names, in order.
     *
     * @return the paths
     * @throws IllegalStateException if the reference is not {@linkplain #isOpenable() openable}
     */
    default List<Path> files() {
        if (!isOpenable()) {
            throw new IllegalStateException("not every reference is an absolute path: " + references());
        }
        List<Path> files = new ArrayList<>(references().size());
        for (String reference : references()) {
            files.add(Path.of(reference));
        }
        return List.copyOf(files);
    }

    /**
     * A clip backed by one file — an import, a bounce, a processed file.
     *
     * @param reference the clip's source file reference; not empty
     */
    record SingleFile(String reference) implements AssetReference {
        public SingleFile {
            Objects.requireNonNull(reference, "reference must not be null");
            if (reference.isEmpty()) {
                throw new IllegalArgumentException("reference must not be empty");
            }
        }

        @Override
        public List<String> references() {
            return List.of(reference);
        }
    }

    /**
     * A recorded take: its segment files in playback order, one asset.
     *
     * @param segments the segment references; copied, not empty, no element empty
     */
    record Take(List<String> segments) implements AssetReference {
        public Take {
            segments = List.copyOf(Objects.requireNonNull(segments, "segments must not be null"));
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("segments must not be empty");
            }
            for (String segment : segments) {
                if (segment.isEmpty()) {
                    throw new IllegalArgumentException("segments must not contain an empty reference");
                }
            }
        }

        @Override
        public List<String> references() {
            return segments;
        }
    }
}
