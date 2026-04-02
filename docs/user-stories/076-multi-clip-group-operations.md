---
title: "Multi-Clip Group Move, Delete, and Duplicate in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Multi-Clip Group Move, Delete, and Duplicate in Arrangement View

## Motivation

The `SelectionModel` supports selecting multiple clips, and user story 075 describes rubber-band selection to populate that selection set. The `ClipInteractionController` currently applies operations (move, split, delete, glue) to individual clips only — when the Pointer tool drags a clip, only the clicked clip moves; when the Eraser tool clicks a clip, only that clip is deleted. There is no logic to apply operations to all selected clips as a group. In professional DAWs, selecting multiple clips and then dragging moves the entire group while preserving their relative positions and track assignments. Pressing Delete removes all selected clips. Ctrl+D duplicates all selected clips. Without group operations, multi-clip selection has no practical benefit — users must operate on clips one at a time regardless of how many are selected.

## Goals

- When multiple clips are selected via the `SelectionModel` and the user drags any one of them with the Pointer tool, move all selected clips by the same beat delta, preserving their relative positions and track assignments
- Support cross-track group move — if the user drags the group vertically, all clips shift by the same number of tracks
- When the Eraser tool is used on a selected clip, delete all clips in the current selection (with a single undoable action)
- When Ctrl+D (Duplicate) is triggered with a multi-clip selection, duplicate all selected clips and place the copies immediately after the rightmost original clip
- When Ctrl+C / Ctrl+X / Ctrl+V (Copy / Cut / Paste) are triggered, operate on the full multi-clip selection as a group
- Register all group operations as a single compound undoable action (one undo reverses the entire group operation)
- Snap the group move to the grid when snap-to-grid is enabled (snap the reference clip, others follow)

## Non-Goals

- Non-uniform scaling of clip durations across the group
- Group fade or trim operations (these remain per-clip)
- Linked clip groups that persist across sessions (selection is transient)
