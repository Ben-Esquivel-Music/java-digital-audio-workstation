---
title: "Captured Input Survives a Render Fault"
labels: ["bug", "recording", "audio-engine", "real-time", "reliability"]
---

# Captured Input Survives a Render Fault

## Motivation

While a take records, the engine hands each block's input to the take inside `RenderPipeline.renderBlock`, and only after the block has been mixed: the mix-down (`mixer.mixDownInstrumented(…)` or `mixer.mixDown(…)`) runs first, then master processing, and only then `recordingCallback.onAudioCaptured(inputBuffer != null ? inputBuffer : mixBuffer, numFrames)`. A throw from the playback side of that block — a track's insert, a send, the master chain — leaves `renderBlock` before the capture call, so the take receives nothing for that block although the input was there.

Story 337 made such a throw survivable and visible (its rulings E8 and E9): the render drive records `audio.render`, sinks a block of silence and keeps running, and after one second of consecutive faulted blocks it halts honestly and the take is ended through the normal Stop, keeping every block captured before the halt (ruling E11). But in every faulted block before that, the performer's input is lost from the take, though nothing about the input itself failed. A performer who keeps playing through a misbehaving plugin loses those blocks, and the user is told only that "a block was played as silence". Take integrity is outside story 324's landed scope and outside story 337's channel, so story 337 left it to this story (story 337's Resolution, "Deferred").

## Goals

- **A playback-side fault does not cost the take its input.** When the block's playback processing throws, the input the engine received for that block still reaches the take's capture callback, once, in order, at its place in the take's timeline.
- **Capture stays real-time safe.** The change adds no lock, publisher, logging or allocation to the render path, and the capture callback is still entered at most once per block.
- **What the user is told stays true.** The render-fault notice keeps saying a block of playback was silenced; it does not claim the take lost audio when it did not.

## Acceptance Criteria

1. With a take recording, a block whose playback processing throws still delivers that block's input to the take.
2. The take's audio for such a block equals the input the engine received, sample for sample, at the right position.
3. A block whose capture itself fails is unchanged by this story: it is counted as today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- An engine-level test, recording through the real pipeline on a mock backend: an insert that throws on chosen blocks; the take's file holds the injected input for every block, the faulted ones included, and the render drive counts `audio.render` for each faulted block.
- The same with the insert throwing on every block until the drive halts: the take holds every block of input delivered before the halt.
- The capture-path sentinel (`RealTimeSafeContractTest`, invariant 8) stays green, and its root-pinning still finds exactly one capture call reachable from the render block.

## Non-Goals

- How a render fault is surfaced and when the drive halts: story 337.
- The capture ring's own overflow, truncation and write-failure counts: story 324, with their visibility in story 339 and the dropout counts in story 338.
- Recording through a backend whose capture does not go through `renderBlock`.

## Technical Notes

- daw-core, `core.audio.RenderPipeline.renderBlock`: the mix-down branch (`mixDownInstrumented` / `mixDown`), master processing, then the capture block (`recordingCaptureActive = true; try { recordingCallback.onAudioCaptured(…); } finally { … }`). `RenderPipeline.renderOffline` shares the block path.
- daw-core, `core.recording.CaptureCallback.onAudioCaptured` (`@RealTimeSafe`; `fillBlock` then `publishAndLeave`), the producer side of `CaptureRing`.
- daw-core, `core.audio.EngineStreamPump.renderOneBlock`: a throw from `processBlock` is recorded as `audio.render` with its streak, and `haltThreshold` consecutive faults end the drive (story 337, rulings E8 to E10).
- Recording Reliability book §5.1 (the capture path's real-time rules) and `RealTimeSafeContractTest`'s capture-path walk (`CAPTURE_PATH_FOLLOWED`, the root pinning).
- Skills: `dawg-annotations-reflection` "### Changing the capture path (the recording callback)"; `studio-recording` "## Session decisions" (dropout detection and recovery for a take).
- Cross-refs: story 337 (rulings E8, E9, E11 and E47, and its Resolution's "Deferred" list), story 324 (the capture path's real-time safety), story 338 (dropout counts during a take), story 339 (the capture warnings' visibility).

## Status

- Deferred
