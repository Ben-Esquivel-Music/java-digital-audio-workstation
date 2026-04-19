---
title: "Slip Edit Within a Clip (Slide Audio Content Inside Fixed Boundaries)"
labels: ["enhancement", "editing", "arrangement"]
---

# Slip Edit Within a Clip (Slide Audio Content Inside Fixed Boundaries)

## Motivation

When a drum fill was recorded slightly early, the engineer needs to slide the *audio content* inside the clip forward without moving the clip boundaries (which would disrupt the edit points with neighboring clips). The current `ClipEditOperations` exposes move, trim, and split but has no concept of a clip window that shifts independently of the underlying audio offset. Pro Tools' "Slip" tool, Reaper's shift-drag inside a clip, Cubase's "Object Selection" + drag-contents — this is a daily editing primitive.

`AudioClip` already carries a `sourceOffset` (where in the source file the clip starts) and a duration. Slip is "change sourceOffset while keeping start/end on the timeline fixed."

## Goals

- Extend `ClipEditOperations` with `slipClip(AudioClip clip, long sampleDelta)` that updates `sourceOffset` by `sampleDelta` while keeping timeline position and duration unchanged.
- Bound-check: the resulting source window must remain within `[0, sourceLengthFrames - durationFrames]`; if not, clamp and surface a "hit edge" visual cue without failing.
- Add `SlipToolHandler` in `daw-app.ui` that activates on `Ctrl+Alt+drag` inside a clip body; drag horizontal displacement maps to `sampleDelta`.
- Render a ghost overlay of the *source* waveform underneath the clip during a slip drag, anchored to sample 0 of the source so the user can see what they're sliding.
- Support slip on MIDI clips too: slip operates on the note-time offset stored in `MidiClip.sourceOffsetTicks`.
- Nudge shortcuts `Shift+Left / Shift+Right` slip by the current grid resolution; `Ctrl+Shift+Left/Right` slip by sample.
- Slip operations are undoable via `SlipClipAction implements UndoableAction`.
- Persist `sourceOffset` (already persisted) — verify no regression under slip edits.
- Tests: slip within bounds shifts content correctly; slip at source-edge clamps; slip on a MIDI clip shifts notes by the same time delta.

## Non-Goals

- Slip across clips (moving content from one clip to another — a different operation).
- Time-stretch during slip (that is story 042).
- Automatic quantize-to-grid during slip.
