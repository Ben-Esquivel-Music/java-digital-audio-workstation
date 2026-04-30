---
title: "Instantiate SnapshotBrowser and Wire Checkpoint Workflow into MainController"
labels: ["enhancement", "persistence", "ui", "undo"]
---

# Instantiate SnapshotBrowser and Wire Checkpoint Workflow into MainController

## Motivation

Story 190 — "Snapshot History Browser with Visual Diff Preview" — provides a timeline browser of the project's autosave / undo / user-checkpoint states with restore-to-state and compare-with-current actions. The implementation is essentially complete:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/snapshot/SnapshotBrowserService.java` (aggregates autosaves, undo points, checkpoints).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/CheckpointManager.java` (Create Checkpoint action).
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/SnapshotBrowser.java` (the JavaFX view).
- `SnapshotBrowserTest`, `SnapshotBrowserServiceTest`, `CheckpointManagerTest`.

But:

```
$ grep -rn 'new SnapshotBrowser(' daw-app/src/main/
(no matches)
```

`SnapshotBrowser` is constructed nowhere. There is no "Snapshots…" menu item, no `Ctrl+Alt+S` "Create Checkpoint" shortcut, and no UI entry into the workflow. The user has no way to recover a state from 30 minutes ago — the use case the story specifically called out.

## Goals

- Compose `SnapshotBrowserService` and `CheckpointManager` in `MainController`'s startup sequence.
- Add "File → Snapshots…" menu item that opens the `SnapshotBrowser` in a modeless dialog or dock pane (workspace dock per story 195).
- Add "File → Create Checkpoint" action with `Ctrl+Alt+S` shortcut. Prompts for an optional label, calls `CheckpointManager.createCheckpoint(label)`. The new checkpoint immediately appears in the browser.
- The browser's "Restore" action prompts to save the current state first (offering Save / Discard / Cancel), then loads the snapshot via `ProjectManager.load(...)`.
- "Compare with current" runs a small diff (the service already produces a structured diff record) and renders a tabular view of changed tracks / clips / plugin parameters.
- Cleanup: a "Purge older than…" action in the browser invokes `SnapshotBrowserService.purge(olderThan)`. Per the original story, autosaves are retained 7 days rolling, user-checkpoints retained indefinitely, undo-points session-only.
- Confirm the existing autosave path (story 019) emits snapshots that `SnapshotBrowserService` can find, and that `CheckpointManager` writes user-checkpoints to a directory the service walks. If discovery is incomplete, finish it.
- Tests:
  - Headless test: create three checkpoints, open the browser, assert all three appear in the list with timestamps and any labels.
  - Test confirms restoring a checkpoint produces bit-identical project state vs loading the snapshot directly via `ProjectManager`.
  - Test confirms "Purge older than 1 day" removes only autosaves older than 1 day, not checkpoints.

## Non-Goals

- Git-style branching / merging of project states.
- Multi-user collaborative timeline.
- Cross-project snapshot comparison.

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose service + browser + menu items + shortcut), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/KeyBindingManager.java` (Ctrl+Alt+S binding), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/SnapshotBrowser.java` (instantiation only — no rewrite).
- `SnapshotBrowserService.list()`, `purge(...)`, `diff(...)` and `CheckpointManager.createCheckpoint(...)` already exist.
- Reference original story: **190 — Snapshot History Browser**.
