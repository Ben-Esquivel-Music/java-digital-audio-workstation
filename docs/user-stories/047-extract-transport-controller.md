---
title: "Extract TransportController from MainController"
labels: ["enhancement", "ui", "transport"]
---

# Extract TransportController from MainController

## Motivation

`MainController` owns all transport playback logic: the `onPlay`, `onPause`, `onStop`,
`onRecord`, `onSkipBack`, `onSkipForward`, and `onToggleLoop` FXML handlers, the time-ticker
methods that drive the `HH:MM:SS.s` display, the `updateStatus` helper that refreshes the
status label and button enabled/disabled states, and the `RecordingPipeline` lifecycle
management that creates, starts, and stops recording sessions.  These ~185 lines of
transport-specific logic are entirely self-contained yet buried inside the 3,400+ line
controller file, making transport behavior hard to follow and impossible to unit-test in
isolation.  Extracting them into a dedicated `TransportController` would clarify the
separation of concerns, simplify future transport features (e.g. loop regions, punch-in /
punch-out), and allow each concern to be reviewed or changed independently.

## Goals

- Move the seven FXML-wired transport action handlers (`onPlay`, `onPause`, `onStop`,
  `onRecord`, `onSkipBack`, `onSkipForward`, `onToggleLoop`) into a new
  `TransportController` class in the `daw-app` module
- Include the time-ticker helpers (`startTimeTicker`, `pauseTimeTicker`, `stopTimeTicker`,
  `refreshTimeDisplay`) and the `updateStatus` method in the same class
- Move `RecordingPipeline` lifecycle management (creation, start, stop, clip registration
  in the undo stack) out of `MainController` and into `TransportController`
- Wire the new class into `MainController` via constructor injection of the dependencies
  it needs (project, transport, undoManager, notificationBar, etc.)
- Maintain all existing keyboard shortcut wiring — the keyboard-shortcut registration code
  in `MainController` can simply delegate to methods on `TransportController`
- The refactoring is purely structural — no visible behavior changes, no new features

## Non-Goals

- Redesigning the transport UI or adding new transport controls (loop region handles,
  punch-in markers, etc.)
- Moving the `AnimationTimer` or glow/blink animations (see the dedicated animation issue)
- Touching recording pipeline internals in `daw-core`
- Addressing any other `MainController` responsibilities beyond transport actions and
  time-display management
