---
title: "Plugin-Parameter Automation Lanes Are Saved"
labels: ["bug", "persistence", "automation", "plugins"]
---

# Plugin-Parameter Automation Lanes Are Saved

## Motivation

A track's `AutomationData` holds three kinds of lane: mixer-parameter lanes, object-parameter lanes (spatial trajectories, story 172) and plugin-parameter lanes keyed by `PluginParameterTarget`. Only the first two reach the project file:

- **The writer skips plugin lanes.** `ProjectSerializer.buildAutomationData` writes `getLanes()` as `<lane>` and `getObjectLanes()` as `<object-lane>`, and returns early when both are empty (`ProjectSerializer.java:824-869`). `getPluginLanes()` is never read, and the reader has no element for them.
- **The model, the recorder and the renderer all use them.** `AutomationRecorder.recordValue(PluginParameterTarget, …)` and `laneFor` create them through `AutomationData.getOrCreatePluginLane` (`AutomationRecorder.java:126-129`, `:241-242`), and `RenderPipeline.applyActivePluginParameterAutomation` plays them into the insert's plugin on the audio thread (`RenderPipeline.java:2515-2535`).
- **No application surface creates one yet.** Nothing in daw-app references `PluginParameterTarget`; story 101 owns the lane selector and recording modes for plugin parameters. So the loss is latent today, and becomes a silent loss of every recorded plugin move the day story 101 lands.
- **The target would not resolve after a reopen anyway.** `PluginParameterTarget.pluginInstanceId` is documented as a per-instance id ("typically the plugin descriptor id combined with the track id and slot index"), but `RenderPipeline.findPluginByInstanceId` compares it with `plugin.getDescriptor().id()`, the plugin's type id (`RenderPipeline.java:2540-2551`), so a lane drives the first slot of that plugin type on the channel. And a built-in slot the reader rebuilds from its `effect-type` (`InsertEffectFactory.createSlot`) holds no `DawPlugin` (`InsertSlot.getPlugin()` is `null` unless the processor is itself a plugin), so `findPluginByInstanceId` finds nothing for it.

For an engineer an automated filter sweep or reverb throw is part of the mix; a session that plays it until it is saved and reopens without it has lost work (Persistence Integrity book §2.1, rejection §9.3).

## Goals

- **Plugin lanes round-trip.** Each plugin-parameter lane is written with its target — the slot it belongs to, the parameter id, and the range, default and unit metadata — its visibility, and its points (time, value, interpolation), and the reader restores it.
- **One instance identity.** A lane names one insert slot, by a stable per-instance id the slot keeps across save and reopen, and the renderer resolves it by that id — the same identity the Javadoc of `PluginParameterTarget` describes.
- **A lane whose slot cannot be resolved is kept and reported**, never dropped: it stays inert, is written back unchanged, and is counted in the open's report (story 330's announcement).

## Acceptance Criteria

1. A project with a plugin-parameter lane on a built-in insert saves and reopens with the lane, its points and its target, and the renderer applies it to the same slot after the reopen.
2. Two slots of the same plugin on one channel keep separate lanes, before and after a reopen.
3. A lane whose slot is gone is kept as written, inert, and reported on open.
4. The parity harness lists every new element and attribute; `ProjectRoundTripInventoryTest`'s automation group compares plugin lanes.
5. A file without plugin lanes opens as today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Round trip**: record two points on a plugin parameter through `AutomationRecorder`, save and reopen; `getPluginLanes()` holds an equal lane, and one render block applies the same value to the same slot as before the save.
- **Two instances**: two compressors on one channel with lanes on the same parameter id; after a reopen each lane drives its own slot.
- **Unresolved slot**: a lane naming a slot the file does not hold is restored inert, reported once, and written back unchanged.
- **Harness**: removing the new lane reader fails `ProjectSchemaParityContractTest` naming it.

## Non-Goals

- The automation lane UI and recording modes for plugin parameters (story 101).
- Descriptors for plugin-backed (external JAR, CLAP) slots (story 334); this story's identity must be the one story 334's descriptors carry.

## Technical Notes

- Code locators: `ProjectSerializer.buildAutomationData`, `ProjectDeserializer.parseAutomationData`, `AutomationData` (`getPluginLanes`, `getOrCreatePluginLane`), `AutomationRecorder.laneFor`, `PluginParameterTarget`, `RenderPipeline.applyActivePluginParameterAutomation` / `findPluginByInstanceId`, `InsertSlot`, `InsertEffectFactory.createSlot` / `createSlotFromPlugin`.
- Story 334 was checked first: it persists plugin-backed insert descriptors, take stacks and the tempo map, not automation lanes. Story 101 owns plugin-parameter automation in the UI and does not mention persistence.
- Found in story 330 (the parity harness implementer's adjacent finding).
- Cross-refs: story 101, story 334, story 330 (the parity harness).

## Status

- Deferred
