---
title: "Project Save, Load, and Auto-Save Reliability"
labels: ["enhancement", "persistence", "project", "core"]
---

# Project Save, Load, and Auto-Save Reliability

## Motivation

The `ProjectManager`, `CheckpointManager`, `AutoSaveConfig`, and `RecentProjectsStore` classes exist in the core module, suggesting a project persistence system is partially implemented. However, the actual serialization of the full project state (tracks, clips, clip positions, mixer settings, transport position, undo history) to a file and restoring it reliably is critical for any DAW. Users will lose hours of work if save/load is unreliable. The UI has Save/Open menu items, but the underlying persistence must handle all project data faithfully. Auto-save should be configurable and unobtrusive (no audio glitches during save).

## Goals

- Serialize the complete project state to a JSON or XML file including:
  - Track list with names, types, and properties (volume, pan, mute, solo, armed)
  - Audio clip references with file paths, positions, offsets, and fade settings
  - Mixer settings (channel volumes, pans, sends, insert effects)
  - Transport state (tempo, time signature, loop settings)
  - Project metadata (name, creation date, last modified)
- Deserialize and restore a saved project exactly as it was
- Implement auto-save at a configurable interval (default: 5 minutes) via `AutoSaveConfig`
- Store auto-save files in a separate directory to prevent overwriting manual saves
- Maintain a recent projects list via `RecentProjectsStore` for quick access
- Show a confirmation dialog when opening a new project with unsaved changes
- Support checkpoint/snapshot saving for non-destructive versioning

## Non-Goals

- Cloud sync or remote project storage
- Collaborative editing with conflict resolution
- Project migration from other DAW formats (DAWproject import is separate)
