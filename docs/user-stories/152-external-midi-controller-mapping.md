---
title: "External MIDI Controller Mapping with Learn Mode"
labels: ["enhancement", "midi", "control", "hardware"]
---

# External MIDI Controller Mapping with Learn Mode

## Motivation

Hands-on control of the mixer via a fader bank (KORG nanoKONTROL, Behringer X-Touch, Mackie MCU) is basic production hygiene. The current DAW treats MIDI input as note data only — there is no mapping layer from `MidiEvent(CC, ch=1, cc=7, value=64)` to `MixerChannel.volume` or to a plugin parameter. Ableton's "MIDI Map Mode" (Ctrl+M), Logic's "Controller Assignments," Reaper's "MIDI learn" are the standard workflows and every professional expects them.

Story 113 provides reflective parameter bindings for automation; this story reuses those bindings to route a MIDI CC stream into the same sinks.

## Goals

- Add `MidiMapping` record in `com.benesquivelmusic.daw.core.midi`: `record MidiMapping(int channel, int ccNumber, String parameterId, double outMin, double outMax, MappingCurve curve)`.
- `MappingCurve` sealed: `Linear`, `Logarithmic(double base)`, `Exponential(double base)`, `Bipolar`.
- `MidiMappingManager` maintains `List<MidiMapping>` and consumes `Flow.Publisher<MidiEvent>` from the active MIDI backend; on each CC event, look up matching mappings and write to the target parameter via the story-113 `ParameterBinding` API.
- "Learn" mode: activated from a toolbar button; next control the user moves on hardware plus next parameter they wiggle in the UI forms a new `MidiMapping`.
- Parameters accept mapping targets from anywhere they are exposed: mixer faders, pans, sends, plugin parameters, transport play/stop, marker jump.
- Mapping browser view: list all active mappings with columns (CC, target, range, curve), inline editing and delete.
- Persist mappings per-project via `ProjectSerializer` and export/import mapping profiles to JSON so the user can reuse the same layout across projects.
- Tests: a CC value of 64 maps to the mathematical midpoint of the configured range per curve type; multiple mappings on the same CC update all targets atomically; learn captures the most recently received CC.

## Non-Goals

- Bidirectional protocol support (MCU V-Pot feedback, OSC) — CC-in only.
- Moving-fader "display" for hardware that supports it.
- Gesture-based mappings (aftertouch, XY controllers) beyond standard CC.
