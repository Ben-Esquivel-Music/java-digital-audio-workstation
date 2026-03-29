---
title: "Extract AnimationController from MainController"
labels: ["enhancement", "ui", "design", "performance"]
---

# Extract AnimationController from MainController

## Motivation

`MainController` owns a single `AnimationTimer` (`mainAnimTimer`) that drives three
completely different visual effects at ~60 fps: the idle visualization demo (synthetic
spectrum and level-meter data), the transport-state glow/blink on the play and record
buttons, and the elapsed-time display ticker.  Additionally, the button-press scale
animations (`applyButtonPressAnimations`, `applyPressAnimation`) are initialized in
`MainController` but are purely cosmetic and unrelated to project or playback logic.
Bundling ~160 lines of animation bookkeeping inside the main controller makes it harder
to profile, tune, or disable individual animation layers.  A dedicated
`AnimationController` class would encapsulate all frame-by-frame and transition-based
animations, expose simple lifecycle methods (`start`, `stop`), and keep `MainController`
free from raw `AnimationTimer` callbacks and Math.sin trigonometry.

## Goals

- Extract the `mainAnimTimer` field and its `handle(long now)` implementation —
  including the `tickIdleVisualization`, `applyTransportGlow`, and time-ticker branches —
  into a new `AnimationController` class in the `daw-app` module
- Move the idle animation state fields (`idleAnimPhase`, `glowAnimPhase`,
  `timeTickerStartNanos`, `timeTickerRunning`, `timeTickerPausedElapsedNanos`,
  `idleSpectrumBins`) and all related helpers (`refreshTimeDisplay`, `startTimeTicker`,
  `pauseTimeTicker`, `stopTimeTicker`) into `AnimationController`
- Move `applyButtonPressAnimations` and `applyPressAnimation` into `AnimationController`
  (or a closely related `ButtonAnimationHelper`)
- Expose `start()`, `stop()`, `startTimeTicker()`, `pauseTimeTicker()`, and
  `stopTimeTicker()` as the public API so `MainController` never manipulates animation
  state directly
- The refactoring is purely structural — no visible behavior changes, no new features

## Non-Goals

- Adding new animation effects or changing the existing visual behavior
- Moving view-transition animations that live inside individual view classes
  (e.g. `TelemetryView.startAnimation()`)
- Introducing a dedicated animation framework or third-party dependency
- Addressing any other `MainController` responsibilities beyond animation and
  button-press effects
