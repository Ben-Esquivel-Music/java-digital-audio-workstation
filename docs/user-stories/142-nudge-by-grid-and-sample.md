---
title: "Nudge Clips and Selections by Grid and by Sample"
labels: ["enhancement", "editing", "keyboard-shortcuts"]
---

# Nudge Clips and Selections by Grid and by Sample

## Motivation

Every editor needs "move this thing a tiny bit left, a tiny bit right." Dragging with the mouse is inherently imprecise and almost always snaps to grid. Every DAW supplies keyboard nudge: Pro Tools `+ / -` for nudge by nudge-value, `Shift + - / +` for larger nudges; Logic `Option+Left/Right`; Reaper `Num 4 / Num 6`. Without nudge, fine timing adjustments are frustrating.

`ClipEditOperations` already has `moveClip(long frameDelta)`. The work is a keyboard-driven front-end plus a configurable nudge value.

## Goals

- Add `NudgeSettings` record in `com.benesquivelmusic.daw.core.project.edit`: `record NudgeSettings(NudgeUnit unit, double amount)` where `NudgeUnit` is a sealed enum: `FRAMES`, `MILLISECONDS`, `GRID_STEPS`, `BAR_FRACTION`.
- Default nudge: 1 grid step.
- Keyboard shortcuts: `Ctrl+Left / Ctrl+Right` nudge by configured nudge value; `Ctrl+Shift+Left/Right` nudge by 10× value; `Alt+Left/Right` nudge by a single sample.
- Nudge applies to the current selection: single clip, multi-selection (from story 075), or time selection (which shifts contained clips).
- Toolbar control to set `NudgeSettings`: unit dropdown + amount input.
- Nudge produces `NudgeClipsAction` undo records; compound when multiple clips are nudged together.
- Live "N: 1/16" indicator in status bar so the user always sees the current nudge value.
- Persist `NudgeSettings` per-project via `ProjectSerializer`.
- Tests: each unit nudges by the mathematically correct amount; boundary checks prevent negative frame positions; multi-selection nudge is a single undo step.

## Non-Goals

- Nudge for MIDI notes specifically (MIDI editor has its own nudge that this story informs but does not wire).
- Nudge automation breakpoints (future story).
- Nudge by audio transient (transient-aware nudge is separate).
