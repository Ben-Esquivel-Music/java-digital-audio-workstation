---
title: "Drag-and-Drop Feedback Rendered, Import Off the FX Thread"
labels: ["bug", "ui", "drag-and-drop", "performance"]
---

# Drag-and-Drop Feedback Rendered, Import Off the FX Thread

## Motivation

Story 197 landed `DragVisualAdvisor` — a fully tested state machine producing ghost preview, drop-target highlight, snap indicator, and modifier-cursor state. Nothing renders it:

- `update(...)` (`DragVisualAdvisor.java:155`) — the method that produces per-cursor-move visual state — has **zero production callers**, and `currentVisualState()` (`:275`) has no production consumer. The package-info admits presenters are "expected to translate" `DragVisualState`; none exist.
- All three drag sources call only the lifecycle half — `beginDrag`/`commit`/`cancel`/`revertCompleted` (`BrowserPanel.java:681`, `ClipInteractionController.java:1233/:1248`, `InsertEffectRack.java:658`) — and discard the returned state.
- A pointer clip drag paints nothing at all: the handler falls through to the comment "Drag preview is visual only — the actual move happens on release" (`ClipInteractionController.java:588-592`) with no code drawing a preview — **the clip teleports on mouse release**. The Esc cancel-revert path drives advisor states and a `PauseTransition` (`:1204-1213`) — nothing on screen animates.
- The dock drop-zone highlight loses its CSS cascade: `.dock-drop-target-active` (`styles.css:1274`) is a single-class selector declared *before* the equal-specificity `.content-area` (`:1365`) and `.browser-panel` (`:1376`) background rules, so the later declarations win and the highlight never appears over the CENTER or LEFT zones.
- Audio import — the usual endpoint of a browser drag — decodes synchronously on the FX thread (`AudioImportController.java:98`), imports only the first of a multi-file drop (`:226` — the rest vanish silently), and mutates the live transport position to the drop beat and back (`:224/:228`), audibly jolting playback.

For a studio engineer: drags are blind — a clip jumps to its destination only on release, a browser drop offers no target feedback, Esc appears to do nothing, and dropping ten samples freezes the UI mid-session, silently imports one of them, and jolts the playhead audibly if the transport is running.

## Goals

- **The drag presenter** (book §3.4): a single overlay layer owned by the arrangement — plus a lightweight equivalent for browser/rack list drags — renders, from `DragVisualState`: the ghost preview at the *snapped* position (translated through the story-341 viewport transform), the drop-target row/slot highlight, the snap guide line with its label, and the modifier cursor (copy/link/no-drop). Highlight colours come from theme tokens (the `-accent-soft` family), never hex.
- **`update(...)` is called on every pointer move** from every drag handler — arrangement clip drags, browser drags, and insert-rack drags — completing existing stories 197 and 248. The presenter is the *only* renderer of drag state; the canvas-drawn slip/trim previews migrate into it or are explicitly exempted as tool-local previews (book §3.4).
- **Commit lands where the ghost was**: on release the ghost is removed and the clip appears at the previewed position — no teleport. **Esc visibly reverts**: the cancel-revert sequence the advisor already drives produces on-screen animation.
- **The dock drop-zone highlight visibly wins for all five zones** — a dedicated overlay or a cascade-proof rule, never an equal-specificity class fighting `.content-area` (book §9.9).
- **Audio import moves off the FX thread**: async decode with `ProjectOperationProgress` reporting, **every** dropped file imported (a per-file failure is reported, not swallowed — silent drops are the defect class, book §9.8), and the drop beat travels as a parameter so the live transport position is never touched.

## Goals — Tests

- **Ghost-render test**: dragging a clip renders a ghost at the snapped position with the drop lane highlighted (nothing renders today); the ghost position translates through the viewport transform at the current zoom/scroll.
- **Update-wiring test**: each of the three drag sources calls `update(...)` per pointer move and the presenter consumes `currentVisualState()` — harness B's seam entries for both methods are deleted, and the scan fails if either loses its production caller again.
- **No-teleport commit test**: on release, the clip lands exactly where the ghost last showed; the presenter clears its overlay.
- **Esc-revert test**: cancelling a drag produces a visible revert animation (the advisor's cancel-revert states drive on-screen change, honouring Reduce Motion).
- **Modifier-cursor test**: copy/link/no-drop states from `DragVisualState` change the rendered cursor.
- **Snap-line test**: the snap guide line and its label render at the advisor-reported snap position.
- **Dock-zone visibility test**: with a dock drag active, the highlight is visually present over every one of the five zones — CENTER and LEFT included (regression test for the cascade loss).
- **Multi-file import test**: a 10-file drop imports all 10 files; decode runs off the FX thread; progress is reported through `ProjectOperationProgress`; a corrupt file in the batch surfaces a per-file failure while the rest import.
- **Playhead-invariance test**: importing at a drop beat during playback never mutates the transport position (the beat is passed as a parameter end-to-end).
- **Token test**: presenter and highlight colours resolve from theme tokens — no new hex literals (feeds the existing colour-audit sentinel's scope).

## Non-Goals

- **MIDI clip drag-move parity** — story 346 (its drag-moves inherit this presenter).
- **Changes to the advisor's state machine** — story 197 landed it as designed; it is consumed, not modified.
- **Menu-truth work, including any import/export menu items** — story 345.
- **Browser waveform thumbnails** — existing story 027.
- **Repaint economy and the motion/theme conformance harness** — story 347 (the drag layer participates in layered painting there; new animation code here still ships motion-gated from day one per landed story 279).
- **Global exception visibility for import failures** — Book 4 stories 336/339 own the failure-surfacing infrastructure; this story reports per-file import outcomes through the existing progress/notification seams only.
- **Dock drag-to-detach gesture mechanics** — landed story 288; only the drop-zone highlight's visibility is in scope here.

## Technical Notes

- **Implements Stage 5 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "Drag-and-Drop Feedback Rendered, Import Off the FX Thread"** (§3.4 drag visual state and its presenter, §5.3 the drag feedback contract). Dependency order 344 → 345 → 341 → 342 → **343** → 346 → 347: requires story 341's viewport transform (ghost/snap translation) and benefits from 342's `rowLayout` alignment so the highlighted lane matches the strip beside it.
- **Completes existing story 197 — "Drag Cursor and Drop-Target Visual Feedback Polish" and existing story 248 — "Integrate DragVisualAdvisor into Clip / Plugin / Sample Drag Sources for Ghost Previews and Drop-Zone Highlighting."**
- Files: `DragVisualAdvisor.java:155/:275` (consumed, unchanged), `ClipInteractionController.java:588-592/:1204-1213/:1233/:1248`, `BrowserPanel.java:681`, `InsertEffectRack.java:658`, `styles.css:1274/:1365/:1376` + the `DockDropZones` surface, `AudioImportController.java:98/:224/:226/:228`. New: the arrangement drag-presenter overlay and the lightweight list-drag presenter.
- Seams to reuse: `ProjectOperationProgress` (landed story 295) for import progress; the `FxDispatcher` marshalling seam (landed story 289) for background-decode → FX-thread hand-off; the repo's CSS-cascade lesson (AUTHOR-level scene CSS beats component UA CSS; equal-specificity order decides) argues for a dedicated overlay node over a selector fix for the dock highlight.
- The drag layer is the third layer of the book's §4.1 painting stack (base / overlay / drag) — it exists only during drags, which keeps story 347's idle-cost budget intact.
- Cross-refs: story 341 (prerequisite), story 342 (alignment), story 346 (MIDI drags inherit the presenter), story 347 (layer economy + harness C), story 345 (menu truth), Book 4 336/339 (failure surfacing), existing 197/248 (completed here), existing 027, landed 288/289/295/279.
