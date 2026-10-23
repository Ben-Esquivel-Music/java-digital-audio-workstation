---
title: "One Octave-Naming Convention for MIDI Note Names"
labels: ["bug", "midi", "ui"]
---

# One Octave-Naming Convention for MIDI Note Names

## Motivation

The application names MIDI note 60 two different ways:

- **The piano roll says C5.** `MidiEditorView` labels each C key with `"C" + octave`, where the octave is the note number divided by 12 (`MidiEditorView.java:394-399`; the row-to-note mapping is `TOTAL_KEYS - 1 - row`, `:954`, `:962`). MIDI 60 is labelled `C5`, MIDI 0 `C0`.
- **The virtual keyboard and the tuner say C4.** `KeyboardProcessor.noteName` computes the octave as `noteNumber / 12 - 1` (`KeyboardProcessor.java:664-670`), so MIDI 60 is `C4`; `VirtualKeyboardEditor` labels its keys with it (`VirtualKeyboardEditor.java:531`). `TunerPlugin` computes its octave the same way (`Math.floorDiv(midiNote, 12) - 1`, `TunerPlugin.java:269`).

More than one convention exists in the industry (instruments and programs label MIDI 60 C3, C4 or C5), but one application must use one. An engineer who sees middle C as `C5` in the piano roll and `C4` on the keyboard and the tuner will place notes an octave off or doubt which one is right. Story 330 sidestepped the question in its log lines: a skipped note is named by its raw note number, never a note name (studio ruling, round 4, Q5).

This story does not pick the convention. Which octave number MIDI 60 carries is ruled by the studio engineer before implementation; a user setting for it is a possible outcome of that ruling.

## Goals

- **One note-name function.** Every surface that names a MIDI note — the piano roll's key labels, the virtual keyboard, the tuner, and any later one — calls one shared, tested function from MIDI note number to name.
- **The convention is stated once**, in that function's Javadoc, with the ruling it follows.

## Acceptance Criteria

1. The piano roll, the virtual keyboard and the tuner give MIDI 60 the same name.
2. No other code computes an octave number from a MIDI note number for display.
3. The function's tests pin MIDI 0, 60, 69 and 127 and every pitch class of one octave.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Function**: the shared function's names for MIDI 0, 12, 60, 69 and 127 and for the twelve pitch classes from 60 to 71 match the ruled convention.
- **Surfaces agree**: the piano roll's label for the C row of MIDI 60, `KeyboardProcessor.noteName(60)` and the tuner's name for 261.63 Hz are equal.
- **Sentinel**: a source scan over the main sources finds no other display octave computation from a MIDI note number.

## Non-Goals

- Note names in the project file (story 330 stores note numbers only).
- Enharmonic spelling (C♯ against D♭) beyond what the ruling decides.

## Technical Notes

- Code locators: `MidiEditorView` (key labels), `KeyboardProcessor.noteName`, `VirtualKeyboardEditor`, `TunerPlugin` (octave), `TunerDisplay` and `TunerEditor` (show the tuner's name and octave).
- Found in story 330 (studio ruling, round 4, Q5 and its future-story bullet: "the piano roll and the virtual keyboard name MIDI 60 differently").
- Cross-refs: story 080 (keyboard processor view), story 082 (tuner), story 146 (piano roll lanes).

## Status

- Deferred
