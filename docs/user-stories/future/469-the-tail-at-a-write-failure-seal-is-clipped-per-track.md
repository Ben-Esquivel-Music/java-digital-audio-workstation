---
title: "The Tail at a Write-Failure Seal Is Clipped per Track"
labels: ["bug", "recording", "reliability"]
---

# The Tail at a Write-Failure Seal Is Clipped per Track

## Motivation

When a take is sealed by anything but a device loss (the person's Stop, disk exhaustion, a write failure), a run of blocks the stream rendered with no captured input that is still open at the seal is written as a counted `input-silence` line, clipped to the frames each file actually holds (story 338, rulings E79 and E90). At a write failure, the block whose write threw is left out of that tail for every track. A track whose own append of that starved block had already succeeded therefore holds one block of silence at the very end of the take that no line marks. Story 338's studio engineer accepted it as a known limit: at most one block of unmarked silence at the very end of a take that a write failure sealed, on tracks whose append of that starved block succeeded, while the write-failure notice already flags the end of that take (ruling E117).

## Goals

- The tail at a write-failure seal is clipped per track, to that track's last frame written (ruling E117).

## Constraints

- The tail stays uncounted only at a device-loss seal, and is clipped to the punch region in a punch take (rulings E76, E87 and E90).
- One event gets one report: a run that ends in an input's declared stop is that stop, not an `input-silence` line (ruling E49).
- No new key and no new field on an existing key of `take.manifest`, and no bump of `manifest-version` (ruling E46).
- The flush thread writes the manifest; nothing is added to the real-time capture path.

## Acceptance Criteria

1. A write that throws after 2 of 3 tracks appended a starved block: the two tracks carry an `input-silence` line covering that block, and the third does not (ruling E117).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- The studio engineer's verification (criterion 1), in `PunchTakeLossesAgainstTheFileTest`'s or `InputStallCaptureTest`'s shape, with a write that fails on the third track's append.
- `StarvedRunsTest`, `TakeManifestTest` and `RecordingPipelineFlushServiceTest` stay green.

## Non-Goals

- Ending a take on a write failure, and its notice: unchanged.

## Technical Notes

- daw-core: `CaptureFlushService` (the seal and the tail), `StarvedRuns`, `FrameSpans`, `TrackCapture`, `TakeManifest.InputSilence`.
- Cross-refs: story 338 (rulings E46, E49, E76, E79, E87, E90 and E117).

## Status

- Deferred
