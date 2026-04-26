package com.benesquivelmusic.daw.core.persistence.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Default {@link MissingAssetResolver} implementation that searches sibling
 * directories of the requested asset, plus any caller-supplied extra hints,
 * for a file whose basename matches.
 *
 * <p>Walks each candidate directory up to {@link #MAX_DEPTH} levels deep
 * to keep search cost bounded on large media drives.</p>
 */
final class SmartSiblingResolver implements MissingAssetResolver {

    /** Maximum directory depth searched under each hint. */
    static final int MAX_DEPTH = 3;

    private final List<Path> extraHints = new ArrayList<>();

    SmartSiblingResolver(Iterable<Path> extraHints) {
        if (extraHints != null) {
            for (Path p : extraHints) {
                if (p != null) {
                    this.extraHints.add(p);
                }
            }
        }
    }

    @Override
    public Optional<Path> resolve(String originalPath, List<Path> searchHints) {
        if (originalPath == null || originalPath.isBlank()) {
            return Optional.empty();
        }
        String basename = baseName(originalPath);
        if (basename.isEmpty()) {
            return Optional.empty();
        }
        Set<Path> roots = new LinkedHashSet<>();
        if (searchHints != null) {
            for (Path hint : searchHints) {
                if (hint != null) {
                    roots.add(hint);
                }
            }
        }
        roots.addAll(extraHints);

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
                Optional<Path> match = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName() != null
                                && p.getFileName().toString().equals(basename))
                        .findFirst();
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException ignored) {
                // skip unreadable roots and continue searching
            }
        }
        return Optional.empty();
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
