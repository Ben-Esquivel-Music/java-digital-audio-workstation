---
title: "MIDI Data Resolution: 14-Bit CC Lanes, CC Value Range and Note Timing"
labels: ["bug", "midi", "persistence", "model"]
---

# MIDI Data Resolution: 14-Bit CC Lanes, CC Value Range and Note Timing

## Motivation

Story 330 made MIDI clips round-trip exactly as the model holds them. Three limits of the model itself now travel with every saved file:

- **No 14-bit lane for an arbitrary CC.** `MidiCcLaneType.supportsHighResolution()` returns `this == PITCH_BEND || defaultLsbCcNumber() >= 0` (`MidiCcLaneType.java:80-82`); `ARBITRARY_CC` has no default CC number, so the answer is always `false`. Its Javadoc says otherwise: for `ARBITRARY_CC` "the lane constructor validates 14-bit eligibility based on the concrete CC number" (`:69-79`). The constructor does check the number — 14-bit needs CC 0–31 (`MidiCcLane.java:60-65`) — but its generic check that follows (`:70-73`) then rejects every 14-bit `ARBITRARY_CC` lane, CC 0–31 included. A 14-bit lane on, for example, CC 7 (volume) or CC 10 (pan) cannot exist, and story 330's reader skips and reports one found in a file.
- **A 7-bit lane accepts 14-bit values.** `MidiCcEvent` accepts any value in 0–16383 (`MidiCcEvent.java:50`), and `MidiCcLane.addEvent` adds an event without checking it against the lane's resolution (`MidiCcLane.java:149-153`). A 7-bit sustain lane can hold 16383, which a 7-bit controller cannot send.
- **Note and CC timing is quantised to sixteenths.** A note's start and length are whole columns of 0.25 transport beats in every producer and consumer, and so is a CC breakpoint's position: `MidiCcEvent.column` is "positioned on the same column grid used by `MidiNoteData`" (its Javadoc), and `ProjectSerializer.buildMidiClip` writes it as `event@column`. Story 330 made that unit part of the file format: `MidiColumnUnitContractTest` (daw-core) and `MidiEditorColumnUnitContractTest` (daw-app) pin it with the message "schema-2 MIDI column = 0.25 beats; changing it requires a project migration". Recording rounds every note to the nearest column (`MidiRecorder.timestampToColumn`, `MidiRecorder.java:529-537`: 125 ms at 120 BPM), and the piano roll rounds a triplet snap back to a column (`MidiEditorView.snapColumn`, `MidiEditorView.java:561-566`). The MIDI file export writes 480 ticks per quarter but only multiples of 120 (`MidiFileExporter.TICKS_PER_COLUMN`). Feel, swing, flams and triplets cannot be stored, and a CC ride can place its breakpoints only on sixteenths.

For an engineer these are data limits, not display limits: a recorded performance loses its timing on the way in, a volume or pan ride cannot have 14-bit resolution, and a lane can hold values its controller cannot produce.

## Goals

- **14-bit arbitrary CC lanes.** An `ARBITRARY_CC` lane on CC 0–31 may be 14-bit, as the Javadoc says; on CC 32–127 it may not. `supportsHighResolution` and its Javadoc agree.
- **Values fit the lane.** A 7-bit lane accepts values 0–127 only, a 14-bit lane 0–16383; the reader skips and reports an event that does not fit its lane (story 330's rule: skip the item, never clamp musical data).
- **Finer note and CC timing.** Notes and CC breakpoints (`MidiCcEvent.column`, saved as `event@column`) are stored in ticks of a fixed resolution (for example 960 per quarter note), through a project migration from columns: an exact ×240 conversion at 960 of `start-column`, `duration-columns` and `event@column` alike, so no saved note or breakpoint moves and the file keeps one time unit. Recording keeps the captured timing, and the grid only snaps.
- **One unit.** Every producer and consumer converts through one shared unit, and the column-unit pins are replaced by pins of the new unit.

## Acceptance Criteria

1. `new MidiCcLane(ARBITRARY_CC, 7, true, 0)` constructs; `new MidiCcLane(ARBITRARY_CC, 64, true, 0)` is rejected.
2. Adding the value 200 to a 7-bit lane is rejected; a file holding it opens with the event skipped and reported.
3. A schema-2 file opens through the migration with every note start and length and every CC breakpoint column equal in beats; a note recorded between two sixteenths, and a CC breakpoint placed between two sixteenths, keep their positions after save and reopen.
4. A triplet note entered on a triplet grid keeps its position.
5. The MIDI file export writes the stored timing without rounding to sixteenths.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **14-bit lanes**: the two constructions above, and a 14-bit CC 7 lane with breakpoints round-trips through save and reopen.
- **Value range**: `addEvent` rejects 128 on a 7-bit lane and accepts 16383 on a 14-bit one; a fixture with a 7-bit lane holding 16383 opens with that event skipped and reported.
- **Migration**: a golden schema-2 clip with notes and a CC lane migrates to ticks with every note start and length and every CC breakpoint column equal in beats; the step has its own DOM-in/DOM-out test.
- **Timing kept**: a note recorded 31.25 ms after a sixteenth at 120 BPM — exactly 60 ticks at 960 per quarter note, a quarter being 500 ms — keeps that offset exactly through save and reopen.
- **CC timing kept**: a CC breakpoint placed between two sixteenths keeps its position through save and reopen.
- **Unit pin**: one contract test pins the new unit for every converter, with a message naming the migration it requires.

## Non-Goals

- MIDI 2.0 (32-bit) controller values.
- Tempo-map-aware recording (story 334 / recording).
- Editing UI for CC lanes (story 146).

## Technical Notes

- Code locators: `MidiCcLaneType.supportsHighResolution`, `MidiCcLane` (constructor, `addEvent`), `MidiCcEvent`, `MidiNoteData`, `MidiRecorder.timestampToColumn`, `MidiEditorView.snapColumn`, the `BEATS_PER_COLUMN` constants (`MidiTrackRenderer`, `MidiRecorder`, `KeyboardProcessor`, `MidiEditorView`), `MidiFileExporter`, `SlipEditService.buildMidiSlip`, `ProjectSerializer.buildMidiClip`, `ProjectDeserializer.parseMidiClip`.
- Ruled by the studio engineer in story 330 (round 1, F4 and F6: the column unit is fixed in schema 2; a move to ticks is an exact ×240 migration at 960 PPQ; the 7-bit lane accepting 16383 is a model gap).
- Story 146 (piano-roll velocity and CC lanes) owns the CC lane editing feature and its goal of 14-bit CC pairs; this story fixes the model and the file format under it.
- Cross-refs: story 146, story 188 (migration registry), story 330 (MIDI round-trip), story 355 (MIDI editor edits through undo).

## Status

- Deferred
