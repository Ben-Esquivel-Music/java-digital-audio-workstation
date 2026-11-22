---
title: "Input Blocks Lost Before the Capture Ring Are Marked at Their Take Frame"
labels: ["feature", "recording", "audio-engine", "reliability"]
---

# Input Blocks Lost Before the Capture Ring Are Marked at Their Take Frame

## Motivation

Captured input can be lost before it reaches a capture ring: a full input ring on the backend's side, or the render pump's full input queue. Story 338 counts every such loss into the take's dropout figures, "Take dropouts K" in the session status strip and "B blocks lost" in the xrun log (story 338, ruling E45): `AudioEngine.capturedInputBlocksLost()` sums the blocks the render pump's input queue refused and the `BackendDropoutCounters.inputBlocksDropped()` of the stream's backend and of every input opened beside it, and the record coordinator baselines it when a take starts. Story 338 does not mark these losses in `take.manifest`, because no frame is known for them: exact frames need a sequence on every delivered block, across daw-sdk and every backend (ruling E44). Two consequences are recorded limits of story 338:

- In a punch take these losses are counted over the whole time the take recorded, pre-roll and the time after punch-out included, so Take dropouts can be higher than the losses in the file (ruling E86 (b)).
- One input stall can count its blocks twice: once as an input-ring overflow and once as the starved blocks the render drive filled with silence (ruling E105).

## Goals

- Every input block lost before the capture ring during a take is marked in `take.manifest` as a `gap=` line at its take frame (story 338, rulings E25 and E46): such a loss removes frames from the file, so later audio moves earlier, which is what `gap=` means.
- Once these losses carry a frame, only those inside the punch region, and inside the region's lap in a loop take, are counted into Take dropouts (ruling E86 (b)).
- A lost input block whose timeline position was filled by a starved block counts once, and the starved block, which has the frame, is the one counted (ruling E105).

## Constraints

- Never a frame taken from the block at which the counter's growth was noticed: that frame is wrong (ruling E44).
- `gap=` keeps its fields, and `manifest-version` is not bumped; zero-filled frames are never written as `gap=` lines (ruling E46).
- Never a fix that makes either loss of ruling E105 count 0.
- The real-time side records only: no lock, no allocation, no logging and no publisher on a callback or the render drive.
- The lap identity of every frame-carrying manifest line is future story 466's; this story's lines follow whatever it rules.

## Acceptance Criteria

1. A block lost before the ring during a take gives one `gap=` line whose start frame is the take frame the lost block would have filled.
2. In a punch take, a block lost in the pre-roll and one lost inside the region give Take dropouts 1.
3. An input stall that overflows the ring by B blocks over B starved periods gives Take dropouts B.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A test backend drops input blocks before the ring at known frames: the manifest holds one `gap=` line per loss at that frame.
- A punch take in which the test backend drops input blocks before the rings once in the pre-roll and once inside the region: Take dropouts counts only the block inside the region (ruling E86 (b)).
- A test backend stalls the input drain so that the ring overflows by B blocks over B starved periods: Take dropouts = B (ruling E105).
- `TakeManifestTest`, `InputStallCaptureTest`, `FileLossCountsTest`, `TakeDropoutTallyTest` and `RealTimeSafeContractTest` stay green.

## Non-Goals

- The marks of starved blocks, stops and clock events: story 338 built them (`input-silence`, `input-stopped`, `clock`).
- The lap a frame-carrying line belongs to: future story 466.

## Technical Notes

- daw-sdk: `BackendDropoutCounters.inputBlocksDropped()`, `AudioBackendSupport`, `AsioBufferSwitchShim`, `MockAudioBackend`.
- daw-core: `AudioEngine.capturedInputBlocksLost()`, `EngineStreamPump`, `CallbackBackendAdapter`, `CaptureFlushService`, `FileLossCounts`, `TakeManifest`.
- daw-app: `TakeDropoutTally`, `TakeDropouts`, `RecordCoordinator`.
- Cross-refs: story 338 (rulings E25, E44 to E46, E86 and E105).

## Status

- Deferred
