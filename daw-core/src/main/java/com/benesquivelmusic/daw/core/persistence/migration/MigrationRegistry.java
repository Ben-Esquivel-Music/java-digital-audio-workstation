package com.benesquivelmusic.daw.core.persistence.migration;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered registry of {@link ProjectMigration}s that knows how to bring
 * any historical project document up to the current schema version.
 *
 * <p>Design goals:</p>
 * <ul>
 *   <li><strong>One migration per version step.</strong> Most entries
 *       cover a single {@code n → n+1} hop and can be unit-tested in
 *       isolation with a golden input/output pair.</li>
 *   <li><strong>Strict, validated ordering.</strong> When a registry is
 *       built it verifies that the registered migrations form a
 *       contiguous chain ending at the current version — a missing
 *       step or a duplicate is a configuration bug and fails fast.</li>
 *   <li><strong>Legacy-batch consolidation.</strong> Migrations older
 *       than {@value #LEGACY_BATCH_THRESHOLD} versions can be folded
 *       into a single "legacy-batch" {@link ProjectMigration} that
 *       spans a range — see the project change log when consolidating.</li>
 *   <li><strong>No I/O.</strong> The registry only mutates the in-memory
 *       DOM; persistence (backups, save) is the caller's responsibility.</li>
 * </ul>
 *
 * <p>The registry is immutable after construction. Use {@link #builder(int)}
 * to assemble one and {@link #defaultRegistry()} to obtain the
 * production registry baked into this build.</p>
 */
public final class MigrationRegistry {

    /** Schema version emitted by the current {@code ProjectSerializer}. */
    public static final int CURRENT_VERSION = 1;

    /**
     * The number of versions before "now" beyond which migrations can be
     * legitimately consolidated into a single legacy-batch entry.
     */
    public static final int LEGACY_BATCH_THRESHOLD = 10;

    /** Name of the {@code version} attribute on the root project element. */
    public static final String VERSION_ATTRIBUTE = "version";

    private final int currentVersion;
    private final List<ProjectMigration> migrations;

    private MigrationRegistry(int currentVersion, List<ProjectMigration> migrations) {
        this.currentVersion = currentVersion;
        this.migrations = List.copyOf(migrations);
        validateChain();
    }

    /** The schema version this registry migrates <em>up to</em>. */
    public int currentVersion() {
        return currentVersion;
    }

    /** Unmodifiable view of the registered migrations, in execution order. */
    public List<ProjectMigration> migrations() {
        return migrations;
    }

    /**
     * Reads the {@code version} attribute from the root element of the
     * given project document, defaulting to {@code 1} when it is absent
     * (the first persisted schema did not record a version explicitly
     * on every legacy artefact). Returns {@code 1} for documents whose
     * attribute is missing or unparseable.
     */
    public static int readVersion(Document document) {
        Objects.requireNonNull(document, "document");
        Element root = document.getDocumentElement();
        if (root == null) {
            return 1;
        }
        String raw = root.getAttribute(VERSION_ATTRIBUTE);
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 1 ? 1 : parsed;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Migrates the given document up to {@link #currentVersion()},
     * applying each registered migration in order.
     *
     * <p>The document is mutated in place; the returned {@link Document}
     * is the same instance unless a migration chose to replace it via
     * its {@code apply} function. The returned {@link MigrationReport}
     * lists the migrations that ran (possibly none).</p>
     *
     * @throws MigrationException if no continuous chain of migrations
     *                            exists from the file's version up to
     *                            {@code currentVersion}
     */
    public MigrationResult migrate(Document document) {
        Objects.requireNonNull(document, "document");
        int fileVersion = readVersion(document);

        if (fileVersion == currentVersion) {
            return new MigrationResult(document, MigrationReport.noOp(currentVersion));
        }
        if (fileVersion > currentVersion) {
            throw new MigrationException(
                    "Project schema version " + fileVersion
                            + " is newer than this build's version " + currentVersion
                            + " — forward migration is not supported",
                    fileVersion, currentVersion, reachableVersions());
        }

        List<MigrationReport.AppliedMigration> applied = new ArrayList<>();
        Document current = document;
        int version = fileVersion;

        for (ProjectMigration step : migrations) {
            if (step.toVersion() <= version) {
                continue; // already past this step (consolidated batches)
            }
            if (step.fromVersion() > version) {
                throw new MigrationException(
                        "No migration registered from schema version " + version
                                + " — registry jumps to " + step.fromVersion(),
                        fileVersion, currentVersion, reachableVersions());
            }
            if (step.fromVersion() < version) {
                // We've already migrated past where this step starts; legal
                // for legacy batches whose toVersion lands beyond `version`.
                if (step.toVersion() <= version) {
                    continue;
                }
            }
            current = step.apply().apply(current);
            if (current == null) {
                throw new MigrationException(
                        "Migration " + step.fromVersion() + "→" + step.toVersion()
                                + " (" + step.description() + ") returned null",
                        fileVersion, currentVersion, reachableVersions());
            }
            applied.add(MigrationReport.AppliedMigration.of(step));
            version = step.toVersion();
            if (version >= currentVersion) {
                break;
            }
        }

        if (version != currentVersion) {
            throw new MigrationException(
                    "Migration chain ended at version " + version
                            + " but current version is " + currentVersion,
                    fileVersion, currentVersion, reachableVersions());
        }

        // Stamp the migrated document with the new version so subsequent
        // re-loads (e.g. autosave round-trips) are no-ops.
        Element root = current.getDocumentElement();
        if (root != null) {
            root.setAttribute(VERSION_ATTRIBUTE, Integer.toString(currentVersion));
        }

        return new MigrationResult(current,
                new MigrationReport(fileVersion, currentVersion, applied, Instant.now()));
    }

    private List<Integer> reachableVersions() {
        List<Integer> versions = new ArrayList<>();
        for (ProjectMigration m : migrations) {
            versions.add(m.fromVersion());
        }
        Collections.sort(versions);
        return versions;
    }

    private void validateChain() {
        int expected = -1;
        for (ProjectMigration step : migrations) {
            if (step.toVersion() > currentVersion) {
                throw new IllegalStateException(
                        "Migration " + step.fromVersion() + "→" + step.toVersion()
                                + " overshoots current version " + currentVersion);
            }
            if (expected != -1 && step.fromVersion() != expected) {
                throw new IllegalStateException(
                        "Migration chain is not contiguous: expected fromVersion="
                                + expected + " but got " + step.fromVersion()
                                + " (" + step.description() + ")");
            }
            expected = step.toVersion();
        }
        if (expected != -1 && expected != currentVersion) {
            throw new IllegalStateException(
                    "Migration chain ends at version " + expected
                            + " but current version is " + currentVersion);
        }
    }

    /**
     * Result of {@link #migrate(Document)} — a tuple of the (possibly
     * mutated) document and the descriptive report.
     */
    public record MigrationResult(Document document, MigrationReport report) {
        public MigrationResult {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(report, "report");
        }
    }

    // ----- builder ------------------------------------------------------

    /** Starts a builder targeting the given current schema version. */
    public static Builder builder(int currentVersion) {
        return new Builder(currentVersion);
    }

    /**
     * Returns the registry baked into this build. The current schema
     * version is {@value #CURRENT_VERSION} and the registry contains
     * no migrations yet (the persistence format has not yet evolved
     * past version 1). Future schema bumps must register exactly one
     * migration per step here.
     */
    public static MigrationRegistry defaultRegistry() {
        return DefaultRegistryHolder.INSTANCE;
    }

    /**
     * Mutable builder for {@link MigrationRegistry}. Migrations may be
     * added in any order; the builder sorts and validates the chain on
     * {@link #build()}.
     */
    public static final class Builder {
        private final int currentVersion;
        private final List<ProjectMigration> migrations = new ArrayList<>();

        private Builder(int currentVersion) {
            if (currentVersion < 1) {
                throw new IllegalArgumentException(
                        "currentVersion must be >= 1, was " + currentVersion);
            }
            this.currentVersion = currentVersion;
        }

        public Builder add(ProjectMigration migration) {
            migrations.add(Objects.requireNonNull(migration, "migration"));
            return this;
        }

        public MigrationRegistry build() {
            List<ProjectMigration> sorted = new ArrayList<>(migrations);
            sorted.sort((a, b) -> Integer.compare(a.fromVersion(), b.fromVersion()));
            return new MigrationRegistry(currentVersion, sorted);
        }
    }

    private static final class DefaultRegistryHolder {
        // Keep this list in strict ascending order. When you bump
        // CURRENT_VERSION, register exactly one ProjectMigration here for
        // the new step, add a unit test with golden XML in/out, and
        // record the change in the project change log. After ten or more
        // intermediate versions you may consolidate the oldest hops into
        // a legacy-batch ProjectMigration that spans a range.
        private static final MigrationRegistry INSTANCE =
                MigrationRegistry.builder(CURRENT_VERSION).build();
    }
}
