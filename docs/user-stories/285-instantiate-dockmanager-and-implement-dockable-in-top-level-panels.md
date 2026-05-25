---
title: "Instantiate DockManager in MainController and Make Top-Level Panels Implement Dockable"
labels: ["bug", "ui", "docking", "ui-overhaul"]
---

# Instantiate DockManager in MainController and Make Top-Level Panels Implement Dockable

## Motivation

User story 195 — "Resizable and Detachable Dockable Panels" — calls for every top-level panel (`ArrangementView`, `MixerView`, `BrowserPanel`, `EditorView`, `TelemetrySetupPanel`, `MasteringChainView`, etc.) to implement a `Dockable` interface, register with a `DockManager`, drag between dock zones, and float out to a `Stage`. The core types are implemented and unit-tested:

- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/DockManager.java`
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/Dockable.java`
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/DockZone.java`
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/DockEntry.java`
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/DockLayout.java`
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/DockLayoutJson.java`
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dock/FloatingWindowStore.java`
- `DockManagerTest`, `WorkspaceDockIntegrationTest` cover register / move / float / persist / monitor-disconnect.

But:

```
$ grep -rn 'new DockManager\b' daw-app/src/main/
(no matches)
$ grep -rn 'implements .*Dockable\|implements Dockable\b' daw-app/src/main/
daw-app/.../MainController.java:1731:   private void registerDockablePanels(...) {
    record Panel(...) implements com.benesquivelmusic.daw.app.ui.dock.Dockable { }
```

Nothing in production instantiates `DockManager`. The one `Dockable` implementation is an inner `record` inside `registerDockablePanels(DockManager dm)` — a method that itself is **never called** (no caller of `registerDockablePanels` exists anywhere in the source tree). `MainController` carries an explicit block-comment at line 1612 — *"Do not create/register a DockManager until the JavaFX docking adapter is available to reconcile layout changes back into the existing panel controllers"* — and the `private com.benesquivelmusic.daw.app.ui.dock.DockManager dockManager;` field at line 200 is read by `onToggleDockMixer` / `onToggleDockBrowser` / `onToggleDockArrangement` (line 1151-1174) only on the `else if (dockManager != null …)` branch, which is forever unreachable because the field is forever null.

Net effect: every "dock" feature shipped by story 195 lives only in tests. `MixerView`, `BrowserPanel`, `EditorView`, `MasteringView`, `ArrangementCanvas`, `TelemetrySetupPanel`, etc. are plain `Region`/`Node` subclasses — none of them implements `Dockable`. The F3 / F4 / F5 keyboard shortcuts fall through to the legacy `viewNavigationController.switchView(...)` / `browserPanelController.toggleBrowserPanel()` path; floating windows for panels do not exist; `WorkspaceManager` (story 193) saves panel show/hide booleans but never restores dock layout because there is no live dock state.

This is also the explicit blocker for user story 282 (Mission Control). `LayoutManager` (story 282) ships its own `Host` bridge (`captureDockLayoutJson` / `applyDockLayoutJson`) intended to flow through `DockManager`, but `LayoutManager` is itself never instantiated in production either — that gap is the subject of a sibling story.

## Goals

- Instantiate the single application-wide `DockManager` in `MainController` once the JavaFX scene graph is mounted. The block-comment at `MainController.java:1611-1616` is the seam: delete the "Do not create/register" caveat and create the manager using a real `DockManager.Host` that wraps the existing `BorderPane` chrome (top / left / right / center / bottom dock zones).
- Implement `Dockable` on each top-level panel as a thin trait rather than inheriting it via an inner record:
  - `MixerView` — `dockId = DefaultWorkspaces.PANEL_MIXER`, `displayName = "Mixer"`, `iconName = "MIXER"`, `preferredZone = DockZone.BOTTOM`.
  - `BrowserPanel` — `PANEL_BROWSER`, `"Browser"`, `"BROWSER"`, `DockZone.LEFT`.
  - `MasteringView` — `PANEL_MASTERING`, `"Mastering"`, `"MASTERING"`, `DockZone.CENTER`.
  - `EditorView` — `PANEL_EDITOR`, `"Editor"`, `"EDITOR"`, `DockZone.CENTER`.
  - `TelemetrySetupPanel` — new id (`PANEL_TELEMETRY` to add to `DefaultWorkspaces`), `"Telemetry"`, `"TELEMETRY"`, `DockZone.RIGHT`.
  - The arrangement view (currently the cached `BorderPane#getCenter()` in `ViewNavigationController#initializeViewNavigation`) — `PANEL_ARRANGEMENT`, `"Arrangement"`, `"TIMELINE"`, `DockZone.CENTER`.
- Delete the orphan `registerDockablePanels(DockManager dm)` private method at `MainController.java:1731-1745`. Replace its single inner `record Panel` with the in-place `Dockable` interface implementations above, then call `dockManager.register(<panel>)` once per panel during initialisation.
- The F3 / F4 / F5 toggle handlers at `MainController.java:1151-1174` lose their `else if (dockManager != null …)` dead branches and become unconditional `dockManager.toggleVisible(<panelId>)` calls; the legacy `viewNavigationController.switchView(...)` / `browserPanelController.toggleBrowserPanel()` path becomes the underlying mechanism the `DockManager.Host` invokes, not a parallel one.
- Wire `WorkspaceManager` (story 193) to the live `DockManager`: when a workspace is applied, the manager's panel-visible / panel-bounds state is restored through `DockManager#applyLayout(DockLayout)` rather than through the existing show/hide booleans. The existing `WorkspaceManager.Host` callback stays, but `MainController`'s implementation now delegates to `DockManager` for layout reconciliation.
- Verify that a panel dragged out to a floating `Stage` and back lands in the same dock zone with the same width / height — the existing `DockManagerTest` covers this in isolation; this story needs an integration test that exercises it through the live `MainController` scene graph.
- Tests:
  - `MainControllerDockManagerInitializedTest` (new): build a `MainController`, force the scene to mount, assert `dockManager != null`, assert `dockManager.layout().contains(PANEL_MIXER)`, `PANEL_BROWSER`, `PANEL_EDITOR`, `PANEL_MASTERING`, `PANEL_ARRANGEMENT`.
  - `DockToggleHandlerTest` (new): trigger `onToggleDockMixer()` via the `DawMenuBarController.Host` callback, assert the mixer's `DockEntry` flips between `visible = true` and `visible = false`. Repeat for browser and arrangement.
  - `DockableImplementationTest` (new): instantiate each of the six top-level panels, assert `instanceof Dockable`, assert `dockId()` / `preferredZone()` match the table above.
  - `WorkspaceAppliesDockLayoutTest` (new): switch from "Tracking" to "Mixing", assert the mixer panel becomes visible and (where the panel-state record carries bounds) sized per the workspace snapshot.

## Non-Goals

- **No Mission Control layout persistence.** `LayoutManager` (story 282) wire-up is a sibling story; this story only ensures `DockManager` itself is reachable from production code. Once `DockManager` is live, `LayoutManager` can subscribe to its layout changes in a follow-on.
- **No floating-plugin-window detach mechanism.** `DetachPluginRequestedEvent` (story 281) currently has no production subscriber; that follow-on is owned by story 282's domain.
- **No new dock zones.** The five existing zones (`TOP` / `LEFT` / `CENTER` / `RIGHT` / `BOTTOM`) are sufficient for this story.
- **No tabbed dock targets.** One panel per dock slot for now; multi-panel tabs in one slot is a future story.
- **No dock-related visual restyle.** Visual polish (drop-zone highlight on drag-over, grip handles, dock-manifest bar at the bottom) is owned by story 282 and the §7.3 background-swap rule.
- **No removal of the existing per-controller show/hide methods** (`browserPanelController.toggleBrowserPanel`, etc.). They become the implementation `DockManager.Host` calls into, not parallel code paths.
- **No virtual-thread / background-thread layout reconciliation.** All dock state mutations stay on the JavaFX application thread (per `DockManager`'s threading contract).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (delete the block-comment caveat at 1611-1616, instantiate `DockManager` in `installWorkspacesMenu` or a new dedicated `installDockManager()`; delete the orphan `registerDockablePanels`; remove the `dockManager != null` dead branches at 1151-1174), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MixerView.java` / `BrowserPanel.java` / `EditorView.java` / `MasteringView.java` / `TelemetrySetupPanel.java` (each implements `Dockable`), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/DefaultWorkspaces.java` (add `PANEL_TELEMETRY` constant if missing).
- The `DockManager.Host` implementation is the load-bearing piece. It needs to know how to add a `Node` to each of the five `BorderPane` zones, how to remove it, how to swap two panels in the same zone (re-ordering), and how to read the zone bounds for floating-window placement. Today every panel's parent is hard-wired in `MainController#initialize` / `MainView.fxml` — the host implementation centralises that knowledge so the existing controllers don't need to know they're now dockable.
- `MainController.java:200` (`private DockManager dockManager;`) already exists; this story just makes it non-null. No new field is required.
- `registerDockablePanels(DockManager dm)` at `MainController.java:1731-1745` is the canonical contract for what should be wired. Use it as the template, then delete it.
- Reference: user story 195 (Resizable and Detachable Dockable Panels), user story 193 (Customizable Workspace Layouts), user story 282 (Mission Control Dock-and-Float Layout — blocked on this).
