---
title: "Track Drag-and-Drop Reordering in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view"]
---

# Track Drag-and-Drop Reordering in Arrangement View

## Motivation

Currently, tracks in the arrangement view are listed in the order they are created. There is no way to visually reorder tracks by dragging them up or down, which is a fundamental interaction in every professional DAW (Pro Tools, Logic Pro, Ableton Live, Reaper). Engineers working on large sessions with 20+ tracks need to group related tracks (e.g., all drums together, all vocals together) to maintain an organized workflow. Without drag-and-drop reordering, users must delete and recreate tracks to change their visual order, which is destructive and loses clip data.

## Goals

- Allow users to drag a track header up or down in the arrangement view to reorder it among other tracks
- Provide a visual drop indicator (highlight line) showing where the track will be placed
- Update the mixer channel strip order to match the arrangement track order
- Make the reorder operation undoable via the existing `UndoManager`
- Persist track order when saving and loading projects

## Non-Goals

- Track grouping or folder tracks (separate feature)
- Reordering mixer channels independently of tracks
- Multi-select drag (moving several tracks at once)
