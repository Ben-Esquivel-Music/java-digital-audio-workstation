---
title: "Snapshot History Browser with Visual Diff Preview"
labels: ["enhancement", "persistence", "undo", "safety"]
---

# Snapshot History Browser with Visual Diff Preview

## Motivation

Autosave (story 019) writes snapshots periodically, and `UndoManager` (story 024) tracks undo history. Users occasionally need to recover a state from 30 minutes ago ("I liked the chorus arrangement before I changed everything"). A timeline browser that shows all snapshots and undo points with visual previews of the arrangement at that point makes this a usable feature. Logic's "Project Alternatives," Cubase's "Project History," Reaper's auto-saved project versions — every DAW has some form of this.

## Goals

- Add `SnapshotBrowser` in `com.benesquivelmusic.daw.app.ui` showing a timeline of project states.
- Sources: `~/.daw/autosaves/<project>/*` + `UndoManager` history + explicit user-created checkpoints (new `Create Checkpoint` action, Ctrl+Alt+S).
- Each snapshot row shows: timestamp, trigger (autosave / user / undo-checkpoint), an arrangement-preview thumbnail rendered from the snapshot, notable changes summary ("+3 clips, -1 track, 14 edits").
- Clicking a snapshot opens a read-only view of the project at that state in a split preview; "Restore" loads it into the current project (after prompting to save the current state).
- "Compare with current" shows a diff table of tracks, clips, and plugin parameters that differ.
- Snapshots retention: autosaves retained for 7 days rolling, user-checkpoints retained indefinitely, undo-point snapshots retained for the current session only.
- Cleanup UI to purge old autosaves per-project or globally.
- Persist checkpoints in the project archive (story 189) if the project is archived.
- Tests: restoring a known snapshot produces bit-identical project state vs loading that snapshot fresh; diff correctly lists added/removed clips.

## Non-Goals

- Git-style branching / merging of project states.
- Multi-user collaborative timeline.
- Cross-project snapshot comparison.
