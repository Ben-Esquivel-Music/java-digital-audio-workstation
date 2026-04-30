---
title: "Integrate DragVisualAdvisor into Clip / Plugin / Sample Drag Sources for Ghost Previews and Drop-Zone Highlighting"
labels: ["enhancement", "ui", "polish", "drag-and-drop"]
---

# Integrate DragVisualAdvisor into Clip / Plugin / Sample Drag Sources for Ghost Previews and Drop-Zone Highlighting

## Motivation

Story 197 — "Drag Cursor and Drop-Target Visual Feedback Polish" — calls for ghost previews (clips, plugins, samples), drop-zone highlighting, snap indicators, and modifier-key cursor changes (Ctrl = duplicate, Alt = link, Shift = no snap). The framework is implemented in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/drag/`:

- `DragVisualAdvisor.java`, `DragVisualState.java`, `GhostPreview.java`, `DropTargetHighlight.java`, `DragCursor.java`, `DragModifier.java`, `DragSourceKind.java`, `DropTargetKind.java`, `AnimationProfile.java`.

But:

```
$ grep -rn 'DragVisualAdvisor\|GhostPreview' daw-app/src/main/ | grep -v '/drag/'
(no matches)
```

Nothing outside the `drag/` package imports any of these types. Every drag interaction in the running app — clip drag in the arrangement view, plugin drag onto a mixer insert slot, sample drag from the browser — falls back to JavaFX's default drag handling (drag image is the source node, no drop-zone highlight, no snap indicator). The polish work shipped as a self-contained module nobody plugs into.

## Goals

- Wire `DragVisualAdvisor` into every existing drag source:
  - Clip drag in `ArrangementCanvas` / `ClipInteractionController` — produces a `GhostPreview` of the clip's waveform outline; drop targets are track lanes; snap indicator (vertical guide line) shows the snapped time position; Ctrl modifier shows a `+` cursor (duplicate); Shift disables snap.
  - Plugin drag in `InsertEffectRack` (already supports drop reordering — extend it with the visual layer) — ghost preview is a compact plugin card with name + icon; drop targets are insert slots, sidechain selectors, and other channels' insert racks; Alt cursor for "link" (mirror to paired channel per story 159 / 231).
  - Sample drag in `BrowserPanel` — ghost preview is the waveform mini-thumbnail (story 027); drop targets are arrangement track lanes, MixerView strip inputs, and the browser's own folders.
- Drop-zone highlighting: the valid drop target gains the `drop-target-active` CSS class (define in the existing dark-theme styles); invalid targets show the `no-drop` cursor.
- Esc cancels the in-progress drag with the short fade-out animation already configured in `AnimationProfile`.
- All drag interactions use a single `AnimationProfile` instance from `AnimationController` so the timing feels cohesive across the app.
- The legacy custom drag handling in each source is replaced by a uniform call to `DragVisualAdvisor.startDrag(sourceNode, sourceKind, payload, ghost)` and a corresponding `endDrag(...)` on commit / cancel. Behaviour should be a behaviour-preserving refactor — the existing drag actions (move clip, duplicate clip, drop plugin, etc.) continue to work exactly as before.
- Tests:
  - Headless JavaFX test: start a clip drag, assert `DragVisualAdvisor.currentState()` reports `dragging` with `DragSourceKind.Clip` and a non-null ghost.
  - Test confirms valid drop targets (track lanes) highlight while invalid targets (e.g., a button outside the lane area) show the `no-drop` cursor.
  - Test confirms Esc during drag returns the source to its original position with the fade animation.

## Non-Goals

- Touch-gesture dragging (mouse / trackpad only).
- Drag-and-drop between separate running DAW instances.
- 3D ghost previews for spatial-panner dragging.
- Per-OS native drag-image rendering (the JavaFX-level overlay is sufficient).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ArrangementCanvas.java`, `ClipInteractionController.java`, `InsertEffectRack.java`, `BrowserPanel.java` (replace ad-hoc drag handling with `DragVisualAdvisor` calls).
- The `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/drag/` package is the source of truth for the visuals; do not duplicate ghost-preview generation in the call sites.
- Reference original story: **197 — Drag-and-Drop Target Visual Feedback**.
