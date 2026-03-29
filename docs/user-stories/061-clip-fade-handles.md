---
title: "Interactive Clip Fade-In and Fade-Out Handles in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Interactive Clip Fade-In and Fade-Out Handles in Arrangement View

## Motivation

User story 002 describes fade-in and fade-out handles on clip corners. The `AudioClip` model already stores `fadeInBeats`, `fadeOutBeats`, `fadeInCurveType`, and `fadeOutCurveType` with support for LINEAR, EQUAL_POWER, and S_CURVE types via `FadeCurveType`. The `EditorView` provides fixed fade-in and fade-out button actions that apply a default 2-beat fade, but there is no interactive fade handle on the clip rectangle in the arrangement view. In professional DAWs, small triangular or curved handles appear at the top-left and top-right corners of a clip, and users drag them inward to set the fade length, with a visual curve overlay showing the fade shape. Without interactive fade handles, users have no intuitive way to set precise fade lengths or visually verify fade curves.

## Goals

- Render small fade handle indicators at the top-left (fade-in) and top-right (fade-out) corners of each clip rectangle in the arrangement view
- When the mouse hovers over a fade handle, show a tooltip with the current fade duration and curve type
- Dragging a fade handle inward adjusts `fadeInBeats` or `fadeOutBeats` on the clip in real time
- Draw a semi-transparent fade curve overlay on the clip waveform showing the fade shape (linear ramp, equal-power curve, or S-curve)
- Right-click on a fade handle to select the fade curve type from a context menu (Linear, Equal Power, S-Curve)
- Register fade handle drags as undoable actions
- Snap fade handle positions to the grid when snap-to-grid is enabled
- Prevent the fade-in handle from crossing the fade-out handle and vice versa

## Non-Goals

- Crossfade editing between overlapping clips on the same track (separate feature)
- Fade handles in the editor view (the editor view already has button-based fade actions)
- Automating fade curve changes over time
