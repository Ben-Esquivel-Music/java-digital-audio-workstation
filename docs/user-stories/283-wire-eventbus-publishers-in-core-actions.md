---
title: "Wire EventBus Publishers in Core Action Classes (Activate the Dormant Bus)"
labels: ["enhancement", "architecture", "events"]
---

# Wire EventBus Publishers in Core Action Classes (Activate the Dormant Bus)

## Motivation

Story 203 landed the central typed event bus — sealed event hierarchies in `daw-sdk` (`ClipEvent`, `PluginEvent`, `MixerEvent`, `TrackEvent`, `AutomationEvent`, `TransportEvent`, `ProjectEvent`) and a working `DefaultEventBus` in `daw-core/.../event/`. The infrastructure is fully implemented, exported, and tested.

**But zero production code publishes a single event.** A grep for `eventBus.publish(` across the reactor returns three hits: the story-203 doc, the Workshop controller's TODO comments, and the `OverflowStrategy` enum's javadoc. No `UndoableAction` subclass, no `MainController` wiring, no transport/project code invokes `bus.publish(...)` anywhere. Any subscriber added today silently no-ops forever — and `EventBusMetrics` would report all-zero published counts, which is the smoke test for this exact condition.

Story 281's second-pass review surfaced this as the root blocker: the Workshop `WorkshopSelectionHostController#pluginPanelCache` and `#clipEditorCache` need event-driven invalidation, but subscribing without publishers is a misleading code surface. The review deferred S3 (cache invalidation) to this story, with precise TODO blocks at `daw-app/.../views/WorkshopSelectionHostController.java:117-128` and `:138-143` naming the exact event types and invalidation rules.

This story activates the bus by wiring publishers into the existing action classes so subscribers become meaningful.

## Goals

- Decide and document the **publisher seam**: a single utility (e.g. `daw-core/.../event/EventBusPublisher.java`) that holds a `Supplier<EventBus>` (or accepts the bus via constructor injection where actions are already DI-friendly) and exposes typed publish helpers — so individual action classes don't each take a constructor dependency on the bus and so tests can run actions with a no-op bus when they don't care. Bus instance comes from `daw-core` (`DefaultEventBus`); the SDK type `EventBus` is the public contract.

- **Minimum-coverage scope (required) — unblocks Workshop S3:**
  - **`ClipEvent`** publishers in clip-mutation actions: `AddClipAction.java`, `RemoveClipAction.java`, `MoveClipAction.java`, `SplitClipAction.java`, `CutClipsAction.java`, `PasteClipsAction.java`, `DuplicateClipsAction.java`, `GroupMoveClipsAction.java`, `CrossTrackMoveAction.java`, `GlueClipsAction.java`, `SlipClipAction.java`, `NudgeClipsAction.java`, `SetClipLockedAction.java`, plus the MIDI-clip mutations under `daw-core/.../midi/`. Each action publishes the matching `ClipEvent.{Added,Removed,Moved,Trimmed,Renamed}` after the model mutation succeeds and before returning. Compound actions (`CompoundUndoableAction`) publish per-leaf, not once for the compound.
  - **`PluginEvent`** publishers in insert-effect actions: locate the equivalents of "add/remove plugin instance" (insert-effect insert / remove / move / bypass-toggle / parameter-set) and publish `PluginEvent.{Loaded,Unloaded,Bypassed,ParameterChanged}`. `PluginEvent.Crashed` is published from the host fault-isolation path, not an action.
  - **`MixerEvent.ChannelAdded` / `ChannelRemoved`** in the channel-creation / channel-removal action paths — needed because Workshop's plugin-panel cache invalidation rule keys on a `trackId` whose channel disappeared. (Other `MixerEvent` variants — `GainChanged`, `PanChanged`, `MuteChanged`, `SoloChanged` — are stretch; mixer parameter changes already drive the UI via property bindings and don't need event-bus reactivity yet.)

- **Stretch scope (nice to have, file as follow-on if it bloats this story):**
  - `TrackEvent.{Added,Removed,Renamed,Muted,Soloed,Armed}` from the track-mutation action classes.
  - `AutomationEvent.{LaneAdded,LaneRemoved,PointAdded,PointRemoved,PointMoved}` from the automation-mutation paths.
  - `TransportEvent.{Started,Stopped,Seeked,TempoChanged,LoopChanged}` from `TransportController` (or equivalent).
  - `ProjectEvent.{Opened,Closed,Saved,Created,Undone,Redone}` from `ProjectLifecycleController` / undo manager.

- **Undo / redo symmetry:** when an `UndoableAction` undoes, publish the **inverse** event (e.g. `undo()` of `AddClipAction` publishes `ClipEvent.Removed`). Compound undo publishes per-leaf in reverse order. The `ProjectEvent.Undone` / `ProjectEvent.Redone` envelope is **independent** of the leaf events — both fire.

- **Dispatch mode default:** publishers always call `bus.publish(event)`; the per-subscription dispatch mode (`ON_CALLER_THREAD` vs `ON_UI_THREAD` vs `ON_VIRTUAL_THREAD`) is the **subscriber's** choice via `bus.on(type, mode, handler)`. Producers do not marshal threads.

- **Wire Workshop S3 in the same PR** (the entire point of this story is to unblock it): in `WorkshopSelectionHostController`, replace the two TODO blocks with the actual `bus.on(PluginEvent.Unloaded.class, ON_UI_THREAD, …)`, `bus.on(MixerEvent.ChannelRemoved.class, ON_UI_THREAD, …)`, and `bus.on(ClipEvent.Removed.class, ON_UI_THREAD, …)` subscriptions using the invalidation rules already documented inline at `:117-128` and `:138-143`. Delete the TODOs once wired.

- Tests:
  - Per minimum-scope publisher: a unit test asserts that executing the action through its normal entry point causes the matching event variant to appear on a test subscriber (use `bus.on(EventType.class, handler)` with `ON_CALLER_THREAD`). One test per action — not per variant.
  - **Undo symmetry test:** execute → assert event A; undo → assert inverse event; redo → assert event A again.
  - **Metrics smoke test:** after running the existing test suite, `EventBusMetrics.publishedCount(ClipEvent.class) > 0` and the same for `PluginEvent`, `MixerEvent` — proves the publishers are actually live, not silently bypassed. This is the canonical "are publishers wired?" assertion.
  - **Workshop S3 integration test** (re-enables / adds): with a real `DefaultEventBus`, build the Workshop controller, populate both caches, publish a `PluginEvent.Unloaded` for the relevant `pluginInstanceId` (via a synthetic action or direct bus call), assert the matching cache entry is evicted; same for `ClipEvent.Removed` against `clipEditorCache`.

## Non-Goals

- **No new event variants.** This story consumes story 203's sealed hierarchy as-is. If a publisher reveals a missing variant (e.g. `MixerEvent.RoutingChanged`), file separately.
- **No removal of existing direct-listener / property-binding paths.** The bus runs alongside them; migration of consumers off direct listeners is a separate refactor (story 203's "retire ad-hoc listener interfaces step by step" is intentionally out of scope here).
- **No `LegacyListenerAdapter` rewiring.** That adapter exists for incremental migration; it stays as-is.
- **No backpressure tuning.** Default buffer capacity (`EventBus.DEFAULT_BUFFER_CAPACITY = 256`) and the per-type overflow strategies from story 203 are used unchanged. If a publisher is observed to overrun, file separately.
- **No virtual-thread / UI-thread plumbing changes.** `DispatchMode` already exists per story 203 + 205; this story just wires the producer side.
- **No new instrumentation UI.** `EventBusMetrics` is the test assertion seam; a debug view on top of it is out of scope.
- **No event publishing from the audio thread.** If an action runs on the audio thread (rare for `UndoableAction`s — they're typically user-initiated), the publish call must hop off the audio thread first (e.g. via the existing UI-side queue). Audio-thread-originating telemetry (xruns, meters) is story-203 future work, not in scope here.

## Technical Notes

- The `WorkshopSelectionHostController` TODO blocks at `:117-128` and `:138-143` are the canonical contract for what "a correctly-wired subscriber" looks like — they predate the publishers by design. Use them as the template for both this story's S3 wiring and any future subscriber.

- **Subscriber-side cancellation:** every consumer (including Workshop S3) must hold the `EventBus.Subscription` handle in a strong field and `close()` it in its `dispose()` chain. Long-lived controllers leaking subscriptions across re-opens of the same view is a future-bug class; flag in code review.

- **Compound action ordering:** publish events in the same order as the model mutations within the compound. Subscribers may rely on "if I saw `ClipEvent.Removed` before `ClipEvent.Added` with the same `clipId`, treat as a move within a compound."

- **Test bus seam:** for tests that don't care about events, expose a `NoOpEventBus` (or use `DefaultEventBus` with no subscribers — `publish` to a bus with no subscribers is a constant-time metrics-bump and should remain so per story 203's contract). Don't make every action's test set up subscribers.

- This story is the **prerequisite for any new reactive consumer.** Before recommending an event-bus subscriber for cache invalidation, telemetry, undo coalescing, browser auto-refresh, mixer refresh, or any model-mutation reactivity, confirm 283 has landed — or carve the specific event types into 283's scope.

- Reference: story 203 (event bus + sealed hierarchies), story 281 second-pass review S3 deferral, `WorkshopSelectionHostController.java:117-145`.
