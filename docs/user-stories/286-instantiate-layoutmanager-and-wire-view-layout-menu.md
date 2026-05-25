---
title: "Instantiate LayoutManager, Wire View → Layout Menu, and Persist Per-Project Layouts"
labels: ["enhancement", "ui", "ui-overhaul", "mission-control", "phase-4"]
---

# Instantiate `LayoutManager`, Wire View → Layout Menu, and Persist Per-Project Layouts

## Motivation

User story 282 — "Mission Control: Dock-and-Float Layout with Per-Project Persistence" — landed the `LayoutManager` façade and its supporting types:

- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/layout/LayoutManager.java` — `savedLayouts()`, `saveCurrent(name)`, `load(name)`, `delete(name)`, `rename(oldName, newName)`, `currentLayoutProperty()`, `toJson()` / `fromJson(json)`.
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/layout/NamedLayout.java`, `BuiltInLayouts.java` (Default / Tracking / Mixing / Mastering / Live), `DockManifestModel.java`, `PanelDetachRequestedEvent.java`, `PanelDockRequestedEvent.java`.
- `LayoutSaveLoadTest`, `LayoutPersistenceAcrossProjectsTest`, `LayoutMenuTest`, `DockManifestTest`, `ArrangementAsDockablePanelTest` — all green against fake `Host` implementations.

But:

```
$ grep -rn 'new LayoutManager\b' daw-app/src/main/
(no matches)
$ grep -rn 'LayoutManager\b'      daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java
(no matches)
$ grep -rn 'PanelDetachRequested\|PanelDockRequested' daw-app/src/main/
(no production handlers)
$ grep -rn 'View → Layout\|"Layout"' daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MenuConstructionService.java
(no matches)
```

`LayoutManager` is reached only by its own unit tests. `MainController` has no `LayoutManager` field. `MenuConstructionService` has no "Layout" submenu under View — the existing Workspaces submenu (story 193) lives there instead. `DockManifestModel` has no JavaFX rendering. The five built-in layouts ("Default", "Tracking", "Mixing", "Mastering", "Live") are unreachable. Per-project layout persistence (which `LayoutManager.toJson()` / `fromJson()` exists for) is never invoked by `ProjectSerializer`.

This story is the production wire-up for story 282. It is also gated on user story 285 (DockManager production wire-up) — `LayoutManager` consumes `DockLayoutJson` via its `Host` bridge, which is the format produced by `DockManager#captureLayout()`. Once 285 lands and `DockManager` is live, this story turns `LayoutManager` into a real user-facing surface.

## Goals

- Instantiate the application-wide `LayoutManager` in `MainController`, with a `Host` implementation that bridges to the production `DockManager` from user story 285:

  ```java
  LayoutManager.Host host = new LayoutManager.Host() {
      @Override public String captureDockLayoutJson() {
          return DockLayoutJson.encode(dockManager.layout());
      }
      @Override public void applyDockLayoutJson(String json) {
          dockManager.applyLayout(DockLayoutJson.decode(json));
      }
  };
  layoutManager = new LayoutManager(host);
  ```

- Add a "View → Layout" submenu to `MenuConstructionService` placed alongside the existing "Workspaces" submenu (story 193):
  - Top group: radio-style entries for every `NamedLayout` in `layoutManager.savedLayouts()` (the five built-ins plus user-saved). The radio is bound to `layoutManager.currentLayoutProperty()` so toggling layouts updates the check mark.
  - Separator.
  - "Save Layout As…" — opens a `DawgDialog` (story 276 chrome) prompting for a name; calls `layoutManager.saveCurrent(name)`. Refuse to overwrite a built-in name (the manager already refuses; surface the error as a notification).
  - "Manage Layouts…" — opens a dialog listing user-saved layouts with Rename / Delete actions; built-ins are read-only in the list.
- Persist the active layout name **per project** through `ProjectSerializer`:
  - Add a `layout` field to the project model (coordinate with story 188's `MigrationRegistry` — bump the format version and add a one-line migration that defaults missing `layout` to `"Default"`).
  - On project load: `ProjectLifecycleController` reads `project.getLayoutName()` and calls `layoutManager.load(name)`.
  - On project save: `ProjectLifecycleController` writes `layoutManager.currentLayoutProperty().get()` into `project.setLayoutName(name)` before serialising.
  - The actual project-file write happens off the FX thread via `javafx.concurrent.Task` (skill §11) — never block the FX thread on layout I/O.
- Wire `PanelDetachRequestedEvent` and `PanelDockRequestedEvent` so panels published from `LayoutManager`-aware code (and from `DetachPluginRequestedEvent` from story 281) flow through `DockManager`'s float / re-dock API. Today both event types exist but have zero production handlers; add an `addEventHandler` at the `rootPane` level in `MainController` that calls `dockManager.floatPanel(panelId, bounds)` and `dockManager.dockPanel(panelId, zone)` respectively.
- Render the dock manifest bar at the bottom of the main window (above the status bar): a horizontal `HBox` bound to `DockManifestModel`. Each entry is a `dawg-button` (story 264) showing the panel display name; click focuses / unhides the panel via `dockManager.toggleVisible(panelId)`. The §4 mockup grip handle (`⋮⋮`) is rendered as an SVG glyph (story 265 icons).
- Tests:
  - `MainControllerLayoutManagerWiredTest` (new): build a `MainController`, force the scene to mount, assert `layoutManager != null` and `layoutManager.savedLayouts()` contains the five built-ins.
  - `ViewLayoutMenuTest` (new): assert the View menu contains a "Layout" submenu with five radio entries, that selecting one calls `layoutManager.load(name)`, and that the radio reflects `currentLayoutProperty()`.
  - `LayoutPerProjectPersistenceTest` (new): create a project, set layout to "Mixing", save, close, reload, assert the layout is restored to "Mixing" on load.
  - `PanelDetachEventBridgesToDockManagerTest` (new): fire a synthetic `PanelDetachRequestedEvent` for the mixer panel; assert `dockManager.layout().getEntry(PANEL_MIXER).floating() == true`.
  - `DockManifestRendersAllPanelsTest` (new): render the manifest bar in a headless scene, assert one button per registered dockable panel and that the button text matches `Dockable#displayName()`.

## Non-Goals

- **No new docking framework.** This story consumes the `DockManager` from user story 285 as-is.
- **No re-implementation of the View → Workspace menu.** The existing Workspaces submenu (story 193) stays; the new Layout submenu is parallel to it. Both ultimately mutate the same dock layout, but Workspaces is panel-visibility-centric whereas Layout is named-layout-centric (different mental model, both worth keeping for now).
- **No multi-monitor automatic layout.** Floating-window positions are user-driven; their bounds are saved as part of the layout JSON but not auto-relocated.
- **No tabbed dock targets.** Same scope cap as user story 285.
- **No cross-DAW interchange.** Importing Ableton / Cubase / Logic layouts is out of scope.
- **No animated dock transitions.** Per user story 279's Reduce Motion default — switch is instant.
- **No removal of `DetachPluginRequestedEvent`'s "stub" status.** This story consumes the event; the plugin-detach UX itself (a floating plugin window with its own chrome) is the focused-plugin story's domain.

## Technical Notes

- Depends on user story 285 (DockManager production wire-up). Without 285, the `LayoutManager.Host` implementation has no live dock state to read / write.
- The block-comment caveat at `MainController.java:1611-1616` is the current "hold-the-line" marker for both stories. Once 285 deletes it, this story adds the `LayoutManager` field, the host bridge, the menu wiring, and the project-persistence hooks.
- `BuiltInLayouts.java` already enumerates the five built-in names; do not move them into `MenuConstructionService`. The menu reads them from `layoutManager.savedLayouts()`.
- `LayoutManager.toJson()` / `fromJson(String)` is opaque — `ProjectSerializer` writes/reads the full JSON blob, not individual fields. This keeps the layout schema owned by the layout package, not by the persistence layer.
- The user-supplied layout name in "Save Layout As…" can contain any character. `MigrationSuppression` (story 188) handles JSON escaping safely; reuse the same encoding helper.
- Static strings ("Layout", "Save Layout As…", "Manage Layouts…", the per-built-in display names, "Delete", "Rename") come from `Messages.properties`. User-saved layout names are user input — no i18n.
- Reference: user story 282 (Mission Control), user story 195 (Dockable Panels), user story 285 (DockManager production wire-up — prerequisite), user story 188 (Project Version Migration Registry — for the `layout` field migration).
