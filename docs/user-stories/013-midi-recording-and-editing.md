---
title: "MIDI Recording and Piano Roll Note Editing"
labels: ["enhancement", "midi", "editor", "recording"]
---

# MIDI Recording and Piano Roll Note Editing

## Motivation

The `EditorView` contains a MIDI piano roll view with note display, but it functions primarily as a visualization. Users cannot record MIDI from a controller, nor can they fully edit notes interactively in the piano roll (add, delete, move, resize notes). The `MidiInputPortSelectionDialog` exists for selecting MIDI input devices, and `MidiEvent` and `MidiNote` types are defined, but the end-to-end MIDI recording and editing workflow is incomplete. MIDI is essential for composing with virtual instruments — the `FluidSynthRenderer` and `JavaSoundRenderer` exist for MIDI playback, but users need to create and edit the MIDI data first.

## Goals

- Enable MIDI recording from connected MIDI controllers via the selected MIDI input port
- Display incoming MIDI notes in real-time on the piano roll during recording
- Allow adding notes by clicking/drawing with the pencil tool on the piano roll grid
- Allow selecting, moving, and resizing notes with the pointer tool
- Allow deleting notes with the eraser tool or Delete key
- Support note velocity editing via a velocity lane below the piano roll
- Quantize recorded or hand-drawn notes to the selected grid resolution
- Play back MIDI tracks through `FluidSynthRenderer` or `JavaSoundRenderer`
- Make all note edits undoable

## Non-Goals

- Step sequencer input mode
- MIDI CC automation lanes (separate feature)
- Score/notation view
- MPE (MIDI Polyphonic Expression) support
