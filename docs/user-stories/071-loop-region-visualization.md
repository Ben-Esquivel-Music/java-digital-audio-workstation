---
title: "Loop Region Visualization and Editing in Arrangement View"
labels: ["enhancement", "ui", "arrangement-view", "transport"]
---

# Loop Region Visualization and Editing in Arrangement View

## Motivation

The `Transport` class fully supports loop playback — it stores `loopStartInBeats`, `loopEndInBeats`, and `isLoopEnabled()`, and the `AudioEngine.renderTracks()` correctly wraps the playhead at loop boundaries during playback. The `TransportController` has a loop toggle button that enables/disables loop mode. However, there is no visual representation of the loop region in the arrangement view. Users can enable loop mode but cannot see where the loop boundaries are, cannot drag them to adjust the loop region, and cannot quickly set a loop region by selecting a range on the timeline ruler. In every professional DAW, the loop region is shown as a colored bar or highlighted zone on the timeline ruler, with draggable left and right locator handles. Without this visualization, users have no way to know or control where the loop starts and ends.

## Goals

- Render the loop region as a semi-transparent colored bar on the timeline ruler between the loop start and loop end beat positions
- Extend the loop region as a faint vertical highlight spanning all track lanes in the arrangement canvas so users can see which section of the timeline is looping
- Show draggable loop-start and loop-end locator handles on the timeline ruler that update `Transport.setLoopStartInBeats()` and `Transport.setLoopEndInBeats()` when dragged
- Allow setting the loop region by Shift-clicking and dragging on the timeline ruler (define start and end in a single gesture)
- Snap loop locators to the grid when snap-to-grid is enabled
- Show the loop start and end positions as beat/bar numbers in a tooltip when hovering over the locators
- Update the loop region visualization in real time during playback as the transport loops
- Ensure the loop region is visible at all zoom levels

## Non-Goals

- Nested or stacked loop regions
- Loop recording (recording continuously across loop cycles into separate takes)
- MIDI-triggered loop start/stop
