---
title: "Lane Folding and Collapse for Automation and MIDI Lanes"
labels: ["enhancement", "ui", "arrangement"]
---

# Lane Folding and Collapse for Automation and MIDI Lanes

## Motivation

A 40-track session with automation lanes on half the tracks rapidly consumes vertical real estate and forces the user to scroll through mostly-empty lanes to find anything. Every DAW lets the user fold lanes: Pro Tools' triangle disclosure on track headers, Logic's "track folders," Ableton's `D` shortcut to collapse a track. Without this, large sessions are ergonomically painful.

`TrackLaneRenderer` and `AutomationLaneRenderer` already compose vertically; this story adds a per-track fold state that collapses automation lanes, take lanes, and MIDI lanes into a single line while preserving data.

## Goals

- Add `TrackFoldState` record on `Track`: `record TrackFoldState(boolean automationFolded, boolean takesFolded, boolean midiFolded, double headerHeightOverride)`.
- Disclosure triangles on the track header next to each foldable lane group; click toggles the corresponding `*Folded` flag.
- When folded, the renderer collapses the lane's height to 0 but keeps a 3 px summary strip showing "N lanes folded" so the user knows data exists.
- Session-level shortcut: `Shift+F` toggles fold for the focused track; `Alt+Shift+F` toggles for every selected track.
- A master "Fold all automation" toolbar button for quick top-down overview.
- Layout reflows arrangement content sample-accurately with no flash or layout jank.
- Persist `TrackFoldState` via `ProjectSerializer`.
- Tests: toggling fold preserves contained clip/automation data bit-exact; the layout heights match the configured fold state.

## Non-Goals

- Folding tracks into folder tracks (that is story 012).
- Hiding tracks entirely (use mute/solo or track filters — a separate ergonomics story).
- Persistent "only show this track" focus mode.
