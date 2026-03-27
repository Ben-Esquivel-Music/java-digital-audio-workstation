---
title: "Undo/Redo UI Integration with History Panel"
labels: ["enhancement", "ui", "usability", "undo"]
---

# Undo/Redo UI Integration with History Panel

## Motivation

The `UndoManager` class in the core module provides a functional undo/redo stack, and `UndoableAction` defines the interface for undoable operations. However, the UI integration is limited — Ctrl+Z/Ctrl+Y shortcuts may exist, but there is no visual undo history panel. Professional DAWs show a scrollable list of recent actions (e.g., "Move Clip", "Adjust Volume", "Add Track") that users can click to jump back to any point in history. This makes undo operations more precise and gives users confidence that their changes are tracked. The current implementation also needs more operations wrapped in `UndoableAction` — many user actions (volume changes, track additions, clip moves) may not be undoable yet.

## Goals

- Add an undo history panel accessible from the Edit menu or a sidebar toggle
- Display a scrollable list of recent actions with descriptive labels
- Highlight the current position in the undo history
- Allow clicking an item in the history to undo/redo all actions up to that point
- Wrap all major user operations in `UndoableAction`:
  - Track add/remove/rename/reorder
  - Clip add/remove/move/split/trim
  - Volume/pan/mute/solo changes
  - Effect insert/remove/reorder
- Show undo/redo buttons in the toolbar with Ctrl+Z / Ctrl+Shift+Z shortcuts
- Display the action name in a tooltip on the undo/redo buttons (e.g., "Undo: Move Clip")

## Non-Goals

- Infinite undo history (existing cap of 100 actions is sufficient)
- Branching undo history (non-linear undo)
- Undo history persistence across project close/reopen
