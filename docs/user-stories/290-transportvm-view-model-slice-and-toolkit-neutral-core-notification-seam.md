---
title: "TransportVM View-Model Slice and the Toolkit-Neutral Core Notification Seam"
labels: ["enhancement", "control-sync", "phase-view-model"]
---

# TransportVM View-Model Slice and the Toolkit-Neutral Core Notification Seam

## Motivation

Story 289 added the `FxDispatcher` marshalling seam but added no state-owning layer. This story takes
Stage 2 of the §8 migration path ("View-model for one vertical slice (transport)"): it proves the whole
vertical slice — core change signal → view-model property → control binding — on the smallest surface
(`docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §8).

It attacks two §1 problem-inventory items head-on:

- **§1.3 the silent model** — "`daw-core` is deliberately JavaFX-free (correct …). But the consequence
  today is that the model is **completely silent** … `Transport` exposes no listener API at all" (verified:
  `daw-core/.../transport/Transport.java` has no listener seam). The transport flips a field and returns;
  the only reason any control updates is that a handler *also* remembered to push the new state. Per §2.5
  the fix is "a **toolkit-neutral listener seam** only (a `Consumer`/`Runnable` callback, never a
  `javafx.beans.Property`)."
- **§1.1 the god controller** + **§1.6 no cascade order** — today the transport display is reconstructed
  by hand in `MainController.updateTempoDisplay()` (`MainController.java:3582`) and
  `updatePlayheadFromTransport()` (`:3708`) plus `syncLoopRegionToCanvas()` (`:3715`) — exactly the
  imperative refreshers §4.3 replaces with bound `TransportVM` properties.

Per §3.1 the view-model layer "is the observable mirror of the model: one `Property` per observable
field, with the view-model as the *sole writer*." Per §3.2 the core "emits a neutral 'something changed'
signal (a `Consumer<ChangeKind>` or a bumped revision counter); the view-model, on the FX thread,
re-reads the affected slice and republishes it as Properties."

## Goals

- Add a **toolkit-neutral notification seam** to the transport core
  (`daw-core/.../transport/Transport.java`): a `Consumer<ChangeKind>` (or `Runnable`) registered by an
  observer, invoked **after** a field mutation completes, carrying a small `ChangeKind` enum (e.g.
  `STATE`, `TEMPO`, `LOOP`, `POSITION`). No `javafx.beans.*` import enters `daw-core` (§2.5, §9). The
  revision-counter alternative is acceptable, but the typed `Consumer<ChangeKind>` is preferred.
- Add `TransportVM` in a new `daw-app/.../ui/vm/` package exposing **read-only** JavaFX properties
  (§3.3): `state`, `tempo`, `timeSignature`, `loopRegion`, and the continuous `playhead` (§5.2). Use
  `ObjectProperty<TransportState>` (the enum `daw-core/.../transport/TransportState.java` already
  exists) and `ObjectProperty<TimeSignature>` for enum/object values — there is **no** `EnumProperty<T>`
  in JavaFX (the repo has been bitten by assuming it exists).
- `TransportVM` is the **single writer** of its properties (§2.4): it registers the core signal, and on
  each `ChangeKind` it marshals the property write onto the FX thread via the story-289 `FxDispatcher`
  (§4.3). The continuous `playhead` during playback is fed by the dispatcher's per-frame buffer drain,
  not a per-tick `runLater` (§4.5).
- Re-wire the transport controls (play/pause, record, tempo field, time-sig field, loop toggle,
  playhead) to **bind** to `TransportVM` instead of reading controller fields (§6.1). The controls emit
  commands (`StartTransportCommand`, `StopTransportCommand`, `ToggleRecordCommand`, `SetTempoCommand`,
  `ToggleLoopCommand`) that wrap the existing transport mutation path (today driven by
  `TransportController`); they never write transport state directly (§2.2 "state flows down, intent flows
  up"). Spacebar, the Transport menu, and the buttons converge on the same commands (§2.8).
- The transport cascade runs the universal phases (§5.1, §5.2): VALIDATE (reject out-of-range tempo;
  engine ready) → MUTATE (`Transport.start()` / `setTempo(...)`, undo-captured where applicable) →
  PROJECT (`ProjectVM.dirty` is story 292's concern; transport-only intents may skip it) → REPUBLISH
  (`TransportVM` property updates via the core signal + dispatcher) → ANNOUNCE (the existing
  `TransportEvent` on the bus, e.g. `TempoChanged`).

Tests (assert on properties / `ChangeKind` callbacks / bus events — never on rasterisation):
- `CoreTransportSignalTest` (in `daw-core`) — asserts the `Consumer<ChangeKind>` fires **after** the
  field changes (the observer reads the post-mutation value), once per mutation, with **no** JavaFX on
  the classpath (proves the seam is toolkit-neutral).
- `TransportVMTest` — asserts each property reflects core state after a signal; asserts `TransportVM` is
  the single writer (a control cannot mutate a property); drives the `FxDispatcher` drain manually so
  the continuous `playhead` is deterministic.
- `TransportBindingTest` — asserts the play/record/tempo/loop controls' visible state follows
  `TransportVM` property changes (assert via property/`selected`/`text` state and style classes, not
  pixels); asserts a control click issues the command (assert through the command seam, not a control
  field).
- `TransportCommandEventTest` — asserts a tempo command runs the mutation and the existing
  `TransportEvent` (`TempoChanged`) reaches a subscriber via the bus; assert the typed event via a
  parent `addEventFilter` on the payload, never `Event.getSource()` identity (bubbling-event pitfall),
  and never on rendered output (headless pitfall).

## Non-Goals

- **No `TrackVM`/`ChannelVM`** — per-track/channel slices and the mute/solo cascade are story 291.
- **No `SelectionVM`/`HistoryVM`/`ProjectVM`** and no full-load cascade — story 292.
- **No god-controller deletion.** `MainController.updateTempoDisplay()`/`updatePlayheadFromTransport()`/
  `syncLoopRegionToCanvas()` may be left dormant/delegating here; their removal and the
  re-entrancy-guard deletions are story 293.
- **No `javafx.beans` in `daw-core`** — the core gains only the toolkit-neutral callback.
- No new bus event types beyond what stories 202/203/283 defined (extend `TransportEvent` only if a
  transport payload is genuinely missing; the `DawEvent` `permits` list is otherwise unchanged).

## Technical Notes

- New: `daw-app/.../ui/vm/TransportVM.java`; `daw-app/.../ui/vm/command/*Command.java` (thin wrappers
  over the existing `TransportController` mutation path). New `daw-core` change-signal API on `Transport`
  (`ChangeKind` enum + register/unregister) — `daw-core/.../transport/Transport.java` is the confirmed
  core class and has no listener API today.
- Build on story 289's `FxDispatcher` for every property write and the continuous playhead drain; build
  on the existing bus (`EventBus`/`DefaultEventBus`/`EventBusPublisher`) and the `TransportEvent`
  publishers from story 283. Subscribe with `DispatchMode.ON_UI_THREAD` (which now routes through the
  `FxDispatcher` per story 289).
- `@RealTimeSafe` paths stay UI-free: the playhead/meters cross via the lock-free buffer drained by the
  dispatcher, not a direct VM write from the audio thread (§4.1, §4.6).
- Register the core signal in the `TransportVM` constructor; expose a `dispose()` that unregisters and
  closes any `Subscription` (no leaked listeners — `javafx-application-design` §3/§4/§11).
- Verified facts: MainController's live transport refreshers are `updateTempoDisplay()` (`:3582`),
  `updatePlayheadFromTransport()` (`:3708`), and `syncLoopRegionToCanvas()` (`:3715`); `Transport` has no
  listener API; the `TransportState` enum already exists for `ObjectProperty<TransportState>`.
- Sibling stories: 289 (dispatcher), 291 (`TrackVM`/`ChannelVM` + cascade), 293 (retire controller +
  delete guards), 283 (publishers), 202/203 (bus). See
  `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §1.3, §2.5, §3.1, §3.2, §4.3, §4.5, §5.1, §5.2,
  §6.1, §8 Stage 2.
- SKILL: `javafx-application-design` (§4 Properties, §11 threading, §12 typed events, §15 anti-patterns),
  `research-daw` (§1 modular, §3 real-time / lock-free), `dawg-annotations-reflection` (`@RealTimeSafe`).
- Build/verify: `mvn -pl daw-core -am test -Dtest=CoreTransportSignal* -Dsurefire.failIfNoSpecifiedTests=false`
  then `mvn -pl daw-app -am test -Dtest=TransportVM*,TransportBinding*,TransportCommandEvent* -Dsurefire.failIfNoSpecifiedTests=false`.
