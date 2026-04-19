---
title: "Project Archive Format (ZIP with All Referenced Audio Assets)"
labels: ["enhancement", "persistence", "portability"]
---

# Project Archive Format (ZIP with All Referenced Audio Assets)

## Motivation

A project file without the audio it references is useless. Users moving a session between studios, emailing to a collaborator, or archiving to cold storage need a single-file bundle that includes everything. Pro Tools has "Save Copy In," Logic has bundle-style `.logicx` packages, Reaper has "Consolidate Project." The current DAW leaves audio files scattered across the user's disk with absolute paths — a recipe for "missing media" errors when the project moves.

## Goals

- Add `ProjectArchiver` in `com.benesquivelmusic.daw.core.persistence.archive` implementing:
  - "Save As Archive…" that writes a `.dawz` file (ZIP format) containing: the project JSON with asset paths rewritten to relative, every referenced audio asset, impulse responses, SoundFont files, and any external metadata.
  - "Open Archive" that extracts to a temp directory and loads the project.
- Asset collection phase walks every `AudioClip.sourceAsset`, `MidiClip.soundFontAssignment.file`, `ConvolutionReverbPlugin.irPath`, etc., deduplicating by content hash.
- Optional "Consolidate in place" mode that copies external assets into a `Project/assets/` subfolder without zipping, converting the project to relocatable form.
- Missing-asset resolver: on open, any asset path that doesn't resolve prompts "Locate..." with smart search of sibling folders.
- Archive metadata header: project name, archive date, total asset count, original absolute root, DAW version, SHA-256 of the project JSON.
- Tests: a project with 10 assets round-trips through archive → open → save-as-archive producing byte-identical asset content and equivalent project state.
- `ProjectArchiveDialog` with progress bar, options (include/exclude unused takes, include impulse responses), and estimated output size.

## Non-Goals

- Differential archives (incremental updates).
- Encryption (separate future story if required).
- Cloud-native archive formats (bucket-based, deduplicated) beyond a simple ZIP.
