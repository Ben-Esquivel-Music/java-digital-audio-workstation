---
title: "Extract ViewNavigationController from MainController"
labels: ["enhancement", "ui", "navigation", "usability"]
---

# Extract ViewNavigationController from MainController

## Motivation

`MainController` currently coordinates four distinct toolbar-interaction areas that all
follow the same pattern — initialize state, wire buttons, update active/inactive styling —
yet none of them are separated from the main class: view navigation (arrangement, mixer,
editor, telemetry, mastering), edit-tool selection (pointer, pencil, eraser, scissors,
glue), snap-to-grid controls (snap toggle, grid-resolution context menu), and zoom controls
(zoom in, zoom out, zoom to fit, Ctrl+scroll).  Together these ~295 lines represent the
entire "what is the user currently looking at and editing" concern.  Extracting them into
a `ViewNavigationController` would give this concern a dedicated home, reduce
`MainController`'s size significantly, and make it easy to add new views or editing modes
(e.g. a video view, a drum-machine view) without touching the main controller at all.

## Goals

- Move `initializeViewNavigation`, `switchView`, and `updateToolbarActiveState` (view
  switching) into a new `ViewNavigationController` class in the `daw-app` module
- Move `initializeEditTools`, `selectEditTool`, `getActiveEditTool`, and
  `updateEditToolActiveState` (edit tool selection) into the same class
- Move `initializeSnapControls`, `onToggleSnap`, `buildGridResolutionContextMenu`,
  `selectGridResolution`, `isSnapEnabled`, `getGridResolution`, `updateSnapButtonStyle`,
  and `syncSnapStateToEditorView` (snap/grid) into the same class
- Move `initializeZoomControls`, `wireScrollZoom`, `onZoomIn`, `onZoomOut`,
  `onZoomToFit`, `updateZoomStatus`, and `getZoomLevel` (zoom) into the same class
- Provide dependencies (rootPane, view-button references, editorView, toolbarStateStore,
  statusBarLabel, etc.) via constructor injection
- Expose simple public methods (`getActiveView()`, `getActiveEditTool()`,
  `isSnapEnabled()`, `getGridResolution()`, `getZoomLevel(DawView)`) for the few places
  in `MainController` that still need to read navigation state
- The refactoring is purely structural — no visible behavior changes, no new features

## Non-Goals

- Changing the set of available views or edit tools
- Implementing new zoom behaviour (e.g. pinch-to-zoom, time-ruler zoom)
- Moving view-implementation classes (`MixerView`, `EditorView`, etc.) — those are
  already separate
- Addressing any other `MainController` responsibilities beyond view switching, edit tools,
  snap/grid, and zoom
