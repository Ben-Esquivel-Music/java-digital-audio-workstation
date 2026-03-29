---
title: "Extract TrackStripController from MainController"
labels: ["enhancement", "ui", "usability"]
---

# Extract TrackStripController from MainController

## Motivation

`MainController` currently contains approximately 910 lines of code dedicated solely to
building and managing individual track strips in the arrangement track list.  The three
main methods — `addTrackToUI`, `buildTrackContextMenu`, and `startTrackRename` — together
with the `midiInstrumentIcon` helper reach a combined line count larger than many complete
classes in the codebase.  Every time a new track-level feature is added (new context-menu
action, new per-track control, new animation) the already-massive `MainController` grows
further, making it increasingly difficult to navigate, review, and test.  Isolating this
responsibility into a dedicated `TrackStripController` would make track-UI logic
independently testable, easier to extend, and simpler to review in pull requests.

## Goals

- Move `addTrackToUI`, `buildTrackContextMenu`, `startTrackRename`, and
  `midiInstrumentIcon` out of `MainController` and into a new
  `TrackStripController` class (or equivalent package-private helper) in the
  `daw-app` module
- `TrackStripController` receives the dependencies it needs (project, undoManager,
  mixerView, notificationBar, statusBarLabel, etc.) via constructor injection so it
  remains unit-testable without a live JavaFX scene
- `MainController` delegates all track-strip construction and context-menu operations
  to `TrackStripController`, keeping its own body focused on top-level coordination
- The refactoring is purely structural — no visible behavior changes, no new features

## Non-Goals

- Changing the visual appearance or interaction model of track strips
- Introducing a new design pattern (MVC, MVP, etc.) beyond what is already in place
- Moving audio-device I/O enumeration logic (that belongs to a separate audio-routing layer)
- Addressing any other `MainController` responsibilities beyond track-strip construction
