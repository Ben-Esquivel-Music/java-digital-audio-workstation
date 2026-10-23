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
| `ProjectMigration` (record) | A single migration: `(fromVersion, toVersion, description, apply, changesAuthoredContent)` where `apply` is a `UnaryOperator<Document>` that mutates (or replaces) the parsed XML DOM and `changesAuthoredContent` says whether the step changes what the user authored. The 4-argument constructor and `step(...)` default it to `true`; `formatOnly(...)` builds a step with `false`. |
| `MigrationRegistry` | Ordered, validated list of migrations targeting a specific `currentVersion`. Validates contiguity at construction. Drives `migrate(Document)` to walk a file from its on-disk version up to current. |
| `MigrationReport` | Immutable record of what ran during a single load: `fromVersion`, `toVersion`, list of `AppliedMigration`s (each carrying its step's `changesAuthoredContent`), timestamp. `wasMigrated()` is true when any step ran; `changesAuthoredContent()` is true when any step that ran changes what the user authored. |
| `MigrationException` | Thrown when no continuous migration chain exists from the file's version to current (e.g., the file is from a future build, or a step is missing). |
| `MigrationSuppression` | Per-project marker (`.migration-report-suppressed`) that records the user's "Don't show again for this project" choice. Suppression is version-scoped — a future schema bump re-surfaces the dialog. |

The dialog itself lives in the app module:

| Class | Role |
|-------|------|
| `com.benesquivelmusic.daw.app.ui.MigrationReportDialog` | JavaFX dialog shown after load when a migration that changes authored content ran (`MigrationReport.changesAuthoredContent()`). Lists the migrations in plain English and provides the suppression checkbox. |

### When the dialog is shown

The dialog's question — "these migrations were applied in memory; review
or roll back?" — only has an answer when a step changed what the user
authored. A load whose steps are all format-only (`ProjectMigration.formatOnly`)
opens silently. Everything else about a migrated load follows
`MigrationReport.wasMigrated()`, whatever the steps' kind: the
pre-migration backup on the first save, the migration history view, the
status strip's "backup available" and the hub's migrated badge.

## Authoring a New Migration

1. **Bump `MigrationRegistry.CURRENT_VERSION`** to the new schema version
   (e.g. `3`).
2. **Register the migration** in
   `MigrationRegistry.DefaultRegistryHolder.INSTANCE`, after the steps
   already there:
   ```java
   MigrationRegistry.builder(CURRENT_VERSION)
       .add(MidiEffectPluginIdMigration.step())
       .add(ProjectMigration.step(2, "rename pan-law attribute",
           document -> {
               // mutate document, return it
               return document;
           }))
       .build();
   ```
   Use `ProjectMigration.step(...)` for a step that changes what the user
   authored — the default, and the one that shows the migration report
   dialog. Use `ProjectMigration.formatOnly(...)` only for a step that
   re-expresses the same session in the new schema (an element re-keyed by
   a stable name, new elements older files simply lack): it is recorded
   like any other step but opens without the dialog.
3. **Add a unit test** in
   `daw-core/src/test/java/.../persistence/migration/`. Each migration
   gets a "golden" XML input and expected XML output so future changes
   cannot regress it.
4. **Update the version emitted by `ProjectSerializer`** — automatic via
   the shared `MigrationRegistry.CURRENT_VERSION` constant.
5. **Record the new version** in the [Schema Version History](#schema-version-history)
   below.

## Legacy-Batch Consolidation

Once a migration is older than `MigrationRegistry.LEGACY_BATCH_THRESHOLD`
(currently 10) versions back, multiple step migrations may be folded
into a single legacy-batch `ProjectMigration` whose `toVersion` is more
than one greater than its `fromVersion`. Document any such consolidation
in the [Schema Version History](#schema-version-history).

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
   surfaces `MigrationReportDialog` to the user when the report
   `changesAuthoredContent()`.
6. **No state change is persisted** unless the user explicitly saves.
   The first save after a migrated load takes a sibling backup
   (`project.daw.v<n>.<timestamp>.bak`) of the original on-disk file
   and only then overwrites it. The backup is taken exactly once per
   load and never overwrites an existing backup. An open that could not
   read everything as written owes a backup too; see
   [The Degraded-Open Backup](#the-degraded-open-backup).

## The Degraded-Open Backup

A migration is not the only open whose first save loses something. When
the deserializer skips or adjusts saved content, the next save writes
the project without it, so `ProjectManager` keeps the original file
first, by the same mechanism as the migration backup.

- **Name.** `project.daw.degraded.<yyyyMMdd-HHmmss>.bak`, a sibling of
  `project.daw`, with the stamp in UTC; `…<stamp>-<n>.bak` when that name
  is taken. One writer, `ProjectManager.writeSiblingBackup`, makes both
  kinds of backup and never overwrites one.
- **When it is armed.** Once per load, in `ProjectManager.openProject`,
  when `ProjectDeserializer.losesSavedContent()` is true: the open
  skipped a MIDI note, a CC lane or a CC event, adjusted an arpeggiator
  setting, or removed a mixer assignment (a cue send, VCA member,
  channel link or bed routing naming a track the file does not hold, a
  malformed id, a channel linked to itself, or a repeat); it replaced a
  malformed or duplicate track id; it dropped the child elements or text
  of a MIDI effect it kept as a placeholder; or it skipped a VCA group
  whose id repeats an earlier group's. What the open does without
  losing saved content arms nothing: an unrecognised MIDI effect kept
  with its attributes, a missing track id, a valid id written in a
  non-canonical form, a missing asset, and a VCA group gain that is not
  a number (it holds no level to lose). Nor does a view or transport
  preference the open resets, though the next save writes the value it
  used: a CC lane's unusable height ratio, a malformed pre/post-roll
  value, and an analyzer setting the reader cannot use (the analyzer
  opens on its defaults, logged at WARNING only, because an analyzer's
  settings are a meter view preference that never reaches the audio).
  Only `openProject` arms it: the other ways in keep their
  source (the snapshot file, the archive file; journal recovery writes
  its own `project.daw.recovered.<stamp>.bak`).
- **When it is written.** On the first save after that open, just
  before `project.daw` is overwritten, and its path is logged at INFO;
  the open's announcement does not mention it. A copy that fails fails
  the save with `project.daw` untouched, and the next save tries again.
  If `project.daw` no longer exists, no backup is owed. A later save of
  the same load writes no further backup.
- **An open that is both migrated and degraded** owes one backup, the
  migration's `project.daw.v<from>.<stamp>.bak`; the INFO line says it
  also keeps the content this save drops.
- **Never shown as a migration.** The Migration History view
  (`MigrationHistoryView`) and the hub's health scan
  (`ProjectHealthScanner`) count only names of the form
  `project.daw.v<n>.….bak` (both patterns require `\.v(\d+)\.`), and the
  migration roll-back looks for the prefix `project.daw.v<from>.`.
  `degraded` never starts with `v` and a digit, so the backup never
  appears as a migration, raises no migrated badge and is never offered
  for roll-back.

## Schema Version History

The version written in `project.daw`'s root `version` attribute, and what
each version changed. Every bump registers exactly one step in
`MigrationRegistry.DefaultRegistryHolder`.

| Version | Step from the previous version | What changed |
|---------|--------------------------------|--------------|
| 1 | — | Baseline. The schema was not frozen while version 1 was current: files from 2026-03-28 on all say `version="1"`, and elements were added without a bump (for example `<midi-effect-chain>`, the transport's `pre-roll-bars` / `post-roll-bars` / `pre-roll-enabled`, `<source-segment>`). Readers therefore never require an element to be present: each tolerates its absence and restores it when present. |
| 2 | `MidiEffectPluginIdMigration` — "add per-track MIDI clips; key MIDI effects by id" (format-only: no dialog) | Each track carries its MIDI clip as a `<midi-clip>` child (notes, CC lanes with breakpoints; written only when the clip holds something). `<midi-effect>` entries are keyed by the plugin's stable `plugin-id` instead of its Java `class` name; the step rewrites the arpeggiator's class name and leaves any other class name as it is (the reader keeps such an entry as a placeholder that writes its attributes back). `mixer/master@name` and `mixer/channels/channel@name` (derived duplicates the reader never honoured) and `send@mode` (a lossy view of `send@tap`) are no longer written; `send@mode` is still read when `tap` is absent. Track ids, the transport's pre/post-roll and the MIDI-effect chain, already present in version 1 files, are now read back. |

## Non-Goals

- Forward migrations (downgrading a file to an older schema).
- Migration of embedded audio file formats.
- Undo of an in-place migration — the backup plus the user's "Save vs.
  Discard" choice is the rollback path.
