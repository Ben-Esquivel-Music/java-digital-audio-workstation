---
title: "Wire EventBus Publishers for Track, Automation, Transport, and Project Events (Story 283 Stretch Scope)"
labels: ["enhancement", "architecture", "events"]
---

# Wire EventBus Publishers for Track, Automation, Transport, and Project Events (Story 283 Stretch Scope)

## Motivation

Story 283 wired the **minimum-coverage** scope of publishers into the central typed event bus from story 203: every `ClipEvent` variant from clip-mutation actions, every `PluginEvent` variant from insert-effect actions, and the `MixerEvent.ChannelAdded` / `ChannelRemoved` pair — enough to unblock the Workshop S3 cache invalidation that motivated 283 in the first place. The seam (`EventBusPublisher` with a `Supplier<EventBus>` shape) and the undo-symmetry contract are both live.

Story 283 explicitly flagged the remaining publishers as **stretch scope, file as follow-on if it bloats this story**:

> - `TrackEvent.{Added,Removed,Renamed,Muted,Soloed,Armed}` from the track-mutation action classes.
> - `AutomationEvent.{LaneAdded,LaneRemoved,PointAdded,PointRemoved,PointMoved}` from the automation-mutation paths.
> - `TransportEvent.{Started,Stopped,Seeked,TempoChanged,LoopChanged}` from `TransportController` (or equivalent).
> - `ProjectEvent.{Opened,Closed,Saved,Created,Undone,Redone}` from `ProjectLifecycleController` / undo manager.

That deferral was the right call for 283 — the minimum scope was already a large PR. But the four families now sit in the same state the clip / plugin publishers were in before 283: the sealed types exist, `DefaultEventBus` will route them, every potential subscriber is silently broken because the producer side is dormant. A reactor-wide grep confirms zero `TrackEvent`, `AutomationEvent`, `TransportEvent`, or `ProjectEvent` `bus.publish(...)` call sites in production code today (the only references are in `daw-core/.../event/package-info.java` and tests). `EventBusMetrics.publishedCount(TrackEvent.class)` will stay at zero forever, which is the canonical "publishers not wired" smoke condition story 283 established.

This story completes the publisher-side coverage so the rest of the bus stops being a no-op.

## Goals

- **Reuse the story-283 seam.** Same `EventBusPublisher` utility, same `Supplier<EventBus>` injection pattern, same convention of publishing **after the model mutation succeeds and before returning**. No new helper classes — if 283's helper is missing a typed `publish(TrackEvent)` overload, add it there; don't fork.

- **`TrackEvent` publishers in track-mutation actions.**
  - `TrackEvent.Added` from the action that creates a new track (audio / MIDI / aux / group).
  - `TrackEvent.Removed` from the action that deletes a track. If track removal cascades a `ChannelRemoved` (it does, via the existing 283 wiring), both events fire — `TrackEvent.Removed` first, then the cascade. Document the ordering.
  - `TrackEvent.Renamed` from the rename action.
  - `TrackEvent.Muted`, `.Soloed`, `.Armed` from the per-track mute/solo/arm toggle actions. Each carries the new boolean state.
  - Track-drag reorder (story 001 / `TrackDragReorderAction` or equivalent) publishes a `TrackEvent.Reordered` if that variant exists; if it does not yet (story 283's Non-Goals forbid new variants), model the reorder as `Removed` + `Added` at the new index — same pattern story 283 used for plugin reorder (commit `37af35de`). The choice depends on whether 202's sealed hierarchy already has `.Reordered`; check before implementing.

- **`AutomationEvent` publishers.**
  - `AutomationEvent.LaneAdded` / `.LaneRemoved` from the actions that add/remove an automation lane on a track.
  - `AutomationEvent.PointAdded` / `.PointRemoved` / `.PointMoved` from the breakpoint-editing actions in `daw-core/.../automation/`.
  - The automation-write path during recording (write/latch/touch from story 101) publishes per-batch, not per-sample — coalesce to one `PointAdded` per recorded segment so the bus does not get spammed at audio-thread rates. The publish call itself still happens off the audio thread; the audio thread queues, the UI-side drainer publishes.

- **`TransportEvent` publishers in transport controller.**
  - `TransportEvent.Started` / `.Stopped` from `play()` / `stop()` (including the auto-stop at end-of-arrangement).
  - `TransportEvent.Seeked` from playhead repositioning (click-to-seek, marker jumps, return-to-zero).
  - `TransportEvent.TempoChanged` from tempo edits — debounced if necessary so a tempo automation lane doesn't flood the bus.
  - `TransportEvent.LoopChanged` from loop-region edits and loop-enable toggles.
  - These publishers run on the **UI / control thread**, not the audio thread. If a transport state change originates on the audio thread (e.g. auto-stop at end-of-song), hop to the UI thread via the existing FX queue before publishing — per the same "no event publishing from the audio thread" rule story 283 established (Non-Goals).

- **`ProjectEvent` publishers in `ProjectLifecycleController` and the undo manager.**
  - `ProjectEvent.Opened`, `.Closed`, `.Saved`, `.Created` from the corresponding lifecycle methods. Long-running I/O for open/save stays on the background virtual thread (`ProjectLifecycleController.java:349-393`); the publish call happens once the I/O has resolved successfully, on the UI thread via `Platform.runLater`.
  - `ProjectEvent.Undone` / `.Redone` from the undo manager — fires **alongside** the leaf events of the undone/redone action, as the envelope. Order: leaf events first (preserving 283's compound-action ordering rule), then the envelope. Subscribers can pick either granularity.

- **Undo / redo symmetry** (the rule story 283 established) extends to every action type covered here:
  - `TrackEvent.Added` (do) ↔ `TrackEvent.Removed` (undo) ↔ `TrackEvent.Added` (redo).
  - `AutomationEvent.PointAdded` ↔ `.PointRemoved` ↔ `.PointAdded`. Same for lanes.
  - `TransportEvent.Seeked` is not undoable (transport state changes are user-driven, not history-tracked); confirm by checking that no `UndoableAction` subclass owns it.
  - `ProjectEvent.Undone` / `.Redone` are themselves not undoable (no recursive undo).

- **Tests:**
  - One unit test per action publisher (matching 283's "one test per action, not per variant" convention): execute via the normal action entry point, assert the matching event variant on a test subscriber with `ON_CALLER_THREAD` dispatch.
  - Undo symmetry test per family: execute → assert event A; undo → assert inverse event; redo → assert event A again.
  - **Metrics smoke tests:**
    - `assertThat(EventBusMetrics.publishedCount(TrackEvent.class)).isGreaterThan(0)` after the existing track-mutation test suite.
    - Same for `AutomationEvent`, `TransportEvent`, and `ProjectEvent`. These mirror the 283 metrics smoke tests for `ClipEvent` / `PluginEvent` / `MixerEvent` and prove every family has at least one live publisher.
  - **Transport integration test:** with a real `DefaultEventBus`, subscribe to `TransportEvent.Started`, call `transportController.play()`, assert the event arrives within 100 ms.
  - **Project integration test:** open a project, subscribe to `ProjectEvent.Saved`, call save, assert the event arrives after the background save I/O completes (use a `CountDownLatch`).
  - **Undo envelope ordering test:** execute a clip-add action (covered by 283), assert `ClipEvent.Added` then `ProjectEvent.Undone` is *not* produced on do; undo, assert `ClipEvent.Removed` (leaf) precedes `ProjectEvent.Undone` (envelope).

## Non-Goals

- **No new event variants.** Same constraint as story 283. If a publisher uncovers a missing variant, file separately — do not expand the sealed hierarchy in this PR.
- **No re-routing of existing direct-listener paths.** Property bindings, listener interfaces, and the `LegacyListenerAdapter` stay as they are. The bus runs alongside them; migration of consumers off direct listeners is its own refactor.
- **No backpressure tuning.** Default `EventBus.DEFAULT_BUFFER_CAPACITY = 256` and per-type overflow strategies stay unchanged. If a publisher in this scope overruns, file separately and gate the rate-limiting behind a story-203 follow-on.
- **No audio-thread publishing.** All four families publish from the UI / control thread. Audio-thread-originating telemetry (xruns, meters, RT-safety violations) is story-203 future work and stays out of scope here.
- **No `EventBusMetrics` UI.** The metrics surface is the test assertion seam — not a debug panel. A future story may build a panel on top; not this one.
- **No reactive consumer wiring.** This story is producer-only. New subscribers (e.g. browser auto-refresh on `ClipEvent.Added`, notification on `ProjectEvent.Saved`, mixer refresh on `TrackEvent.Reordered`) are filed separately and may now consume what this story produces.
- **No coalescing framework.** Automation write-batching is one-off, hand-rolled at the point of publish. A generic event-coalescing API is a separate story if multiple producers need the same shape.
- **No change to `DispatchMode` defaults.** Subscribers continue to choose `ON_CALLER_THREAD` / `ON_UI_THREAD` / `ON_VIRTUAL_THREAD` per subscription; producers always call `bus.publish(event)` unmarshalled.

## Technical Notes

- The story-283 convention "one publish call per action, immediately after the model mutation succeeds and before returning, never twice for one logical change" extends to every action class touched here. Compound actions publish per-leaf; envelopes (`ProjectEvent.Undone` / `.Redone`) publish once for the whole compound after the leaves.
- The `TransportController` is the canonical owner of transport state. Confirm via `grep -rn "class .*TransportController"` that there is a single controller — if multiple seams exist (UI controller + core controller), publish from the **core** controller so headless / test runs also produce events.
- The undo manager's `undo()` / `redo()` entry point is the right place to publish `ProjectEvent.Undone` / `.Redone`. It is *not* the right place to publish the leaf events — those come from each action's own `undo()` / `redo()` via the same publisher seam the do-path uses. This keeps compound ordering correct.
- The metrics smoke tests are the canonical "are publishers wired?" assertion. After this story, all seven typed event families (`ClipEvent`, `PluginEvent`, `MixerEvent`, `TrackEvent`, `AutomationEvent`, `TransportEvent`, `ProjectEvent`) should report `publishedCount(...) > 0` after a normal test-suite run. Any regression that silently bypasses a publisher will trip the matching smoke test — same defence story 283 established for its three families.
- The Workshop cache invalidation from story 283 is already live; this story does not affect it. The new event families either bypass Workshop entirely (`TransportEvent`) or unlock additional reactive consumers that other stories will file.
- Per the existing `EventBusPublisher` testing pattern, tests that don't care about events use `NoOpEventBus` (or a `DefaultEventBus` with no subscribers — `publish` to a bus with no subscribers is a constant-time metrics-bump per story 203's contract). Don't make every action's test set up subscribers; one publisher test per action is sufficient.
- Reference: story 203 (event bus + sealed hierarchies), story 283 (publisher seam + minimum-scope publishers + undo symmetry rule), story 202 (sealed `*Event` hierarchies — check `TrackEvent.Reordered` existence before modelling reorder as `Removed+Added`).
