---
title: "Standardize Spacing, Row Heights, and Corner Radii to the 4 px Grid"
labels: ["enhancement", "ui", "ui-overhaul", "phase-1", "design-system", "tokens", "spacing"]
---

# Standardize Spacing, Row Heights, and Corner Radii to the 4 px Grid

## Motivation

Phase 1 of the UI Design Book §6 migration roadmap. Builds on: #260.

UI Design Book §1.7 documents the rhythm problem directly: "A track row at 8 px padding sits next to a transport row at 3 px padding sits next to a status row at 4 px padding. The eye is reading three different rhythms in the same window." Today's `styles.css` confirms this — `.transport-button` is padded `3 8`, `.toolbar-button` is `6 10`, `.button` is `6 14`, and the FXML sets transport bar insets to `top="4" right="12" bottom="4" left="12"` (`daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/main-view.fxml:17`). None of these align.

UI Design Book §2.3 declares the cure: every dimension is a multiple of 4. Padding, gap, height, corner radius. Row heights are 24 / 28 / 32 / 36. Corner radii are 0 / 4 / 6 / 8 ("Stop using `radius=10`; it doesn't tile cleanly with anything", §3.3). Once the grid is enforced, alignment problems stop being possible.

This story introduces the spacing/sizing tokens *and* enforces them across every existing CSS rule and FXML insets in one pass. It does not change any colour or icon — it changes only numbers.

## Goals

- Add the spacing token set from UI Design Book §3.3 to `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css` as JavaFX looked-up *numeric* lookups via the Palette A block established by story 260. Tokens (`-spacing-xxs: 2`, `-spacing-xs: 4`, `-spacing-sm: 8`, `-spacing-md: 12`, `-spacing-lg: 16`, `-spacing-xl: 24`, `-spacing-xxl: 32`) and (`-row-compact: 24`, `-row-default: 28`, `-row-touch: 32`, `-row-transport: 36`) and (`-radius-0: 0`, `-radius-1: 4`, `-radius-2: 6`, `-radius-3: 8`). JavaFX CSS does not type-check numeric lookups the way it does colour lookups; emulate via documented comment block plus a parallel constants class `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/design/SpacingTokens.java` so FXML-driven `Insets` can consume the same constants.
- Rewrite every literal pixel value in `styles.css` to a multiple of 4. Replace `radius=10` everywhere with `-radius-2` (6 px) for cards / popovers or `-radius-1` (4 px) for buttons / inputs / badges per §3.3. Replace `radius=6` and `radius=4` consistently where they appear. Forbid `radius=5, 7, 9, 10, 11`.
- Pad every interactive control to a row-height token: `.transport-button` → row-transport (36 px), `.toolbar-button` → row-default (28 px), `.button` → row-default (28 px). Use the *padding* to get to the height — keep `prefHeight` unset so the row collapses naturally.
- Update `main-view.fxml` Insets and HBox spacing to use multiples of 4. Specifically:
  - Transport bar `<Insets top="4" right="12" bottom="4" left="12"/>` becomes `top="8" right="16" bottom="8" left="16"` (matches the §5.1 36 px transport row spec when combined with the new button padding).
  - All `HBox spacing="6"` become `spacing="8"`.
- Tests:
  - Extend `TokenValidationTest` (introduced in story 260) with a second assertion that scans `styles.css` for `-fx-(padding|background-radius|border-radius|spacing)` declarations and asserts every numeric value parses to a multiple of 4. Allow exception list (the `2` from `-spacing-xxs`) listed inline.
  - New `MainViewFxmlSpacingTest` that parses `main-view.fxml` with the standard `FXMLLoader` (the file already loads in headless tests for unrelated reasons), walks the scene graph, and asserts every `Insets` value and every container `spacing` is a multiple of 4.
  - Headless `SceneSnapshotTest` (or pixel-aware test if story 208 has landed; otherwise rely on existing snapshot infra) that asserts the transport bar, status bar, and a sample track row all report `Region#getLayoutBounds#getHeight` that snaps to a grid row (36 / 24 / 28).
- Document the grid contract at the top of `styles.css` so future contributors see the rule before they add any value: "*All numeric values in this file are multiples of 4. Use a -spacing-/-row-/-radius- token, never a literal.*"

## Non-Goals

- Replacing literal hex (already covered by story 260).
- Removing per-button colour borders from the transport (story 262).
- Component refactors — `Control`/`Skin` work is Phase 2, stories 267–271.
- Density modes (Compact / Comfortable / Touch as a *user setting*) — that is story 278; this story merely defines the row-height tokens those modes will toggle between.
- JavaFX CSS does not support arithmetic on lookups (`calc(...)`); accept that some padding values are written as composite literals (`-fx-padding: -spacing-sm -spacing-md`).

## Technical Notes

- The `SpacingTokens` companion Java class is what allows FXML-side `Insets` to share the same numbers as CSS-side padding. Both must reference the same constants; the validation test asserts they agree.
- Some CSS rules will gain padding *and lose* `prefHeight`/`minHeight` (e.g., the time-display label currently hard-codes height) — the grid is enforced via padding, not fixed heights, so the row collapses to the largest child.
- Reference: UI Design Book §1.7 (problem), §2.3, §3.3 (solution), §7.4 (mixed-radius veto — implicit AC).
