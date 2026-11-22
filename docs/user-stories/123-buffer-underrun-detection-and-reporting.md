---
title: "Buffer Underrun Detection, Recovery, and User-Visible xruns Reporting"
labels: ["enhancement", "audio-engine", "performance", "reliability"]
---

# Buffer Underrun Detection, Recovery, and User-Visible xruns Reporting

## Motivation

The real-time audio callback in `AudioEngine` and `DefaultAudioEngineController` assumes every buffer is filled within the deadline, but when a plugin, a GC pause, or a slow disk read blows the budget the engine silently clips or glitches. Every professional DAW — Pro Tools ("DAE errors -6101/-9128"), Reaper ("xruns" counter), Logic ("System overload"), Ableton ("CPU" meter turning red) — surfaces these events as first-class telemetry because dropouts that are not reported are dropouts that are not debugged.

The codebase has the hooks to detect this: `AudioBufferPool` and the render loop know when a buffer was late, the JavaFX transport bar already renders a CPU indicator, and `NotificationManager` exists for user-visible messages. What is missing is a single `XrunDetector` that observes callback timing, classifies overruns, and exposes them through a `TransportStatusBar` counter plus an optional modal when severity crosses a threshold.

## Goals

- Add a `XrunEvent` sealed interface in `com.benesquivelmusic.daw.sdk.audio` with permitted records: `BufferLate(long frameIndex, Duration deadlineMiss)`, `BufferDropped(long frameIndex)`, `GraphOverload(String offendingNodeId, double cpuFraction)`.
- Add `XrunDetector` in `com.benesquivelmusic.daw.core.audio.performance` that wraps the render callback, compares elapsed time against `bufferSize / sampleRate`, and publishes `XrunEvent`s to a `Flow.Publisher<XrunEvent>`.
- Extend `AudioEngineController` with `Flow.Publisher<XrunEvent> xrunEvents()` so the UI can subscribe without polling.
- Add `XrunCounterLabel` to the transport bar (next to CPU meter) showing a rolling 30-second count; clicking opens an `XrunLogDialog` with the last 100 events and offending node IDs.
- When `GraphOverload` fires 3× in 5 seconds, surface a `NotificationManager` warning suggesting larger buffer size and auto-bypass the most-expensive insert as a safety net (reversible via undo).
- Reset the counter on transport stop/start and on buffer-size change via `AudioSettingsDialog`.
- Unit tests simulate late buffers via an injected clock; integration test confirms no underruns during a 60-second render of a representative session.

## Non-Goals

- Deterministic real-time scheduling via OS priority APIs — backend-specific and out of scope here.
- Automatic plugin removal on overload — only bypass, and only with user opt-in.
- Long-term performance telemetry collection to a remote service.

## Note from Story 338

Story 338 built the feeding half and the counter UI of this story, in the shape its studio engineer ruled, which differs from the Goals above:

- **The detector does not wrap the render callback or publish from it.** The render drive hands each rendered block's processing time to `XrunDetector.recordTick`, which only stores plain fields, and `publishPending()` offers the events off the real-time thread; `beginTick`, `Tick` and `reset()` are gone. `AudioEngineController.xrunEvents()` is fed, and no window surface subscribes to it.
- **No `XrunCounterLabel`.** The counter is the session status strip's Xruns cell ("Xruns N"), and its click, Enter or Space opens `XrunLogDialog` ("Xrun Log", the newest 100 entries). An entry names a track, never a node id.
- **What counts.** The number counts observed gaps only: silenced callback periods and render blocks, refused blocks, rendered blocks never played, device periods that found the output buffer empty, and the driver's overload and resync messages. A late block is a log entry and never raises it (story 338, rulings E17, E38 and E72).
- **No rolling 30-second count, and no reset at transport start or stop.** The cell shows the count since its last reset, in the warning accent while it grew in the last 30 seconds; it restarts when a reconfigure rebuilds the detector (a Settings apply, Restart Audio Engine or a driver's reopen), and at the log's Clear, never at Play, Stop or Reconnect (rulings E22 and E71).
- **No `GraphOverload` is published**: `XrunDetector.reportGraphOverload` has no production caller. The CPU budget enforcer's degradations are logged as "Shed" entries that name the track and what was done, and never raise the number (rulings E21 and E42).
- **Not built:** the warning after three overloads in five seconds with the opt-in bypass of the most expensive insert, and the 60-second render test. They are future story [462](future/462-repeated-graph-overloads-raise-a-warning-with-an-opt-in-bypass.md).
