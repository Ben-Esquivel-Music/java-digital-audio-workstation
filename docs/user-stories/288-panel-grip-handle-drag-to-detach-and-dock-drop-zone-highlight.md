---
title: "Panel Grip-Handle Drag-to-Detach and Dock Drop-Zone Highlight (Fire PanelDetach/PanelDock Events From a User Gesture)"
labels: ["enhancement", "ui", "ui-overhaul", "mission-control", "docking", "phase-4"]
---

# Panel Grip-Handle Drag-to-Detach and Dock Drop-Zone Highlight

## Motivation

User story 282 — "Mission Control: Dock-and-Float Layout with Per-Project Persistence" — specifies two user-gesture surfaces that stories 285 and 286 did **not** deliver:

> **Detach behaviour:**
> - Every panel grows a grip handle (the §4 mockup shows `⋮⋮`). Drag the grip → detach to a floating window (uses 195's mechanism if available).
> - Float-to-dock by dragging the floating window back over a dock target; dock targets light up with `-accent-soft` overlay per the §7.3 "use background swap, not border" rule.
> — `docs/user-stories/282-mission-control-dock-and-float-layout.md:48-50`

Story 286 created the typed events `PanelDetachRequestedEvent` and `PanelDockRequestedEvent` and wired **receiving** bridge handlers in `MainController` that translate them into `DockManager.float_(...)` / `DockManager.moveToEnd(...)`:

```java
// MainController.java:1875-1888 (story 286)
rootPane.addEventHandler(PanelDetachRequestedEvent.PANEL_DETACH_REQUESTED, e -> {
    if (dockManager != null) dockManager.float_(e.getPanelId(), null);
});
rootPane.addEventHandler(PanelDockRequestedEvent.PANEL_DOCK_REQUESTED, e -> {
    if (dockManager != null) dockManager.moveToEnd(e.getPanelId(), e.getTargetZone());
});
```

But **nothing in production ever fires those events** — they are constructed only in test code:

```
$ grep -rn 'new PanelDetachRequestedEvent\|new PanelDockRequestedEvent' daw-app/src/
daw-app/.../PanelDetachRequestedEvent.java:39:    public PanelDetachRequestedEvent(String panelId) {
daw-app/.../PanelDetachRequestedEvent.java:48:    public PanelDetachRequestedEvent(String panelId, Object source, EventTarget target) {
daw-app/.../PanelDockRequestedEvent.java:39:    public PanelDockRequestedEvent(String panelId, DockZone targetZone) {
daw-app/.../PanelDockRequestedEvent.java:46:    public PanelDockRequestedEvent(String panelId, DockZone targetZone, ...) {
daw-app/src/test/.../PanelDetachEventBridgeTest.java:52:   handler.handle(new PanelDetachRequestedEvent("mixer"));
daw-app/src/test/.../PanelDetachEventBridgeTest.java:74:   handler.handle(new PanelDockRequestedEvent("mixer", DockZone.BOTTOM));
daw-app/src/test/.../ArrangementAsDockablePanelTest.java:73:   PanelDetachRequestedEvent detach = new PanelDetachRequestedEvent("arrangement");
daw-app/src/test/.../ArrangementAsDockablePanelTest.java:78:   PanelDockRequestedEvent dock = new PanelDockRequestedEvent("arrangement", DockZone.CENTER);
```

The only constructor call sites are the two constructors themselves plus four lines in `PanelDetachEventBridgeTest` and `ArrangementAsDockablePanelTest`. There is no grip handle on any dockable panel and no drag gesture that publishes either event:

```
$ grep -rn 'setOnDragDetected\|dock-grip\|⋮⋮\|drop-zone\|DropZone' daw-app/src/main/java/.../ui/dock/
(no matches)
```

The only `⋮⋮` glyph in the entire source tree is on `TrackStripSkin` (`daw-app/.../controls/skin/TrackStripSkin.java:37`) — that is the *track-reorder* handle from story 270, unrelated to panel docking. No dockable **panel** (`MixerView`, `BrowserPanel`, `EditorView`, `MasteringView`, the arrangement, or any visualisation panel) has a docking grip handle, a `setOnDragDetected` handler, or a drop-zone highlight on drag-over.

Net effect: the detach/redock *machinery* is live (the `DockManager.float_` / `move` API works, the receiving bridge works, the floating-`Stage` registry in `MainControllerDockHost.ensureFloating(...)` at `MainController.java:2167` works), but a user has **no way to invoke it by direct manipulation**. Panels can only be floated programmatically or via a future menu. The grip-handle drag and the drop-zone highlight — two explicit 282 Goals — are the missing user-facing surface. Neither appears in the Goals or Non-Goals of story 285 or 286 (286 carved out the *event consumer* wire-up but not the *event producer* gesture), and neither is on any deferral list in `GAP_AUDIT_NOTES.md`.

## Goals

- **Grip handle on every dockable panel.** Add a small drag affordance (the §4 mockup `⋮⋮`, rendered as a Lucide SVG glyph per story 265) to the chrome of each registered `Dockable` panel — `MixerView`, `BrowserPanel`, `EditorView`, `MasteringView`, the arrangement view, and (after story 287) the visualisation panels. Factor the handle into one reusable node (e.g. `daw-app/.../dock/PanelGripHandle.java`) so each panel does not re-implement the gesture. The handle reads its panel id from the host `Dockable#dockId()`.
- **Drag the grip → detach.** Install `setOnDragDetected` on the grip that starts a JavaFX drag-and-drop gesture carrying the panel id (a `ClipboardContent` with a custom `DataFormat`, e.g. `application/x-dawg-dock-panel`). When the drag ends over empty space / outside a dock target, fire `new PanelDetachRequestedEvent(panelId)` on the panel node so it bubbles to the `rootPane` handler installed at `MainController.java:1875-1881`, which already calls `dockManager.float_(panelId, bounds)`. Pass the drop-point bounds (not `null`) so the floating `Stage` opens where the user released the drag — extend the existing handler to honour a non-null `bounds` from the event (today it passes `null`; add a `bounds` accessor to `PanelDetachRequestedEvent` or compute bounds in the handler from the drop screen coordinates).
- **Drop-zone highlight on drag-over.** While a panel drag is in progress, each of the five dock zones (`TOP` / `BOTTOM` / `LEFT` / `RIGHT` / `CENTER`) shows a hover highlight when the pointer is over it. Per UI Design Book §7.3 and 282's explicit AC, the highlight is a **background swap** to `-accent-soft`, **not** a border. Implement via `setOnDragOver` / `setOnDragExited` on each zone region of the main `BorderPane`, toggling a `dock-drop-target-active` style class that maps to `-fx-background-color: -accent-soft;` in `styles.css`. `setOnDragOver` must `acceptTransferModes(TransferMode.MOVE)` only when the drag carries the dock `DataFormat`.
- **Drop on a zone → re-dock.** When the drag is released over a dock zone, fire `new PanelDockRequestedEvent(panelId, targetZone)` so it bubbles to the `rootPane` handler at `MainController.java:1882-1888`, which already calls `dockManager.moveToEnd(panelId, targetZone)`. Clear all drop-zone highlights on `setOnDragDone`.
- **Drag a floating window back onto a dock target → re-dock.** For a panel already floating in its own `Stage` (created by `MainControllerDockHost.ensureFloating`, `MainController.java:2167-2219`), dragging its grip back over a main-window dock zone fires the same `PanelDockRequestedEvent`. The existing `ensureFloating` already re-docks on OS-close (`stage.setOnCloseRequest`, `MainController.java:2195-2202`); this adds the drag-back path.
- **Respect Reduce Motion.** The drop-zone highlight is an instantaneous background swap (no fade) — consistent with the `MotionManager` Reduce-Motion default (story 279). No animated dock/undock transition (282 Non-Goal: "Animating dock / undock transitions — instant per Reduce Motion rules").
- Tests:
  - `PanelGripFiresDetachEventTest` (new): construct a panel with a `PanelGripHandle`, simulate a drag-detected → drag-released-outside gesture (or invoke the handle's gesture-completion hook directly), assert a `PanelDetachRequestedEvent` with the correct `panelId` is dispatched. Verify through a `rootPane.addEventFilter` on the event payload, **not** via `Event.getSource()` identity (per `feedback_javafx_bubbling_event_test_pitfall.md`).
  - `DropZoneFiresDockEventTest` (new): simulate a drag carrying the dock `DataFormat` released over the `LEFT` zone region, assert a `PanelDockRequestedEvent(panelId, DockZone.LEFT)` is dispatched.
  - `DropZoneHighlightTest` (new): fire a synthetic `DragEvent.DRAG_OVER` carrying the dock `DataFormat` onto a zone region, assert it gains the `dock-drop-target-active` style class; fire `DRAG_EXITED`, assert the class is removed. (Headless-safe — operates on style classes, not rasterisation; see `feedback_javafx_headless_test_pitfalls.md`.)
  - `GripHandlePresentOnAllPanelsTest` (new): instantiate each registered dockable panel, assert each exposes a `PanelGripHandle` (e.g. a node with style class `dock-grip`) in its chrome.
  - `DetachEventCarriesDropBoundsTest` (new): fire a `PanelDetachRequestedEvent` with non-null bounds through the live `rootPane` bridge, assert `dockManager.layout().entry(panelId).floatingBounds()` equals the supplied bounds (proves the handler now honours drop-point placement rather than `null`).

## Non-Goals

- **No new `DockManager` API.** `float_`, `move`, `moveToEnd`, `updateFloatingBounds` (`daw-app/.../dock/DockManager.java:133-188`) are sufficient. This story only adds the *gesture* that drives them.
- **No tabbed dock targets.** Dropping a panel onto a zone that already holds a panel follows the existing single-panel-per-slot behaviour (CENTER stays single-selection per `MainController.toggleCenterDockPanel`, `MainController.java:2029-2050`); multi-panel tabbing in one slot remains a future story (same cap as 285/286).
- **No change to the receiving bridge's target resolution beyond drop-bounds.** The `rootPane` handlers at `MainController.java:1875-1888` already translate the events into dock operations; the only handler change is honouring a non-null `bounds` on detach.
- **No floating-plugin-window detach.** `DetachPluginRequestedEvent` (story 281) is a separate, deliberately-deferred stub (`GAP_AUDIT_NOTES.md` "held off" section); its consumer is the focused-plugin story's domain. This story handles *panel* detach (`PanelDetachRequestedEvent`), not *plugin* detach.
- **No animated transitions.** Instant per Reduce Motion (story 279, 282 Non-Goal).
- **No multi-monitor auto-placement.** The floating `Stage` opens at the drop point; it is not auto-relocated to another monitor (282 Non-Goal).
- **No grip handle on non-dockable child controls.** The track-reorder `⋮⋮` on `TrackStripSkin` (story 270) is unrelated and unchanged.

## Technical Notes

- Files: new `daw-app/.../dock/PanelGripHandle.java` (the reusable grip + `setOnDragDetected` gesture), `daw-app/.../MainController.java` (add `setOnDragOver` / `setOnDragExited` / `setOnDragDropped` on the five `BorderPane` zone regions; extend the detach bridge at 1875-1881 to honour drop bounds; mount the grip handle on each panel's chrome), `daw-app/.../MixerView.java` / `BrowserPanel.java` / `EditorView.java` / `MasteringView.java` (host the grip in their headers), `styles.css` (the `.dock-drop-target-active { -fx-background-color: -accent-soft; }` rule), and possibly `PanelDetachRequestedEvent.java` (add a `Rectangle2D bounds` field/accessor for drop-point placement).
- The drag payload is a JavaFX drag-and-drop `Dragboard` with a custom `DataFormat` (e.g. `new DataFormat("application/x-dawg-dock-panel")`) whose string content is the panel id. `setOnDragOver` accepts the transfer only when `db.hasContent(DOCK_PANEL_FORMAT)`, so the dock drop zones do not react to clip/sample drags (which use the existing `DragVisualAdvisor` path from story 248 — keep the two drag vocabularies distinct).
- `-accent-soft` is the established drop-target token (it is the exact token 282 names for this purpose, line 50; the same token used for dock-target overlays in the §7.3 background-swap rule). Verify it exists in the Onyx-Refined token set (`styles.css`, story 260) — if absent, add it as a low-alpha derivative of `-accent` rather than a raw hex (per `feedback_control_css_role_token_forwarding.md` / `feedback_ui_design_philosophy.md`: tokens not hex).
- Drop-bounds derivation: `DragEvent.getScreenX()/getScreenY()` give the release point; build a `com.benesquivelmusic.daw.sdk.ui.Rectangle2D` with the panel's current width/height anchored at the drop point for the floating-`Stage` placement.
- The bottom `VBox` ordering (manifest bar above status bar, plus any BOTTOM-zone visualisation panel from story 287) means the BOTTOM drop zone's hit-region is the BOTTOM dock content area, not the manifest bar itself — scope the `setOnDragOver` to the correct region node.
- Event-test pitfall: JavaFX rewrites `Event.getSource()` on each node during bubbling, so assert on the event payload via a parent `addEventFilter`, never on `getSource()` identity (`feedback_javafx_bubbling_event_test_pitfall.md`).
- Reference: user story 282 (Mission Control — the source of these goals, lines 48-50), user story 285 (DockManager wire-up — the `float_`/`move` API and floating-`Stage` registry), user story 286 (LayoutManager wire-up — created the receiving event bridge this story finally drives), user story 279 (Reduce Motion — instant highlight), user story 265 (Lucide icons — the `⋮⋮` grip glyph), user story 248 (`DragVisualAdvisor` — the *other* drag vocabulary to keep distinct), UI Design Book §4 Concept D and §7.3 (background-swap drop-target rule).
