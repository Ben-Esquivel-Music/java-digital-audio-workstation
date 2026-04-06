---
title: "Split EditorView into Separate MIDI and Audio Editor Components"
labels: ["enhancement", "ui", "editor", "midi"]
---

# Split EditorView into Separate MIDI and Audio Editor Components

## Motivation

`EditorView` is 1,487 lines containing two entirely distinct editing workflows in a single class:

1. **MIDI piano roll editor** — note rendering on a piano roll grid, note add/delete/move/resize with mouse interaction, velocity display, quantize controls, MIDI-specific toolbar
2. **Audio waveform editor** — `WaveformDisplay` rendering, amplitude/time zoom, clip-level editing operations

These are fundamentally different editing paradigms that share almost no code. Combining them in one class creates several problems:
- Difficult to navigate: developers must mentally separate which methods belong to MIDI vs. audio editing
- Hard to test: test setup requires mocking both MIDI and audio state even when testing only one path
- Feature additions to either editor type increase the line count of an already oversized class
- The class switches behavior based on the selected clip type, creating implicit mode-dependent logic throughout

Professional DAWs treat piano roll and audio editors as separate views with distinct controls (Logic Pro: Piano Roll vs. Audio Editor; Ableton: MIDI Clip View vs. Audio Clip View; Pro Tools: MIDI Editor vs. Waveform View).

## Goals

- Create a `MidiEditorView` class that owns all MIDI piano roll rendering, note interaction, velocity editing, and MIDI-specific toolbar controls
- Create an `AudioEditorView` class that owns all waveform rendering, amplitude/time zoom, and audio clip editing controls
- `EditorView` becomes a thin container that detects the selected clip type (audio vs. MIDI) and delegates to the appropriate sub-view, or is replaced entirely by the two new views
- Move MIDI-specific fields (note grid dimensions, piano key labels, velocity lane height, MIDI tool state) into `MidiEditorView`
- Move audio-specific fields (`WaveformDisplay` instance, amplitude zoom, audio tool state) into `AudioEditorView`
- Each sub-view is independently testable without requiring the other's state
- No visible behavior changes — both editors look and function identically to the current implementation
- Each resulting class should be under 800 lines

## Non-Goals

- Adding new editing features to either the MIDI or audio editor
- Changing the visual design or layout of either editor
- Creating a combined "combi" view that shows both editors simultaneously
- Refactoring the underlying undoable actions for MIDI or audio editing
