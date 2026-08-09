---
title: "Arrangement Navigation: Zoom That Zooms, Scrolling, Minimap"
labels: ["bug", "ui", "arrangement-view", "navigation"]
---

# Arrangement Navigation: Zoom That Zooms, Scrolling, Minimap

## Motivation

Every zoom control in the arrangement is dead. Ctrl+scroll (`ViewNavigationController.java:847-858`), the keyboard zoom bindings, the track context menu's "Zoom In"/"Zoom Out" items (`TrackStripController.java:1089/:1102`), and Home (`MainController.java:4302-4304`) all converge on `onZoomIn()/onZoomOut()/onZoomToFit()` (`ViewNavigationController.java:863-885`), which mutate a `ZoomLevel` object and write "Zoom in: 125%" to the status bar — and nothing reads that object. `ArrangementCanvas.setPixelsPerBeat` (`ArrangementCanvas.java:162`) and `TimelineRuler.setPixelsPerBeat` (`TimelineRuler.java:159`) have **zero production callers**: the canvas is pinned at 40 px/beat forever while the status bar reports a fiction.

There is no scrolling at all. `setScrollXBeats`/`setScrollYPixels` (`ArrangementCanvas.java:191/:197`) have zero production callers; only playhead auto-scroll ever moves the horizontal offset and nothing moves the vertical one. The arrangement pane in `main-view.fxml:144` is a bare StackPane with no ScrollPane and no pan handler.

The story-021 navigation layer was built and never plugged in: `ArrangementNavigator` (`ArrangementNavigator.java:23`) — self-described "central point for handling all navigation interactions", owning `ZoomLevel`, `TrackHeightZoom`, `ScrollPosition`, and `MinimapModel`, with a `ViewportState` persistence snapshot — is never instantiated in production. Only its `BASE_PIXELS_PER_BEAT` constant is referenced (`ArrangementCanvas.java:64`), and `MinimapModel` has no view component anywhere.

For a studio engineer: any session taller than ~8 tracks or longer than one screen is uneditable — the tracks and bars beyond the first viewport are unreachable by any gesture, and the zoom percentage on screen is a lie. The inner editing loop (trim/fade/slip/split/glue/clipboard/nudge/automation) is genuinely good; the shell around it is theatre.

## Goals

- **Instantiate `ArrangementNavigator` as the single viewport transform** (book §3.1): one instance per arrangement owns `pixelsPerBeat`, `scrollXBeats`/`scrollYPixels`, `rowLayout`, and `contentExtent`. The canvas, ruler, strip panel, minimap, and every hit-test read the *same* instance; no surface keeps a private copy of any of its facts (book §2.3, rejection #5).
- **Every zoom/scroll gesture routes through the transform** (book §5.1): Ctrl+wheel zoom is **cursor-anchored** (the beat under the pointer stays under the pointer); wheel scrolls vertically (clamped to `contentExtent`); Shift+wheel scrolls horizontally in beats; Alt+wheel drives track-height zoom via `rowLayout`; +/− keys, menu zoom, and the context-menu zoom items share one code path (anchor = view centre); Home / zoom-to-fit frames `contentExtent` exactly.
- **Strips scroll in lock-step with lanes**: the strip panel renders from the transform's `rowLayout` (base track heights) and vertical offset, so row N sits beside lane N at every scroll position.
- **Scrollbars** appear when content exceeds the viewport, two-way bound to the transform with ranges derived from `contentExtent`.
- **Minimap view built over `MinimapModel`**: click/drag scrolls to the framed region; the viewport box position derives from the transform and tracks it live.
- **Status-bar zoom truth by construction**: the reported percentage reads the transform's actual `pixelsPerBeat` ratio.
- **`ViewportState` persists per project** (the model already defines the snapshot) and restores on open — reopening a project restores zoom and scroll.
- **Harness-B ledger shrink**: the wiring-seam ledger entries for the viewport setters this story wires (`ArrangementCanvas.setPixelsPerBeat/:162`, `setScrollXBeats/:191`, `setScrollYPixels/:197`, `TimelineRuler.setPixelsPerBeat/:159`) are deleted as they gain production callers (harness B lands with story 345, the preceding stage).

## Goals — Tests

- **Cursor-anchor test**: Ctrl+wheel over the arrangement changes `pixelsPerBeat` and the beat under the pointer is identical before and after; canvas, ruler, and minimap update together.
- **Reachability test**: a 40-track, 200-bar session is fully reachable — every track by wheel/scrollbar, every bar by Shift+wheel/scrollbar/minimap; nothing beyond the first viewport is unreachable.
- **Lock-step test**: at every vertical offset, strip row N aligns with canvas lane N; hit-testing resolves the same track the row appears beside.
- **Horizontal-agreement test**: after Shift+wheel, the ruler origin and the canvas origin agree (same `scrollXBeats`).
- **Single-path test**: +/− keys, the menu items, and the context-menu zoom items all mutate the transform through the same route as Ctrl+wheel (view-centre anchor) — no second zoom code path exists.
- **Zoom-to-fit test**: Home/fit frames `contentExtent` exactly — the first and last clip are both visible.
- **Minimap test**: clicking/dragging the minimap scrolls the viewport; the viewport box position is computed from the transform (no private copy).
- **Status-truth test**: the status-bar percentage equals the transform's `pixelsPerBeat` ratio after any zoom route.
- **Persistence round-trip test**: zoom in, scroll to bar 90, save, reopen — the viewport restores; `ViewportState` round-trips through the project save path.
- **Ledger test**: harness B's seam list no longer contains the wired viewport setters — the scan fails if any of them loses its production caller again.

## Non-Goals

- **The ruler's stale-Transport rebind** (`ArrangementCanvasFactory.java:49`, `TimelineRuler.java:118`) — Book 1's story 315 owns it. This story keeps all ruler *geometry* reads on the transform and must not capture project state by value anywhere new (book §8 Stage 3).
- **Strip/lane height unification for automation-lane expansion and fold state** (and the "Expand Track" context item) — story 342 completes `rowLayout` coverage; this story lands base-height lock-step scrolling.
- **`samplesPerBeat`/`sessionRateHz` feeds, waveform windowing, and everything else in the visual-truth contract** — story 342.
- **Drag ghost/snap rendering** — story 343 (its presenter translates positions through this transform).
- **Ledger-driven repaint economy** — story 347 (viewport changes become invalidation-ledger causes there; until then the existing repaint path repaints on transform change).
- **Marker/locator ruler flags and jump navigation** — existing story 032 (this story unblocks it; ruler flags only make sense once the ruler scrolls and zooms).
- **Browser waveform thumbnails** — existing story 027 (deferred scope).

## Technical Notes

- **Implements Stage 3 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "Arrangement Navigation: Zoom That Zooms, Scrolling, Minimap"** (§3.1 the viewport transform, §4.1 the arrangement rendering stack, §5.1 the navigation gesture contract). The book's stages run in **dependency order 344 → 345 → 341 → 342 → 343 → 346 → 347**: this story assumes story 344's focus-guarded shortcuts and story 345's harness-B wiring-seam ledger are in place.
- **Completes existing story 021 — "Waveform Zoom and Scroll with Minimap Navigation"** (its commit landed model classes and tests only; the models are well designed and are consumed as-is, per book §1.10).
- Files: `ViewNavigationController.java:847-885` (gesture routes retargeted to the navigator), `TrackStripController.java:1089/:1102` (context zoom), `MainController.java:4302-4304` (Home), `ArrangementCanvas.java:64/:162/:191/:197`, `TimelineRuler.java:159`, `main-view.fxml:144` (scroll/viewport container), `ArrangementNavigator.java:23` + `ZoomLevel`/`TrackHeightZoom`/`ScrollPosition`/`MinimapModel`/`ViewportState` (existing, dormant); new minimap view component.
- The load-bearing property of §4.1 is **one arrow direction**: gestures write the transform, every surface reads it, nothing else stores geometry. `ZoomLevel`'s status-bar reporting stays — it just becomes true.
- `ViewportState` persistence goes through the project save/load path (the book hands the expectation to `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` in §7); no format work beyond round-tripping the snapshot the model already defines. Atomic-write mechanics are story 331's.
- Unblocks: story 342 (peak-cache keys and waveform windows need live pixels-per-beat), story 343 (ghost/snap positions translate through the transform), story 347 (viewport changes become ledger causes), and existing story 032.
- Research backing: the `research-daw` skill's DAW-architecture analysis (Ardour/Audacity/LMMS editor-viewport patterns informing §3.1, per the book's Appendix B).
- Cross-refs: existing 021 (completed here), 315 (ruler transport rebind, Book 1), 342/343/346/347 (later stages), 344/345 (preceding stages), 331 (save mechanics), 032/027 (unblocked/adjacent).
