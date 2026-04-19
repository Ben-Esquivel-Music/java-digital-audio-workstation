---
title: "Loop-Record Mode Producing Multiple Takes per Pass"
labels: ["enhancement", "recording", "comping"]
---

# Loop-Record Mode Producing Multiple Takes per Pass

## Motivation

Vocalists and soloists work best when they can run a phrase several times in a row without stopping, picking the best pass afterward. Every modern DAW supports this as "loop record" or "cycle record": the transport loops between two markers, and each loop lap creates a new take stacked under the same clip slot. Story 040 introduces comping UI; loop-record is the capture half of that workflow, and without it comping has no material to work with.

The existing `LoopRegion`, `Transport`, and `RecordingPipeline` classes have everything needed — the engine already loops during playback. Loop-record is "if `record` is armed when the loop wraps, stamp the buffered audio as a new take rather than overwriting."

## Goals

- Add `TakeGroup` record in `com.benesquivelmusic.daw.core.recording`: `record TakeGroup(UUID id, List<Take> takes, int activeIndex)` where `Take` references an `AudioClip` and a capture timestamp.
- Extend `RecordingPipeline` with a `loopRecord` flag; when set, on loop-region wrap, finalize the current take into a `TakeGroup` and begin the next without dropping input frames.
- Each take's audio is a distinct `AudioClip` referenced by the `TakeGroup`; only the `activeIndex` take plays back on the track lane.
- Render a "take stack" overlay in `TrackLaneRenderer` showing take count; clicking cycles the active take; integrates with the comping view from story 040 for detailed selection.
- Keyboard shortcut `Alt+Shift+R` toggles loop-record; UI indicator in transport bar.
- Writes happen on a background I/O thread (virtual thread from story 205) so take finalization does not disturb the audio callback.
- Persist `TakeGroup` via `ProjectSerializer`; each take is a separate audio asset.
- A "Flatten takes" maintenance command collapses a `TakeGroup` to its active take and deletes the others (destructive, undoable).
- Tests: ten consecutive loops produce ten distinct takes with contiguous sample counts; loop boundaries are sample-accurate; disk I/O does not cause xruns.

## Non-Goals

- The comping UI itself (story 040).
- Cross-take crossfades within a single comp (covered by comping story).
- Automatic take-quality ranking or "best take" detection.
