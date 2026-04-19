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
