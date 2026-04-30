---
title: "Show MigrationReportDialog Automatically on Project Load When Schema Was Migrated"
labels: ["enhancement", "persistence", "ui", "migration"]
---

# Show MigrationReportDialog Automatically on Project Load When Schema Was Migrated

## Motivation

Story 188 — "Project Version Migration Registry" — defines the schema-migration framework so projects authored under earlier versions of the DAW load cleanly, with a `MigrationReport` describing what changed (e.g., "Renamed channel-link `linkInserts` → `linkInserts` default true", "Added per-track CPU budget defaults"). The framework is implemented and used in production:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/migration/MigrationRegistry.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/migration/MigrationReport.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/migration/MigrationException.java`
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/migration/MigrationSuppression.java`
- `ProjectManager` invokes the registry on load, captures the report, and triggers a pre-migration backup when `wasMigrated()`.
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MigrationReportDialog.java` exists and is JavaFX-tested in `MigrationReportDialogTest`.

But:

```
$ grep -rn 'MigrationReportDialog' daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java
(no matches)
$ grep -rn 'new MigrationReportDialog\|MigrationReportDialog\.' daw-app/src/main/
daw-app/.../MigrationReportDialog.java:39:    public MigrationReportDialog(MigrationReport report, Path projectDirectory) {
daw-app/.../MigrationReportDialog.java:99:        new MigrationReportDialog(report, projectDirectory).showAndWait();
```

Line 99 is a static factory method *inside the dialog's own class*. No production caller invokes it. So when the user opens an older project, the migration runs (correctly), the backup is created (correctly), but the user is never told what happened. Per the original story's spec ("the user can roll back to the pre-migration version") the user has no way to know there is a pre-migration version available unless they happen to look in the autosaves directory.

## Goals

- After every project load, `ProjectLifecycleController` checks the `MigrationReport` returned by `ProjectManager.load(...)`. If `report.wasMigrated()` is true:
  - Open `MigrationReportDialog.show(report, projectDirectory)` on the JavaFX thread.
  - The dialog already lists the per-version migrations applied and explains where the pre-migration backup lives (under `~/.daw/autosaves/<project>/pre-migration-<timestamp>/`).
  - Add a "Roll back…" button that loads the pre-migration backup via `ProjectManager.load(...)` after confirming "Discard the migrated version?". On confirm, swap to the rolled-back project and surface a notification.
  - Add a "Don't show this again for this project" checkbox tied to `MigrationSuppression` (per-project, persisted in the project file or a sidecar) so projects that the user has already accepted as migrated do not re-prompt on every open.
- `MigrationException` paths (an unmapped version) are already handled with an exception dialog — confirm that path also surfaces clearly.
- Tests:
  - Headless test: open a project with a synthesised older `formatVersion`, assert `MigrationReportDialog` opens, contains the expected migration list, and the "Roll back" button reverts to the pre-migration backup.
  - Test confirms the suppression checkbox prevents the dialog from re-opening the same project.

## Non-Goals

- Defining new migrations (story 188 covers the registry; this story is about surfacing the existing report).
- Forward migration (downgrading a newer project file to an older version).
- Per-feature opt-out of migrations (the registry is all-or-nothing per version step).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java` (invoke the dialog after every successful load when `wasMigrated()`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MigrationReportDialog.java` (add Roll back button + suppression checkbox if not already present), `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/migration/MigrationSuppression.java` (use existing API).
- `ProjectManager` already returns the report on load — verify the accessor (e.g., `getLastMigrationReport()`) is reachable from `ProjectLifecycleController`.
- Reference original story: **188 — Project Version Migration Registry**.
