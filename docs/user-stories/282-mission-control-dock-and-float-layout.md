---
title: "Mission Control: Dock-and-Float Layout with Per-Project Persistence"
labels: ["enhancement", "ui", "ui-overhaul", "phase-4", "mission-control", "docking"]
---

# Mission Control: Dock-and-Float Layout with Per-Project Persistence

## Motivation

Phase 4 of the UI Design Book §6 migration roadmap. Builds on: every previous Phase 1 / 2 / 3 story. Extends / partially supersedes #195 (Resizable, Detachable, Dockable Panels).

UI Design Book §4 Concept D ("Mission Control") is the most flexible and most ambitious layout. Every panel detaches into a window. The DAW becomes a workspace, not a single layout. Per §4:

- Persistent **dock manifest** at the bottom listing every panel.
- Panels carry a grip handle — drag to retile or detach.
- A **layout switcher** in the menu bar lets the user save / load named layouts ("Tracking", "Mixing", "Mastering", "Live").
- Visualisation tiles (spectrum, correlation, loudness) are first-class panels.
- Arrangement view is *just another panel* — it does not own the centre.

§4 marks Mission Control as High-risk / High-wow / High-cost. "D in particular needs a real docking framework decision; defer until phases 1–3 ship." This story explicitly carries that long-horizon caveat.

**Relationship to story 195**: story 195 ("Resizable, Detachable, Dockable Panels") covers the structural docking-framework decision and the basic detach/redock interaction. Mission Control is the *fully-realised* version of that capability — once 195 has chosen a docking foundation (DockFX, hand-rolled, or other), Mission Control adds:
- Per-project layout persistence (different layouts for Tracking / Mixing / Mastering / Live).
- The dock manifest at the bottom.
- Treating the arrangement view as just another dockable panel (rather than the privileged centre slot).
- The View → Layout menu for save / load / rename / delete named layouts.

If story 195 has not yet been implemented when this story is picked up, this story depends on it; if 195 has only partially solved the problem, this story builds on the existing foundation.

## Goals

- **Coordinate with story 195.** If 195's docking framework decision has been made, consume it here. If 195 has only delivered a partial mechanism, extend it. Do not re-author the basic detach/redock interaction.
- Add `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/layout/LayoutManager.java`:
  - `ObservableList<NamedLayout> savedLayouts` — each `NamedLayout` is a record of the dock state (which panels are docked where, which are floating, window positions, split-pane ratios).
  - Methods: `saveCurrent(String name)`, `load(String name)`, `delete(String name)`, `rename(String oldName, String newName)`.
  - Layouts are persisted **per project** in the project file (coordinate with story 188's version migration). Project files without a layout fall back to a sensible default.
  - Built-in layouts (read-only): "Default", "Tracking", "Mixing", "Mastering", "Live".
- Implement the dock manifest:
  - A horizontal bar at the bottom of the main window (above the status bar), listing every panel (docked or floating) as a tab. Each tab has the grip handle from §4's mockup.
  - Click a tab → focuses / unhides the panel.
  - Tab styling reuses story 264's `dawg-button` for consistency.
- Treat the arrangement view as just-another-panel:
  - Today the arrangement panel is hardcoded into the centre of the `BorderPane` in `main-view.fxml`. After this story, the centre is a dock host; the arrangement panel is a first-class dock entry that *defaults* to the centre but can be moved or floated like any other.
- Add a "View → Layout" menu (extending story 077's menu bar):
  - List of saved layouts (radio-style — current layout is checked).
  - "Save Layout As…" → opens a dialog (story 276 chrome) to name the current layout.
  - "Manage Layouts…" → opens a dialog listing saved layouts with rename / delete actions.
- Detach behaviour:
  - Every panel grows a grip handle (the §4 mockup shows `⋮⋮`). Drag the grip → detach to a floating window (uses 195's mechanism if available).
  - Float-to-dock by dragging the floating window back over a dock target; dock targets light up with `-accent-soft` overlay per the §7.3 "use background swap, not border" rule.
- Visualisation tiles as first-class panels:
  - Spectrum, correlation, loudness, tuner, room 3D — each becomes a dockable panel rather than a fixed bottom-row decoration. The existing visualisation panels are wrapped in a `DockablePanel` adapter; their internals don't change.
- Tests:
  - `LayoutSaveLoadTest`: arrange three panels (arrangement centre, mixer right, browser left), save as "Test"; rearrange to defaults; load "Test"; assert the previous arrangement is restored.
  - `LayoutPersistenceAcrossProjectsTest`: open project A with layout "Mixing", switch to project B with layout "Tracking", switch back to A, assert layout is "Mixing".
  - `DockManifestTest`: assert the dock manifest at the bottom enumerates every currently-active panel; close one panel, assert the manifest updates.
  - `ArrangementAsDockablePanelTest`: float the arrangement, assert the centre dock slot is empty and the arrangement is in a separate window. Re-dock, assert centre slot is restored.
  - `LayoutMenuTest`: open View → Layout, assert the five built-in layouts appear in the menu and the current layout is checked.

## Non-Goals

- Choosing a docking framework. That is story 195's domain. This story consumes whatever 195 picked.
- Multi-monitor *automatic* layout (e.g., "put the mixer on monitor 2 by default") — the user manually positions floating windows; their positions are saved.
- Per-user (vs per-project) layout libraries — defer; layouts are per-project, with built-ins available everywhere.
- Cross-DAW interchange of layouts (importing Ableton's layout, etc.) — out of scope.
- Tabbed dock targets (multiple panels in the same dock slot as tabs) — defer to a follow-on; for this story each dock slot hosts one panel.
- Animating dock / undock transitions — instant per Reduce Motion rules.

## Technical Notes

- This is the highest-risk Phase 4 story by far. The user's design book is explicit: "defer until phases 1–3 ship." This story should be picked up only after the entire Phase 2 component library exists, every panel has been ported to the new controls, and the Phase 3 theme/density/motion settings are stable.
- The layout persistence schema is new — add a `layout` field to the project model and bump the version (story 188). The actual project-file write happens off the FX thread via a `javafx.concurrent.Task` (skill §11) — never block the FX thread on layout I/O.
- Built-in layout names ("Default", "Tracking", "Mixing", "Mastering", "Live"), View → Layout menu item labels, and dock manifest tab tooltips come from the existing `Messages.properties` resource bundle. User-saved layout names are user-supplied strings (no i18n). Skill §14.
- Detach/redock interactions fire typed `javafx.event.Event` subclasses (`PanelDetachRequestedEvent`, `PanelDockRequestedEvent`) so consumers integrate via the standard event dispatch chain. Skill §12.
- Long-horizon. May be split into sub-stories during implementation (e.g., dock manifest as a separate PR, layout persistence as another, arrangement-as-panel as a third).
- Reference: UI Design Book §4 Concept D, §6, §7.3 (background-swap rule for dock targets — implicit AC).
