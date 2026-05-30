---
title: "Make Visualization Analyzers (Spectrum / Correlation / Loudness / Tuner / Room-3D) and the Telemetry Setup Panel First-Class Dockable Panels"
labels: ["enhancement", "ui", "ui-overhaul", "mission-control", "docking", "phase-4"]
---

# Make Visualization Analyzers and the Telemetry Setup Panel First-Class Dockable Panels

## Motivation

User story 282 — "Mission Control: Dock-and-Float Layout with Per-Project Persistence" — lists, as an explicit Goal, that the visualisation tiles become first-class dockable panels:

> **Visualisation tiles as first-class panels:**
> Spectrum, correlation, loudness, tuner, room 3D — each becomes a dockable panel rather than a fixed bottom-row decoration. The existing visualisation panels are wrapped in a `DockablePanel` adapter; their internals don't change.
> — `docs/user-stories/282-mission-control-dock-and-float-layout.md:51-52`

Stories 285 (DockManager wire-up) and 286 (LayoutManager wire-up) landed the docking foundation, but **neither delivered the visualisation-tiles goal** — it appears in no Goal and no Non-Goal of either follow-up. The visualisations remain exactly the "fixed bottom-row decoration" 282 set out to replace.

The five analyzer tiles are built into a fixed `@FXML` `HBox` row (`vizTileRow`) and toggled as a single row via a context menu:

```
$ grep -rn 'dockManager.register' daw-app/src/main/
daw-app/.../MainController.java:1795:   dockManager.register(new ArrangementDockable());
daw-app/.../MainController.java:1798:   if (mixer != null) dockManager.register(mixer);
daw-app/.../MainController.java:1800:   if (editor != null) dockManager.register(editor);
daw-app/.../MainController.java:1802:   if (mastering != null) dockManager.register(mastering);
daw-app/.../MainController.java:1805:   dockManager.register(browserPanelController.getBrowserPanel());
```

Only five panels (arrangement, mixer, editor, mastering, browser) are registered with the `DockManager`. No visualisation display is registered.

```
$ grep -rn 'implements Dockable' daw-app/src/main/java/.../ui/display/
(no matches)
```

None of `SpectrumDisplay`, `CorrelationDisplay`, `LoudnessDisplay`, `LevelMeterDisplay`, `WaveformDisplay`, `TunerDisplay`, or `RoomTelemetryDisplay` implements `Dockable`.

How they are surfaced today:

- `VisualizationTileBuilder.build(HBox)` (`daw-app/.../VisualizationTileBuilder.java:36-67`) constructs five tiles — Spectrum, Peak/RMS, Oscilloscope, Loudness, Phase/Correlation — and adds them as children of `vizTileRow`. They live or die as a single row, governed by `VisualizationPanelController` (`daw-app/.../VisualizationPanelController.java`) with a right-click "Show All / Hide All / Reset Layout" context menu (lines 129-217). The row cannot be detached, floated, moved to another edge, or persisted as part of a named layout.
- `vizTileRow` is a hard-coded `@FXML` node (`MainController.java:134`) mounted once at `MainController.java:403`.
- Tuner and Room-3D are surfaced *only* as separate top-level windows (`SpectrumDisplayWindow`, `TunerDisplayWindow`, `CorrelationDisplayWindow`, `LoudnessDisplayWindow`) — `*DisplayWindow` standalone `Stage` wrappers (`daw-app/.../display/SpectrumDisplayWindow.java:43`), not dock entries.

Net effect: the analyzer displays are reachable only as a fixed bottom row plus a handful of ad-hoc floating windows, and are absent from the dock manifest (`MainController.java:1900-1932`), the View → Layout named layouts, and per-project layout persistence. `DefaultWorkspaces` even reserves the constants `PANEL_LOUDNESS` (`DefaultWorkspaces.java:47`) and `PANEL_VISUALIZATIONS` (`DefaultWorkspaces.java:35`) for this purpose, but no `Dockable` advertises either id.

The same gap applies to the **Telemetry Setup panel**. Story 285 listed `TelemetrySetupPanel` as a `Dockable` (`PANEL_TELEMETRY`, `DockZone.RIGHT`) but landed it un-published, leaving an explicit hand-off:

```
$ grep -n -A4 'Dockable contract deferred' daw-app/.../ui/TelemetrySetupPanel.java
138: // ── Dockable contract deferred ──────────────────────────────────
139: // Story 285 — TelemetrySetupPanel is owned by TelemetryView (plugin view)
140: // and is not yet wired as a top-level dock surface, so the Dockable
141: // contract is not published here. A future story can re-add it (and
142: // PANEL_TELEMETRY in DefaultWorkspaces) once there is a consumer.
```

`TelemetrySetupPanel extends ScrollPane` with no `implements Dockable`, and `PANEL_TELEMETRY` does not exist in `DefaultWorkspaces`. This story is the consumer story 285 deferred to: it registers the dockable analyzers above *and* finally publishes the `TelemetrySetupPanel` dock contract alongside them.

## Goals

- Make each analyzer display a `Dockable` so it participates in the live `DockManager` from story 285. Either implement `Dockable` directly on the `*Display` `Region` subclasses, or wrap each in a thin `DockableVisualizationPanel` adapter (the 282 Goal text says "wrapped in a `DockablePanel` adapter; their internals don't change" — prefer the adapter so the GPU render shells in `daw-app/.../display/` stay free of dock concerns). The `Dockable` contract is `dockId()` / `displayName()` / `iconName()` / `preferredZone()` (`daw-app/.../dock/Dockable.java:25-62`).
- Add the dock-id constants to `DefaultWorkspaces` (`daw-app/.../DefaultWorkspaces.java`), reusing the two that already exist and adding the rest:
  - Spectrum — new `PANEL_SPECTRUM = "spectrum"`, `displayName = "Spectrum"`, `iconName = "SPECTRUM"`, `preferredZone = DockZone.BOTTOM`.
  - Correlation/Phase — new `PANEL_CORRELATION = "correlation"`, `"Correlation"`, `"PHASE_METER"`, `DockZone.BOTTOM`.
  - Loudness — reuse existing `PANEL_LOUDNESS` (`DefaultWorkspaces.java:47`), `"Loudness"`, `"LOUDNESS_METER"`, `DockZone.BOTTOM`.
  - Tuner — new `PANEL_TUNER = "tuner"`, `"Tuner"`, `"TUNER"` (or the existing `DawIcon` for the tuner), `DockZone.BOTTOM`.
  - Room-3D telemetry — new `PANEL_ROOM_3D = "room3d"`, `"Room 3D"`, `"ROOM"` (or the existing room icon), `DockZone.RIGHT`.
  - Telemetry setup — new `PANEL_TELEMETRY = "telemetry"`, `"Telemetry"`, icon `DawIcon.SURROUND` (the glyph `TelemetryView.java:86` already uses for its header), `DockZone.RIGHT`. This is the id/zone story 285 named (`TelemetrySetupPanel.java:138-142`).
- Register each visualisation `Dockable` with the application-wide `DockManager` in `MainController.installDockManager()` (`MainController.java:1793-1821`), immediately after the five existing `dockManager.register(...)` calls (lines 1795-1805). Set their initial visibility to mirror the current `VisualizationPanelController` per-tile preferences so the first toggle reflects the live state (the same pattern the method already uses for arrangement/mixer/etc. at lines 1814-1819).
- **Publish and register the Telemetry Setup panel's `Dockable` contract** — the consumer story 285 deferred. Delete the "Dockable contract deferred" comment at `TelemetrySetupPanel.java:138-142`, make `TelemetrySetupPanel` advertise `dockId() = PANEL_TELEMETRY` / `displayName() = "Telemetry"` / `preferredZone() = DockZone.RIGHT` (directly or via the same adapter route chosen for the analyzers), and `dockManager.register(...)` it next to the others in `installDockManager()`.
  - **Ownership caveat (load-bearing):** `TelemetrySetupPanel` is currently the *setup-state* child of `TelemetryView` (constructed at `TelemetryView.java:104`, exposed via `getSetupPanel()` at `:337`). `TelemetryView` toggles between this setup panel and the `RoomTelemetryDisplay` that backs `PANEL_ROOM_3D` above — so the room-3D analyzer and the telemetry-setup form are the two faces of one plugin view. Register a **single shared instance**: do not construct a second `TelemetrySetupPanel` for the dock or the docked form and `TelemetryView`'s copy will diverge. Decide explicitly whether the dock host references the instance `TelemetryView` owns, or `TelemetryView` is refactored to read the docked instance, and record the chosen relationship in the PR description. Per `feedback_test_only_callers_are_not_live_usage.md` / `feedback_replace_over_sibling_class.md`, do not leave a parallel duplicate panel.
- Teach `MainControllerDockHost.reconcile(...)` / `resolveNode(...)` (`MainController.java:2081-2123`, `:2233-2252`) how to mount/unmount each visualisation node into its preferred dock zone and how to resolve its `Node`, mirroring the existing `reconcileBrowser` / `reconcileCenterView` arms. A `BOTTOM`-zone visualisation panel docks into the same bottom region as the dock manifest bar; a `RIGHT`-zone panel (Room-3D) docks alongside the inspector. Because these are additive (not single-selection like CENTER), each gets its own independent show/hide reconcile arm — do **not** route them through `CENTER_ZONE_PANELS` (`MainController.java:229`).
- The visualisation `Dockable`s appear automatically in:
  - the dock manifest bar (`MainController.mountDockManifestBar()` / `rebuildManifestBar()`, `MainController.java:1900-1932`) — it iterates `dockManifestModel.entries()`, which is fed by `DockManager.registered()` (`DockManifestModel.java:143`), so registering the panels is sufficient;
  - the View → Layout named layouts (story 286) — their dock state is captured by `DockManager.captureJson()` and restored by `applyJson(...)`, so saving a layout with the spectrum floated and re-loading it restores the float with no extra work once they are registered.
- Retire the fixed-row mechanism in favour of the dockable panels: the `VisualizationTileBuilder` row (`VisualizationTileBuilder.java:36-67`) and `VisualizationPanelController` row-toggle/context-menu (`VisualizationPanelController.java`) are superseded. Migrate any still-valid per-tile-visibility behaviour onto `DockManager.toggleVisible(panelId)` and delete the row plumbing (the `@FXML vizTileRow` field at `MainController.java:134`, the `VisualizationTileBuilder.build(...)` call at `MainController.java:403`, and the `VisualizationPanelController` field at `MainController.java:189`). Per the project convention (`feedback_test_only_callers_are_not_live_usage.md` and `feedback_replace_over_sibling_class.md`), replace the legacy pathway in place rather than leaving a parallel row alongside the new dockable panels. Migrate `VisualizationPanelControllerTest` assertions onto the dock-visibility model.
- Tests:
  - `VisualizationDockablesRegisteredTest` (new): build a `MainController`, force the scene to mount, assert `dockManager.registered()` contains a `Dockable` for each of `PANEL_SPECTRUM`, `PANEL_CORRELATION`, `PANEL_LOUDNESS`, `PANEL_TUNER`, `PANEL_ROOM_3D`, and `PANEL_TELEMETRY`, and assert each `dockId()` / `preferredZone()` matches the table above (`PANEL_TELEMETRY` → `DockZone.RIGHT`).
  - `TelemetrySetupPanelSingleInstanceTest` (new): assert the `Dockable` registered for `PANEL_TELEMETRY` is the *same* `TelemetrySetupPanel` instance `TelemetryView.getSetupPanel()` returns (no duplicate construction), proving the ownership caveat is honoured. (Use the in-process substitution pattern from `feedback_maincontroller_test_substitute.md` — register the dockables against a live `DockManager` constructed with a fake `DockManager.Host` rather than FXML-loading `MainController`, if the full controller hangs.)
  - `VisualizationPanelFloatRoundTripTest` (new): float the spectrum panel via `dockManager.float_(PANEL_SPECTRUM, bounds)`, assert `dockManager.layout().entry(PANEL_SPECTRUM).zone() == DockZone.FLOATING`; re-dock via `dockManager.move(PANEL_SPECTRUM, DockZone.BOTTOM, 0)`, assert it returns to `BOTTOM`.
  - `VisualizationInDockManifestTest` (new): render the manifest bar in a headless scene (per `feedback_javafx_headless_test_pitfalls.md`), assert one manifest button exists per visualisation panel and the button text equals the `Dockable#displayName()`.
  - `VisualizationLayoutPersistedTest` (new): float the loudness panel, `layoutManager.saveCurrent("Viz")`, switch to a built-in layout, `layoutManager.load("Viz")`, assert the loudness panel is floating again (proves the analyzer dock state round-trips through the named-layout JSON).
  - Update `VisualizationPanelControllerTest` (and any `vizTileRow`-dependent test) to the new dockable model, or delete it if the row plumbing is removed entirely.

## Non-Goals

- **No change to the analyzer DSP or pixel output.** The GPU render shells (`SpectrumDisplay`, `CorrelationDisplay`, `LoudnessDisplay`, `TunerDisplay`, `RoomTelemetryDisplay` — all `extends GpuCanvasView`, stories 250-254/284) are unchanged; this story only changes *where they are hosted and how they are reached*.
- **No grip-handle drag-to-detach gesture.** The user-gesture trigger that fires `PanelDetachRequestedEvent` is a separate 282 gap with its own story; this story only makes the analyzers *registerable / floatable via the existing `DockManager` API and the View → Layout menu*. Floating them via the menu / programmatic API is sufficient here.
- **No tabbed dock targets.** Same scope cap as stories 285 and 286 — one panel per dock slot; multiple visualisations sharing a single slot as tabs is a future story.
- **No removal of the `*DisplayWindow` standalone windows** unless they become trivially redundant. If a `*DisplayWindow` is still launched from a menu action, leave it; the dockable panel and the standalone window can coexist for one cycle (mirrors the alias-retained-one-cycle pattern from story 271).
- **No new dock zone.** `BOTTOM` and `RIGHT` (both already in `DockZone`, `daw-app/.../dock/DockZone.java:17-29`) are sufficient.
- **No spatial-panner display change.** `SpatialPannerDisplay` (story 254) is part of the spatial-panner editor, not a free-standing analyzer tile; it is out of scope.
- **No redesign of `TelemetryView`'s setup⇄display state machine.** This story only shares the existing `TelemetrySetupPanel` instance with the `DockManager` so it can be docked/floated; the "Generate Telemetry" / "Reconfigure" toggle (`TelemetryView.java`) is unchanged beyond whatever minimal re-parenting the shared-instance wiring requires.

## Technical Notes

- Files: `daw-app/.../MainController.java` (register the new dockables in `installDockManager()` at 1793-1821; extend `MainControllerDockHost.reconcile`/`resolveNode` at 2081-2123 / 2233-2252; remove the `vizTileRow` field / build call / `VisualizationPanelController` field at 134 / 403 / 189), `daw-app/.../DefaultWorkspaces.java` (add the missing panel-id constants incl. `PANEL_TELEMETRY`), `daw-app/.../TelemetrySetupPanel.java` (publish the `Dockable` contract; delete the deferred-contract comment at 138-142), `daw-app/.../TelemetryView.java` (share its `getSetupPanel()` instance with the dock host), new `daw-app/.../display/DockableVisualizationPanel.java` adapter (if the adapter route is chosen), and the retired `VisualizationTileBuilder.java` / `VisualizationPanelController.java`.
- The `DockManager` public API to use: `register(Dockable)` (`DockManager.java:90`), `setVisible(String, boolean)` (`:188`), `toggleVisible(String)` (`:202`), `float_(String, Rectangle2D)` (`:160`), `move(String, DockZone, int)` (`:133`), `registered()` (`:114`), `layout()` (`:119`). `Rectangle2D` is `com.benesquivelmusic.daw.sdk.ui.Rectangle2D`.
- BOTTOM is currently occupied by the dock manifest bar (`mountDockManifestBar`, `MainController.java:1900-1920`), which the host inserts into the `rootPane` bottom `VBox`. A BOTTOM-zone visualisation panel must dock *above* the manifest bar (which itself is above the status bar) — coordinate the bottom `VBox` child ordering with the existing manifest-bar insertion at `MainController.java:1910-1919`.
- Icon names resolve through the icon registry (story 265 Lucide adoption). Use the same `DawIcon` constants `VisualizationTileBuilder` already uses (`DawIcon.SPECTRUM`, `DawIcon.PHASE_METER`, `DawIcon.LOUDNESS_METER` at `VisualizationTileBuilder.java:46-50`) for `iconName()` consistency.
- Display names ("Spectrum", "Correlation", "Loudness", "Tuner", "Room 3D") come from `Messages.properties` (skill §14 / story 286 Technical Notes). Reuse the existing labels where the tiles already have them.
- Headless-test caveats: per `feedback_javafx_headless_test_pitfalls.md`, prefer testing the pure dock model (register / float / layout JSON) without a toolkit, and use the in-process `DockManager` substitution rather than FXML-loading `MainController` (`feedback_maincontroller_test_substitute.md`).
- Reference: user story 282 (Mission Control — the source of this goal, line 51-52), user story 285 (DockManager production wire-up), user story 286 (LayoutManager / dock manifest / View → Layout — the sibling surfaces this feeds into), user stories 250-254 and 284 (the GpuCanvas analyzer displays this story re-hosts). Skill: `dawg-annotations-reflection` is not relevant (no annotation involved); the relevant convention notes are `feedback_replace_over_sibling_class.md` and `feedback_test_only_callers_are_not_live_usage.md`.
