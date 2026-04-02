---
title: "Implement Clip Paste Over, Trim to Selection, and Crop Operations"
labels: ["enhancement", "editing", "arrangement-view"]
---

# Implement Clip Paste Over, Trim to Selection, and Crop Operations

## Motivation

User story 038 describes clip clipboard operations including copy, cut, paste, and duplicate. The `ClipboardManager` implements copy, cut, paste, and duplicate correctly, and these work via keyboard shortcuts and the track context menu. However, three additional editing operations in the track context menu — "Paste Over", "Trim to Selection", and "Crop" — display "not yet implemented" notifications when triggered. These are visible menu items that users can click, but they do nothing except show a warning. In professional DAWs, "Paste Over" replaces the content at the playhead with the clipboard contents (overwriting existing clips), "Trim to Selection" trims the selected clip to the boundaries of the time selection, and "Crop" removes all audio outside the time selection on the selected track. These are essential editing refinements that professional users expect.

## Goals

- **Paste Over**: When invoked, paste the clipboard clip at the current playhead position on the target track, splitting and removing any existing clips that overlap the pasted region, then place the pasted clip — effectively replacing audio in that time range
- **Trim to Selection**: When a time selection is active and a clip is selected, trim the clip's start and end to align with the selection boundaries (adjusting `startBeat`, `durationBeats`, and `sourceOffsetBeats`) — equivalent to an interactive trim but using the selection range
- **Crop**: When a time selection is active, remove all clip content outside the selection range on the selected track(s) — split clips at the selection boundaries and delete the portions outside
- Register all three operations as undoable actions via the `UndoManager`
- Enable/disable each menu item based on the appropriate preconditions (clipboard non-empty for Paste Over, time selection active for Trim to Selection and Crop, clip selected for Trim to Selection)
- Update the arrangement canvas immediately after each operation

## Non-Goals

- Ripple editing (shifting subsequent clips after paste or crop)
- Cross-track paste operations (pasting onto a different track type)
- Destructive editing (all operations use non-destructive clip offsets)
