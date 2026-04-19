---
title: "MIDI Quantize with Swing, Strength, and Groove Template Import"
labels: ["enhancement", "midi", "editor", "groove"]
---

# MIDI Quantize with Swing, Strength, and Groove Template Import

## Motivation

"Quantize these notes to 1/16" is not a single operation — real users want `strength=75%` (snap 75% of the way toward grid, preserving feel), `swing=54%` (offset every other 1/16 to create groove), and `groove template: Logic MPC Funk 01` (snap toward a captured human performance). The current MIDI editor (if it has quantize at all) has none of this. Groove quantize is a defining feature of modern DAWs: Logic's "Groove Templates," Cubase's "Quantize Panel," Reaper's "Groove Pool," Ableton's "Groove Pool." Without it, programmed MIDI sounds mechanical.

## Goals

- Add `QuantizeConfig` record in `com.benesquivelmusic.daw.sdk.midi`: `record QuantizeConfig(GridResolution division, double strength, double swing, Optional<GrooveTemplate> groove, boolean quantizeEnd)`.
- Add `GrooveTemplate` record: `record GrooveTemplate(String name, List<GrooveHit> hits)` where `GrooveHit` is a normalized `(position, velocityDelta)` tuple within one bar.
- Add `MidiQuantizer` service in `com.benesquivelmusic.daw.core.midi` that takes a list of notes and a `QuantizeConfig` and produces a new list with adjusted start times, end times (if enabled), and velocities.
- `GrooveTemplateLibrary` loads user-extractable `.groove` files (simple JSON schema) from `daw-core/src/main/resources/groove-templates/` and the user's `~/.daw/groove-templates/`.
- "Extract groove" action: given a selected region of MIDI, produce a `GrooveTemplate` capturing timing + velocity offsets and save it to the user library.
- `QuantizeDialog` in the MIDI editor with controls for each field and a "Preview" button that applies non-destructively for audition.
- Undo-redo for every quantize apply via `QuantizeNotesAction`.
- Persist no quantize state on notes (quantize is destructive); the user can extract groove from the result if they want it back.
- Tests: strength=0 is a no-op; strength=100 snaps to grid; swing produces the expected temporal offset on odd subdivisions; groove extraction followed by quantize reconstructs the original timing within template-resolution tolerance.

## Non-Goals

- Audio quantize (warping audio events to grid — a separate major story).
- Real-time input quantize during recording.
- Machine-learning-assisted groove detection from audio.
