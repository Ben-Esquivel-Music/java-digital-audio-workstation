---
title: "Automation Lane UI Rendering and Breakpoint Editing"
labels: ["enhancement", "ui", "arrangement-view", "automation"]
---

# Automation Lane UI Rendering and Breakpoint Editing

## Motivation

User story 003 describes per-track automation lanes with envelope editing. The core module has `AddAutomationPointAction`, `MoveAutomationPointAction`, `RemoveAutomationPointAction`, and `InterpolationMode` — a complete undo-aware automation point data model. However, there is no visual rendering of automation lanes in the arrangement view. No collapsible automation lane appears below any track, no envelope line is drawn, and no breakpoint nodes can be clicked or dragged. The automation data model exists but is completely invisible to the user. In every professional DAW, automation lanes are drawn as semi-transparent overlays or collapsible sub-lanes with a colored envelope line connecting breakpoint nodes, and users can click to add points, drag to move them, and right-click to delete them.

## Goals

- Add a collapsible automation lane panel below each track lane in the arrangement view
- Provide a toggle button on each track strip to show/hide its automation lane
- Render the automation envelope as a colored polyline connecting breakpoint nodes, using the `InterpolationMode` (linear or curved) to determine the line shape
- Allow clicking on the automation lane to add a new breakpoint at that beat position and value, using `AddAutomationPointAction` for undo support
- Allow dragging breakpoint nodes to move them, using `MoveAutomationPointAction` for undo support
- Allow right-clicking or using the Eraser tool on a breakpoint to delete it, using `RemoveAutomationPointAction`
- Provide a parameter selector dropdown on each automation lane to choose which parameter to automate (volume, pan, mute, send level)
- Synchronize automation lane scrolling and zoom with the arrangement timeline

## Non-Goals

- Plugin parameter automation (requires plugin parameter discovery)
- Automation recording from controller input (write/latch/touch modes)
- Freehand pencil drawing of automation curves
