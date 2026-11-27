---
title: "An Error From the Capture Thread's Warning Sink Never Skips the Take's Seal"
labels: ["bug", "recording", "reliability"]
---

# An Error From the Capture Thread's Warning Sink Never Skips the Take's Seal

## Motivation

When a write fails, or the capture thread's own loop fails, `CaptureFlushService` warns through the pipeline's warning sink and then seals the take early, so that everything captured so far is kept and recoverable (the Recording Reliability book's disk-headroom and failure paragraphs; story 323). In both places the warning comes first: `failAndSeal` calls `warn("Recording stopped early — …")` and the run loop's catch calls `warn("Recording stopped unexpectedly — …")`, each before the `try` that calls `sealEarly`. `warn` contains only a `RuntimeException` the sink throws, so an `Error` the sink throws leaves that method before its seal. At a loop failure nothing after that seals the take: its lanes and its final manifest are not sealed by the early seal, and its early-seal signal is not completed. At a write failure the seal is left to the run loop's catch, whose own warning comes first too. Since story 338's closing change sets (ruling E274) every early-seal path, the disk headroom floor's included, first gives the pass's ring-overflow warning (`lineThePassBeforeTheEarlySeal`), before its own warning and its seal, so an `Error` from that warning skips them too. The same `Error` from the ring-overflow warning that the look ending each pass gives (`lookAtTheRing`, after it has written that pass's `gap=` lines) leaves those lines written with the drain's accounting of the ring's drops (`noteOverflow`, after the warning) not advanced. This code is older than story 338, apart from the order its closing change sets gave the warnings; its review round found it (story 338's review of the capture-loss fixes).

## Goals

- An `Error` thrown by the capture thread's warning sink never skips what the capture thread does after that warning: after a write failure or a failure of the capture thread's own loop the take is still sealed early as when the sink returns normally, every lane sealed and the final `take.manifest` written with `seal-status=aborted` and its `sealed-by` line, and the record coordinator still stops the take and reports its early seal, so the transport never goes on showing a take that records nothing; and a ring overflow whose warning the sink threw on keeps its `gap=` line, its place in `overflow-blocks` and its count in the take's dropouts, and the take's Stop still seals the take (story 338, ruling E263).

## Constraints

- No new words: the warnings, the take's ERROR and the manifest's `seal-status` and `sealed-by` lines are unchanged, and `manifest-version` is not bumped (story 338, ruling E46).
- A take sealed early keeps story 338's rule for what counts after the early seal (rulings E263 and E274).
- The capture thread's other steps keep their order.

## Acceptance Criteria

1. A warning sink that throws an `Error` at the write-failure warning: the take is sealed early, every lane is sealed, the final manifest reads `seal-status=aborted` and `sealed-by=write-failure`, the early-seal signal completes, and the record coordinator stops the take and reports its early seal, so the transport no longer shows the take recording.
2. The same at the loop-failure warning.
3. A warning sink that throws an `Error` at a ring-overflow warning: the drop keeps its `gap=` line, its place in `overflow-blocks` and its count in the take's dropouts, `awaitFlushed` returns, and the take's Stop then seals the take.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- One test per acceptance criterion, its fault driven through `CaptureFlushService`'s existing fault seams, with the record coordinator running the take where the criterion names it.
- `CaptureFlushServiceTest`, `DiskExhaustionSealsCleanlyContractTest`, `EarlySealSignalContractTest`, `RecordingPipelineFlushServiceTest` and `StopFenceTailIntegrityContractTest` stay green.

## Non-Goals

- What the application shows for the `Error` itself: story 336's failure funnel.

## Technical Notes

- daw-core: `CaptureFlushService` (`warn`, `failAndSeal`, the run loop's catch, the headroom branch of `applyBlock`, `lineThePassBeforeTheEarlySeal`, `lookAtTheRing`, `sealEarly`, `noteOverflow`, `awaitFlushed`), `RecordingPipeline.earlySeal()`.
- Cross-refs: stories 323 and 336; story 338 (rulings E46, E263, E273 and E274).

## Status

- Deferred
