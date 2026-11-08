---
title: "A Plug-In Fault's Bypass Stays Out of the Saved Project"
labels: ["plugin", "persistence", "failure-surfacing"]
---

# A Plug-In Fault's Bypass Stays Out of the Saved Project

## Motivation

When a plug-in throws on the audio thread, `PluginInvocationSupervisor` bypasses its insert (`handleFault`: `slot.setBypassed(true)`, and the same for a parameter-callback fault through `reportAudioFault`), and the Plugin Fault Log's Re-enable undoes it (`reenable`: `slot.setBypassed(false)`, then the quarantine is cleared and the chain rebuilt). Both write `InsertSlot`'s one `bypassed` field, which `ProjectSerializer` saves as the insert's `bypassed` attribute and `ProjectDeserializer` restores as a plain bypass, and neither marks the project dirty: `InsertSlot.setBypassed` assigns the field (a re-enable also clears the slot's parameter-fault flag) and marks nothing. So whether a safety bypass the session applied, or the user's re-enable, reaches the saved project depends on whether an unrelated edit dirtied it: a project saved after a fault reopens with the plug-in bypassed as if the engineer had switched it off, and a project with nothing else unsaved closes without saving a re-enable, while the fault log itself is per session ("No plugin faults recorded this session."). This was found while verifying PR #989 review 5711460812 (story 333); it is not a limit of story 333.

## Goals

The studio engineer's ruling: a session's safety measure must not silently change the next session's mix.

- **A fault's bypass is session state**, held apart from the user's own bypass (a fault flag on the slot).
- **The saved `bypassed` attribute carries only the user's decision.**
- **A reopened plug-in is active unless the user bypassed it.** If it faults again, the supervisor bypasses it again and says so, as it does now.
- **Re-enable clears the fault state and marks nothing dirty.**
- **A user's own bypass toggle stays a user edit**, on the rack's gated, undoable path.

## Acceptance Criteria

1. Fault, then save: the saved attribute is the user's bypass from before the fault, and the reopened insert is active.
2. A user bypass made before the fault is saved as bypassed.
3. Re-enable leaves the dirty state as it was.
4. The rack and the fault log show a faulted insert apart from a user's bypass.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A serializer round trip: a faulted insert saves the user's bypass and reopens active; a user-bypassed insert that then faults saves as bypassed.
- A supervisor fault on a test plug-in, then a save: the saved project holds the user's decision.
- Re-enable from the Plugin Fault Log: the dirty state is as it was.
- Fault checks, each run once, each turning its test red: the fault state saved as the bypass; Re-enable marks the project dirty.

## Non-Goals

- Persisting quarantine.

## Technical Notes

- Ruling (studio engineer, PR #989 review 5711460812, J5): tangential to story 333, listed under its "Deferred to future stories" only.
- The audio-thread fault path gains no check and no dirty mark (dawg-annotations-reflection §4 "Adding a new `@RealTimeSafe` method"; ruling R-5.2); any project-state work runs on the FX side.
- Code locators: daw-core `PluginInvocationSupervisor` (`handleFault`, `reportAudioFault`, `reenable`), `InsertSlot.setBypassed` and its parameter-fault flag, `ProjectSerializer` and `ProjectDeserializer` (the insert's `bypassed` attribute); daw-app `PluginFaultLogDialog` (Re-enable), `InsertEffectRack` (the user's bypass toggle).
- Design references: Failure Surfacing book §2.6 "State never lies; copy never overstates".
- Cross-refs: story 333 (the edit gate the fault log's Re-enable reads).

## Status

- Deferred
