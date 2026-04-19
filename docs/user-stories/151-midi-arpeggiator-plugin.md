---
title: "Built-In MIDI Arpeggiator Plugin with Rate, Pattern, and Gate"
labels: ["enhancement", "midi", "plugins", "built-in"]
---

# Built-In MIDI Arpeggiator Plugin with Rate, Pattern, and Gate

## Motivation

An arpeggiator converts a held chord into a rhythmic sequence of the chord's notes — essential for synth leads, EDM patterns, and many compositional workflows. Every DAW ships one: Logic's "Arpeggiator," Ableton's "Arpeggiator" MIDI effect, Cubase's "Arpache." It must be a MIDI effect that sits before a sound source in the plugin chain, not a standalone synth.

The existing `BuiltInDawPlugin` sealed interface covers audio-effect and instrument kinds. This story adds a MIDI-effect variant.

## Goals

- Extend `BuiltInDawPlugin` sealed hierarchy with a `MidiEffectPlugin` variant whose contract is `MidiMessage[] process(MidiMessage[] in, int sampleOffset, ProcessContext ctx)`.
- Add `ArpeggiatorPlugin` record implementing `MidiEffectPlugin` in `com.benesquivelmusic.daw.core.plugin.builtin.midi`.
- Parameters (all `@AutomationParameter`): `rate` (1/4 .. 1/32 .. 1/32T .. 1/16D), `pattern` (UP, DOWN, UP_DOWN, DOWN_UP, RANDOM, AS_PLAYED, CHORD), `octaveRange` (1–4), `gate` (10–200%), `swing` (0–75%), `latch` (on/off).
- `Track.midiEffectChain` holds an ordered `List<MidiEffectPlugin>`; `RenderPipeline` feeds incoming MIDI through the chain before passing to the instrument.
- UI: compact `ArpeggiatorPluginView` with rate, pattern dropdown, octave, gate, swing sliders, and a latch toggle; step-sequence indicator lights on each played step.
- Persist parameter state via `ProjectSerializer`; migration creates empty `midiEffectChain` on legacy projects.
- Undo: `SetArpeggiatorParamAction` using the reflective parameter binder from story 113.
- Tests: playing a 4-note chord at 1/16 rate produces 4 output notes per bar at correct positions; UP pattern ordering is ascending; gate at 50% produces note-off at half the step length.

## Non-Goals

- User-editable step patterns (that is a full step-sequencer story).
- Retrigger on note-change semantics beyond the standard modes.
- Hosting external MIDI-effect plugins (CLAP MIDI-effect support — a separate story).
