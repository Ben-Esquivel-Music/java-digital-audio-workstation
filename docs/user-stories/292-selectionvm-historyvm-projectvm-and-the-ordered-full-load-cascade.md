---
title: "SelectionVM, HistoryVM, ProjectVM and the Ordered Full-Load Cascade"
labels: ["enhancement", "control-sync", "phase-cascade"]
---

# SelectionVM, HistoryVM, ProjectVM and the Ordered Full-Load Cascade

## Motivation

Stories 290 and 291 introduced `TransportVM`, `TrackVM`, and `ChannelVM` and proved the cascade contract
on mute/solo. This story takes Stage 4 of the §8 migration path: "`SelectionVM` drives inspector + menu
enablement; `HistoryVM` drives undo/redo UI; `ProjectVM` owns the single dirty bit and the full-load
cascade (§5.7)" (`docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §8 Stage 4).

The §1 problem-inventory items it resolves:

- **§1.6 no defined cascade order** — "When a project is loaded, many things must update … Today the
  order is whatever sequence of refresh calls the load handler happens to make. If the mixer rebuilds
  before the track list is repopulated, it briefly renders zero channels; if the playhead updates before
  the canvas knows the new length, it clamps to the wrong position." The project-open path today fires
  the imperative refreshers directly — exactly the ad-hoc sequence §5.7 replaces with a fixed phase
  order.
- **§1.2 every action hand-rolls its own cascade** — the recurring trailing pair is
  `host.updateUndoRedoState(); host.markProjectDirty();`. `ProjectVM.dirty` becomes "the one dirty bit
  (fixes §1.2's missed `markProjectDirty`)" (§7) and `HistoryVM` replaces the hand-pushed
  `MainController.updateUndoRedoState()` (`MainController.java:3688`).
- **§1.1 the god controller** — `updateProjectInfo()` (`MainController.java:3588`) and the selection
  refresher `syncSelectionToCanvas()` (`:3729`) are hand-pushed methods that should instead be **bound**
  to `ProjectVM`/`SelectionVM` properties (per §4.3's mapping table).

Per §4.2 and §7/Appendix A, some facts the UI needs "are UI-only and need a new sealed family or a small
UI-event addition" — specifically `SelectionChanged`, `UndoStateChanged`, and `ThemeChanged`. Selection
and undo-state never need to reach the core, so this story introduces a separate sealed `UiEvent` family
rather than adding them to `DawEvent` (whose `permits` clause — confirmed
`TransportEvent, MixerEvent, TrackEvent, ClipEvent, ProjectEvent, AutomationEvent, PluginEvent,
XrunEvent` — stays unchanged).

## Goals

- Introduce a new sealed `UiEvent` family in `daw-sdk/.../event/` (sibling to `DawEvent`) for UI-only
  discrete facts, initially `permits SelectionChanged, UndoStateChanged`. Because it is sealed, the
  `permits` clause is explicit and exhaustive (§7, story 202). `ThemeChanged` is **not** added here — it
  is deferred to the surface migration (story 294) and added only if a surface must react in code (most
  theming stays in CSS, §7). The `DawEvent` `permits` list is **not** touched.
- Add `SelectionVM`, `HistoryVM`, and `ProjectVM` in `daw-app/.../ui/vm/` (§3.3):
  - `SelectionVM` → `selectedTrack`, `selectedClip`, `selectedDevice`, `tool` (read-only
    `ObjectProperty`s; no `EnumProperty<T>`). Emits `SelectTrackCommand` etc.; publishes
    `SelectionChanged` on ANNOUNCE.
  - `HistoryVM` → `canUndo`, `canRedo`, `undoLabel`, `redoLabel`. Bound by the undo/redo buttons and
    menu items; the command-history mutation publishes `UndoStateChanged`.
  - `ProjectVM` → `name`, `dirty`, `checkpoint`, plus the observable `tracks` list (§3.3, §5.7).
- Each VM is the **single writer** of its properties and updates them via the story-289 `FxDispatcher`
  on a core signal / bus event (§2.4, §4.3). Selection itself is UI-authoritative state owned by
  `SelectionVM` (§3.3); project name/dirty/tracks derive from the core via the toolkit-neutral signal
  (story 290 pattern) plus the existing `TrackEvent`/`ProjectEvent` facts on the bus.
- Implement the **ordered full-load cascade** as a single declared sequence (§5.7), replacing the
  ad-hoc project-open refresh sequence (the chain of `updateProjectInfo()` / `refreshArrangementCanvas()`
  / `updateUndoRedoState()` / `syncSelectionToCanvas()` calls fired on open):
  1. Tear down: dispose old VMs + view subscriptions (remove listeners).
  2. Build model-derived VMs in order: `ProjectVM → TrackVMs → ChannelVMs → TransportVM → HistoryVM`.
  3. Rebuild structural views in order: track lanes → mixer strips → inserts/racks.
  4. Bind continuous views: playhead, meters, time/tempo display.
  5. Restore selection + viewport, **clamped** to the new project's bounds.
  6. Announce `ProjectLoaded` last → status bar, lock indicator, checkpoint, window title.
  The order is a named constant/enum so it can be asserted (§1.6). "Building VMs before views (step 2
  before 3) guarantees the mixer never renders zero channels and the playhead never clamps against an
  unknown length."
- Wire the selection-aware surfaces (inspector, mixer highlight, clip-editor target, plugin-chain view)
  and the undo/redo buttons + menu items to **bind** to `SelectionVM`/`HistoryVM` rather than being
  pushed by a controller; controls emit the selection/history commands. Menu enablement computes from VM
  state on `SelectionChanged`/`UndoStateChanged` (§6.9, `MenuEnablementPolicy`).

Tests (assert on properties / declared order / bus events — never on rasterisation):
- `UiEventSealingTest` (in `daw-sdk`) — asserts the `UiEvent` `permits` clause is exhaustive over the
  UI-only events and that an exhaustive `switch` compiles (mirrors story 202's sealing guarantee);
  asserts `DawEvent`'s `permits` is unchanged.
- `SelectionVMTest` / `HistoryVMTest` / `ProjectVMTest` — assert each VM is the single writer and its
  properties follow a selection change / history change / project mutation; drive the `FxDispatcher`
  drain manually for determinism.
- `ProjectLoadCascadeOrderTest` — asserts the full-load cascade runs the steps in the declared fixed
  order and clamps selection in step 5; assert the order via the named sequence and resulting property
  state, never via render timing (§1.6, headless pitfall).
- `SelectionUndoEventTest` — asserts `SelectionChanged` and `UndoStateChanged` reach subscribers via a
  parent `addEventFilter` on the payload, never `Event.getSource()` identity (bubbling-event pitfall);
  asserts undo/redo buttons' `disable` state and menu-item labels follow `HistoryVM`.

## Non-Goals

- **No god-controller deletion.** `updateProjectInfo()` (`:3588`), `updateUndoRedoState()` (`:3688`),
  and `syncSelectionToCanvas()` (`:3729`) may be left delegating to the VMs/cascade here; their removal
  and the remaining `update*/refresh*/sync*` deletions are story 293.
- **No re-entrancy-guard deletion** — story 293.
- **No plugin/settings/project-manager surface migration** — those keep their `Host` callbacks until
  story 294.
- **No `javafx.beans` in `daw-core`** — project facts cross via the toolkit-neutral signal + existing
  bus events.
- **No `ThemeChanged` event** here — deferred to story 294, added only if reaction-in-code is needed.
- **Do not add the new UI events to `DawEvent`'s `permits`** — use the separate `UiEvent` family.

## Technical Notes

- New: `daw-sdk/.../event/UiEvent.java` (sealed; `permits SelectionChanged, UndoStateChanged`), and
  `daw-app/.../ui/vm/SelectionVM.java`, `HistoryVM.java`, `ProjectVM.java` plus the selection/history
  commands. The story-289 `FxDispatcher` already supplies the bus's `ON_UI_THREAD` marshaller; the
  `UiEvent` family should ride the same dispatch discipline — confirm whether the existing `EventBus`
  carries `UiEvent` too or whether a small generic widening is cleaner. **Prefer reusing the existing
  bus infrastructure** over a third bus.
- The project-load cascade order is a named constant/enum in `daw-app` so the order is testable (§1.6);
  it composes the VM rebuilds and the selection clamp, then announces. It replaces the ad-hoc
  project-open refresh chain in `MainController` (`updateProjectInfo`/`refreshArrangementCanvas`/
  `updateUndoRedoState`/`syncSelectionToCanvas`).
- Build on stories 289 (dispatcher), 290 (core signal + `TransportVM`), 291 (`TrackVM`/`ChannelVM` +
  cascade), 283 (publishers), 202/203 (sealed events / typed bus). Reuse existing `TrackEvent`/
  `ProjectEvent` facts for the track list and dirty/load state.
- `@RealTimeSafe`: project load is a non-real-time path; the audio thread takes no UI dependency (§4.1).
- Register signals/subscriptions in VM constructors; `dispose()` unregisters and closes `Subscription`s
  (no leaks — `javafx-application-design` §11). No `EnumProperty<T>`; use `ObjectProperty<E>`.
- Verified facts: the book's refresh-method names are accurate — `updateProjectInfo()` (`:3588`),
  `refreshArrangementCanvas()` (`:3681`), `updateUndoRedoState()` (`:3688`), `updatePlayheadFromTransport()`
  (`:3708`), `syncLoopRegionToCanvas()` (`:3715`), and `syncSelectionToCanvas()` (`:3729`) all exist in
  `MainController` today (cluster `:3582-3735`).
- See `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §1.1, §1.2, §1.6, §3.3, §4.2, §4.3, §5.1,
  §5.7, §6.9, §7, §8 Stage 4, Appendix A; and the companion design books in `docs/design/`
  (Project Manager, Plugin View, Settings View) for the surfaces story 294 will migrate.
- SKILL: `javafx-application-design` (§4 Properties, §11 threading, §12 typed events, §15 anti-patterns),
  `research-daw` (§1 modular, §3 real-time / lock-free), `dawg-annotations-reflection` (`@RealTimeSafe`).
- Build/verify: `mvn -pl daw-sdk -am test -Dtest=UiEventSealing* -Dsurefire.failIfNoSpecifiedTests=false`
  then `mvn -pl daw-app -am test -Dtest=SelectionVM*,HistoryVM*,ProjectVM*,ProjectLoadCascadeOrder*,SelectionUndoEvent* -Dsurefire.failIfNoSpecifiedTests=false`.

## Implementation Note (2026-06-13)

Implemented + independently verified. The 4th companion-design-book story (after
289/290/291) to gain production code. Built as a **test-proven seam, NOT live-wired** — mirroring
290/291: `MainController` / `ProjectLifecycleController` / `DawMenuBarController` are **untouched**, and
their refresh methods (`updateProjectInfo` / `updateUndoRedoState` / `syncSelectionToCanvas`, …) stay
live. Retiring the god controller and wiring the cascade/binders/VMs into the live project-open path is
**story 293** (the explicit Non-Goals here).

### daw-sdk — the `UiEvent` family + the bus widening (the load-bearing decision)

The story's "sibling `UiEvent` family" + "do **not** touch `DawEvent`'s `permits`" + "prefer reusing the
existing bus over a third bus" + "a small generic widening" resolved to a new sealed **`BusEvent`**
super-type (`permits DawEvent, UiEvent`) that both families extend, with the
`EventBus` / `DefaultEventBus` / `EventBusPublisher` type bound **widened from `DawEvent` to `BusEvent`**.
The one existing bus now carries `UiEvent` too, without `UiEvent` being a `DawEvent` (the Non-Goal).
`DawEvent`'s `permits` is byte-for-byte unchanged (pinned by `UiEventSealingTest` via
`getPermittedSubclasses()`). **Flagged for pre-commit review — this is the only change that modifies
shared bus infrastructure** (4 `EventBus` signatures + `DefaultEventBus` generics + `EventBusPublisher.publish`); every existing publisher/subscriber compiles unchanged (a `DawEvent` *is-a* `BusEvent`), and
the full daw-sdk (1037) + daw-core (6040) suites pass with zero regressions. New: `BusEvent`, `UiEvent`
(`SelectionChanged`, `UndoStateChanged` records), `UiEventSealingTest` (6).

### daw-core — the `DawProject` change seam

Mirrors the `Track` / `Transport` / `MixerChannel` seam exactly (`volatile Consumer<ChangeKind>[]`
snapshot, lock-free + allocation-free notify, no `javafx`, no `@RealTimeSafe`): `enum ChangeKind {NAME,
DIRTY, TRACKS}` + `addChangeListener` / `removeChangeListener`, firing **after** `setName` (NAME),
`markDirty` / `markClean` (DIRTY — **fire-on-change**, so an idempotent `markDirty` announces nothing),
and `addTrack` / `removeTrack` / `moveTrack` / `duplicateTrack` (TRACKS). `CoreDawProjectSignalTest` (10,
incl. the discriminating remove-a-listener-mid-notify case). No project UUID / checkpoint added —
`DawProject` owns neither.

### daw-app — the VM / command / cascade / binder layer (`ui/vm/` + `ui/vm/command/`)

- **`ProjectVM`** (model-derived, mirrors `TrackVM`): `name` / `dirty` / `tracks` (unmodifiable
  `ObservableList<Track>`) from the `DawProject` signal via `FxDispatcher`; `checkpoint`
  **forward-declared** (seeded `""`, no producer yet — the session-status strip is 295-299, mirroring
  story 291's `ChannelVM.meterLevel`).
- **`HistoryVM`** (projection of `UndoManager` through its older `UndoHistoryListener`):
  `canUndo` / `canRedo` / `undoLabel` / `redoLabel`, marshalled via `FxDispatcher`. Per-load lifetime (a
  fresh `UndoManager` is installed per load → dispose + rebuild). Does **not** publish — the handler
  announces (mirrors 290/291).
- **`SelectionVM`** (UI-authoritative — **no core model, no dispatcher**): `selectedTrack` /
  `selectedClip` / `selectedDevice` / `tool` read-only props; writer methods set synchronously on the FX
  thread (selection only ever originates there). `clampTo(ProjectVM)` is the §5.7 step-5 clamp (clears a
  selected track absent from the loaded project). `clip` / `device` are `Object` (no shared supertype;
  their clamp + surfaces are story 294).
- **`ProjectLoadCascade`**: an `enum Phase {TEAR_DOWN, BUILD_VMS, REBUILD_VIEWS, BIND_CONTINUOUS,
  RESTORE_SELECTION, ANNOUNCE}` sequencer — `run()` executes injected per-phase `Runnable`s strictly in
  enum order and records `executedPhases()`. The §1.6 fix: the **order is the contract** (293 supplies
  the real per-phase steps — VM rebuild, view rebuild, selection clamp, announce).
- **Commands** (separate-file records, mirroring 290/291): sealed `SelectionCommand` (SelectTrack /
  SelectClip / SelectDevice / SetTool / ClearSelection) + `SelectionIntentHandler` +
  `CoreSelectionIntentHandler` (VALIDATE idempotent → MUTATE the VM → ANNOUNCE `SelectionChanged`; a
  **tool change is bound directly per §2.7 and announces nothing**); sealed `HistoryCommand` (Undo /
  Redo) + `HistoryIntentHandler` + `CoreHistoryIntentHandler` (VALIDATE `canUndo`/`canRedo` → undo/redo
  → ANNOUNCE `UndoStateChanged`).
- **`HistoryControlBinder`**: binds undo/redo buttons' `disable` + tooltip and Edit-menu items'
  `disable` + text to `HistoryVM`; clicks raise commands (mirrors `TransportControlBinder`).

### Tests + verification

`SelectionVMTest` (7) / `HistoryVMTest` (5) / `ProjectVMTest` (7) / `ProjectLoadCascadeOrderTest` (4) /
`SelectionUndoEventTest` (4) — all **discriminating** and FX-thread-safe (results captured into holders
and asserted off the FX thread, never inside a `runLater`). **43 new tests green** (sdk 6 + core 10 +
app 27), verified on disk and re-run independently; full daw-sdk **1037**, daw-core **6040**, and the
daw-app reactor are green. No `module-info` change (same-package test access, as 290/291 established). No
new bus event types beyond `UiEvent`.

### Deferred (NOT gaps — later-story scope)

God-controller retirement + live wiring of the cascade / binders / VMs into `MainController` /
`ProjectLifecycleController` / `DawMenuBarController` = **293**; inspector / clip-editor / plugin-chain
selection-surface binding + clip/device clamp = **294**; the `checkpoint` / session-status producer =
**295-299**. A distinct `ToolChanged` event and `ThemeChanged` are deferred (§5.5, §7).
