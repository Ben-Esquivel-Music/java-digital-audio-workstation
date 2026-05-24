---
title: "Mission Control: Promote Arrangement and Visualisation Tiles to First-Class Dockable Panels (Story 282 Follow-On)"
labels: ["enhancement", "ui", "ui-overhaul", "phase-4", "mission-control", "docking"]
---

# Mission Control: Promote Arrangement and Visualisation Tiles to First-Class Dockable Panels (Story 282 Follow-On)

## Motivation

Two distinct UI structural goals from story 282 did not ship and are surfaced here so neither slips:

1. **Arrangement as just-another-panel.** Story 282's *Goals* states: "Today the arrangement panel is hardcoded into the centre of the `BorderPane` in `main-view.fxml`. After this story, the centre is a dock host; the arrangement panel is a first-class dock entry that *defaults* to the centre but can be moved or floated like any other." The shipped PR added `LayoutManager` and `DockManifestModel` but left `main-view.fxml`'s centre slot literally pointing at the arrangement view. The `ArrangementAsDockablePanelTest` test class exists, but it exercises the *model* expectation — the production scene graph still hardcodes the arrangement as the privileged centre node.
2. **Visualisation tiles as first-class panels.** Story 282 §Goals: "Spectrum, correlation, loudness, tuner, room 3D — each becomes a dockable panel rather than a fixed bottom-row decoration. The existing visualisation panels are wrapped in a `DockablePanel` adapter; their internals don't change." None of the existing `*Display` classes under `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/display/` are referenced from `DockManager` today; they remain pinned in whatever container their host controller put them in. After story 284 they share a `GpuCanvasView` base — the moment to lift them is now, before more wiring solidifies.

Both items are structural refactors of the running scene graph (not just model classes), they affect every test that walks `main-view.fxml`, and they pair naturally — promoting the arrangement to a dockable panel is the same scaffolding change that promotes the visualisation tiles. They are filed together for that reason; the implementer may split this into two PRs if either half balloons.

## Goals

- **Introduce `DockablePanel` adapter** in `daw-app/.../ui/dock/` (or extend the existing `Dockable` interface from story 195 if present): a thin wrapper that holds the panel's `Node`, a stable `panelId` (string, used as the `DockManifestModel` key and the `NamedLayout` serialisation key), a localised display name from `Messages.properties`, and a preferred default dock location (`CENTRE`, `BOTTOM`, `RIGHT`, `LEFT`, or `FLOATING`). Wrap, do not subclass — the existing display Nodes are unchanged.

- **Promote the arrangement view to a `DockablePanel`.**
  - Replace `main-view.fxml`'s hard reference to the arrangement view in the `BorderPane.center` with a generic `dock-host` container (a `StackPane` styled by `.root-pane .dock-host`).
  - On controller initialisation, `DockManager.dock(arrangementPanel, DockLocation.CENTRE)` registers the arrangement under panelId `"arrangement"`.
  - Default project layouts (`BuiltInLayouts.DEFAULT`, `.TRACKING`, `.MIXING`, `.MASTERING`, `.LIVE`) all dock the arrangement to the centre — but the layout file now carries this explicitly so a user can save a layout that floats the arrangement.
  - Detach (via the existing `DockManager` mechanism) moves the arrangement into a floating `Stage`; the centre slot reveals the empty `dock-host` background.
  - Re-docking the floating arrangement back to the centre restores the previous state without scene-graph churn other than the parent swap.
  - The arrangement's existing public API (`ArrangementView` and friends) is unchanged; only the FXML reference site moves.

- **Wrap and register every visualisation `*Display` as a `DockablePanel`.**
  - Candidates: `WaveformDisplay`, `SpectrumDisplay`, `LevelMeterDisplay`, `SpatialPannerDisplay`, `RoomTelemetryDisplay`, `CorrelationDisplay`, `LoudnessDisplay`, `TunerDisplay` (the `GpuCanvasView` subclasses from story 284). `InputMeterStrip` and `MiniClipIndicator` remain embedded — they are per-track ornaments, not standalone panels.
  - Each gets a stable `panelId` (`"spectrum"`, `"correlation"`, `"loudness"`, `"tuner"`, `"spatial-panner"`, `"room-telemetry"`, `"waveform"`).
  - Default location for each is its current home (most → `BOTTOM`; `RoomTelemetryDisplay` → `RIGHT`). The `BuiltInLayouts` entries are updated to match so existing visual defaults are preserved.
  - The wrapper does **not** modify the `GpuCanvasView` subclass internals. `setAnimated`, `dispose()`, and the renderer lambda remain untouched. The wrapper only adds dock identity, a localised title, and an optional `padding`/`background` chrome from existing role tokens.

- **Layout serialisation.** `NamedLayout` (from story 282) gains the ability to record floating-window positions and sizes for any panel that was floating when the layout was saved. `LayoutManager.load(name)` restores those positions. Built-in layouts use the default location; only user-saved layouts persist floating geometry.

- **Per-project default layout.** When a project is opened that has no saved layout, apply `BuiltInLayouts.DEFAULT`. When a project is closed mid-edit, `LayoutManager.captureCurrent()` writes the live layout into the project model before serialisation (coordinate with `ProjectManager`; the long I/O write stays on the existing background virtual thread per `ProjectLifecycleController.java:349-393`).

- **Tests:**
  - `ArrangementAsCentreDockableTest`: load `main-view.fxml`, assert the `BorderPane.center` is a `.dock-host` container hosting the arrangement view via `DockManager`. Detach to a floating window; assert the centre slot is empty and the arrangement is in a new `Stage`. Re-dock; assert the centre slot is restored and the floating `Stage` is closed.
  - `VisualisationTilesDockableTest`: assert each `GpuCanvasView` subclass listed above is registered with `DockManager` under its canonical `panelId`. Detach `SpectrumDisplay`, assert it is in a floating `Stage` and the dock manifest from story 285 reflects the new state.
  - `LayoutFloatingGeometryPersistenceTest`: float two panels, resize one, save the layout as "X"; reset to default; load "X"; assert both panels are floating with their previous bounds (within 1 px tolerance).
  - `BuiltInLayoutsCoverageTest`: for each of the five built-in layouts, instantiate it and assert every visualisation panel + the arrangement appear in a valid dock location (no "ghost" panels missing from the manifest).
  - `ProjectLayoutRoundTripTest`: open a fresh project (no layout), float the arrangement, save the project, close, reopen; assert the arrangement is still floating.
  - Existing snapshot tests for visualisation panels do **not** need re-baselining — pixel output is bit-identical; only the parent container changes.

## Non-Goals

- **No new `DockManager` API.** Story 195 owns the docking framework. If a `dock`/`undock`/`focus` method is missing, file separately rather than expanding `DockManager` here.
- **No `LayoutManager` model changes beyond floating-geometry persistence.** Adding generic per-panel state (filter settings, scroll positions, etc.) into the layout file is a separate concern.
- **No `GpuCanvasView` API changes.** Story 284 owns the display shell; this story consumes it as-is. The dockable wrapper composes; it does not subclass.
- **No menu wiring or dock manifest bar work.** Those are story 285's domain. This story can land first or second — they are independent and only meet at the dock manifest UI, which uses whatever panels are registered.
- **No tabbed dock targets.** Inherited non-goal from story 282; deferred to a separate follow-on.
- **No drag-handle (`⋮⋮`) chrome on panels** — that is a visual-polish concern; the existing `DockManager` drag affordance remains.
- **No replacement of `InputMeterStrip` or `MiniClipIndicator` with dockables.** They are intentionally embedded ornaments.
- **No animation of dock / undock transitions** — instant per Reduce Motion (story 279).
- **No multi-monitor auto-placement.** Per-monitor placement is whatever the user dragged the floating `Stage` to; persisted as-is.
- **No CSS palette additions.** Reuse `.root-pane` role tokens for the dock-host background. Per the repo convention (memory: structural selectors consume `.root-pane` semantic tokens; future palettes override role token values in the Palette A block, not selector literals).

## Technical Notes

- The arrangement view is the largest pinned UI in `main-view.fxml`. Replacing the hard reference with a dock host is mechanically simple — define the `StackPane` in FXML with `fx:id="centreDockHost"`, then have `MainController.initialize()` ask `DockManager` to install the arrangement panel into that host. The existing `MainController` constructor pattern (used for `ThemeManager`, etc.) is the model.
- `setOnHidden` on the primary `Stage` is owned by `MainController` for app-lifetime cleanup (per existing convention at `MainController.java:471-494` and `DawApplication.java:66-72`). Floating-panel `Stage` hide handlers must be composed via `addEventHandler` so they don't shadow that primary cleanup.
- The visualisation tiles are `GpuCanvasView` subclasses (story 284). They drive `AnimationTimer` frames internally and require `setAnimated(false); dispose();` on shutdown. The `DockablePanel` wrapper must forward `dispose()` to the wrapped node when the dock host removes the panel (e.g. on app shutdown or panel close); otherwise `AnimationTimer` instances leak per the story-284 contract.
- `GpuCanvas.requestRender()` renders immediately on the FX thread with `deltaSeconds=0.0` (per existing convention). Floating a panel re-parents the `GpuCanvasView`, which triggers the scene-null one-shot handshake — this is exercised by `GpuCanvasViewTest` and must keep passing after this story lands. Add an explicit regression test if any wrapper introduces a parent-change side effect.
- Long-running I/O — including the project save that now embeds the layout — already runs on a background virtual thread via `ProjectLifecycleController` (per `ProjectLifecycleController.java:349-393`). This story does not add new I/O; layout capture is in-memory.
- `BuiltInLayouts` is read-only (per story 282); modifying it to record the new default dock locations is allowed because no user layout depends on the previous values (none exist). Confirm `BuiltInLayoutsCoverageTest` covers every visualisation panel after the change.
- Long-running E2E render/export tests under `daw-app/src/test/long/` (per `pom.xml:264-286`) are not run in the default Surefire pass; verify them manually with `-P long-tests` if the arrangement-as-dockable change affects render-export wiring (it should not — the render path goes through `daw-core`, not the UI scene graph).
- Reference: story 282 (model layer), story 195 (`DockManager` foundation), story 284 (`GpuCanvasView` shell), story 285 (dock manifest bar + View → Layout menu wiring).
