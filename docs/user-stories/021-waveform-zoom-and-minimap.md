---
title: "Waveform Zoom and Scroll with Minimap Navigation"
labels: ["enhancement", "ui", "arrangement-view", "navigation"]
---

# Waveform Zoom and Scroll with Minimap Navigation

## Motivation

The `ZoomLevel` class exists for managing zoom state, but the arrangement view's zoom and scroll behavior is rudimentary. Professional DAWs provide smooth horizontal and vertical zoom (Ctrl+scroll to zoom time, Alt+scroll to zoom track height), a minimap showing the entire session with a viewport indicator, and pinch-to-zoom on trackpads. The current view becomes unusable for sessions longer than a few bars because there's no efficient way to navigate to different parts of the timeline or see the overall session structure at a glance.

## Goals

- Support smooth horizontal zoom with Ctrl+mouse scroll or pinch gesture
- Support vertical zoom (track height) with Alt+mouse scroll
- Add a session minimap/overview bar at the top of the arrangement showing all tracks as thin colored bars
- Show a viewport rectangle on the minimap that can be dragged to navigate
- Support keyboard zoom (+ and - keys, Ctrl+0 to fit all)
- Maintain zoom center at the mouse cursor position during zoom
- Preserve zoom level and scroll position per-project when saving
- Show zoom level percentage in the status bar

## Non-Goals

- Waveform amplitude zoom (vertical waveform scaling independent of track height)
- Separate zoom controls for the editor view (reuse the same zoom infrastructure)
- Infinite scroll beyond the project bounds
