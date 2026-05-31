---
title: "Retire the God Controller: Replace the update*/refresh*/sync* Methods and Host Callbacks with Bindings"
labels: ["enhancement", "control-sync", "phase-retire-controller"]
---

# Retire the God Controller: Replace the update*/refresh*/sync* Methods and Host Callbacks with Bindings

## Motivation

Stories 290-292 moved transport, track/channel, selection, history, and project state onto view-models,
and the controls now bind to those VMs. This story takes Stage 5 of the §8 migration path: "With every
refresh method replaced by a binding/subscription, `MainController` shrinks to *composition root* only
(construct VMs, wire the bus, own the Stage). Delete the … `update*/refresh*/sync*` methods and the
`Host` interfaces. Remove the re-entrancy guard flags (§1.4) made unnecessary by single-writer binding"
(`docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §8 Stage 5).

The §1 problem-inventory items it finally resolves:

- **§1.1 the god controller** — the book measures `MainController.java` at "**3,114 lines**" with
  "dozens of imperative refresh methods." It is **3,739 lines** today, and the book's named refresh
  methods are accurate (verified): `updateTempoDisplay()` (`:3582`), `updateProjectInfo()` (`:3588`),
  `refreshLockStatusIndicator()` (`:3659`), `updateCheckpointStatus()` (`:3666`),
  `updateArrangementPlaceholder()` (`:3676`), `refreshArrangementCanvas()` (`:3681`),
  `updateUndoRedoState()` (`:3688`), `updatePlayheadFromTransport()` (`:3708`), `syncLoopRegionToCanvas()`
  (`:3715`), and `syncSelectionToCanvas()` (`:3729`) — a tight cluster at `:3582-3735`, plus several
  `Host` anonymous-impl forwarders that delegate up into them (e.g. `:1004-1209`, `:1616-1618`).
- **§1.4 hand-rolled re-entrancy guards** — "ad-hoc boolean flags scattered across controllers and
  controls: `suppressChangeEvents`, `updatingControls`, `suppressNotification`, … `updating`." The real
  set is five, all confirmed at the book's exact locations: `suppressChangeEvents`
  (`AudioSettingsDialog.java:160`), `updatingControls` (`KeyboardProcessorView.java:77`),
  `suppressNotification` (`BinauralMonitorPluginView.java:54`), `programmaticDimensionUpdate`
  (`TelemetrySetupPanel.java:137`), and `updating` (`UndoHistoryPanel.java:43`). Per §4.4, with a
  single-writer view-model "the control never races itself, so `suppressChangeEvents`/`updatingControls`
  are unnecessary by construction. … The guards are deleted, not replaced."
- **§4.2 / §9** reject keeping the callback-up pattern: "Callback-up `Host` interfaces for cross-surface
  updates" is a rejection-list item; "Use publish/subscribe." The book counts "**25 distinct `Host`
  callback interfaces** … declared in the sub-controller files." The real count is **24** nested
  `interface Host` declarations, all in `daw-app/.../ui/` (the book's "not inside `MainController`
  itself" is correct). Of these, **four are framework hosts unrelated to the control-sync cascade** —
  `DockManager.Host` (`:52`), `LayoutManager.Host` (`:69`), `WorkspaceManager.Host` (`:57`),
  `PerformanceStageView.Host` (`:101`) — and are **out of scope**. The remaining 20 are
  cascade/sub-controller hosts; this story removes the ones whose facts the bus + VMs now carry and
  defers the project/plugin-surface ones to story 294.

## Goals

- Delete (or reduce to removed no-ops) the now-dead `update*/refresh*/sync*` methods in `MainController`
  whose work is now done by VM bindings (§4.3): `updateTempoDisplay()` and `updatePlayheadFromTransport()`
  and `syncLoopRegionToCanvas()` (story 290 → `TransportVM`); `updateUndoRedoState()` (story 292 →
  `HistoryVM`); `syncSelectionToCanvas()` (story 292 → `SelectionVM`); `updateProjectInfo()` (story 292 →
  `ProjectVM`); and fold the project-open chain (`refreshArrangementCanvas()` /
  `updateArrangementPlaceholder()`) into the story-292 ordered full-load cascade. `updateCheckpointStatus()`
  / `refreshLockStatusIndicator()` bind to `ProjectVM` facts. Verify the exhaustive current list
  (`:3582-3735`) before deleting, and remove the anonymous-`Host` forwarders that delegate to them.
- Remove the **cascade-feeding** nested `Host` interfaces and their controller-to-controller callback
  wiring now that the bus + VMs carry those facts. The transport/track/clip-edit hosts are the primary
  set: `TransportController.Host` (`:68`), `TrackStripController.Host` (`:76`), `ClipEditController.Host`
  (`:41`), `ClipInteractionController.Host` (`:44`), `ClipTrimHandler.Host` (`:46`),
  `ClipFadeHandler.Host` (`:44`), `SlipToolHandler.Host` (`:37`), `RippleModeController.Host` (`:34`),
  `TempoEditController.Host` (`:22`), `HistoryPanelController.Host` (`:30`), and `DawMenuBarController.Host`
  (`:40`, menu enablement now from VM state via §6.9). The project/plugin/lifecycle hosts
  (`ProjectLifecycleController.Host`, `PluginViewController.Host`, `SnapshotsController.Host`, and the
  track-creation/import hosts) are **out of scope** — story 294. The four framework hosts (`DockManager`,
  `LayoutManager`, `WorkspaceManager`, `PerformanceStageView`) are **never** touched.
- **Delete the five §1.4 re-entrancy guards** made dead by single-writer VMs: `suppressChangeEvents`
  (`AudioSettingsDialog`), `updatingControls` (`KeyboardProcessorView`), `suppressNotification`
  (`BinauralMonitorPluginView`), `programmaticDimensionUpdate` (`TelemetrySetupPanel`), and `updating`
  (`UndoHistoryPanel`). Each deletion removes the flag, its guard sites, and any setter recursion it
  defended, replacing the bidirectional write with single-writer binding (§4.4). Where a flag has a
  genuinely live non-cascade use, flag it and keep narrowly (scope-fix, defer tangential).
- `MainController` shrinks toward **wiring and lifecycle** (§1.1, §8 Stage 5): it constructs the VMs, the
  `FxDispatcher`, and the bus subscription, hands VMs to controls, and owns dispose — it no longer owns
  these cross-cutting refresh methods.
- No behaviour change for the user: every flow stories 290-292 re-wired keeps working; this story is the
  *removal* half of those stages.

Tests (assert on absence-of-flags via source scan, and on behaviour via bindings — never rasterisation):
- `MainControllerShrinkTest` — a source-scan guard (conformance-sentinel pattern): asserts the deleted
  `update*/refresh*/sync*` method names no longer exist in `MainController`, and that `MainController` no
  longer references the removed cascade-feeding `Host` interfaces; non-empty-scan guard so the test fails
  if it matches nothing. `MainController` is never FXML-loaded in tests (it spins up `AudioEngine`/
  autosave) — assert against the *source*, not a live instance.
- `ReentrancyGuardRemovalTest` — a source-scan guard asserting `suppressChangeEvents`,
  `updatingControls`, `suppressNotification`, `programmaticDimensionUpdate`, and `updating` are gone from
  the five enumerated files (and absent app-wide except any explicitly-justified survivor).
  Non-empty-scan guard.
- `SingleWriterNoLoopTest` — asserts that toggling a control whose VM property it binds does **not**
  re-enter the control's own handler (command → mutation → core signal → VM write → binding round-trip
  runs exactly once); assert via an invocation counter on the command/handler, proving §4.4's "control
  never races itself" now that the guards are gone.
- `HostCallbackRemovalTest` — asserts the cascade-feeding `Host` interfaces named above are removed and
  their facts now travel as typed events; assert event delivery via a parent `addEventFilter` on the
  payload, never `Event.getSource()` identity (bubbling-event pitfall). Asserts the four framework hosts
  (`DockManager`/`LayoutManager`/`WorkspaceManager`/`PerformanceStageView`) are untouched.

## Non-Goals

- **No plugin/settings/project-manager/lifecycle surface migration** — `ProjectLifecycleController.Host`,
  `PluginViewController.Host`, `SnapshotsController.Host`, and the track-creation/import hosts, plus the
  plugin/settings/project surfaces, move in story 294.
- **No removal of the four framework `Host` interfaces** — `DockManager`/`LayoutManager`/
  `WorkspaceManager`/`PerformanceStageView` are docking/layout/stage seams, not control-sync cascades.
- **No new view-models or events** — this story only *removes* the now-dead controller machinery; the
  VMs and the `UiEvent` family already exist (stories 290-292).
- **No `FxDispatcher` change** — the marshalling seam (story 289) is unchanged.
- **No `javafx.beans` in `daw-core`** — unchanged; this is a `daw-app`-only deletion sweep.
- No behaviour or visual change; no renaming of surviving public API beyond what deletion forces.

## Technical Notes

- Touched: `daw-app/.../ui/MainController.java` (the refresh-method deletions at `:3582-3735` and the
  anonymous-`Host` forwarders), the cascade-feeding sub-controllers under `daw-app/.../ui/`
  (`TransportController`, `TrackStripController`, `ClipEditController`, `ClipInteractionController`,
  `ClipTrimHandler`, `ClipFadeHandler`, `SlipToolHandler`, `RippleModeController`, `TempoEditController`,
  `HistoryPanelController`, `DawMenuBarController` — remove the nested `Host` + callback wiring), and the
  five guard-flag files (`AudioSettingsDialog`, `KeyboardProcessorView`, `BinauralMonitorPluginView`,
  `TelemetrySetupPanel`, `UndoHistoryPanel`). Verify each guard's live uses before deleting (test-only
  callers aren't live usage — remove the API, migrate the still-valid tests onto the binding path).
- Build on stories 290-292 (the VMs that made the controller methods dead) and story 289 (dispatcher).
  Reuse the existing bus and `UiEvent` family; do not invent new infrastructure.
- This is the §8 Stage 5 "controller becomes a composition root" step; aligns with story 093 (extract
  `MainController` responsibilities) and stories 198/199 (decompose god class / DI over singletons).
- Where a source-scan sentinel test is added, follow the established conformance-sentinel pattern
  (SOURCE/CLASS marker + mandatory-TODO + source-scan test + non-empty-scan guard) rather than a
  one-off scan.
- Verified facts: MainController is 3,739 lines (book said 3,114); **24** nested `interface Host`
  declarations exist, all under `ui/` — 4 of them framework (`DockManager`/`LayoutManager`/
  `WorkspaceManager`/`PerformanceStageView`), the rest sub-controller cascade hosts; the book's named
  refresh methods all exist at `:3582-3735`; the five guard flags are at the book's exact lines.
- See `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §1.1, §1.4, §4.2, §4.3, §4.4, §8 Stage 5,
  §9, Appendix A.
- SKILL: `javafx-application-design` (§11 threading, §15 anti-patterns), `research-daw` (§1 modular),
  `dawg-annotations-reflection` (`@RealTimeSafe`).
- Build/verify: `mvn -pl daw-app -am test -Dtest=MainControllerShrink*,ReentrancyGuardRemoval*,SingleWriterNoLoop*,HostCallbackRemoval* -Dsurefire.failIfNoSpecifiedTests=false`.
