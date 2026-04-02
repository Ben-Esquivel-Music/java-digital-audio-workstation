---
title: "Time Selection Range Visualization and Interaction in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "editing"]
---

# Time Selection Range Visualization and Interaction in Arrangement View

## Motivation

The `SelectionModel` class stores a time selection range (`startBeat`, `endBeat`) and a clip selection set, and several editing operations depend on it — "Trim to Selection" and "Crop" in the track context menu check `selectionModel.hasSelection()`, and `StemExporter` can export a selected time range. However, the arrangement view does not render any visual indication of the time selection. Users cannot see the selected range, cannot create a selection by click-dragging on an empty area of the arrangement, and have no visual feedback about which portion of the timeline is selected. In professional DAWs, the time selection is shown as a semi-transparent highlighted region spanning all tracks, with draggable start/end handles. Without visualization, the time selection is invisible and the features that depend on it (Trim to Selection, Crop, export selection) are effectively unusable.

## Goals

- Render the active time selection as a semi-transparent highlighted region spanning all visible track lanes in the arrangement canvas
- Allow creating a time selection by clicking and dragging on empty space in the arrangement view when the Pointer tool is active (click sets start beat, drag sets end beat)
- Show draggable handles at the left and right edges of the selection for fine-tuning the selection boundaries
- Snap selection boundaries to the grid when snap-to-grid is enabled
- Display the selection range (start beat, end beat, duration) in the status bar or as a tooltip
- Clear the selection when clicking outside the selected region (without Shift held)
- Support extending the selection by Shift-clicking on a second position
- Update the `SelectionModel` in real time so that dependent features (Trim to Selection, Crop, export range) reflect the visual selection

## Non-Goals

- Per-track selection (selecting different time ranges on different tracks)
- Selection grouping (saving named selections for recall)
- Selection-based playback (playing only the selected region)
