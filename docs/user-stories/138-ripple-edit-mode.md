---
title: "Ripple Edit Mode (Delete and Move Propagate to Later Clips)"
labels: ["enhancement", "editing", "arrangement"]
---

# Ripple Edit Mode (Delete and Move Propagate to Later Clips)

## Motivation

Removing 8 bars from the middle of a song currently requires selecting every clip on every track after the cut, moving them left by 8 bars, and praying nothing slipped. This is the exact workflow every DAW solves with "ripple edit": deleting or moving a clip shifts all later clips on the same track (or on all tracks, depending on mode) to close the gap. Pro Tools' "Shuffle" mode, Cubase's "Ripple," Reaper's "Ripple editing — all tracks" / "Ripple editing — per track" all implement this pattern. It is indispensable for editing spoken-word/podcast, film-dialogue, and song-structure work.

The existing `ClipEditOperations` and `UndoManager` handle atomic clip operations; ripple mode composes into an atomic multi-clip shift.

## Goals

- Add `RippleMode` enum in `com.benesquivelmusic.daw.sdk.edit`: `OFF`, `PER_TRACK`, `ALL_TRACKS`.
- Add `RippleEditService` in `com.benesquivelmusic.daw.core.project.edit` that, given an edit and current `RippleMode`, computes the atomic set of clip-position deltas and returns a `CompoundUndoableAction`.
- Integrate with `ClipEditOperations.delete`, `.move`, and `.cut` — when `RippleMode != OFF`, the operation calls `RippleEditService` to produce the full atomic action.
- UI toggle in the toolbar: cycling button `OFF / PER_TRACK / ALL_TRACKS` with distinct icons and shortcuts `Shift+1 / Shift+2 / Shift+3`.
- Status-bar indicator when ripple is active (mirrors Reaper's aggressive red banner) so the user never forgets the mode is on.
- Ripple respects time selection when present: deletions within the selection ripple, moves outside do not.
- `ALL_TRACKS` mode rippling is gated by a confirmation-prompt once per session (suppressed by "don't ask again") because it is destructive across tracks.
- Persist `RippleMode` as a per-project UI preference via `ProjectSerializer`.
- Tests: ripple delete closes the gap across the configured scope; undo restores original positions; overlapping clips on ripple-destination tracks produce a validation error (not silent data loss).

## Non-Goals

- Ripple editing for automation data (automation follows its parent clip positions via story 139 slip or stays put — this story focuses on clips).
- Cross-project ripple (edits never cross project boundaries).
- Non-linear ripple (preserving gaps) — this is strict gap-closing.
