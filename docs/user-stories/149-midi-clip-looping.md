---
title: "MIDI Clip Looping with Independent Loop Region and Clip Length"
labels: ["enhancement", "midi", "clip"]
---

# MIDI Clip Looping with Independent Loop Region and Clip Length

## Motivation

Dragging a 2-bar drum loop to span 8 bars should produce 4 repeats of the 2-bar material, not a single 2-bar clip followed by 6 bars of silence. Today's `MidiClip` has a single duration and no concept of "source loop region." Every DAW — Ableton's clip loop flag, Logic's "Loop" attribute, Cubase's "Repeat" handle, Reaper's drag-right-edge looping — supports a clip that plays its source region repeatedly for the visible clip length.

## Goals

- Extend `MidiClip` with `record MidiLoopConfig(boolean loopEnabled, long sourceStartTicks, long sourceEndTicks)` stored as an `Optional<MidiLoopConfig>`.
- When `loopEnabled == true`, playback iterates notes within `[sourceStart, sourceEnd)` repeatedly until the clip's visible end; partial final loop truncates cleanly at clip end.
- Right-edge drag handle on MIDI clips with loop-enabled draws a repeating-waveform visual; the waveform tiles visually showing loop boundaries.
- Toggle loop mode via clip context menu or shortcut `L` while clip is selected.
- Edits to notes in the source region automatically propagate visually to all loop iterations (notes are rendered once per iteration rather than copied).
- Persist via `ProjectSerializer`.
- Applies symmetrically to audio clips via an analogous `AudioLoopConfig` (loop the audio source between two sample positions); share rendering logic with MIDI via a `ClipLoopRenderer`.
- Tests: dragging right extends the clip with loop iterations at correct positions; sample-accurate playback of looped content; disabling loop truncates to single source length.

## Non-Goals

- Per-iteration modifications (e.g., different velocity each loop) — loops are identical repetitions.
- Variable-length loops within a single clip.
- Time-stretch on loops (covered by story 042).

## WON't DO