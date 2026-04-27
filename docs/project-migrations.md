# Project File Schema Migrations

The DAW persistence layer (`ProjectSerializer` / `ProjectDeserializer`)
evolves over time as features are added, fields renamed, and layouts
restructured. To keep that evolution maintainable and testable,
migrations are managed through an explicit, ordered registry — much
like Django, Rails, or Flyway migrations — instead of ad-hoc
`if (version < N)` branches scattered through the deserializer.

## Components

All classes live in
`com.benesquivelmusic.daw.core.persistence.migration`:

| Class | Role |
|-------|------|
| `ProjectMigration` (record) | A single migration: `(fromVersion, toVersion, description, apply)` where `apply` is a `UnaryOperator<Document>` that mutates (or replaces) the parsed XML DOM. |
| `MigrationRegistry` | Ordered, validated list of migrations targeting a specific `currentVersion`. Validates contiguity at construction. Drives `migrate(Document)` to walk a file from its on-disk version up to current. |
| `MigrationReport` | Immutable record of what ran during a single load: `fromVersion`, `toVersion`, list of `AppliedMigration`s, timestamp. |
| `MigrationException` | Thrown when no continuous migration chain exists from the file's version to current (e.g., the file is from a future build, or a step is missing). |
| `MigrationSuppression` | Per-project marker (`.migration-report-suppressed`) that records the user's "Don't show again for this project" choice. Suppression is version-scoped — a future schema bump re-surfaces the dialog. |

The dialog itself lives in the app module:

| Class | Role |
|-------|------|
| `com.benesquivelmusic.daw.app.ui.MigrationReportDialog` | JavaFX dialog shown after load when a migration ran. Lists the migrations in plain English and provides the suppression checkbox. |

## Authoring a New Migration

1. **Bump `MigrationRegistry.CURRENT_VERSION`** to the new schema version
   (e.g. `2`).
2. **Register the migration** in
   `MigrationRegistry.DefaultRegistryHolder.INSTANCE`:
   ```java
   MigrationRegistry.builder(CURRENT_VERSION)
       .add(ProjectMigration.step(1, "rename pan-law attribute",
           document -> {
               // mutate document, return it
               return document;
           }))
       .build();
   ```
3. **Add a unit test** in
   `daw-core/src/test/java/.../persistence/migration/`. Each migration
   gets a "golden" XML input and expected XML output so future changes
   cannot regress it.
4. **Update the version emitted by `ProjectSerializer`** — automatic via
   the shared `MigrationRegistry.CURRENT_VERSION` constant.

## Legacy-Batch Consolidation

Once a migration is older than `MigrationRegistry.LEGACY_BATCH_THRESHOLD`
(currently 10) versions back, multiple step migrations may be folded
into a single legacy-batch `ProjectMigration` whose `toVersion` is more
than one greater than its `fromVersion`. Document any such consolidation
in the project change log.

## Runtime Flow

1. `ProjectDeserializer.deserialize(xml)` parses the XML into a DOM
   `Document` and reads the root `version` attribute (defaulting to 1
   when missing).
2. The registered `MigrationRegistry` is consulted: if the file is at
   the current version, no migrations run; otherwise each registered
   migration is applied in order.
3. The migrated DOM is re-stamped with the current schema version and
   parsed into a `DawProject` — the rest of the deserializer always
   sees a current-version document.
4. The deserializer exposes the `MigrationReport` via
   `getLastMigrationReport()`.
5. `ProjectManager.openProject(...)` records the report, then UI code
   may surface `MigrationReportDialog` to the user.
6. **No state change is persisted** unless the user explicitly saves.
   The first save after a migrated load takes a sibling backup
   (`project.daw.v<n>.<timestamp>.bak`) of the original on-disk file
   and only then overwrites it. The backup is taken exactly once per
   load and never overwrites an existing backup.

## Non-Goals

- Forward migrations (downgrading a file to an older schema).
- Migration of embedded audio file formats.
- Undo of an in-place migration — the backup plus the user's "Save vs.
  Discard" choice is the rollback path.
