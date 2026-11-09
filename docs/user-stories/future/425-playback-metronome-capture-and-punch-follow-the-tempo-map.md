---
title: "Playback, Metronome, Capture and Punch Follow the Tempo Map"
labels: ["enhancement", "transport", "audio-engine", "recording"]
---

# Playback, Metronome, Capture and Punch Follow the Tempo Map

## Motivation

Since story 334 the transport's tempo map round-trips: tempo changes (position, BPM, `INSTANT`, `LINEAR` or `CURVED` transition) and meter changes after beat 0 are saved and restored, and the ruler's TIME labels and `beatsToSeconds` / `secondsToBeats` read the map (`TimelineRulerModel`, through `Transport.getTempoMap()`).

The engine does not follow it. `SourceShortfall`'s class note records decision D37: "the render pipeline plays every audio clip at that tempo — the tempo at beat 0, whatever the tempo map holds later", and it states that a renderer which follows the tempo map must replace that rule with one beat-to-source-frame function that it and the shortfall check both call. Story 334's studio ruling E19 keeps it so: story 334 persists the map and does not make playback, the metronome, capture or punch conversion follow it.

So a project with a mid-song tempo change shows its ruler in time at the new tempo while its clips, its click and its recorded takes keep the beat-0 tempo. No production path but the project reader adds a change after beat 0 yet (future story 427), which is why the gap is latent today.

## Goals

- **One beat-to-time function follows the tempo map**, every transition type included, and every consumer calls it: clip playback (the beat-to-source-frame rule D37 names), the metronome's clicks and count-in, the capture's beat anchors and punch conversion, and the missing-assets shortfall check.
- **Sample-accurate across a change**: a clip that runs through a tempo change plays its source continuously, and the click lands on the beats the ruler draws.
- **Capture stays real-time safe**: what the recording callback reads stays allocation-free and lock-free (`RealTimeSafeContractTest`'s capture walk).

## Acceptance Criteria

1. A project with a tempo change at beat 32 plays a clip starting at beat 40 at the second the ruler shows for beat 40.
2. The metronome clicks on each beat of the map, through a `LINEAR` ramp included.
3. A take recorded after the change is placed at the beat the ruler shows for its start; a punch range is converted at the map's tempo.
4. D37's pinning tests change to the new rule, deliberately.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A render across `INSTANT`, `LINEAR` and `CURVED` changes: the rendered onset of each clip lands within one sample of the map's time for its beat.
- The click schedule across the same map.
- A capture and a punch across a change.
- The capture walk of `RealTimeSafeContractTest` stays green.
- Fault check, run once, turning a test red: one consumer left on the beat-0 tempo.

## Non-Goals

- Bars and the meter grid (future story 426).
- Authoring tempo and meter changes (future story 427).
- Time-stretching audio to a tempo change.

## Technical Notes

- Ruling: studio engineer, story 334, E19 ("Persistence only … FUTURE: Playback, metronome, capture and punch follow the tempo map").
- Code locators: `TempoMap` (`beatsToSeconds`, `secondsToBeats`), `Transport.getTempo` and `getTempoMap`, `SourceShortfall` (decision D37), `RenderPipeline`, `Metronome`, `RecordingPipeline`, `TimelineRulerModel`, `RealTimeSafeContractTest` (`CAPTURE_PATH_FOLLOWED` includes `TempoMap` and `TempoChangeEvent`).
- Design references: Persistence Integrity book §4.6 (the tempo map's schema); story 036's goals.
- Cross-refs: story 036 (tempo changes), story 334 (the tempo map's persistence), future stories 426 and 427.

## Status

- Deferred
