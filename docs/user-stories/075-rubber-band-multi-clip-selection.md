---
title: "Rubber-Band Multi-Clip Selection in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Rubber-Band Multi-Clip Selection in Arrangement View

## Motivation

The `SelectionModel` fully supports multi-clip selection — it maintains a `Map<AudioClip, Track>` of selected clips and provides `selectClip()`, `toggleClipSelection()`, and `selectClipsInRegion()` methods. The `ClipInteractionController` wires single-click selection and Shift-click toggle selection. However, there is no rubber-band (marquee) selection gesture — users cannot click on an empty area and drag to draw a selection rectangle that selects all clips within that region. This is a fundamental interaction in every professional DAW and general creative application (Pro Tools, Logic Pro, Ableton Live, Photoshop, Figma). Without rubber-band selection, users must individually click or Shift-click each clip they want to include in a multi-clip operation (move, delete, copy, cut), which is tedious in sessions with many small clips.

## Goals

- When the Pointer tool is active and the user clicks on empty space in the arrangement canvas (not on a clip), initiate a rubber-band selection by tracking the mouse drag
- Render a semi-transparent selection rectangle as the user drags, with a dashed or highlighted border
- On mouse release, select all clips (audio and MIDI) whose rendered rectangles intersect the rubber-band region, using `SelectionModel.selectClipsInRegion()`
- Highlight all selected clips with a visual indicator (e.g., brighter border, selection overlay color) that is distinct from the hover highlight
- Support additive selection: holding Shift while rubber-band selecting adds to the existing selection rather than replacing it
- Ensure that selected clips can then be operated on as a group — move, delete, copy, cut, duplicate all apply to the full selection
- Clear the selection when clicking on empty space without dragging (and without Shift)

## Non-Goals

- Lasso (freeform) selection
- Selection across different arrangement view tabs or editor views
- Selection persistence across undo/redo operations
