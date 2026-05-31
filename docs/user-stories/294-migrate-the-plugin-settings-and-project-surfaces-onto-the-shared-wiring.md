---
title: "Migrate the Plugin, Settings, and Project Surfaces Onto the Shared Wiring"
labels: ["enhancement", "control-sync", "phase-surface-migration"]
---

# Migrate the Plugin, Settings, and Project Surfaces Onto the Shared Wiring

## Motivation

Stories 290-292 built the view-model layer and story 293 retired the cascade-feeding god-controller
machinery (the transport/track/clip-edit `Host` interfaces, the dead refresh methods at
`MainController.java:3582-3735`, and the five re-entrancy guards). Three surfaces still carry their own
`Host`-callback wiring. This story takes the final Stage 6 of the §8 migration path: "Migrate the
remaining surfaces (plugin view, settings, project manager) per §6-§7 so all four companion books share
one wiring" (`docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §8 Stage 6).

The §1 problem-inventory items it closes out:

- **§1.1 the god controller** / **§9** — these surfaces are the last holders of the controller-to-
  controller `Host`-callback pattern outside the four framework hosts. The remaining surface/lifecycle
  nested `Host` interfaces after story 293 are real and live in `daw-app/.../ui/`:
  `ProjectLifecycleController.Host` (`:67`), `PluginViewController.Host` (`:30`),
  `SnapshotsController.Host` (`:63`), `TrackCreationController.Host` (`:30`),
  `TrackTemplateController.Host` (`:70`), and `AudioImportController.Host` (`:34`). Per §9, "Callback-up
  `Host` interfaces for cross-surface updates" is a rejection-list item — "Use publish/subscribe."
- **§1.2 every action hand-rolls its own cascade** — the plugin add/remove cascade spans the plugin
  surface, the mixer strip, and the Workshop; it is exactly the cascade story 281's deferred S3 cache
  invalidation needs, and the `PluginEvent`/`MixerEvent` producers exist since story 283
  (`TrackStripController`/`TrackCreationController` already publish `MixerEvent.ChannelAdded/Removed`).
- **§1.4** — the plugin surface's own `suppressNotification` (`BinauralMonitorPluginView`) and
  `updatingControls` (`KeyboardProcessorView`) are removed in story 293; this story binds the plugin
  surface to a VM so the bidirectional writes those guards defended cannot reappear (§4.4). The
  `PluginParameterEditorPanel.refreshControls()` spurious-fire bug (§1.4) is retired by single-writer
  binding.

Per §6.7/§7, theming "is just a `ThemeChanged` event every subscriber honours" — but most theming stays
in CSS via the idempotent `ThemeManager.applyTo(...)` (story 277). If any of these surfaces must react to
a theme change in code (beyond the CSS cascade), this is where `ThemeChanged` is added to the `UiEvent`
family (story 292) — otherwise it stays CSS-only.

## Goals

- Migrate the **plugin view / Workshop** surface (story 281 — `WorkshopView`, `PluginViewContainer`,
  `ui/views/WorkshopSelectionHostController.java`, and `PluginViewController`) onto the shared wiring: it
  binds to the relevant VMs (`SelectionVM.selectedDevice`, the per-track plugin chain projected from a
  `TrackVM`/`ChannelVM`) and reacts to `PluginEvent` (`Loaded`/`Unloaded`/`Bypassed`/`ParameterChanged`)
  through the story-289 `FxDispatcher`, replacing the `PluginViewController.Host` callback. This delivers
  story 281's deferred S3 plugin-view cache invalidation as a bus subscriber — the canonical
  reactive-subscriber pattern established by story 283 (and `WorkshopSelectionHostController` already
  holds its bus instance directly per story 283's capture-the-instance rule).
- Migrate the **settings view** (story 276 dialog work + the Settings design book) onto the shared
  wiring: settings facts other surfaces care about travel as a settings VM slice (and a `SettingsApplied`
  fact + live-manager subscriptions per §6.7) instead of being pushed; controls bind to the settings VM
  rather than being poked. Live managers (`ThemeManager`, `DensityManager`, `MotionManager`) become
  subscribers (§6.7).
- Migrate the **project manager / project-lifecycle / snapshot / track-creation** surfaces onto
  `ProjectVM` (story 292) and the existing `ProjectEvent`/`TrackEvent`/`MixerEvent` facts, replacing the
  `ProjectLifecycleController.Host`, `SnapshotsController.Host`, `TrackCreationController.Host`,
  `TrackTemplateController.Host`, and `AudioImportController.Host` callbacks (New/Open/Save/Recent,
  snapshot, and track-creation/import flows bind to / publish through the shared layer; long I/O stays on
  background virtual threads and is marshalled via the dispatcher, §5.8/§6.8).
- Remove those surface/lifecycle nested `Host` interfaces once their callers are on the bus/VMs, so that
  **only the four framework hosts** (`DockManager`/`LayoutManager`/`WorkspaceManager`/
  `PerformanceStageView`) remain in `daw-app/.../ui/` after this story — completing §9 / §1.1 for the
  control-sync cascade.
- The plugin add/remove cascade runs the universal phases (§5.1, §5.4): VALIDATE → MUTATE (existing
  undoable insert action, publishes `PluginEvent`, story 283) → PROJECT (`ProjectVM.dirty` via the
  existing path) → REPUBLISH (the plugin-chain VM projection + channel-strip insert list + Workshop cache
  recompute) → ANNOUNCE (the already-published `PluginEvent` reaches all subscribers).
- Add `ThemeChanged` to the `UiEvent` family **only if** a surface must react in code (§6.7/§7);
  otherwise leave theming to CSS (story 277) and do not add the event.

Tests (assert on properties / bus subscription / style classes — never rasterisation):
- `PluginSurfaceWiringTest` — asserts the plugin/Workshop surface updates from a `PluginEvent`
  (`Loaded`/`Unloaded`) (story 281 S3 cache invalidated) and from `SelectionVM.selectedDevice`, with no
  `PluginViewController.Host` callback involved; assert the typed event via a parent `addEventFilter` on
  the payload, never `Event.getSource()` identity (bubbling pitfall).
- `SettingsSurfaceWiringTest` — asserts a settings change propagates to a dependent live manager via the
  shared layer (settings VM / `SettingsApplied`) rather than a controller field (assert via VM property
  / event, not a controller field).
- `ProjectSurfaceWiringTest` — asserts the project-lifecycle surface binds `ProjectVM.name`/`dirty`/
  `tracks` and that New/Open/Save flows update those properties (assert on properties, not pixels).
- `NoSurfaceHostInterfaceRemainsTest` — a source-scan guard (conformance-sentinel pattern) asserting the
  migrated surface/lifecycle `Host` interfaces are gone and **only** the four framework hosts remain in
  `daw-app/.../ui/`; non-empty-scan guard so it fails if the scan can't run. Substitute in-process tests
  against registered objects; `MainController` is never FXML-loaded in tests.

## Non-Goals

- **No re-derivation of the VM/event/dispatcher infrastructure** — it all exists (stories 289-293); this
  story only moves the remaining surfaces onto it.
- **No removal of the four framework `Host` interfaces** — `DockManager`/`LayoutManager`/
  `WorkspaceManager`/`PerformanceStageView` are docking/layout/stage seams, not control-sync cascades.
- **No `javafx.beans` in `daw-core`** — unchanged; surface migration is `daw-app`-only plus reuse of the
  existing bus.
- **No new `DawEvent` variants** beyond the existing `PluginEvent`/`ProjectEvent`/`TrackEvent`/
  `MixerEvent`; add `ThemeChanged` to the `UiEvent` family only if §6.7/§7 reaction-in-code is genuinely
  required.
- **No theming rework** — story 277 owns the token/CSS theme system; most theming stays in CSS.
- No new surfaces and no feature changes to the plugin/settings/project views beyond the wiring swap.

## Technical Notes

- Touched: the plugin/Workshop surface (`daw-app/.../ui/views/WorkshopView.java`,
  `PluginViewContainer.java`, `ui/views/WorkshopSelectionHostController.java`, `ui/PluginViewController.java`,
  and the plugin views under `ui/`), the settings surface (`ui/AudioSettingsDialog.java` /
  `ui/BackupSettingsDialog.java` and the settings controller/view — verify the live class; Settings
  design book), and the project/lifecycle/snapshot/track-creation controllers
  (`ui/ProjectLifecycleController.java`, `ui/SnapshotsController.java`, `ui/TrackCreationController.java`,
  `ui/TrackTemplateController.java`, `ui/AudioImportController.java`). Verify the exact class set before
  editing.
- Build on stories 289 (dispatcher), 290-292 (VMs + `UiEvent`), 293 (controller already thinned), 283
  (the `PluginEvent`/`ProjectEvent`/`MixerEvent` producers this subscribes to — `EventBus`/
  `DefaultEventBus`/`EventBusPublisher`). Subscribe with `DispatchMode.ON_UI_THREAD`. Do **not** invent
  a parallel bus.
- This completes story 281's deferred S3 (plugin-view cache invalidation) by making the Workshop a bus
  subscriber (EventBus active post-story-283). `WorkshopSelectionHostController` is the canonical
  subscriber seam and already captures its bus instance once at construction (story 283).
- The companion design books guide the per-surface VMs: `docs/design/` Plugin View, Settings View, and
  Project Manager books (verify file:line refs before relaying — they drift). Those books are operationalized as the surface stories this wiring serves: Plugin View → stories 300-304, Settings → 305-309, Project Manager → 295-299.
- channelId==trackId carve-out still applies to the plugin-chain projection: a plugin chain on an
  `addTrack` channel projects via the shared id; aux/return/cue/VCA chains do not (story 291).
- `@RealTimeSafe`: plugin add/remove and settings/project flows are non-real-time; the audio thread
  takes no UI dependency (§4.1, §4.6). Subscriptions registered in surface controllers' init; closed on
  dispose (no leaks — `javafx-application-design` §11). No `EnumProperty<T>`; use `ObjectProperty<E>`.
- Verified facts: the surface/lifecycle `Host` interfaces remaining after story 293 are
  `ProjectLifecycleController.Host` (`:67`), `PluginViewController.Host` (`:30`), `SnapshotsController.Host`
  (`:63`), `TrackCreationController.Host` (`:30`), `TrackTemplateController.Host` (`:70`), and
  `AudioImportController.Host` (`:34`); the four framework hosts are deliberately retained; the Workshop
  already subscribes to the bus directly (story 283).
- See `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §1.1, §1.2, §1.4, §5.1, §5.4, §6.6, §6.7,
  §6.8, §7, §8 Stage 6, §9.
- SKILL: `javafx-application-design` (§4 Properties, §11 threading, §12 typed events, §15 anti-patterns),
  `research-daw` (§1 modular, §3 real-time / lock-free), `dawg-annotations-reflection` (`@RealTimeSafe`).
- Build/verify: `mvn -pl daw-app -am test -Dtest=PluginSurfaceWiring*,SettingsSurfaceWiring*,ProjectSurfaceWiring*,NoSurfaceHostInterfaceRemains* -Dsurefire.failIfNoSpecifiedTests=false`.
