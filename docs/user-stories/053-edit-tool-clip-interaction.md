---
title: "Wire Edit Tools to Clip Interactions in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Wire Edit Tools to Clip Interactions in Arrangement View

## Motivation

The toolbar exposes five edit tools — Pointer, Pencil, Eraser, Scissors, and Glue — with buttons, keyboard shortcuts, and active-state styling all correctly wired via `ViewNavigationController` and `KeyBindingManager`. However, selecting any tool has no effect on clip interaction behavior because the arrangement view does not process mouse events against clips. The core model already supports the operations these tools imply: `AudioClip.splitAt()` is implemented, `SplitClipAction` provides undo support, clips can be moved and resized via their setters, and the `UndoManager` is available for all actions. What is missing is the event-handling layer that translates mouse clicks and drags in the arrangement view into model operations based on the active tool.

## Goals

- **Pointer tool**: click to select a clip; drag to move a clip to a new beat position or a different track; Shift-click to add to selection
- **Pencil tool**: click on an empty area of a track lane to create a new empty clip at that beat position with a default duration
- **Eraser tool**: click on a clip to delete it from the track (undoable via `UndoManager`)
- **Scissors tool**: click on a clip at a beat position to split it into two clips using `AudioClip.splitAt()` and `SplitClipAction`
- **Glue tool**: click between two adjacent clips on the same track to merge them into one clip (undoable)
- All tool operations must be undoable and update the arrangement view immediately
- Show a cursor change appropriate to the active tool when hovering over the arrangement canvas

## Non-Goals

- Clip trimming via edge handles (covered by the existing clip splitting/trimming story)
- Automation point editing with tools (separate feature)
- Multi-clip selection rubber-band box
