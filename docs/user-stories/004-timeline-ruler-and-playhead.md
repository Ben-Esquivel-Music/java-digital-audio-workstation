---
title: "Timeline Ruler with Bar/Beat Grid and Click-to-Seek Playhead"
labels: ["enhancement", "ui", "arrangement-view", "transport"]
---

# Timeline Ruler with Bar/Beat Grid and Click-to-Seek Playhead

## Motivation

The arrangement view needs a proper timeline ruler at the top showing bar numbers, beat subdivisions, and time markers. Users should be able to click anywhere on the ruler to move the playhead to that position. The current playhead implementation lacks visual polish — it should be a prominent vertical line that spans all tracks and scrolls with the timeline. Additionally, the ruler should display both musical time (bars:beats) and absolute time (minutes:seconds) with a toggle between modes. Without a clear timeline reference, users cannot navigate their session efficiently.

## Goals

- Add a timeline ruler at the top of the arrangement view showing bar numbers and beat subdivisions
- Support musical time (bars:beats:ticks) and absolute time (hh:mm:ss:ms) display modes with a toggle
- Allow clicking on the ruler to reposition the playhead
- Render the playhead as a prominent vertical line spanning all track lanes
- Auto-scroll the arrangement view to follow the playhead during playback
- Show the current tempo and time signature in the ruler area
- Support ruler zoom that adapts subdivision granularity to the current zoom level

## Non-Goals

- Tempo automation or tempo ramps along the timeline
- Markers and locators (separate feature)
- SMPTE timecode display
