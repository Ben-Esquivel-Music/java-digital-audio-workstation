---
title: "Project Version Migration Registry for Legacy Schemas"
labels: ["enhancement", "persistence", "schema", "reliability"]
---

# Project Version Migration Registry for Legacy Schemas

## Motivation

`ProjectSerializer` / `ProjectDeserializer` evolve every release — fields are added, renamed, and restructured. Today these migrations are ad-hoc `if (version < X) { … }` branches scattered through the deserializer. As the number of versions grows this pattern becomes unmaintainable and untestable. Every mature persistence layer has a migration registry: Django migrations, Rails migrations, Flyway. The pattern — one migration per version step, tested independently — is well-understood.

## Goals

- Add `ProjectMigration` sealed interface in `com.benesquivelmusic.daw.core.persistence.migration`: `record ProjectMigration(int fromVersion, int toVersion, String description, UnaryOperator<JsonNode> apply)`.
- Add `MigrationRegistry` that maintains an ordered list of `ProjectMigration`s covering every step from version 1 to the current schema version.
- `ProjectDeserializer` on load: read the `version` field; if it matches current, deserialize; otherwise apply each migration in order from the file's version up to current, then deserialize.
- Extract every existing ad-hoc migration branch into a discrete `ProjectMigration` record.
- Per-migration unit tests: each migration gets a "golden" JSON input and expected JSON output so future changes cannot regress it.
- Deprecation strategy: migrations older than 10 versions can be consolidated into a "legacy-batch" migration; the change log records the consolidation.
- `MigrationReportDialog` shown after load when migrations ran, listing what was migrated with "Don't show again for this project" checkbox.
- Persist no state change if the user chose to open the file without saving (migrations are in-memory; save triggers a backup of the original file first).
- Tests: a version-1 file migrates through all intermediate versions and produces the same final state as a natively current-version file.

## Non-Goals

- Forward migrations (downgrading to an older schema).
- Migration of embedded audio file formats.
- Undo of an in-place migration (the backup + user's choice to save is the rollback path).
