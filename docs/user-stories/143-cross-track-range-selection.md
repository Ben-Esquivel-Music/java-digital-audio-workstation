---
title: "Cross-Track Range Selection with Edit Tools"
labels: ["enhancement", "editing", "arrangement", "selection"]
---

# Cross-Track Range Selection with Edit Tools

## Motivation

The current arrangement-view selection model is per-clip. Selecting "everything from bar 9 to bar 16 on all tracks" requires clicking each clip individually, and operations like "copy bar 9–16 across every track" have no clean equivalent. Every DAW supports time-range selection that spans tracks: Pro Tools' "Selector" tool, Reaper's "time selection," Logic's "marquee tool." Combined with cut/copy/paste it unlocks the primary edit-at-range workflow.

Story 072 introduces a time-selection *visualization*; this story adds the interaction model and the operations that consume it.

## Goals

- Add `CrossTrackSelection` record in `com.benesquivelmusic.daw.sdk.edit`: `record CrossTrackSelection(long startFrames, long endFrames, Set<UUID> trackIds)`.
- Marquee-drag with the `EditTool.RANGE` tool across the arrangement canvas creates a `CrossTrackSelection`; the vertical span determines `trackIds`.
- Operations `cut`, `copy`, `paste`, `duplicate`, `delete` on a `CrossTrackSelection` apply to the clipped intersection of each selected track's clips.
- Clips partially inside the range are split at the boundaries (using existing `splitClip`) before operations apply; parts outside the range remain untouched.
- Paste honors the destination track set (pasting onto the selected tracks) and the timeline destination.
- Ripple mode (story 138) interacts: `cut` under `ALL_TRACKS` ripple closes the gap on all selected tracks.
- Selection rendering uses the existing story 072 chrome; the cross-track selection highlights the affected track lanes.
- Undo: every operation on a `CrossTrackSelection` is a single `CompoundUndoableAction`.
- Tests: partial-clip splitting at boundaries is sample-accurate; operations on an empty track set are no-ops; paste preserves relative inter-track offsets.

## Non-Goals

- Non-contiguous track selection (every selected track is between the top and bottom of the marquee).
- Cross-project range operations.
- Freeform polygon selection (rectangular marquee only).
