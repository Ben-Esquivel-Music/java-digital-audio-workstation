---
title: "Channel-Strip Presets, Track Templates and Their Undo Keep Plugin-Backed and Offline Inserts"
labels: ["bug", "mixer", "plugin", "undo", "templates"]
---

# Channel-Strip Presets, Track Templates and Their Undo Keep Plugin-Backed and Offline Inserts

## Motivation

A channel-strip preset and a track template capture a strip's inserts through `TrackTemplateService.captureChannelStrip`, which keeps an insert only as an `InsertEffectSpec(type, parameters, bypassed)` and skips every slot whose `getEffectType()` is `null` or `CLAP_PLUGIN` ("unsupported for templates/presets"). That skips an external JAR or CLAP plugin's slot, and, since story 334, an offline insert too: `InsertSlot.ofOffline` sets no effect type. `TrackTemplateService.captureTrack` captures a template's strip through the same method.

Applying a preset is a live loss, not only an incomplete preset. `TrackTemplateService.applyPreset` first removes every insert of the channel, then adds the preset's. `ApplyChannelStripPresetAction.execute` captures the channel's previous state with `captureChannelStrip` before it applies, and `undo` applies that captured state. So applying a strip preset and then undoing it removes the strip's plugin-backed and offline inserts for good: the undo cannot put back what the capture skipped. `TrackTemplateController` reaches the action through the undo manager in production.

Story 334's studio ruling E10.7 rules this a live loss outside project persistence and gives it high priority.

## Goals

- **The undo of Apply channel strip restores the strip exactly**: every insert it held, in order, the same slot objects or equal ones (plugin-backed inserts with their plugin, offline inserts with their saved element), with bypass, expensive and sidechain state.
- **Presets and templates keep plugin-backed and offline inserts**: a capture keeps each such insert by its descriptor (source kind, plugin id, version, parameters or state, as story 334's project format does), and an offline insert as its saved element; applying it resolves each through the same resolver a project open uses, and an insert that does not resolve is placed offline, never skipped.
- **The insert chain never shortens silently** through a preset or a template, as story 334 rules for every load path.

## Acceptance Criteria

1. Apply a strip preset to a channel holding a JAR plugin insert and an offline insert, then undo: the channel holds both again, at their positions, the plugin with its parameters and the offline insert with its saved element.
2. Save a channel-strip preset from a strip holding a first-party plugin, a JAR plugin and an offline insert, and apply it to another channel: all three are present in order, the JAR plugin resolved through the registry (off the FX thread) or offline with its cause when it is not installed.
3. A track template made from such a track creates a track whose strip holds the same chain.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Apply, then undo, on a strip `[built-in effect, JAR plugin, offline insert]`: the chain after the undo equals the chain before the apply, and the strip renders bit-equal to its render before the apply.
- Capture and apply of a preset with each insert kind: chain length and order equal; the offline insert's saved element written back equal by the next project save.
- Fault checks, each run once, each turning a test red: the capture skipping a slot with no effect type again; the undo restoring from the lossy capture.

## Non-Goals

- A new preset file format beyond what the descriptor needs.
- CLAP resolution in production (story 034).

## Technical Notes

- Ruling: studio engineer, story 334, E3 ("Save channel strip / track template; apply strip preset") and E10.7 ("a live loss outside project persistence … Give it high priority").
- Code locators: `TrackTemplateService.captureChannelStrip`, `captureTrack`, `applyPreset`, `createTrackFromTemplate`; `ApplyChannelStripPresetAction` (`execute`, `undo`); `ChannelStripPreset`, `InsertEffectSpec`; `TrackTemplateController`; story 334's descriptor and offline insert: `ProjectSerializer.writePluginDescriptor`, `writeOfflineInsert`, `InsertSlot.ofOffline`, `OfflineInsert`, `PluginResolver`, `PluginRegistry.resolve`.
- Design references: Persistence Integrity book §4.5 (the descriptor and the offline insert).
- Cross-refs: story 334, future story 366 (Duplicate Track copies offline inserts with their descriptor).

## Status

- Deferred
- Priority: high (studio ruling E10.7: a live loss through undo of Apply channel strip)
