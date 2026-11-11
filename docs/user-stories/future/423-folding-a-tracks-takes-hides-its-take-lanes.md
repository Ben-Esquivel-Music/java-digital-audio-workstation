---
title: "Folding a Track's Takes Hides Its Take Lanes"
labels: ["bug", "ui", "recording", "comping"]
---

# Folding a Track's Takes Hides Its Take Lanes

## Motivation

A track's fold state has three lane groups — automation, takes and MIDI (`TrackFoldState`: `automationFolded`, `takesFolded`, `midiFolded`) — and the take fold is a saved fact: `ProjectSerializer` writes `takes-folded` on each track and `ProjectDeserializer` reads it. `ArrangementCanvas.toggleAllFoldsForTrack`, whose Javadoc names the `Shift+F` per-track shortcut, sets all three.

Nothing draws the take fold. `ArrangementCanvas.takeLanesHeight` gives a track `laneCount × TAKE_LANE_HEIGHT` whenever its comping is active (`TakeComping.isActive()`), whatever `takesFolded` says, and no code in daw-app reads `takesFolded()`. So folding a track's takes changes a saved flag and nothing on screen, while folding its automation does collapse the automation lane.

Story 334 restores take lanes at open, so a reopened project now shows every track's lanes with no way to fold them. Its studio ruling E13 keeps `takes-folded` as saved view state and leaves the renderer to this story.

## Goals

- **A track whose takes are folded shows no take lanes**: its slot is the main lane's height, and its take lanes neither draw nor take pointer input.
- **Unfolding shows them again**, with their comp regions, as before.
- **The fold toggles from the track**, as the automation fold does, and the existing all-groups toggle includes it.

## Acceptance Criteria

1. Fold a track's takes: its slot shrinks by its take lanes' height, and the tracks below move up.
2. Unfold: the lanes and their region overlays are drawn again.
3. A reopened project whose track was saved with `takes-folded="true"` opens with that track's lanes hidden.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- `ArrangementCanvas` layout with a three-lane track, folded and unfolded: the slot heights and the lane Y positions follow.
- A reopen of a file with `takes-folded="true"`: the canvas lays the track out without its lanes (as `RestoredTakeLanesCanvasTest` lays restored lanes out).
- Fault check, run once, turning a test red: `takeLanesHeight` ignoring the fold again.

## Non-Goals

- Lockstep comping (future story 422).

## Technical Notes

- Ruling: studio engineer, story 334, E13 ("FUTURE: Folding a track's takes hides its take lanes: `takes-folded` round-trips but no renderer reads it").
- Code locators: `TrackFoldState`, `ArrangementCanvas.takeLanesHeight`, `toggleAllFoldsForTrack`, `toggleAutomationFold` (the precedent), `TAKE_LANE_HEIGHT`; `ProjectSerializer` / `ProjectDeserializer` (`takes-folded`); test `RestoredTakeLanesCanvasTest`.
- Cross-refs: story 328 (take lanes on the canvas), story 334 (take persistence).

## Status

- Deferred
