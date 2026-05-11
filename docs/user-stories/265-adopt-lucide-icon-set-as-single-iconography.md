---
title: "Adopt Lucide as the Single Iconography Family"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "design-system", "iconography"]
---

# Adopt Lucide as the Single Iconography Family

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #264.

UI Design Book §1.4 ("Icon-in-button overload") captures the user's own complaint about the current state: every transport button carries an icon *and* a text label, the toolbar carries icons in pill buttons, and the visualisation tiles carry icon labels above small graphs. Icons compete with each other rather than telling a story. The fix per §2.4 is simple: an icon is a **replacement** for a label, not a decoration on it. Icons appear in three places only: sidebar/browser tabs (icon + label), toolbar mode toggles (icon-only with tooltip), and inline status glyphs (record dot, clip warning, freeze flake).

The codebase today uses a grab-bag of icon sources — `IconNode.java`, mixed FontAwesome/Material glyphs, custom SVG paths — that read at different stroke weights and at different optical sizes. UI Design Book §3.6 prescribes the cure: one icon family, monochrome line icons at 16 px nominal (also 20 / 24 for sidebar density), 1.5 px optical stroke, inheriting `currentColor` so the same icon flips with the theme. The book lists three permissive-licence candidates (Lucide / Tabler / Phosphor Duotone) and explicitly recommends Lucide for its 1.5 px stroke and modern silhouettes.

This story:
1. Bundles Lucide (ISC license, https://lucide.dev) into the project resources.
2. Adds a single Java entry point (`DawgIcon`) for resolving an icon by name into a JavaFX `Region` that takes its colour from CSS `currentColor`.
3. Replaces every existing icon usage in the UI module.
4. Removes the "icon next to label" pattern from transport, toolbar, and labelled buttons.

## Goals

- Bundle the Lucide SVG icon set in the resources module:
  - Vendor the subset actually used (initially: `play`, `pause`, `square` (stop), `circle` (record), `skip-back`, `skip-forward`, `repeat` (loop), `volume-2`, `mic`, `headphones`, `disc`, `music`, `folder`, `info`, `bell`, `settings`, `chevron-down`, `chevron-right`, `chevron-left`, `x`, `plus`, `pencil`, `trash`, `grip-vertical`, `search`, `play-circle`, `pause-circle`, `circle-dot`, `alert-triangle`, `check-circle`, `snowflake` (freeze), `lock`, `unlock`, `eye`, `eye-off`, `link`, `unlink`).
  - Place under `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/icons/lucide/`. Add license: vendor `LICENSE` and `ATTRIBUTION` files alongside the icons.
  - Add a build-time inventory test that fails if the directory contains an icon not in a whitelist `icons.allowed.txt` — prevents drift toward "every developer adds one more icon".
- Introduce `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/icons/DawgIcon.java`: a factory that returns a JavaFX `Region` styled to render the SVG path from a Lucide icon name, sized via a size enum (`SIZE_16`, `SIZE_20`, `SIZE_24`), with the path fill bound to a `StyleableObjectProperty<Paint>` defaulting to `-text-hi` so CSS can re-tint via `-fx-icon-color: -accent;` per UI Design Book §2.4.
- Replace the existing `IconNode.java` and ad-hoc icon helpers across the UI:
  - Sidebar items, browser tabs: `DawgIcon` + label.
  - Toolbar mode toggles, snap toggle, ripple toggle, metronome toggle: `DawgIcon` only with `Tooltip`.
  - Inline status glyphs (record dot, freeze flake): `DawgIcon` only.
  - **Labelled** buttons (transport Play/Stop/Record, dialog Save/Cancel): drop the icon. Buttons keep only their text label per §2.4. (This is the user's specific veto — story 077 partly attempted this; this is the proper second pass.)
- Update `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml` to remove icon-and-text combinations. The transport buttons after this story are pure text. (If the user later wants icon-only transport per §5.1's optional treatment, that is a follow-on driven by Preferences — out of scope here.)
- Add an `IconAuditTest` that scans the UI module sources for any string literal naming an old icon family ("FontAwesome", "fa-", "Material:", "MDI", etc.) and asserts zero matches. Forbid `import javafx.scene.text.Font` / `Glyph` patterns from the obsolete icon helper.
- Tests:
  - `DawgIconTest`: instantiate `DawgIcon.of("play", DawgIcon.SIZE_16)`; assert the rendered region reports `prefWidth == prefHeight == 16` (or document the exact bound used).
  - CSS-tint test: apply `-fx-icon-color: red;` via a parent stylesheet and assert the icon's fill resolves to `Color.RED` after `applyCss()`.
  - Snapshot test (if 208 has landed): one snapshot per icon name × size matrix, captured into `daw-app/src/test/resources/icon-snapshots/` for visual regression.

## Non-Goals

- Replacing Lucide with Tabler / Phosphor — the design book recommends Lucide; revisit only if a needed icon is genuinely missing.
- Animating icons (a record-pulse, a save-confetti) — Phase 4 / never.
- Adding a runtime icon-set switcher in Preferences (Lucide vs Tabler) — over-flexible; the design book mandates one family.
- Migrating plugin GUI views that contain their own iconography (e.g., `KeyboardProcessorView`, plugin-internal icons). They can be migrated as part of their respective Phase 2 component refactors.
- Building an icon-only transport mode toggle (out of scope, addressed by user preference if at all).

## Technical Notes

- Lucide ships SVGs at viewBox 24×24 with 1.5 px stroke; rendering at 16 px nominal means scaling the SVG path. JavaFX supports SVG paths via `SVGPath`; the renderer in `DawgIcon` wraps an `SVGPath` inside a `Region` so it participates in layout cleanly. Inheriting `currentColor` is achieved by leaving `SVGPath#fill` bound to the styleable property; the path itself has no per-element fill.
- The "no icon next to label" rule is the most user-visible change from the §1.4 critique — expect feedback. Document the change in the changelog with a short rationale paragraph citing the design book.
- Builds on the unified button system (story 264) so icon-only buttons (toolbar) are `.dawg-button.icon-only.size-default` — the `icon-only` modifier collapses the padding to a square and centres the icon. Add this modifier to `styles.css` in this story.
- Reference: UI Design Book §1.4, §2.4, §3.6, §7.9 (icons-on-labelled-buttons veto — implicit AC).
