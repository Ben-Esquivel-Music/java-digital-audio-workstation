---
title: "Replace Universal Drop-Shadow and Purple Borders with the Elevation System"
labels: ["enhancement", "ui", "ui-overhaul", "phase-1", "design-system", "performance"]
---

# Replace Universal Drop-Shadow and Purple Borders with the Elevation System

## Motivation

Phase 1 of the UI Design Book §6 migration roadmap. Builds on: #260 (tokens), #261 (grid).

UI Design Book §1.5 ("Tile inflation") and §7.10 ("`-fx-effect: dropshadow` on every tile") describe the problem precisely. Today every `.tile`, `.viz-tile`, `.track-list-panel`, `.arrangement-panel`, `.mixer-panel`, `.mixer-channel`, `.track-item` carries the same chrome: 10 px radius, 1 px border at `#2a2a2a`, and a `dropshadow(gaussian, rgba(0, 0, 0, 0.5), 8, 0, 0, 2)` (see `styles.css:23–31`). Every region looks "important" because every region wears the same boxy uniform. Worse, drop-shadow in JavaFX forces an off-screen render pass per node (per the `javafx-application-design` skill §13), so this universal styling has a real frame-rate cost.

Compounding this, §7.1 ("Glow on hover") flags that every hover today carries a soft purple drop-shadow (`styles.css:307–309, 600–608, 651–657`). A glow is a *focal* effect; using it on every interactive element devalues it.

UI Design Book §3.4 specifies the cure: a five-level elevation system. Only level 0 ("flush with panel") is flat; levels 1–4 are reserved for cards-on-panels, hovered/dragged elements, popovers, and modals respectively. Static panels do **not** carry shadow. The shadow communicates "this surface is closer to you" — it is information, not decoration.

This story:
1. Defines the five elevation tokens.
2. Strips drop-shadow from every static panel and tile.
3. Adds shadow only at hover/drag (level 2), popover (level 3), and modal (level 4).
4. Replaces all purple-glow hover effects with `surface-3` background swaps per §7.1.
5. Replaces every literal `dropshadow(...)` declaration with the corresponding `-elevation-N` token.

## Goals

- Add the elevation tokens from UI Design Book §3.4 to `styles.css` (consumed via the Palette A block from story 260):
  - `-elevation-1: dropshadow(gaussian, rgba(0,0,0,0.35), 2, 0, 0, 1);`
  - `-elevation-2: dropshadow(gaussian, rgba(0,0,0,0.45), 6, 0, 0, 2);`
  - `-elevation-3: dropshadow(gaussian, rgba(0,0,0,0.50), 16, 0, 0, 6);`
  - `-elevation-4: dropshadow(gaussian, rgba(0,0,0,0.55), 32, 0, 0, 16);`
- Strip `-fx-effect: dropshadow(...)` from every static panel/tile rule in `styles.css`. After this story, the only places using a shadow effect are:
  - `.card:hover` / `.card:pressed` / drag-source state → `-elevation-2`.
  - Popovers / tooltips → `-elevation-3`.
  - Dialogs / modals → `-elevation-4` (applied to the modal's root, not nested children).
- Replace every "hover purple glow" effect (the `styles.css:307–309, 600–608, 651–657` blocks plus any sibling instances) with a `surface-3` background swap. Document this in the file header: "Hover state changes background to `-surface-3`, never effect, never border. See UI Design Book §7.1 / §7.3."
- Drop the 1 px purple border on `.toolbar-button` and `.button` outside the focused state (the focus ring stays — but the focus ring uses `-focus-ring`, blue, per Palette A, and is only visible on `:focused`, not on resting state).
- Tests:
  - `TokenValidationTest` extension: assert every `-fx-effect: dropshadow(...)` declaration in `styles.css` is either (a) inside a `.card:hover` / drag / popover / modal rule, or (b) a literal definition of an `-elevation-*` token in the Palette A block. Static panel rules (e.g. `.tile`, `.arrangement-panel`) must have **zero** `dropshadow` matches.
  - Headless render-cost test (lightweight): build a scene with N=64 cards, call `scene.getRoot().applyCss()` to resolve any `:hover`-conditional effects, and assert the number of nodes that report `getEffect() != null` is at most `N_hovered + N_modal`. (Concretely: zero in resting state.) This guards against future regressions where a contributor adds `-fx-effect` to a panel by reflex.
  - Visual snapshot test (if 208 has landed): assert that the arrangement panel resting state shows no perceptible shadow line at the panel edges (pixel-diff < threshold against a flat reference).
- Update `styles.css` header to add a one-line elevation contract: "*Elevation rule: panels are flat. Cards are elevation 1. Hovered/dragged → elevation 2. Popover → 3. Modal → 4. No nested shadows.*"

## Non-Goals

- Removing all borders — `line-soft` 1 px borders remain as panel-to-panel dividers per §3.1. This story is about *effects*, specifically drop-shadow, not borders.
- Replacing the focus ring effect with a CSS outline — JavaFX's focus mechanism is per-control; leave it as-is until story 268/270 build the Control+Skin level meter / knob / track strip, at which point the ring is drawn by the skin.
- Touching JavaFX's built-in default control shadows (combo box popups, menu shadows) — those are level 3 popovers and already correct.
- Compact Object Headers / GC tuning to mitigate Prism cost — separate concerns (story 204 covers JVM tuning).

## Technical Notes

- JavaFX's Prism pipeline composites every `-fx-effect: dropshadow(...)` via an off-screen pass; the cost is per-node-per-frame. Removing the universal shadow is a measurable performance win at high track counts.
- One shadow per node — never stack `-elevation-2` on a child of an `-elevation-1` parent. The §3.4 rule applies node-by-node.
- Elevation 3 (16 px blur) and elevation 4 (32 px blur) exceed the `javafx-application-design` skill §9 general "≤ 10 px blur" guidance. Accepted because (a) popovers and modals are short-lived overlay surfaces, (b) only one or two are on-screen at any moment, and (c) those elevations convey depth that smaller radii cannot on a flat dark surface. Elevation 1 (2 px) and 2 (6 px) — applied to resting and hovered cards (the high-instance-count case) — stay within the conservative envelope.
- Reference: UI Design Book §1.5, §3.4, §7.1, §7.3, §7.10.
