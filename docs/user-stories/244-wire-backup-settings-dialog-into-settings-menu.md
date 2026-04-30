---
title: "Wire BackupSettingsDialog into Settings Menu and Apply Live Retention Policy"
labels: ["enhancement", "persistence", "ui", "backup"]
---

# Wire BackupSettingsDialog into Settings Menu and Apply Live Retention Policy

## Motivation

Story 191 — "Auto-Backup Rotation and Retention Policy" — adds configurable auto-backup retention (max-age, max-count, total-size cap, per-project quotas) so the autosaves directory does not grow unbounded. The dialog and the engine are implemented:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/backup/BackupRetentionPolicyStore.java` (persisted policy in `~/.daw/backup-policy.json`).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/backup/BackupRetentionService.java` (applies the policy: prunes old files, enforces caps).
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/BackupSettingsDialog.java` (the JavaFX dialog).
- Tests for the store, service, and the migration / pre-migration backup interaction.

But:

```
$ grep -rn 'new BackupSettingsDialog' daw-app/src/main/
(no matches)
```

`BackupSettingsDialog` is constructed nowhere. There is no "Settings → Backups…" menu item, no live policy invocation. The autosave directory grows indefinitely.

## Goals

- Add "Settings → Backup retention…" menu item that opens `BackupSettingsDialog`. The dialog presents max-age, max-count, total-size, per-project quotas with sensible defaults (max-age 7 days, max-count 100 per project, total cap 5 GiB).
- On dialog Apply: write the policy via `BackupRetentionPolicyStore.save(...)` and immediately invoke `BackupRetentionService.applyPolicy(currentPolicy)` against `~/.daw/autosaves/` so the change takes effect without restart.
- Schedule periodic policy application: a background task runs `BackupRetentionService.applyPolicy(...)` once per hour while the app is running, so backups created during long sessions are pruned without manual intervention. Use `Executors.newScheduledThreadPool(1)` with a daemon thread (per `daw-app` conventions).
- `MainController` initializes `BackupRetentionPolicyStore`, runs `applyPolicy` once on startup, and registers the periodic task.
- The dialog's "Show backup folder" button reveals the autosave directory in the OS file browser (Windows Explorer / Finder / Nautilus) using the existing OS-launcher utility; no new abstraction needed.
- Story 188's pre-migration backup creation already routes through `BackupRetentionService` indirectly — confirm and document; do not change the migration path.
- Tests:
  - Headless test: open the dialog, set max-age to 1 day, apply, assert files in `~/.daw/autosaves/` older than 1 day are removed.
  - Test confirms the periodic task fires (driven by an injected `Clock` so the test does not actually wait an hour).
  - Test confirms the policy persists across app restart via the store.

## Non-Goals

- Cloud backup integration.
- Backup of project assets (story 189 handles archive workflow).
- Encrypted backups.
- Differential / incremental backup format.

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose store + service, schedule task, mount menu item), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/BackupSettingsDialog.java` (instantiation only — no rewrite).
- `BackupRetentionPolicy` record, `BackupRetentionPolicyStore.save / load`, and `BackupRetentionService.applyPolicy` already exist.
- The periodic task should respect the story-205 virtual-thread guidance for non-realtime work (a single platform daemon thread is fine here; the workload is I/O bound and infrequent).
- Reference original story: **191 — Auto-Backup Rotation and Retention Policy**.
