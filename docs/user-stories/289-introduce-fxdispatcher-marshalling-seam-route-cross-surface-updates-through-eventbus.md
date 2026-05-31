---
title: "Introduce the FxDispatcher Marshalling Seam and Route Cross-Surface Updates Through the Existing EventBus (No Behaviour Change)"
labels: ["enhancement", "control-sync", "phase-foundation"]
---

# Introduce the FxDispatcher Marshalling Seam and Route Cross-Surface Updates Through the Existing EventBus (No Behaviour Change)

## Motivation

The Control Synchronization Design Book (`docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md`)
diagnoses the user's recurring "the UI feels flaky" complaint as a *missing wiring layer*. Two of its
§1 problem-inventory items are pure threading hazards that can be retired **before** any view-model
work, and this story — Stage 1 of the §8 migration path — does exactly that:

- **§1.5 informal cross-thread `runLater`** — "There are **27 separate files** in the UI package that
  call `Platform.runLater` directly. Each is an independent, un-coordinated hop onto the FX thread.
  Nothing guarantees ordering between them, nothing coalesces a burst of meter updates into one repaint,
  and nothing documents which signals are allowed to originate off-thread." (Verified: 47 occurrences
  across exactly 27 `daw-app` files today — e.g. `MainController` 3, `ProjectLifecycleController` 5,
  `TrackFreezeController` 3, `TaskProgressIndicator` 6.)
- **§1.6 no cascade order** — partly a downstream consequence: when twenty-seven unrelated sites each
  hop threads on their own, "some orders cause visible flicker."

The seam this story adds is already anticipated *in the code*. `DispatchMode.ON_UI_THREAD` is documented
as "every event is re-dispatched through the UI executor (typically `Platform::runLater`) so the
subscriber always runs on the JavaFX Application Thread" (`daw-sdk/.../event/DispatchMode.java`), and
`DefaultEventBus` already takes that UI executor via its builder — `DefaultEventBus.builder().uiExecutor(Executor)`,
documented "typical wiring in JavaFX code is `uiExecutor(Platform::runLater)`" — which production wires
today as the **bare** `DefaultEventBus.builder().uiExecutor(Platform::runLater).build()`
(`daw-app/.../DawApplication.java:63-65`, immediately before `EventBusPublisher.setDefault(bus)` at
`:66`, the story-283 install). Per §4.5 the dispatcher "is the *only* place `Platform.runLater` (or an
`AnimationTimer`) appears."

This story makes the `FxDispatcher` real and routes the existing cross-thread hops through it — with
**no behaviour change** — so every later stage builds on a real, tested marshalling seam.

## Goals

- Add `FxDispatcher` in a new `daw-app/.../ui/marshal/` package as the single seam between non-FX
  threads and the FX thread, with the two §4.5 jobs:
  - **Discrete:** `onFx(Runnable)` posts work to the FX thread, with keyed coalescing so a burst of
    duplicate keyed updates collapses to one run.
  - **Continuous:** drain a lock-free, single-reader buffer once per frame via an `AnimationTimer` the
    dispatcher owns, so a ~1 kHz meter stream becomes ~60 coalesced UI updates/s (meters, playhead).
- Retarget the production `EventBus` `ON_UI_THREAD` delivery through the seam: build the bus as
  `DefaultEventBus.builder().uiExecutor(fxDispatcher::onFx).build()` instead of
  `.uiExecutor(Platform::runLater)` at `DawApplication.java:63-65`, so every `ON_UI_THREAD` subscription
  (story 283 producers + future UI subscribers) marshals through the one coalescing seam rather than a
  bare `runLater`.
- Replace the 27 raw `Platform.runLater` call sites in `daw-app` with `fxDispatcher.onFx(...)`. After
  this story, `Platform.runLater` and `new AnimationTimer` appear **only** inside `FxDispatcher`.
- Route the continuous meter/playhead path through the lock-free buffer the dispatcher drains; the
  `@RealTimeSafe` audio thread writes the buffer and **never blocks** on the UI (§4.1, §4.6, §9). The
  audio thread takes no JavaFX dependency and never subscribes to the bus.
- No behaviour change: every visible flow that worked before works identically; this is consolidation
  only (§8 Stage 1: "behaviour is identical, but the seam now exists").

Tests (operate on properties / buffer state / call routing — never on rasterisation):
- `FxDispatcherTest` — asserts `onFx` runs work on the FX thread; asserts that N keyed duplicate posts
  within one pulse coalesce to a single execution (assert an invocation counter, not pixels).
- `FxDispatcherDrainTest` — asserts the lock-free buffer is single-reader-drained per frame: many writes
  between two manual drains yield the latest coalesced value, and a writer thread never blocks under
  concurrent writes (drive the drain manually rather than relying on a live `AnimationTimer`).
- `BusUiDispatchRoutesThroughDispatcherTest` — builds `DefaultEventBus.builder().uiExecutor(fxDispatcher::onFx).build()`,
  publishes an event with an `ON_UI_THREAD` subscriber, and asserts the handler ran on the FX thread via
  the dispatcher seam (assert through the bus API + an FX-thread latch, never `Event.getSource()`
  identity).
- `RunLaterConsolidationTest` — a source-scan guard (mirrors the `@LegacyDialog`/`@HardcodedColorAllowed`
  conformance-sentinel pattern): asserts no `daw-app` source outside `FxDispatcher` references
  `Platform.runLater` or constructs an `AnimationTimer`; non-empty-scan guard so the test fails if the
  scan matches nothing.

## Non-Goals

- **No view-models.** `TransportVM`, `TrackVM`/`ChannelVM`, `SelectionVM`, `HistoryVM`, `ProjectVM` are
  later stages (stories 290-292). This story adds zero `vm` classes and changes no control bindings.
- **No core notification seam.** The toolkit-neutral `Consumer<ChangeKind>` signal in `daw-core` is
  story 290 — `daw-core` is untouched here and stays JavaFX-free.
- **No new event types.** `SelectionChanged`, `UndoStateChanged`, `ThemeChanged` (§4.2, §7, Appendix A)
  arrive with the stages that need them (story 292). The `DawEvent` `permits` list is unchanged.
- **No re-entrancy-guard deletion.** The five §1.4 flags (`suppressChangeEvents`, `updatingControls`,
  `suppressNotification`, `programmaticDimensionUpdate`, `updating`) are removed in story 293 once
  single-writer view-models exist; they remain in place here.
- No behaviour or visual change, and no god-controller decomposition (stories 293/294).

## Technical Notes

- New: `daw-app/.../ui/marshal/FxDispatcher.java` (+ a small lock-free single-reader ring/exchanger for
  the continuous path). The dispatcher is constructor-injected where the 27 sites live (DI over
  singletons — stories 198/199), so tests can drive `onFx`/drain deterministically.
- Build on the existing bus end-to-end — do **not** invent a parallel bus: `daw-sdk/.../event/EventBus.java`
  (`publish`, `subscribe(Class)`, `on(Class, Consumer)`, `on(Class, DispatchMode, Consumer)`, returning
  `Subscription extends AutoCloseable`), `daw-sdk/.../event/DispatchMode.java` (`ON_UI_THREAD` runs via
  the bus's UI executor), impl `daw-core/.../event/DefaultEventBus.java` (the `Builder.uiExecutor(Executor)`
  seam — toolkit-neutral, takes `java.util.concurrent.Executor`, no JavaFX type), and the publish seam
  `daw-core/.../event/EventBusPublisher.java` (live since story 283; `EventBusPublisher.setDefault(bus)`
  at `DawApplication.java:66`). The only production wiring change is the `uiExecutor` passed at
  `DawApplication.java:63-65`.
- `@RealTimeSafe` is on the audio-thread paths; the audio thread writes the lock-free buffer only, never
  calls into JavaFX, preserving the module boundary and real-time safety (`research-daw` §3 lock-free;
  `dawg-annotations-reflection`).
- Register the `AnimationTimer` once in the constructor and stop it in `dispose()`; never start ad-hoc
  timers elsewhere. There is no `EnumProperty<T>` in JavaFX (not needed here, but flagged for later
  stages — the repo has been bitten by assuming it exists).
- Verified facts: 27 `daw-app` files call `Platform.runLater` (the book's count is correct);
  `DefaultEventBus` is configured via `builder().uiExecutor(...)` (no `Consumer<Runnable>` ctor); the
  live wiring is `DawApplication.java:63-66`.
- Sibling stories: 283 (publishers — the producer half this consumes), 202/203 (sealed `DawEvent` /
  central typed bus), 290 (first VM + core signal), 293 (re-entrancy-guard deletions). See
  `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §1.5, §1.6, §4.1, §4.5, §4.6, §8 Stage 1, §9,
  Appendix A.
- SKILL: `javafx-application-design` (§11 threading/marshalling, §6 Canvas, §12 typed events, §15
  anti-patterns), `research-daw` (§3 real-time / lock-free), `dawg-annotations-reflection`
  (`@RealTimeSafe`).
- Build/verify: `mvn -pl daw-app -am test -Dtest=FxDispatcher*,BusUiDispatchRoutesThroughDispatcher*,RunLaterConsolidation* -Dsurefire.failIfNoSpecifiedTests=false`.
