---
title: "Take Groups: Tracks Recorded in One Pass Are Comped in Lockstep"
labels: ["enhancement", "recording", "comping", "editing"]
---

# Take Groups: Tracks Recorded in One Pass Are Comped in Lockstep

## Motivation

Comping is per track: each track owns its `TakeComping`, with its own take lanes and comp regions, and a swipe on one track's lane sets that track's regions only (`SetCompRegionAction`). A loop pass recorded on several armed tracks gives each of them lanes of its own (`RecordingPipeline` names each new lane `Take <n>` by its index on its track), and nothing ties a region on one track to the same range on another.

A drum kit or any multi-microphone source recorded in one pass must be comped in lockstep: the same take chosen at the same boundaries on every microphone, or the phase relationships between the microphones break at each boundary and the kit smears. Today the engineer has to repeat every swipe on every track, exactly.

Story 334's studio ruling E13 drops the term "take groups" from the persisted model (it names nothing in the model since story 328 deleted `TakeGroup`) and rules lockstep comping a feature, not persistence.

## Goals

- **A take group links the lanes one pass recorded on several tracks**, made when the pass is recorded and editable afterwards (ungroup, regroup).
- **A swipe on a grouped lane sets the same region on every grouped track**, as one undoable edit, and each track's compile follows.
- **The group is project data** and round-trips through the project format under a schema step, as story 334's take lanes do.

## Acceptance Criteria

1. Loop-record one pass on three armed tracks; a swipe on lane 2 of one track selects lane 2 over the same range on all three, and one undo reverts all three.
2. Ungrouping a track makes its swipes its own again.
3. Save and reopen keep the group; an older file opens with no groups.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A three-track pass with distinct signals per track: after one swipe, each track's compile takes the same lane over the same beats, sample-aligned.
- Undo and redo of a grouped swipe restore every track together.
- A round trip of the group, and a pre-group file opening with none.
- Fault check, run once, turning a test red: a grouped swipe that updates only the track it was made on.

## Non-Goals

- Crossfades at comp boundaries (future story 421).
- Comp output edits (future story 353).

## Technical Notes

- Ruling: studio engineer, story 334, E13 ("FUTURE: Take groups: tracks recorded in one pass are comped in lockstep").
- Code locators: `TakeComping`, `TakeLane`, `CompRegion`, `SetCompRegionAction`, `CompileCompAction`, `RecordingPipeline` (where a pass creates its lanes), `CompToolHandler`, `ClipInteractionController.handleCompPress`; story 334's take schema (`ProjectSerializer.buildTakes`, `ProjectDeserializer.parseTakes`) and the migration registry.
- Design references: Persistence Integrity book §4.6 (the take schema), §6.3 (schema evolution).
- Cross-refs: story 328 (comping), story 334 (take persistence).

## Status

- Deferred
