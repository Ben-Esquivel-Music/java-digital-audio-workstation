---
title: "Duplicate Track Copies the Whole Track"
labels: ["bug", "tracks", "mixer", "midi", "undo"]
---

# Duplicate Track Copies the Whole Track

## Motivation

Copy Track in the track strip's context menu (`TrackStripController.buildTrackContextMenu`) calls `DawProject.duplicateTrack`, which story 330 fixed so the copy's mixer channel sits right after the original's. What the copy holds is still far less than the track it was made from:

- **No MIDI.** `Track.duplicate` copies the track's own properties (volume, pan, mute, solo, phase, input monitoring, input device and routing, colour, SoundFont assignment, MIDI input device, automation mode) and a copy of every audio clip (`AudioClip.duplicate`). It copies neither the track's `MidiClip` — notes, CC lanes, lock flag — nor its MIDI-effect chain. A duplicated MIDI track is an empty MIDI track with the same name plus " (copy)".
- **A default mixer strip.** `DawProject.duplicateTrack` gives the copy a new `MixerChannel` (`new MixerChannel(copy.getName(), …)`) with the channel's defaults: no inserts, no sends, no cue sends, no VCA membership, default fader and pan. `Track.duplicate` copies the track's volume and pan, so the copy's `Track` and its channel disagree from the start. Engineers expect a duplicate to carry the source's channel settings (story 330's studio ruling, round 3, Q2).
- **Redo makes another track.** The Copy Track action's `execute()` calls `duplicateTrack` every time it runs, and `redo` runs `execute()` again, so each redo creates a new `Track` object with a new id. An undo entry recorded on the first copy — a rename, a mute, a clip edit — then refers to an object that is no longer in the project, and redoing it changes nothing the user can see.
- **No automation.** `Track.duplicate` does not copy the track's `AutomationData` either.

## Goals

- **A duplicate is a full copy.** The copy carries the source's MIDI clip (notes, CC lanes with breakpoints, lock flag), MIDI-effect chain (each effect a fresh instance with the same settings), automation lanes, and audio clips, under a new track id.
- **The channel strip is copied.** The copy's channel gets the source channel's fader, pan, mute, solo, phase, inserts (fresh processor instances with the same parameters, bypass and sidechain source), sends with their levels and taps, and output routing. Membership in VCA groups, channel links and cue buses follows a rule the studio engineer rules on before implementation (copy, offer, or leave out — a channel link, for example, cannot hold three channels).
- **Redo restores the same copy.** The Copy Track action creates its copy once; undo removes it and redo re-adds that same object, so later undo entries stay valid.

## Acceptance Criteria

1. Duplicating a MIDI track with notes, CC lanes, a locked clip and an arpeggiator gives a copy whose clip and chain are equal field by field, and editing the copy leaves the source unchanged.
2. The copy's channel has the source's fader, pan, inserts with their parameters, sends and routing; its `Track` and channel agree on volume and pan.
3. Copy Track, undo, redo gives back the same `Track` object with the same id; a rename of the copy recorded before the undo applies again after the redo.
4. The copy and the source survive save and reopen with their own settings (story 330's channel-order and identity rules hold).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **MIDI copied**: `DawProject.duplicateTrack` over a track with two notes, a CC lane with breakpoints, `locked = true` and a non-default arpeggiator; the copy's clip and chain compare equal field by field and are distinct objects.
- **Strip copied**: a source channel at a non-default fader and pan, with a compressor keyed from another track and a send to a return bus; the copy's channel holds equal values and its own processor instances.
- **Redo keeps the copy**: drive the Copy Track action through the `UndoManager`: execute, rename the copy, undo twice, redo twice; the project holds the original copy, renamed.
- **Round trip**: duplicate, save and reopen; every value of the copy and of the source compares equal by track id.

## Non-Goals

- Copying a track between projects.
- Duplicating takes and comp selections (story 334 persists them first).

## Technical Notes

- Code locators: `Track.duplicate`, `DawProject.duplicateTrack`, `TrackStripController.buildTrackContextMenu` (the Copy Track `UndoableAction`), `MidiClip`, `Track.getMidiEffectChain`, `MixerChannel`, `InsertEffectFactory`.
- Found in story 330 (the studio engineer's round-3 Q2 ruling and the implementers' adjacent findings).
- Cross-refs: story 330 (channel order equals track order, track identity), story 355 (MIDI editor edits through the undo history), story 151 (arpeggiator processing), story 365 (the engine sees a duplicate as one change).

## Status

- Deferred
