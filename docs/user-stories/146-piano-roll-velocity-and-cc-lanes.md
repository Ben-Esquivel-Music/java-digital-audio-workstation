---
title: "Piano Roll Velocity Lane and CC Editing Lanes"
labels: ["enhancement", "midi", "editor"]
---

# Piano Roll Velocity Lane and CC Editing Lanes

## Motivation

`MidiEditorView` draws notes as rectangles but offers no way to view or edit per-note velocity, and CC (control change) data is not editable at all. Every DAW's piano roll has a second pane underneath the notes for velocity bars and a switchable CC lane (mod wheel, sustain, aftertouch, pitch bend): Logic's "Hyper Draw," Cubase's "Key Editor" CC lanes, Ableton's "Envelope" editor, Reaper's "MIDI Editor" CC row. Expression data is half the feel of a MIDI performance; without editing it, the piano roll is a sketch, not a finished product.

## Goals

- Add `MidiCcLane` render component below the piano roll; switchable lane type via a dropdown: `Velocity`, `ModWheel (CC 1)`, `Expression (CC 11)`, `Sustain (CC 64)`, `PitchBend`, or arbitrary CC number.
- Velocity lane: a vertical bar per note aligned with the note's start; drag the top of the bar to change velocity; multi-select + drag lifts the whole cluster preserving relative differences.
- CC lane: breakpoint line (like automation) editable with click, drag, and delete.
- A "ramp" helper: select two breakpoints, `R` inserts a line between them at configurable density (default one point per grid step).
- Multiple CC lanes can be stacked (e.g., velocity + mod wheel visible simultaneously) with drag-to-resize.
- Edits produce `SetNoteVelocityAction` / `SetCcValueAction` undo records; multi-note edits compound.
- Persist lane configuration (which CCs are shown) per-clip via `ProjectSerializer`.
- Support 14-bit CC pairs for high-resolution pitch and mod.
- Tests: velocity bar reflects note velocity; CC breakpoint values round-trip through save/load; ramp produces mathematically correct intermediate values.

## Non-Goals

- Editing SysEx messages graphically.
- Real-time recording of CCs from hardware during editing (that is a separate story).
- Automation-style curve shapes between CC breakpoints (only line-segment interpolation in MVP).
