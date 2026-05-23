---
title: "Wire Story-203 EventBus Publishers in Core Actions"
labels: ["enhancement", "core", "event-bus", "infrastructure"]
---

# Wire Story-203 EventBus Publishers in Core Actions

## Motivation

Story 203 landed a complete typed event-bus infrastructure: `daw-sdk/event/{ClipEvent,PluginEvent,MixerEvent,TrackEvent,AutomationEvent,TransportEvent,ProjectEvent}` (sealed hierarchies) plus `daw-core/event/DefaultEventBus` (a working dispatcher with DispatchMode + OverflowStrategy + EventBusMetrics + AutoSaveListener wiring).

**The bus is fully implemented but unused: zero production code publishes a single event.** No `UndoableAction` subclass, no `MainController` wiring, no mixer/transport/project code invokes `eventBus.publish(...)`. Every reactive consumer that would subscribe — including story 281's Workshop cache invalidation (see TODO blocks in `WorkshopSelectionHostController#pluginPanelCache` and `#clipEditorCache`) — silently no-ops.

This story lands the publisher side so the bus carries the events the SDK already defines.

## Goals

- Inject the application-wide `EventBus` instance into the production object graph so action classes can publish. Most likely sites:
  - `MainController` constructs / obtains the bus once and passes it to whoever creates actions, OR
  - The bus is exposed via a service-locator-style accessor that actions can resolve (mirror how `UndoManager` is currently wired).
  - Pick whichever matches existing core conventions; do not invent a new DI mechanism.
- Publish events from `UndoableAction#execute` and `#undo` in every action whose state change matches a sealed event variant in `daw-sdk/event/`. Minimum coverage (the set that unblocks story 281's S3 cache invalidation):
  - **ClipEvent**: `AddClipAction`, `RemoveClipAction`, `MoveClipAction`, `SplitClipAction`, `TrimClipAction`, `FadeClipAction`, `GlueClipsAction`, `DuplicateClipsAction`, `CutClipsAction`, `PasteClipsAction`, `NudgeClipsAction`, `SlipClipAction`, `GroupMoveClipsAction`, `CrossTrackMoveAction`, `ClipEdgeTrimAction`, `PitchShiftClipAction`, `TimeStretchClipAction`, `NormalizeClipGainAction`, `SetClipGainEnvelopeAction`, `ImportAudioClipAction`, `SetClipLockedAction`.
  - **PluginEvent**: `InsertEffectAction`, `RemoveEffectAction`, `InsertClapEffectAction`, `RemoveClapEffectAction`, `ReorderEffectAction`, `ToggleBypassAction`.
- Stretch coverage (land in the same story if scope allows; otherwise file follow-ons):
  - **TrackEvent**: `AddTrackAction`, `RemoveTrackAction`, `RenameTrackAction`, `MoveTrackAction`, `CreateTrackGroupAction`, `RemoveTrackGroupAction`, `MoveToFolderAction`, `RemoveFromFolderAction`, `FreezeTrackAction`, `UnfreezeTrackAction`, `BatchFreezeTracksAction`, `BatchUnfreezeTracksAction`, `RenderInPlaceAction`.
  - **MixerEvent**: `SetVolumeAction`, `SetPanAction`, `ToggleMuteAction`, `ToggleSoloAction`, `SetSendRoutingAction`, `SetSendTapAction`, `SetCueSendAction`, `AddReturnBusAction`, `RemoveReturnBusAction`, `CreateCueBusAction`, `DeleteCueBusAction`, `LinkChannelsAction`, `UnlinkChannelsAction`, `SetVcaGainAction`, `AssignVcaMemberAction`, `CreateVcaGroupAction`, `SetSoloSafeAction`.
  - **AutomationEvent**: `AddAutomationPointAction`, `MoveAutomationPointAction`, `RemoveAutomationPointAction`.
- Wire story 281's `WorkshopSelectionHostController` to subscribe (replace the two TODO blocks): on relevant `ClipEvent`, evict that clip's `clipEditorCache` entry; on relevant `PluginEvent`, evict matching `(trackId, insertIndex)` entries from `pluginPanelCache`. The TODO blocks already name the exact contract.
- Tests:
  - For each action with a publisher, a unit test that constructs the action with a stub `EventBus`, calls `execute()`, asserts the expected event variant was published with the expected payload. Symmetric test for `undo()`.
  - `WorkshopCacheInvalidationTest`: with a real `DefaultEventBus` wired into the controller, populate both caches, publish a `ClipEvent.Removed`/`PluginEvent.Removed`, assert the correct cache entries are evicted and untouched entries remain.
  - Existing action tests must still pass with no behavioral change in non-event semantics.

## Non-Goals

- TransportEvent and ProjectEvent publishers — those originate from the transport/project lifecycle, not from `UndoableAction`; file separately if needed.
- Subscriber consumers beyond the Workshop cache invalidation (story 281 S3). Other downstream consumers (telemetry, undo coalescing, mixer refresh, browser auto-refresh) are out of scope; this story lands publishers + one demonstrating consumer.
- Removing `LegacyListenerAdapter` — keeps existing listeners alive during the transition.
- Replacing the existing `UndoManager`/`UndoableAction` contract — actions gain an optional bus publish, they do not change shape.
- Performance tuning of `DispatchMode`/`OverflowStrategy` — accept the defaults DefaultEventBus ships with; revisit if metrics show hot-spots.

## Technical Notes

- The bus is `daw-core/.../event/DefaultEventBus.java`; the events are sealed under `daw-sdk/.../event/`. Both already exist — this story is purely producer wiring, no new contracts.
- Each action's publish call belongs at the end of a successful `execute()` (and a successful `undo()`), after the model mutation has applied. If `execute()` is no-op or rejected, do not publish.
- Compound actions (`CompoundUndoableAction`) should publish a single composite event if the SDK defines one; otherwise publish the constituent events in order. Check `daw-sdk/event/` for what's available before inventing anything.
- Workshop cache invalidation (S3 from story 281's review): in `WorkshopSelectionHostController` constructor, subscribe via `eventBus.subscribe(...)`; in `dispose()`, unsubscribe. Reference the TODO blocks already in the file for the exact eviction rules.
- The `EventBusMetrics` should show non-zero published counts after this story lands — a simple smoke test that proves the wiring is live end-to-end.
- Reference: story 203 (event-bus infrastructure), story 281 (first reactive consumer waiting on this), `WorkshopSelectionHostController` TODO blocks.
