---
title: "Playhead Visual Rendering and Click-to-Seek in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "transport"]
---

# Playhead Visual Rendering and Click-to-Seek in Arrangement View

## Motivation

User story 004 describes a timeline ruler with a click-to-seek playhead, and a `TimelineRuler` class exists in the UI module. However, the arrangement view is currently a placeholder, so there is no visible playhead line spanning the track lanes. The transport advances its internal beat position during playback, and the time display in the toolbar updates, but users have no visual feedback in the arrangement about where playback is occurring. In every DAW, the playhead is a prominent vertical line that moves across the timeline in real time during playback and can be repositioned by clicking on the ruler or the arrangement background. Without it, users cannot visually follow playback position or quickly navigate to a specific point in their session.

## Goals

- Render the playhead as a bright, high-contrast vertical line that spans from the timeline ruler down through all visible track lanes
- Animate the playhead position in real time during playback, synchronized with the transport's current beat position
- Allow clicking on the timeline ruler area to reposition the playhead (set the transport position)
- Allow clicking on empty arrangement background to reposition the playhead when the Pointer tool is active
- Auto-scroll the arrangement view horizontally to keep the playhead visible during playback
- Snap the playhead to the grid when snap-to-grid is enabled
- Show the playhead position in both the ruler and the toolbar time display simultaneously

## Non-Goals

- Playhead rendering in the mixer or mastering views
- Loop region markers (separate feature)
- SMPTE timecode synchronization
