package com.benesquivelmusic.daw.core.persistence.archive;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-missing-asset archive decision.
 *
 * <p>The archive dialog gathers one decision per missing project asset and
 * passes the resulting list to {@link ProjectArchiver}. Decisions are matched
 * by the original path string stored in the project document.</p>
 *
 * @param originalPath the path recorded in the project
 * @param action       how the archive should handle that missing asset
 * @param locatedPath  replacement file for {@link Action#LOCATE}; ignored for
 *                     the other actions
 */
public record ArchiveAssetDecision(
        String originalPath,
        Action action,
        Path locatedPath) {

    /** Available user choices for one missing asset. */
    public enum Action {
        /** Leave the original path in project.daw and omit the asset payload. */
        SKIP,
        /** Use a manually located replacement file and include it in assets/. */
        LOCATE,
        /** Include a deterministic stub payload and rewrite the project to it. */
        USE_STUB
    }

    public ArchiveAssetDecision {
        Objects.requireNonNull(originalPath, "originalPath must not be null");
        if (originalPath.isBlank()) {
            throw new IllegalArgumentException("originalPath must not be blank");
        }
        Objects.requireNonNull(action, "action must not be null");
        if (action == Action.LOCATE && locatedPath == null) {
            throw new IllegalArgumentException("LOCATE decisions require a locatedPath");
        }
    }

    /** Builds a skip decision. */
    public static ArchiveAssetDecision skip(String originalPath) {
        return new ArchiveAssetDecision(originalPath, Action.SKIP, null);
    }

    /** Builds a locate decision. */
    public static ArchiveAssetDecision locate(String originalPath, Path locatedPath) {
        return new ArchiveAssetDecision(originalPath, Action.LOCATE, locatedPath);
    }

    /** Builds a stub/silence decision. */
    public static ArchiveAssetDecision useStub(String originalPath) {
        return new ArchiveAssetDecision(originalPath, Action.USE_STUB, null);
    }

    /** Optional replacement path for locate decisions. */
    public Optional<Path> locatedPathOptional() {
        return Optional.ofNullable(locatedPath);
    }
}
