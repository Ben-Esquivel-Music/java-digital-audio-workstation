---
title: "Punch-In / Punch-Out Recording Regions with Auto-Punch"
labels: ["enhancement", "recording", "transport"]
---

# Punch-In / Punch-Out Recording Regions with Auto-Punch

## Motivation

`RecordingPipeline` records continuously from `record-arm` until stop. Fixing a single wrong word in a vocal take requires recording over the whole phrase and praying the performer doesn't breathe loudly before the problem word. Punch-in/out solves this: the engineer defines a region (e.g., bars 25 beat 3 through bar 26 beat 1), arms the track, and hits play from an earlier point. The engine plays back up to the punch-in point, seamlessly swaps to recording for the region, then swaps back to playback. Every DAW — Pro Tools' oldest, most beloved feature — supports this.

The `MarkerManager` and `Transport` already provide the navigation primitives, and `RecordingPipeline.isInputMonitoringActive()` already gates the input-vs-playback decision (extended in story 133 for per-track modes). This story composes those into a punch region.

## Goals

- Add `PunchRegion` record in `com.benesquivelmusic.daw.sdk.transport`: `record PunchRegion(long startFrames, long endFrames, boolean enabled)`.
- Add `PunchRegion` field on `Transport`; expose `setPunchRegion`, `clearPunchRegion`, `isPunchEnabled`.
- Wire `RecordingPipeline` to start capturing input at punch-in and stop at punch-out when `isPunchEnabled` is true and the track is armed.
- Render punch markers on `TimelineRulerRenderer` (distinct from loop markers — e.g., red I/O flags) and allow shift-drag to set from the ruler.
- Keyboard shortcuts: `I` sets punch-in at playhead, `O` sets punch-out, `Shift+P` toggles punch enabled.
- Auto-punch mode: when enabled, transport record stays armed but only captures within the region, allowing repeated takes without re-pressing record.
- Pre-roll / post-roll (story 134 extension): punch region + pre/post-roll compose naturally — pre-roll starts playback N bars earlier, post-roll continues N bars after punch-out.
- Persist `PunchRegion` through `ProjectSerializer`.
- Tests: boundaries are sample-accurate; input audio outside the region is not captured; crossfades at punch-in/out avoid clicks using 5 ms cosine ramps.

## Non-Goals

- Loop-record (multiple takes) — that is story 132.
- Per-track independent punch regions; regions are global.
- Punching arbitrary, non-contiguous ranges.
