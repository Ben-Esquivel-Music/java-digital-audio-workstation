---
title: "Tempo and Meter Changes Can Be Authored on a Tempo Track"
labels: ["enhancement", "transport", "arrangement-view", "ui"]
---

# Tempo and Meter Changes Can Be Authored on a Tempo Track

## Motivation

Story 036 set out to support tempo and time-signature changes along the timeline, with a tempo track for editing them, linear and curved transitions, and a metronome and grid that follow. The model exists: `TempoMap` holds tempo changes (`TempoChangeEvent`, with `INSTANT`, `LINEAR` and `CURVED` transitions) and time-signature changes (`TimeSignatureChangeEvent`), and since story 334 both lists round-trip through the project file.

No production path creates a change after beat 0. `TempoMap.addTempoChange` and `addTimeSignatureChange` are called by the project reader (`ProjectDeserializer`, for what a file holds) and by `Transport.setTempo` and `setTimeSignature`, which change beat 0 only; the tempo editor (`TempoEditController`) edits that one beat-0 tempo. So a mid-song tempo or meter change can reach a project only through a file. Story 334's studio ruling E19 leaves the authoring to this story, with story 036's unimplemented goals.

## Goals

- **A tempo track** shows the tempo map's changes on the timeline and lets the engineer add, move, edit and remove tempo changes (BPM and transition) and meter changes (numerator and denominator), each as an undoable edit that marks the project dirty.
- **Authoring rules belong here**, not in the reader: whether a meter change must fall on a barline, and which denominators the editor offers. Story 334's reader accepts exactly what the model accepts (ruling E7) and stays so.
- **Values the model refuses are refused at the edit**: a non-finite or out-of-range BPM, a position at or before beat 0 for a list entry, a numerator or denominator that is not a positive whole number.

## Acceptance Criteria

1. Adding a tempo change of 140 BPM at bar 17 puts one `TempoChangeEvent` in the map at the bar's beat; undo removes it.
2. Changing a tempo change's transition to `LINEAR` ramps from the previous change.
3. Adding a 3/4 meter change at bar 33 puts one `TimeSignatureChangeEvent` in the map; save and reopen keep it.
4. An invalid value is refused with the editor's invalid-input feedback and changes nothing.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Each edit and its undo against the `TempoMap`, with the project's dirty state.
- A round trip of an authored map through story 334's format.
- The refused values, each changing nothing.
- Fault check, run once, turning a test red: an edit that skips the undo history.

## Non-Goals

- Playback, the metronome, capture and punch following the map (future story 425).
- Drawing bars from the meter map (future story 426).
- Tempo maps imported from MIDI files and tap tempo (story 036's Non-Goals).

## Technical Notes

- Ruling: studio engineer, story 334, E19 ("Tempo and meter changes can be authored on a tempo track (story 036's unimplemented goals)") and E7 (the reader accepts what the model accepts; authoring rules belong to the future tempo track).
- Code locators: `TempoMap` (`addTempoChange`, `addTimeSignatureChange`, `getTempoChanges`, `getTimeSignatureChanges`), `TempoChangeEvent`, `TimeSignatureChangeEvent`, `Transport.setTempo` / `setTimeSignature`, `TempoEditController`, `TimelineRuler`.
- Cross-refs: story 036 (tempo changes), story 334 (the tempo map's persistence), future stories 425 and 426.

## Status

- Deferred
