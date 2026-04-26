package com.benesquivelmusic.daw.core.persistence.archive;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Strategy invoked when {@link ProjectArchiver#openArchive} or
 * {@link ProjectArchiver#consolidateInPlace} encounters an asset path that
 * cannot be resolved on disk.
 *
 * <p>Implementations typically present a "Locate…" dialog to the user. A
 * default smart-search implementation is provided by
 * {@link #smartSiblingSearch(Iterable)}.</p>
 */
@FunctionalInterface
public interface MissingAssetResolver {

    /**
     * Asks the resolver to locate the asset that was originally referenced
     * by {@code originalPath}.
     *
     * @param originalPath the path recorded in the project document
     * @param searchHints  candidate directories that the caller has already
     *                     identified as worth searching (sibling folders,
     *                     project root, original archive root, …)
     * @return the resolved absolute path, or {@link Optional#empty()} if the
     *         asset is permanently lost
     */
    Optional<Path> resolve(String originalPath, List<Path> searchHints);

    /**
     * A resolver that always returns {@link Optional#empty()} — useful when
     * callers want to detect missing assets without triggering UI.
     */
    static MissingAssetResolver none() {
        return (path, hints) -> Optional.empty();
    }

    /**
     * A resolver that walks each search hint (and its subdirectories, up to
     * a small depth) looking for a file whose name matches the basename of
     * the requested asset. The first match wins.
     *
     * @param extraHints additional roots to search beyond what callers pass
     *                   in (e.g. user-configured "media drives")
     */
    static MissingAssetResolver smartSiblingSearch(Iterable<Path> extraHints) {
        return new SmartSiblingResolver(extraHints);
    }
}
