package com.benesquivelmusic.daw.core.persistence.migration;

import org.w3c.dom.Document;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A single, atomic migration that transforms a project document from one
 * schema version to the next.
 *
 * <p>Migrations are kept as small, independently testable records so that
 * the project persistence layer can evolve in the same incremental,
 * auditable way that other mature persistence layers (Django, Rails,
 * Flyway) evolve. Each migration owns exactly one version step
 * ({@code fromVersion} → {@code toVersion}, where {@code toVersion ==
 * fromVersion + 1}, except for consolidated "legacy-batch" migrations
 * which may span a wider range — see {@link MigrationRegistry}).</p>
 *
 * <p>The original specification described migrations operating on a
 * {@code JsonNode}; this codebase persists projects as XML, so the
 * functional payload operates on a DOM {@link Document} instead. The
 * migration receives the parsed document, mutates (or replaces) it, and
 * returns the result. Migrations <strong>must not</strong> perform I/O
 * and <strong>must</strong> be deterministic — they are run on every
 * load of a legacy project file.</p>
 *
 * @param fromVersion the source schema version (inclusive); must be &gt;= 1
 * @param toVersion   the target schema version; must be &gt; {@code fromVersion}
 * @param description short, human-readable summary of what this migration
 *                    changes — surfaced in the migration report dialog
 * @param apply       the pure transformation applied to the document
 */
public record ProjectMigration(int fromVersion,
                               int toVersion,
                               String description,
                               UnaryOperator<Document> apply) {

    public ProjectMigration {
        if (fromVersion < 1) {
            throw new IllegalArgumentException(
                    "fromVersion must be >= 1, was " + fromVersion);
        }
        if (toVersion <= fromVersion) {
            throw new IllegalArgumentException(
                    "toVersion (" + toVersion + ") must be > fromVersion (" + fromVersion + ")");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(apply, "apply");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }

    /**
     * Convenience factory for the common case of a single-step migration
     * from version {@code n} to version {@code n + 1}.
     */
    public static ProjectMigration step(int fromVersion,
                                        String description,
                                        UnaryOperator<Document> apply) {
        return new ProjectMigration(fromVersion, fromVersion + 1, description, apply);
    }

    /**
     * Returns {@code true} if this migration covers more than a single
     * version step, indicating that it is a consolidated legacy-batch
     * migration. See {@link MigrationRegistry} for the consolidation
     * policy.
     */
    public boolean isLegacyBatch() {
        return toVersion - fromVersion > 1;
    }
}
