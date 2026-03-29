---
title: "Interactive Clip Edge Trim Handles in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Interactive Clip Edge Trim Handles in Arrangement View

## Motivation

User story 002 describes the ability to trim clip start and end by dragging clip edges in the arrangement view. The `AudioClip` model fully supports non-destructive trimming — it has `startBeat`, `durationBeats`, `sourceOffsetBeats`, and a `trimTo()` method. The `EditorView` provides a basic trim action (10% trim from each end), but this is a fixed operation triggered by a button, not an interactive drag gesture. In professional DAWs, users hover over the left or right edge of a clip rectangle, see a resize cursor, and drag to trim the clip start or end in real time. The clip's waveform shifts accordingly because trimming adjusts the source offset rather than destroying audio data. Without interactive trim handles, users cannot precisely adjust where a clip begins or ends in the timeline.

## Goals

- When the Pointer tool is active and the mouse hovers within a few pixels of a clip's left or right edge, change the cursor to a horizontal resize cursor
- Dragging the left edge adjusts `AudioClip.startBeat` and `sourceOffsetBeats` simultaneously (the clip start moves but the audio content stays aligned)
- Dragging the right edge adjusts `AudioClip.durationBeats` (the clip end extends or contracts)
- Snap the trim position to the grid when snap-to-grid is enabled, using the existing `SnapQuantizer`
- Show a real-time preview of the new clip boundary while dragging (ghost line or highlighted region)
- Register the trim operation as an `UndoableAction` when the drag completes
- Prevent trimming beyond the source audio boundary (clip cannot be extended beyond its original length)
- Update the clip waveform preview in real time during the drag

## Non-Goals

- Fade handle dragging (covered separately)
- Time-stretching during trim (trim is non-destructive source offset adjustment only)
- Trimming MIDI clips (MIDI note trimming is a separate editor feature)
