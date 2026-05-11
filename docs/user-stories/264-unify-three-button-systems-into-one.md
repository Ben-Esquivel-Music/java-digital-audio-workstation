---
title: "Unify the Three Button Systems Into One With Size Variants"
labels: ["enhancement", "ui", "ui-overhaul", "phase-1", "design-system", "buttons"]
---

# Unify the Three Button Systems Into One With Size Variants

## Motivation

Phase 1 of the UI Design Book §6 migration roadmap. Builds on: #260 (tokens), #261 (grid), #262 (de-rainbow transport — already unified the transport-button row).

UI Design Book §1.3 ("Three button systems, none aligned") documents the problem with a table:

| Class | Padding | Radius | Border |
|---|---|---|---|
| `.transport-button` | `3 8` | `4` | 1 px coloured |
| `.toolbar-button` | `6 10` | `6` | 1 px purple |
| `.button` (generic) | `6 14` | `4` | 1 px purple |

These are three classes for the same idea (a clickable button). They have different heights, different corner radii, different focus rings. The FXML places them adjacent on the same row (`main-view.fxml:21–89`), so the misalignment is visible at a glance. Story 261 already standardised the grid; story 262 collapsed the transport row's per-button hues. This story collapses the three *classes* into one, with explicit size variants so a 28 px sidebar button can sit next to a 36 px transport button without using a different style class.

UI Design Book §3.3 and §5 specify the size table: compact (24 px), default (28 px), touch (32 px), transport (36 px). The corner radius is uniformly `-radius-1` (4 px) for every button per §3.3 ("buttons, inputs, badges"). The border, hover state, focus state, and pressed state are uniform.

## Goals

- Introduce a single `.dawg-button` style class in `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css` with size modifiers (`.dawg-button.size-compact`, `.dawg-button.size-default`, `.dawg-button.size-touch`, `.dawg-button.size-transport`). The base class uses:
  - `-fx-background-color: -surface-2`
  - `-fx-text-fill: -text-hi`
  - `-fx-background-radius: -radius-1`
  - `-fx-border-color: -line-soft`
  - `-fx-border-width: 1`
  - `-fx-padding`: size-variant-specific, all multiples of 4 (e.g. `-spacing-xs -spacing-sm` for compact; `-spacing-xs -spacing-md` for default; `-spacing-sm -spacing-md` for touch; `-spacing-sm -spacing-lg` for transport).
- Define **one** hover behaviour: `:hover` → `-fx-background-color: -surface-3`. No effect change, no border change. (Per UI Design Book §7.1 / §7.3.)
- Define **one** pressed behaviour: `:pressed` / `:armed` (custom pseudo-class for toggles, see story 262) → `-fx-background-color: -accent`, `-fx-text-fill: -text-on-accent`.
- Define **one** disabled behaviour: `:disabled` → `-fx-opacity: 0.45`, no other change.
- Define **one** focus behaviour: `:focused` → `-fx-border-color: -focus-ring`, width 1.
- Introduce a **danger** variant `.dawg-button.danger` used by the Record button (Record is the only place; this is the established §2.1 exception). Danger variant: `-fx-background-color: -danger` when `:armed`, `-fx-background-color: -surface-2` when not armed (so the button is neutral until armed).
- Replace every `.transport-button`, `.toolbar-button`, and `.button` style class reference across:
  - `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml`
  - all Java FXML helper code in `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/`
  - any nested FXML if present (none today, but check `daw-sdk` resources too)
  with the equivalent `dawg-button` + size-variant combination. The mapping: `transport-button` → `dawg-button size-transport`; `toolbar-button` → `dawg-button size-default`; `.button` (generic) → `dawg-button size-default`.
- Keep the *legacy* class selectors in `styles.css` as **alias rules** for one release cycle so any third-party plugin views (e.g. plugin GUIs that style their own dialog buttons against `.button`) still inherit the new look: the legacy class is empty and the actual rules are on `.dawg-button`. Document in the file header: "*Legacy aliases retained for one cycle; remove in story 264 follow-up.*"
- Tests:
  - Extend `TokenValidationTest` (story 260): assert there is **exactly one** declaration of base button background-color in `styles.css` (i.e. only the `.dawg-button` selector — no `.button`, `.toolbar-button`, or `.transport-button` rule re-declares `-fx-background-color`).
  - `MainViewFxmlSpacingTest` (story 261): assert every `<Button>` in `main-view.fxml` carries `dawg-button` as a style class with exactly one size variant.
  - Headless `ButtonAlignmentTest`: instantiate three buttons (sidebar/`size-default`, transport/`size-transport`, dialog footer/`size-default`) in a row, apply the stylesheet, and assert the `default`-sized buttons render to the *same* pixel height — i.e. `Math.abs(a.getLayoutBounds().getHeight() - c.getLayoutBounds().getHeight()) < 1.0` even though they live in different parents.

## Non-Goals

- Removing `Button` controls in favour of `Hyperlink` for borderless actions — the design book is silent on hyperlink usage; treat as a follow-on.
- Auto-sizing buttons to icon-only square form factor — that is handled by the `.icon-only` modifier added in story 265.
- Building a `Control + Skin` button (the existing `Button` is sufficient; the design system is purely CSS-driven for buttons).
- Renaming the existing `ButtonPressAnimator.java` interaction; the animation (if any) keeps targeting `Button` regardless of style class.

## Technical Notes

- `dawg-button` is namespaced with the project prefix (`dawg`) so it cannot collide with JavaFX's built-in `.button` style class, which JavaFX applies automatically to every `Button` instance. Both rules can co-exist; `.dawg-button` and JavaFX's `.button` have *equal* CSS specificity (both are single-class selectors), so our rules take effect not because of specificity but because (a) author stylesheets have higher precedence than JavaFX's user-agent `modena.css`, and (b) `styles.css` loads after `modena.css`. Where the two selectors set the same property, the author-stylesheet value wins by the cascade.
- Reference: UI Design Book §1.3, §3.3, §5.1 (transport button spec), §7.4 (mixed-radius veto — implicit AC).
