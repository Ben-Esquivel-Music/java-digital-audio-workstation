---
title: "The Ruler Draws Bars from the Meter Map"
labels: ["bug", "transport", "ui", "arrangement-view"]
---

# The Ruler Draws Bars from the Meter Map

## Motivation

A transport beat is a quarter note: a clip's `start-beat`, MIDI columns at 0.25 beats each and automation all count in quarter-note beats, and the MIDI file export counts four columns per quarter (`MidiFileExporter.COLUMNS_PER_QUARTER = 4`).

The ruler's bar grid does not. `TimelineRulerModel` takes its `beatsPerBar` from `transport.getTimeSignatureNumerator()` (near `:137` and `:165`), the beat-0 numerator alone. It ignores the denominator, so its bar is always numerator quarter-note beats — six for 6/8, where a 6/8 bar holds three — and it ignores every meter change after beat 0. For any x/8 meter the ruler and the MIDI export, which counts in quarter notes, disagree on where a bar starts, and a meter change moves no barline.

Since story 334 meter changes after beat 0 round-trip (`<meter-change position numerator denominator>`), and its studio ruling E19 leaves the grid to this story; `TempoMapPersistenceTest.theRulerModelGivesIdenticalOutputBeforeAndAfterReopen` notes that the bar grid reads only the beat-0 meter today.

## Goals

- **A bar is numerator × 4 / denominator quarter-note beats**, from the meter in force at that bar.
- **Meter changes move the grid**: bar numbers count across every change, and the BARS_BEATS_TICKS labels follow.
- **One bar↔beat conversion** that the ruler and the MIDI export share.

## Acceptance Criteria

1. In 6/8, a bar spans three quarter-note beats on the ruler, as in the MIDI export.
2. A meter change from 4/4 to 3/4 at beat 16 makes bar 5 start at beat 16 and bar 6 at beat 19.
3. A project with no meter change draws the grid it draws today in 4/4.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- `TimelineRulerModel` labels and grid lines in 4/4, 6/8 and across a meter change, against a hand-computed table.
- The MIDI export and the ruler agree on every bar start of the same map.
- Fault check, run once, turning a test red: the grid reading the beat-0 numerator alone again.

## Non-Goals

- Playback following the tempo map (future story 425).
- Authoring meter changes (future story 427).

## Technical Notes

- Ruling: studio engineer, story 334, E19 ("The ruler draws bars from the meter map: a bar is numerator × 4/denominator quarter-note beats, and meter changes move the grid").
- Code locators: `TimelineRulerModel` (its two `beatsPerBar` reads), `Transport.getTimeSignatureNumerator` / `getTimeSignatureDenominator` / `getTempoMap`, `TempoMap.getTimeSignatureChanges`, `TimeSignatureChangeEvent`, `MidiFileExporter.COLUMNS_PER_QUARTER`.
- Cross-refs: story 036 (tempo and time-signature changes), story 334 (the meter map's persistence), future stories 425 and 427.

## Status

- Deferred
