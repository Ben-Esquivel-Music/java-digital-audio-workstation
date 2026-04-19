---
title: "Per-Clip Gain Envelope (Breakpoint Automation Inside the Clip)"
labels: ["enhancement", "editing", "automation"]
---

# Per-Clip Gain Envelope (Breakpoint Automation Inside the Clip)

## Motivation

Reducing the volume of a single breath inside a vocal take without affecting the surrounding phrase requires either (a) splitting the clip, applying a gain change, and rejoining — destructive and clip-boundary-polluting — or (b) drawing track automation, which scales the whole channel and interacts poorly with track compression. Pro Tools has "Clip Gain," Reaper has "clip gain envelope," Studio One has "Gain Envelope," and everyone uses it dozens of times per session. It is the most ergonomic tool for volume rides when the rider is shorter than the clip.

`AudioClip` has the hook for it (clip-local metadata), `AutomationLaneRenderer` has the rendering knowledge, and the `RenderPipeline` applies per-clip gain today via a single `clipGain` scalar. Extending that scalar to an envelope is localized.

## Goals

- Add `ClipGainEnvelope` record in `com.benesquivelmusic.daw.sdk.audio`: `record ClipGainEnvelope(List<BreakpointDb> breakpoints)` where `BreakpointDb` is `record BreakpointDb(long frameOffsetInClip, double dbGain, CurveShape curve)`.
- Extend `AudioClip` with `Optional<ClipGainEnvelope> gainEnvelope()`; when absent, behavior matches today (single `clipGain`).
- Render the envelope as a second polyline on top of the clip waveform with drag-to-add / click-to-delete breakpoints (mirroring `AutomationLaneRenderer` interaction).
- `RenderPipeline` evaluates the envelope per render block and applies the sample-accurate gain factor to the clip's output buffer.
- Supply a "Normalize to -X dB" clip-gain action that analyzes peak level and adjusts the envelope's start breakpoint to target the specified dB.
- Edits produce `SetClipGainEnvelopeAction` undo records; pre-existing `clipGain` is migrated lazily into a one-breakpoint envelope at load time.
- Persist the envelope via `ProjectSerializer`.
- Tests: sample-accurate gain application at each breakpoint; curves (linear, exponential, S-curve) produce the mathematically expected values; undo restores prior envelope.

## Non-Goals

- Cross-clip envelopes (each envelope is clip-scoped).
- Automation of clip *pan* via clip envelope (track-pan automation remains the channel).
- MIDI-clip velocity envelope (that is a note-level attribute handled in the MIDI editor).
