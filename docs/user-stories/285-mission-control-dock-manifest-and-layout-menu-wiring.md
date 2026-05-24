---
title: "Mission Control: Wire Dock Manifest Bar and View → Layout Menu (Story 282 UI Follow-On)"
labels: ["enhancement", "ui", "ui-overhaul", "phase-4", "mission-control", "docking"]
---

# Mission Control: Wire Dock Manifest Bar and View → Layout Menu (Story 282 UI Follow-On)

## Motivation

Story 282 landed the **model layer** for Mission Control — `LayoutManager`, `NamedLayout`, `DockManifestModel`, `BuiltInLayouts`, and the typed `PanelDetachRequestedEvent` / `PanelDockRequestedEvent` envelopes are all present under `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/layout/`, with thorough unit tests (`ArrangementAsDockablePanelTest`, etc.). The story's *Goals* list, however, included three UI integrations that did **not** ship:

1. The **dock manifest bar** — "a horizontal bar at the bottom of the main window (above the status bar), listing every panel (docked or floating) as a tab." `DockManifestModel` exists but has no view; `main-view.fxml` is untouched.
2. The **View → Layout menu** — a radio-group of saved layouts plus "Save Layout As…" and "Manage Layouts…". `MenuConstructionService` has no `LayoutManager` reference today; the menu is missing entirely.
3. The actual **instantiation and wiring** of `LayoutManager` into the running application. A reactor-wide grep for `new LayoutManager(` returns hits only in tests — production code never constructs one, so saving/loading layouts is dead code from the user's perspective.

A grep for `LayoutManager` across `MainController.java`, `MenuConstructionService.java`, and `DawApplication.java` returns zero hits. A grep for `dock-manifest` / `dockManifest` in `main-view.fxml` returns zero hits. The model is correct; the controller wiring is missing.

This is the *visible* half of story 282 — the half a user can see and use. It is filed separately because story 282's review already shipped the model layer and the next user-facing slice deserves its own focused PR.

## Goals

- **Instantiate `LayoutManager` once in the app composition root.** Construct it in `DawApplication.start(...)` (or wherever the dock host is created in `MainController`), pass it a `LayoutManager.Host` implementation backed by the existing `DockManager`, and expose it through the same controller-injection pattern used for `ThemeManager`, `KeyboardShortcutController`, etc. The instance lives for the app lifetime.

- **Add the dock manifest bar to `main-view.fxml`.**
  - Place a new `HBox` (style class `dock-manifest`) immediately above the status bar in the main `BorderPane`'s bottom slot — the existing bottom slot becomes a `VBox` containing the manifest bar on top and the status bar on the bottom.
  - Bind the manifest's children to `DockManifestModel.entries()` via a small `DockManifestView` controller in `daw-app/.../ui/layout/`. Each entry renders as a story-264 `dawg-button` (`.dawg-button.tab`) showing the panel name; tooltip is the i18n localized panel name from `Messages.properties` (skill §14).
  - Clicking a tab calls `DockManager.focus(panelId)`; for floating panels this raises and focuses the floating `Stage`, for docked panels it brings the dock slot to the front (or scrolls it into view for tabbed slots).
  - Subscribe to `DockManifestModel.addListener(...)` so the bar refreshes when panels are added / removed / float / dock. Remember to call the returned `Runnable` to unsubscribe in `dispose()` — per the existing `DockManager.addListener` contract (`DockManager.java:123-128`).
  - Manifest bar height comes from `SpacingTokens` (matches the status bar row); padding/spacing must be multiples of 4 per `TokenValidationTest` (`-spacing-xs` between tabs).
  - Styling reuses the role tokens from `.root-pane` (background `surface-2`, border-top `border-subtle`). No new hard-coded colors. Story 277 theme tokens apply automatically.

- **Add the "View → Layout" menu to `MenuConstructionService`.**
  - A new top-level `Menu` in the `View` menu (after the existing items, before the separator preceding "Workshop View" / "Performance Stage" if they are siblings — otherwise at the end of View).
  - Sub-items:
    - One `RadioMenuItem` per entry in `LayoutManager.savedLayouts()` (built-ins first, then user-saved, alphabetically within each group). The currently-loaded layout is checked, driven by `LayoutManager.currentLayout()`.
    - Separator.
    - `MenuItem` "Save Layout As…" → opens a story-276 themed `TextInputDialog`; on OK calls `LayoutManager.saveCurrent(name)`. Empty / duplicate names are rejected with the standard validation pattern (the dialog stays open).
    - `MenuItem` "Manage Layouts…" → opens a small modal listing the saved layouts in a `TableView` with rename / delete actions. Built-in layouts (per `BuiltInLayouts.isBuiltIn(name)`) are read-only: their rows render with a disabled action column. The dialog is themed via `ThemeManager.applyTo(dialogPane)` per the existing convention.
  - The menu rebuilds reactively when `LayoutManager.savedLayouts()` changes (it is an `ObservableList`; just bind once).
  - Labels and tooltips come from `Messages.properties`; keep the existing locale-aware pattern.

- **Keyboard shortcut.** Add a `KeyCodeCombination` (default `Shift+Ctrl+L` on Win/Linux, `Shift+Cmd+L` on Mac, see `KeyboardShortcutController`) that opens "Save Layout As…". This is the only new binding; layout switching is menu-driven for v1.

- **Tests:**
  - `DockManifestViewTest`: build the view with a `DockManifestModel` over a stub `DockManager`, assert one tab per entry; mutate the model (add/remove a panel), assert the bar updates; click a tab, assert `DockManager.focus(panelId)` is invoked. Use a `Runnable` capture to confirm `dispose()` calls the unsubscribe handle.
  - `LayoutMenuConstructionTest`: build the menu with a `LayoutManager` containing the five built-ins plus one user-saved layout; assert six radio items and the two trailing menu items; assert the user-saved layout is grouped after built-ins; assert the currently-loaded layout's `RadioMenuItem.selected` is true.
  - `LayoutMenuLifecycleTest`: invoke "Save Layout As…" via the menu, supply a name, assert `LayoutManager.savedLayouts()` grew by one and a new `RadioMenuItem` appeared.
  - `ManageLayoutsDialogTest`: open the dialog with one built-in and one user layout; assert the built-in's rename/delete buttons are disabled; rename the user layout; assert `LayoutManager.savedLayouts()` reflects the rename.
  - `MainViewDockManifestSlotTest`: load `main-view.fxml`, walk the scene graph, assert a `dock-manifest`-styled node sits immediately above the status bar in the bottom slot.

## Non-Goals

- **No new `LayoutManager` API.** Consume what story 282 shipped as-is. If the wiring reveals a missing method (e.g. `currentLayoutProperty()`), file separately rather than expanding scope.
- **No grip handles on panels and no drag-to-detach interaction.** Panels keep their existing drag-to-detach mechanism (from story 195 / `DockManager`). Adding the §4 mockup's `⋮⋮` grip is a separate visual-polish concern — file as a follow-on if surfaced.
- **No tabbed dock targets.** Each dock slot still hosts one panel. Story 282's non-goal stays a non-goal.
- **No promotion of the arrangement view to a dockable panel.** That migration is the subject of the parallel follow-on story (286). This story only wires the manifest bar around whatever `DockManager` currently exposes.
- **No persistence-schema changes.** `LayoutManager` already serialises via story 282; this story neither bumps the project version nor touches `ProjectManager` migration code. If the saved-layout JSON needs a field this story uncovers, file separately and gate the wiring on a default.
- **No CSS palette changes.** Use existing role tokens from `.root-pane`. No additions to the Palette A token block in `styles.css`.
- **No live re-theming of the dialogs beyond `ThemeManager.applyTo(DialogPane)`** — the existing helper already handles palette swaps; no per-dialog stylesheet management.
- **No virtual-thread / I/O threading work.** Layout save/load is fast in-memory mutation; the actual project-file write that persists the layout is owned by story 282 / `ProjectManager` and runs on a background virtual thread there already.

## Technical Notes

- The dock manifest bar belongs in the same `VBox` as the status bar so the layout system treats them as a single bottom region. Don't create a separate `BorderPane`-bottom child — Story 274 already standardised the status-bar row, and inserting between `BorderPane` slots forces an FXML controller swap. A `VBox` with two children is the smallest possible change.
- `DockManager.addListener(Consumer<DockLayout>)` returns a `Runnable` you must invoke to unsubscribe (per existing repo convention). Store the handle in a final field and call it in `dispose()`; missing this leaks the manifest view across project re-opens — the exact future-bug class story 283's notes call out for any new subscriber.
- The two new dialogs ("Save Layout As…" and "Manage Layouts…") must call `ThemeManager.applyTo(dialogPane)` (see `ThemeManager.java:309-334`) so they pick up Palette A defaults and live re-theming. This is idempotent and the canonical pattern in this codebase.
- The `RadioMenuItem.selected` binding must be unidirectional from `LayoutManager.currentLayout()` → menu — not the other way. The user picks a layout via `LayoutManager.load(name)`; that mutates `currentLayout()`; the binding updates the radio. Letting the menu *write* to `currentLayout()` directly bypasses the manager's save-current-on-switch logic.
- The "Save Layout As…" dialog's text field must `requestFocus()` on show. Pre-existing convention in `RenderCacheStatsDialog`, etc.
- Built-in layout names ("Default", "Tracking", "Mixing", "Mastering", "Live") and the menu's labels come from the existing `Messages.properties` resource bundle. User-saved layout names are user-supplied strings (no i18n) — render them verbatim with the surrounding chrome localised. Skill §14.
- This story is the *visible* completion of story 282; story 286 (arrangement-as-dockable + visualisation tiles as dockables) is the parallel follow-on. The two are independent — either may merge first.
- Reference: story 282 (model layer), story 195 (`DockManager` foundation), story 264 (`dawg-button`), story 274 (status bar row), story 276 (dialog chrome), story 277 (theme tokens).
