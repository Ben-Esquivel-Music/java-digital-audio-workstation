---
title: "Drag Cursor and Drop-Target Visual Feedback Polish"
labels: ["enhancement", "ui", "polish"]
---

# Drag Cursor and Drop-Target Visual Feedback Polish

## Motivation

Dragging anything in the current UI (a clip, a plugin, a sample from the browser) produces minimal visual feedback: no ghost preview, no snap indicator, no informative cursor. Users learn what's valid by trial and error. Every mature desktop tool surfaces drag state explicitly — Figma, Excel, Pro Tools, Ableton — with ghost previews, snap lines, drop-zone highlighting, and cursor modifiers that tell the user "drop here will do X."

## Goals

- Add `DragVisualAdvisor` in `daw-app.ui.drag` that every draggable source consults to produce a drag ghost (a semi-transparent preview image) plus a drop-target highlighter.
- Ghost previews:
  - Clips: waveform-ghosted outline of the clip.
  - Plugins: compact plugin card with name + icon.
  - Samples: waveform mini-preview from the browser.
- Drop-zone highlighting: the valid target (track lane, insert slot, send slot) highlights with a soft tint; invalid targets show a subtle "no" cursor.
- Snap indicators: when dropping a clip onto the arrangement, a vertical guide line shows the snapped position; current snap value displayed inline.
- Cursor modifiers: Ctrl during drag shows a `+` cursor (duplicate); Alt shows a "link" cursor for aliases; Shift disables snap (visible cursor change).
- Keyboard cancellation: Esc during drag reverts the source with a short animation.
- Consistent timing: all drag animations use a single `AnimationProfile` from `AnimationController` for a cohesive feel.
- Tests: start-drag produces a ghost; valid / invalid targets highlight correctly; Esc cancels and restores source position.

## Non-Goals

- Touch-gesture dragging (this story is mouse/trackpad only).
- Drag-and-drop between separate running DAW instances.
- 3D ghost previews for spatial-panner dragging.
