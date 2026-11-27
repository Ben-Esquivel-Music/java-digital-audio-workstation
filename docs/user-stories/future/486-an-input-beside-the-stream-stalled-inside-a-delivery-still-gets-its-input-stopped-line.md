---
title: "An Input Beside the Stream Stalled Inside a Delivery Still Gets Its Input-Stopped Line"
labels: ["bug", "recording", "audio-engine", "reliability"]
---

# An Input Beside the Stream Stalled Inside a Delivery Still Gets Its Input-Stopped Line

## Motivation

When the engine declares an input opened beside the stream stalled during an audio take, story 338 gives every track that records it an `input-stopped` line in `take.manifest` and one warning, and the take keeps recording (story 338, rulings E13 and E14). The line is written only when the capture thread applies a zero-width block of that input (`CaptureFlushService.flagUnavailable`), by the take's seal for such blocks a full capture ring dropped, or was left holding, after the last block the take kept from that ring (story 338, rulings E277 and E303), or, when the input delivers again in the same take after such dropped blocks, by the block with which it delivers (ruling E302), and for an input beside the stream only the render thread produces such a block: it clocks that input's ring with silence once the input's source state in `AudioEngine` reads clocked-silent.

A traced suspicion that nobody has proved: the declaration hands the source off (`AudioEngine.declareInputStalled`, the source states' `handOff`), and when the input's own thread is inside a delivery at that moment, `handOff` moves the source from delivering to hand-off-requested; only that delivery's release completes the move to clocked-silent. A delivery that is hung, the very case a stall verdict exists for, never releases, so the render thread never clocks that input and the take may get no `input-stopped` line for it, against ruling E13. Whether it does could not be decided by reading the code (story 338's review, the studio engineer's consult 45 and its fact check).

## Goals

- When the engine declares an input opened beside the stream stalled during an audio take, every track that records that input gets its `input-stopped` line, at the take frame after the last block that input delivered, and its warning, and holds silence from that line to the end of the take, aligned with the other tracks, also when that input's own thread is inside a delivery at the declaration and never returns from it; if that delivery returns later, its block is not recorded and gets no `gap=` line and no count (story 338, rulings E13, E14, E15 and E207).

## Constraints

- No new words; `input-stopped` keeps its fields and `manifest-version` is not bumped (rulings E46 and E50).
- The take keeps recording, and every other track's audio is unchanged, as story 338 builds it for an input beside the stream that ends mid-take (`MultichannelInputCaptureStory326Test.terminatedSiblingInputKeepsClockedSilenceUnavailableAndFlagged`).
- One event gets one report (ruling E49).
- The real-time side records only: no lock, allocation, logging or publisher on a device callback or the render drive.

## Acceptance Criteria

1. During an audio take, a test holds an input beside the stream inside its delivery, declares that input stalled, and stops the take: the sealed manifest holds that track's `input-stopped` line at the take frame after the last block that input delivered, the warning is given once, that track holds silence from that line to the end of the take, aligned with the other tracks, and the other tracks' files are unchanged.
2. The same without a held delivery gives the same line, as today.
3. The same as criterion 1, with the held delivery released after the declaration: its block is not in that track's file, and the take gets no `gap=` line and no count in its dropouts for it.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- Criteria 1 and 3 hold the delivery deterministically, with no timed wait, and release it in a `finally`.
- `InputStallCaptureTest`, `MultichannelInputCaptureStory326Test`, `AudioEngineInputStallTest`, `AudioEngineInputDeviceCloseTest` and `RealTimeSafeContractTest` stay green.

## Non-Goals

- The silence after an input's declared stop and the block the stream's own input can deliver after it: built in story 338 (rulings E276, E277 and E281 to E284).

## Technical Notes

- daw-core: `AudioEngine` (`declareInputStalled`, the source states' `claim`, `release` and `handOff`, `AdditionalInputSubscriber`), `CaptureCallback`, `CaptureFlushService.flagUnavailable`.
- daw-app: `EngineHeartbeatWatchdog`, `DefaultAudioEngineController` (the stall verdict for a capture input).
- Cross-refs: story 338 (rulings E13, E14, E46, E49, E50, E276, E277, E302 and E303).

## Status

- Deferred
