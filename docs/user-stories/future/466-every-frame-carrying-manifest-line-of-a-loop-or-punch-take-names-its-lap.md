---
title: "Every Frame-Carrying Manifest Line of a Loop or Punch Take Names Its Lap"
labels: ["feature", "recording", "persistence", "reliability"]
---

# Every Frame-Carrying Manifest Line of a Loop or Punch Take Names Its Lap

## Motivation

A loop take records one lane per lap: each track's `segment=<trackId>|<lane>|<index>|…` entries in `take.manifest` list the files of each lane. The lines that mark what happened in the take carry transport frames at the take's rate: `gap=` (an overflow or truncation episode), and the three keys story 338 added, `input-stopped=<trackId>|<stopFrame>|<resumeFrame>`, `input-silence=<trackId>|<startFrame>|<frames>` and `clock=<unlocked|locked|resync>|<frame>`. In a loop take those frames are normalised into the loop range: `input-silence` is split at the loop end, and `input-stopped` is never split, its frames lying inside the loop range or in lap 1's pre-roll, with a resume frame smaller than the stop frame meaning that the loop wrapped between them (story 338, rulings E75, E89 and E115). A resume frame larger than the stop frame does not rule out a wrap between them: an input beside the stream that the stream first clocks in a later lap, at a frame later in the loop than its stop, resumes at a larger frame, and so does an input whose stop lies in lap 1's pre-roll and whose resume lies in a later lap (rulings E186 and E191). None of these lines says which lap it belongs to, so a reader cannot place a mark in the right lane's file. `gap=` and `start-frame=` have the same limit. In a take that is both a loop and a punch take, the flag of a track that re-enters lap N+1's punch region (an `input-stopped` line whose stop frame is the punch-in frame, or the later of the punch-in and the loop start when the input's own frames are in a later lap, rulings E179 and E186) does not say which lap it belongs to either (ruling E88). Story 338 recorded this as a limit.

## Goals

The requirement, as story 338's studio engineer ruled it:

- In a loop take, every manifest entry that carries a frame (`gap=` and every key story 338 added) says which lap or lane file it applies to, and a reader can place it in that file (ruling E75).
- In a loop or punch take, `input-stopped` and the re-entry flag also name their lap or lane file (the studio engineer's requirement with rulings E88 and E89).

## Constraints

- No new field on `gap=` or `routing-unavailable=`, and no bump of `manifest-version` (rulings E46 and E75).
- `input-stopped` is never split, there is one per track, and its frames stay inside the loop range or in lap 1's pre-roll from the take's start frame (rulings E89 and E115).
- An `input-stopped` stop set by a delivered block that ran across the loop end lies in the next lap, where that lap's file begins, while story 338's warning names the lap whose file holds the block; both mark the same instant, and this story names the next lap on that line (ruling E188).
- An older manifest reads as it does today, and an older reader ignores what it does not know (the manifest grammar ignores unknown keys).
- The writer is the `capture-flush` thread, as for every manifest line; nothing is added to the real-time capture path.

## Acceptance Criteria

1. A loop take with a starved run, an input stop and a clock unlock in lap 2: each mark is placed in lap 2's file and in no other.
2. A loop-plus-punch take whose input stops after punch-out in lap 1: lap 2's file carries the stop, and lap 1's carries none.
3. A `gap=` line of an overflow in lap 3 is placed in lap 3's file.
4. A manifest written before this story reads as before.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- The two verification takes the studio engineer stated (criteria 1 and 2), built in the shape of `TakeMarksInPunchAndLoopTakesTest`.
- A round trip of every frame-carrying key through `TakeManifest.write` and `read`, with and without the lap identity.
- `TakeManifestTest`, `TakeMarksInPunchAndLoopTakesTest`, `InputStallCaptureTest` and the take recovery tests stay green.

## Non-Goals

- Frames for input blocks lost before the capture ring: future story 463.

## Technical Notes

- daw-core: `TakeManifest` (its grammar javadoc, `InputStopped`, `InputSilence`, `ClockMark`, the gap entry), `CaptureFlushService`, `TrackCapture` (laps and lanes), `StarvedRuns`, `FrameSpans`, `TakeRecovery`.
- Cross-refs: story 328 (loop and punch takes), story 338 (rulings E46, E75, E88, E89 and E115); Recording Reliability book §3.3 and §5.6.

## Status

- Deferred
