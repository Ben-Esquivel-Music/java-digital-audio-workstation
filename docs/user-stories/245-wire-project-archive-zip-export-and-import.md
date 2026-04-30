---
title: "Wire ProjectArchiver into File Menu: Archive Project (ZIP With Assets) and Restore From Archive"
labels: ["enhancement", "persistence", "ui", "archive"]
---

# Wire ProjectArchiver into File Menu: Archive Project (ZIP With Assets) and Restore From Archive

## Motivation

Story 189 — "Project Archive (ZIP With Assets)" — is the workflow that lets a user package a project into a single self-contained ZIP file containing the project XML plus every referenced audio asset, so the project can be moved between machines, sent to collaborators, or archived for long-term storage. The core is implemented:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/archive/ProjectArchiver.java` (asset-plan walker + ZIP writer).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/archive/ProjectArchiveSummary.java` (per-asset status: included / missing / skipped).
- `ProjectArchiverTest`.

But:

```
$ grep -rn 'ProjectArchiver' daw-app/src/main/
(no matches)
```

There is no "File → Archive Project…" or "File → Restore from Archive…" menu item. The archive feature is engine-only. A user who wants to send a session to a collaborator has to manually walk the assets and zip them — exactly the chore the story was meant to eliminate.

## Goals

- "File → Archive Project…" menu item:
  - Opens a save-file dialog filtered to `.dawzip` (or whatever extension the existing tests assert).
  - Computes the asset plan via `ProjectArchiver.planArchive(project)` and shows a confirmation dialog summarising: total assets, total size, missing assets (red), skipped assets (yellow). Cancel returns to the project; OK proceeds.
  - Runs the archive offline with a `TaskProgressIndicator` for projects whose total asset size exceeds a few hundred megabytes; progress drives off `ProjectArchiver`'s existing per-asset events.
  - On success, surfaces a notification ("Archive saved: 42 assets, 1.2 GiB") with a "Show in folder" link.
  - On any missing asset, the resulting `ProjectArchiveSummary` is presented in a follow-up dialog so the user can decide to abort or proceed (a "Continue with missing assets" toggle).
- "File → Restore from Archive…" menu item:
  - File picker for `.dawzip`. Prompt for a destination directory (default: `Documents/<project-name>-<timestamp>/`).
  - Calls `ProjectArchiver.restore(archive, destination)` which extracts the assets, rewrites the project XML's asset paths to the new location, and returns a restored project file path.
  - Loads the restored project via the existing `ProjectManager.load(...)` path.
- Update `WorkspaceJson` / recent-projects so an archive that was just restored appears in the recent projects list.
- Persistence-archive integration with story 190 (snapshot history): the archive workflow can optionally embed user-created checkpoints (per the original story 190 reference), gated by a "Include checkpoint history" toggle in the archive dialog, defaulting off (smaller archives by default).
- Tests:
  - Headless test: archive a small project containing a single audio clip, restore into a different directory, assert the restored project loads and the audio file paths resolve correctly.
  - Test confirms the missing-asset path: archive a project where one referenced audio file has been deleted, assert the summary lists the missing asset and the user can choose to continue.

## Non-Goals

- Cloud-archive integration.
- Encrypted archives.
- Cross-DAW archive format (the archive contains the project's own XML).
- Selective asset archive (archive includes all referenced assets — the user can prune the project before archiving).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` and / or `ProjectLifecycleController.java` (mount menu items + run flows), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/TaskProgressIndicator.java` (shared from story 234), new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ArchiveSummaryDialog.java`.
- `ProjectArchiver.planArchive(...)`, `ProjectArchiver.archive(...)`, `ProjectArchiver.restore(...)`, `ProjectArchiveSummary` already exist.
- Reference original story: **189 — Project Archive (ZIP With Assets)**.
