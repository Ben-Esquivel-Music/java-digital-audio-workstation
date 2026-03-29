---
title: "Wire Track Drag-and-Drop Reordering in Track List Panel"
labels: ["enhancement", "ui", "arrangement-view"]
---

# Wire Track Drag-and-Drop Reordering in Track List Panel

## Motivation

User story 001 describes track drag-and-drop reordering, and the core model fully supports it — `DawProject.moveTrack(track, newIndex)` reorders the track list and synchronizes the mixer channel order. However, the `TrackStripController` that builds and manages track strip HBox nodes in the `trackListPanel` VBox does not attach any JavaFX drag-and-drop event handlers (`onDragDetected`, `onDragOver`, `onDragDropped`) to the track strip nodes. There is no visual drag indicator, no drop target highlighting, and no way for users to reorder tracks by dragging. Users must currently delete and recreate tracks to change their order, which loses clip data and mixer settings.

## Goals

- Attach `onDragDetected` to each track strip's header region so that pressing and dragging initiates a JavaFX drag-and-drop gesture
- Set the drag content to identify the source track (e.g., track ID in the dragboard)
- Show a visual drop indicator (horizontal highlight line) between track strips during drag-over to indicate where the track will land
- On `onDragDropped`, call `DawProject.moveTrack()` to reorder the track in the model, then reorder the track strip nodes in the `trackListPanel` VBox to match
- Wrap the reorder operation in an `UndoableAction` registered with the `UndoManager`
- Update the mixer channel order via `DawProject.moveTrack()` so the mixer view stays synchronized
- Animate the drop with a brief translate/fade transition consistent with the existing track-strip animation style

## Non-Goals

- Multi-select drag (moving several tracks at once)
- Reordering mixer channels independently of tracks
- Drag-and-drop from external sources (file import is a separate feature)
