---
title: "Drum Grid Editor View Optimized for Rhythmic Programming"
labels: ["enhancement", "midi", "editor", "drum-machine"]
---

# Drum Grid Editor View Optimized for Rhythmic Programming

## Motivation

`MidiEditorView` is a piano roll: 96 keys vertically, 32 grid columns horizontally, with notes drawn as horizontal rectangles. That layout is correct for melodic and harmonic editing but is the wrong shape for drum programming, where the user thinks in terms of a small fixed set of named percussion sounds (kick, snare, hat, ride, ...) and wants to step-program a pattern across a 16-or-32-step grid. Logic's "Drum Machine Designer" and "Step Sequencer," Ableton's "Drum Rack" view, Reaper's "Inline Drum Editor," and Studio One's "Pattern Editor" all use a row-per-drum layout where each cell is a binary on/off (with a velocity or accent overlay). This is a much faster interface for beat-making — clicking 16 cells beats clicking and resizing 16 piano-roll rectangles.

We have everything we need to build this view alongside the existing piano roll. `MidiClip` already holds notes, `MidiNoteData.noteNumber` is the General MIDI drum-map pitch (36 = kick, 38 = snare, etc.), and the existing `AddMidiNoteAction` / `RemoveMidiNoteAction` pair handles the model edits. The new code is purely a view: a `DrumGridEditorView` JavaFX component that renders a row per drum hit with a known cell layout, a click handler that toggles cells, and a drum-map definition that names the rows ("Kick", "Snare", "Closed Hat", "Open Hat", "Crash", "Ride"). Switching between piano roll and drum grid is a per-track choice driven by the track's content type (a track with `SoundFontAssignment` to a drum kit defaults to drum grid; the user can override).

## Goals

- Add a `DrumMap` record in `com.benesquivelmusic.daw.core.midi` containing `String name, List<DrumLane> lanes` where `DrumLane` is `record DrumLane(String label, int noteNumber, Color color)`.
- Add a `DrumMapLibrary` providing the General MIDI drum map and 2–3 commonly used kit-specific maps (808, 909, acoustic kit) loaded from JSON resources under `daw-core/src/main/resources/drum-maps/`.
- Add a `DrumGridEditorView` JavaFX component in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/` modeled after `MidiEditorView` (final, package-private, extends `VBox`) that renders one row per `DrumLane`, columns at the active `GridResolution`, and binary cells.
- Cell click toggles a note at that row's note number and column position; the click stores the configured default velocity (per-lane override available); right-click opens a small popover for per-cell velocity adjustment.
- Add a per-track preference stored on `Track` (new field `boolean preferDrumGrid`) plus an explicit "Show as Drum Grid" / "Show as Piano Roll" toggle in `EditorView` that switches between the two views; default to drum grid when a track's `SoundFontAssignment` references a recognized drum kit.
- All edits use the existing `AddMidiNoteAction` / `RemoveMidiNoteAction` so undo/redo and persistence flow through the same MIDI plumbing as the piano roll.
- A small "fill" tool (Shift+drag across cells) toggles a range of cells in one undo step via `CompoundUndoableAction`; an "alternate" tool (Alt+click) places ghost-note (low-velocity) hits.
- Persist `preferDrumGrid` and per-track active drum map name via `ProjectSerializer` and `ProjectDeserializer` with sensible defaults for older projects.
- Tests cover: clicking a cell adds a note with the correct note number and column; clicking again removes it; switching drum maps re-labels rows without modifying notes; bulk-fill produces a single undo step; project round-trips preserve the drum-grid preference.

## Non-Goals

- A dedicated step sequencer with per-step probability, ratchets, and length variation — that is a separate story; this story is a flat 2D grid.
- Per-pad sample assignment within the editor itself (the `SoundFontAssignment` and plugin parameter UI handle that already).
- Polyrhythmic grids where different rows have different column counts — every row uses the same `GridResolution`.
- Drum-map editing UI within the app — drum maps come from JSON resources and the user picks among them; authoring a custom drum map is a future enhancement.
- Importing patterns from external drum-machine formats (Beatstep, Maschine) — out of scope.

## WON't DO