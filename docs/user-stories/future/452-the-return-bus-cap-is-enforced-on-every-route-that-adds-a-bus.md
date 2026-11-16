---
title: "The Return-Bus Cap Is Enforced on Every Route That Adds a Bus"
labels: ["bug", "mixer", "routing", "undo", "persistence"]
---

# The Return-Bus Cap Is Enforced on Every Route That Adds a Bus

## Motivation

The render pipeline preallocates send buffers for at most `Mixer.MAX_RETURN_BUSES` (16) return buses; sends to any bus beyond that are silent. `Mixer.addReturnBus(String)` enforces the cap: `if (returnBuses.size() >= MAX_RETURN_BUSES) { throw new IllegalStateException("cannot exceed " + MAX_RETURN_BUSES + " return buses"); }`. Not every route that adds a bus goes through that check:

- **Undo and redo.** `Mixer.addReturnBus(MixerChannel)`, "used by undo operations to re-add a previously removed return bus", adds the bus with no cap check. `RemoveReturnBusAction.undo()` and `AddReturnBusAction.execute()` on redo call it, so removing a bus, adding a new one in its place and then undoing the removal leaves the mixer with more buses than the cap.
- **Project load.** `ProjectDeserializer.parseMixer` reuses the mixer's existing return buses and adds each further bus in the file through `addReturnBus(String)`, so a file with more buses than the cap does not exceed it; the load throws `IllegalStateException` at the first bus past the cap instead. What the reader then does with the file is this story's to rule.

Story 337 made the overflow visible during playback: the first block a pipeline renders on a render thread with too many buses records one `audio.config` record (subject `RenderPipeline.RETURN_BUS_CAP_SUBJECT`, detail the bus count), and the drain shows the WARNING "This mix has {0} return buses but only {1} can receive sends, so sends to the others are silent. Remove return buses you do not need." (its ruling E25). The flag latches only when that record was delivered, and an offline render, which runs on no render thread, writes one WARNING log record per pipeline before its first block instead (story 337, ruling E76). Preventing the overflow is a mixer feature, so story 337 left it to this story (story 337's Resolution, "Deferred"; `RenderPipelineReturnBusCapTest` says "The undo overload does not enforce the cap (future story 452)").

## Goals

- **No route leaves the mixer above the cap.** Undo, redo and any other caller of `addReturnBus(MixerChannel)` either refuse an add past the cap, telling the user why, or are made unable to reach it; the undo history stays consistent either way.
- **Loading a project with more buses than the cap is decided, not accidental.** With the studio engineer, this story rules what a file with more than 16 return buses does at load — refuse, load the first 16 and report the rest, or another rule — and the reader does exactly that, with a message that names the buses it could not load.
- **The story-337 notice stays as a backstop.** The render-path `audio.config` record keeps catching any route this story misses.

## Acceptance Criteria

1. Undoing and redoing return-bus edits never leaves more than `Mixer.MAX_RETURN_BUSES` return buses in the mixer.
2. A project file with more return buses than the cap loads, or is refused, as the ruling says, and the user is told which buses were not loaded.
3. Every route that adds a return bus is listed in the story's Resolution with how it enforces the cap.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- An undo test: at the cap, remove a bus, add a new one, undo the removal; the mixer holds at most 16 return buses and the user is told why when an add is refused.
- A redo test of `AddReturnBusAction` at the cap.
- A load test with a file holding 17 return buses: the ruled outcome and its message.
- `RenderPipelineReturnBusCapTest` stays green, its fixture reaching the over-cap state through a route this story still allows, or rewritten in place if none remains.

## Non-Goals

- Raising the cap, or sending to more than 16 return buses.
- The render-path notice itself: story 337.

## Technical Notes

- daw-core, `core.mixer.Mixer`: `MAX_RETURN_BUSES = 16`; `addReturnBus(String)` (checks the cap) and `addReturnBus(MixerChannel)` (does not).
- daw-core, `core.mixer.AddReturnBusAction` (`execute()` re-adds through the unchecked overload on redo) and `RemoveReturnBusAction` (`undo()` re-adds through it).
- daw-core, `core.persistence.ProjectDeserializer.parseMixer`: reuses `project.getMixer().getReturnBuses()` and adds each further `return-bus` element through `addReturnBus(busName)`.
- daw-app, `MixerView` adds a bus through `project.getMixer().addReturnBus(busName)`.
- daw-core, `core.audio.RenderPipeline`: the once-per-pipeline `audio.config` record (`returnBusCapNoticeRaised`, `RETURN_BUS_CAP_SUBJECT`), the flag set from the record's answer, and `renderOffline`'s one WARNING per pipeline (story 337, ruling E76); `RenderPipelineReturnBusCapTest`. The warning in an export's result is future story 454's.
- Cross-refs: story 337 (ruling E25 and its Resolution's "Deferred" list), the undo framework's return-bus actions.

## Status

- Deferred
