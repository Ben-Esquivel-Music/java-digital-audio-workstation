---
title: "Small Round-Trip Gaps Left After Story 330"
labels: ["bug", "persistence", "mixer", "tracks", "transport"]
---

# Small Round-Trip Gaps Left After Story 330

## Motivation

Story 330 pinned the whole project schema with the write/read parity harness and made track identity, MIDI and pre/post-roll round-trip. Five smaller gaps were found on the way, each checked in the current tree. Each is a place where the session the engineer works in differs from the session a reopen gives back, or where the model keeps a fact the file cannot carry.

- **The mixer channel name does not follow a track rename.** `MixerChannel`'s name is final (`MixerChannel.java:101`) and `Track.setName` changes only the track (`Track.java:180-187`). The mixer strip label follows the track, but every reader of `channel.getName()` keeps the old name until the project is reopened — for example the insert rack's sidechain-source and insert pickers (`InsertEffectRack.java:435`, `:618`) and the VCA strip's members menu (`VcaStrip.java:254`), where an engineer can pick the wrong key input. Since story 330 the file no longer writes `channel@name`, and a reopened channel takes the track's name (`DerivedMixerAttributesPersistenceTest.renamedTrackReopensWithItsChannelNamedLikeIt`), so the live session and the reopened one disagree.
- **Folder-track membership is not written.** `DawProject.moveTrackToFolder` and `removeTrackFromFolder` keep a track's parent and a folder's children (`Track.getParentTrack`, `addChildTrack`), but `ProjectSerializer` writes no folder, parent or child token, so a reopen makes every track top-level. Nothing in the application creates a folder track yet (story 012 owns the folder UI), so the gap is latent.
- **A stale channel link blocks relinking.** `DawProject.removeTrack` keeps a deleted track's channel link in the `ChannelLinkManager`, so undo can restore it. In the mixer, the link toggle between the surviving channel and its new neighbour is then disabled with "One of these channels is already linked to a different channel. Unlink it first." (`MixerView.buildLinkToggle`, `MixerView.java:3437-3443`), and the only ways to unlink — that toggle between the two linked strips and its `ChannelLinkPopover` — left with the deleted strip. Since story 330 a save and reopen clears the link (the writer does not write a link to a track it does not write); within the session it stays.
- **A Waveshaper insert changes its label on reopen.** Inserted from the rack (`InsertEffectFactory.createSlotFromPlugin`), the slot is named by the plugin's descriptor, `Waveshaper / Saturation` (`WaveshaperPlugin`). The file stores that as `insert@name`, but the reader rebuilds a built-in slot from its `effect-type` (`InsertEffectFactory.createSlot`), named by `InsertEffectType.getDisplayName()`, `Waveshaper`. Story 330's studio ruling keeps the reader's rule — a built-in slot's label comes from its effect type — so the label given at insert time is the one out of line.
- **Pre-roll and post-roll share one enable flag.** `PreRollPostRoll(preBars, postBars, enabled)` has one flag for two sides, so a toggle loses a bar count in two cases. Turning one side off while the other rolls zeroes its count — `TransportController.onTogglePreRoll`'s Javadoc: "turning it off while post-roll rolls keeps post-roll on and zeroes the pre-roll bars, `(0, postBars, true)`" — and turning pre-roll back on then uses the default bars, not the user's. Turning one side on drops the other side's inert count — `toggled`'s Javadoc: "where the single `enabled` flag forces a choice, the other side's inert amount is dropped" — so a Pre-Roll click on `(2, 3, false)` gives `(2, 0, true)`. Story 330's studio ruling (F1, F6) asks for per-side flags.

## Goals

- **Channel names follow the track.** Every surface that names a track's channel shows the track's current name, live, with no reopen.
- **Folder membership round-trips.** The file carries each track's folder, and the reader restores it; a folder reference that does not resolve is dropped and reported (story 330's rule for references).
- **A deleted track's link does not block the mixer.** The surviving channel can be unlinked or linked to another channel while the link is kept for undo, or deleting the track removes the link as part of the same undoable edit — the studio engineer rules which before implementation.
- **One label rule for built-in slots.** A built-in slot shows the same label live and after a reopen.
- **Per-side pre/post-roll flags.** The model and the file carry `pre-roll-enabled` and `post-roll-enabled`; a migration maps a saved single flag to both; turning one side off keeps its bar count, and turning one side on keeps the other side's.

## Acceptance Criteria

1. After renaming a track, the sidechain-source picker, the insert picker and the VCA members menu show the new name.
2. A track moved into a folder is still in that folder after a save and reopen.
3. After deleting one track of a linked pair, the surviving channel can be linked to its new neighbour; undoing the deletion restores the original link.
4. A Waveshaper slot inserted from the rack shows the same label before and after a reopen.
5. Turning pre-roll off while post-roll rolls keeps the pre-roll bar count, and turning it on again restores it; from (2, 3, both off), turning pre-roll on gives pre on, post off, with post's 3 bars kept; a schema-2 file's flag opens as both per-side flags.
6. The parity harness lists every new attribute.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Rename**: rename a track; every listed picker and menu reads the new name from the model without a reopen.
- **Folder**: move a track into a folder, save and reopen through `ProjectManager`; `getParentTrack()` is the reopened folder.
- **Stale link**: link A and B, delete B; linking A to C succeeds (or is offered, per the ruling); undo restores A–B.
- **Label**: insert a Waveshaper through `InsertEffectFactory.createSlotFromPlugin`, save and reopen; the slot names are equal.
- **Per-side flags**: from `(2, 3, both on)`, turn pre-roll off and on; the value comes back to `(2, 3, both on)`. From `(2, 3, both off)`, turning pre-roll on gives pre on, post off, with post's 3 bars kept. A golden schema-2 fixture migrates `pre-roll-enabled="true"` to both flags.
- **Harness**: removing each new reader fails `ProjectSchemaParityContractTest` naming it.

## Non-Goals

- The folder-track UI (story 012).
- Return-bus names (story 364).
- Pre-roll "set from selection" (story 134).

## Technical Notes

- Code locators: `MixerChannel` (name), `Track.setName`, `InsertEffectRack`, `VcaStrip`, `DawProject.moveTrackToFolder` / `removeTrack`, `ChannelLinkManager`, `MixerView.buildLinkToggle`, `ChannelLinkPopover`, `InsertEffectFactory.createSlot` / `createSlotFromPlugin`, `WaveshaperPlugin`, `InsertEffectType`, `PreRollPostRoll`, `TransportController.toggled` / `onTogglePreRoll` / `onTogglePostRoll`, `ProjectSerializer`, `ProjectDeserializer.parseTransport`.
- Ruled by the studio engineer in story 330: round 1, F1 and F6 (per-side flags); round 2, R2 (the rename is a live-model problem; built-in slot labels come from the effect type; folder membership is a §3.1 inventory gap). The stale link and the Waveshaper label were found in story 330's track-identity analysis.
- Design references: Persistence Integrity design book §3.1 (the folder-track row, owner 374), §4.4.
- Cross-refs: story 012, story 134, story 322 (mixer control truth), story 330, story 364.

## Status

- Deferred
