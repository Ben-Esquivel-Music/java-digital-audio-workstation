---
title: "Status Pills Meet Small-Text Contrast in Every Theme"
labels: ["bug", "ui", "theme", "accessibility"]
---

# Status Pills Meet Small-Text Contrast in Every Theme

## Motivation

Story 333 moved the status bar's lock indicator from inline colours to the house token rule (UI Design Book §3.1: tokens are referenced by role, never by hex). Its state pills are now `-text-on-accent` over a semantic fill, one state class each (`styles.css`): `.lock-status-indicator.lock-held` over `-ok`, `.lock-read-only` over `-warn`, and `.lock-stolen` over `-danger`, a lock another session took. That is the pattern other small fills in the stylesheet already use, among them the tracks' mute, solo and arm buttons (`.track-solo-button:hover` over `-ok`, `.track-mute-button:hover` and `.track-solo-button:active` over `-warn`, `.track-arm-button:active` over `-danger`) and the mixer's active A/B slot (`.mixer-slot-active` over `-warn`).

In some themes that pattern's small-text contrast is below the 4.5:1 that WCAG 2.1 asks of normal-size text (success criterion 1.4.3). In Atelier (`themes/atelier.css`) `-text-on-accent` is `#FFFFFF`: white on `-ok` (`#3FA76A`) is about 3.0:1, and on `-warn` (`#B57A1B`) about 3.6:1. Before story 333 the lock indicator's inline green (`#1f8a3a` under white text) gave about 4.4:1. The indicator follows the house rule, so no change is wanted in it: the values belong to the themes, and they reach every component that uses the pattern. Story 333 records this as a known-limit category, theme-wide and not that story's, with a theme-token story as its owner; this is that story.

Nothing checks it today. `ThemeContrastValidator` computes WCAG 2.1 contrast ratios, but it audits only the pairs that the JSON themes declare (`ThemeAuditReport.audit`, `ThemeRegistry.auditAll`); the token-CSS themes the application applies (`ThemeManager.Theme`: Onyx Refined, whose values are in `styles.css`, and the Studio Slate and Atelier overlays) declare no pairs, and no test computes their text-on-fill contrast.

## Goals

- **The status-fill pattern reaches 4.5:1 in every theme.** In each `ThemeManager.Theme`, `-text-on-accent` over `-ok`, `-warn` and `-danger` reaches 4.5:1, or each such fill gets a text token that does. The fills keep their roles (UI Design Book §3.1, “SEMANTIC (used only for what they say)”) and the theme its one accent. Values change only in the token blocks; no component gets a colour of its own.
- **The focus ring keeps its contrast.** This story owns the token values and their contrast, the focus ring's included (story 333: the studio engineer's ruling on app fix pass 2's Java items, §2). Story 333's closing-progress step line shows the house 1 px `-focus-ring` while it holds the focus (`.closing-progress-step:focused`) on the dialog's `-surface-1`; a change of either token's values keeps that ring at 3:1 or above, as WCAG 2.1 asks of a focus indicator (success criterion 1.4.11).
- **A test guards the values.** A test reads each theme's resolved token values and computes the contrasts with `ThemeContrastValidator.contrastRatio`, so a later palette change cannot drop a pair below its threshold unnoticed.
- **The book follows.** UI Design Book §3.1's palette tables show the values the themes ship.
- **Story 333's category closes.** Its Resolution marks the lock-pill contrast category as closed by this story.

## Acceptance Criteria

1. In Onyx Refined, Studio Slate and Atelier, every status fill's text — `-text-on-accent`, or the text token the Resolution introduces — reaches 4.5:1 over `-ok`, `-warn` and `-danger`.
2. In each theme `-focus-ring` reaches 3:1 over `-surface-1`.
3. The lock indicator, the tracks' mute, solo and arm buttons and the mixer's A/B slot take their colours from tokens only, as today.
4. UI Design Book §3.1 lists the shipped values.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Status-fill contrast**: for each `ThemeManager.Theme`, the resolved text and fill colours of the lock indicator's three state classes give at least 4.5:1 through `ThemeContrastValidator.contrastRatio`. The colours are resolved from the stylesheet, as `LockStatusIndicatorStyleTest.eachLockStateResolvesToItsRoleTokenFromTheStylesheet` resolves the state classes today, not copied into the test.
- **Focus ring**: for each theme, `-focus-ring` over `-surface-1` gives at least 3:1; `ClosingProgressDialogTest.theParkedFocusOnTheStepLineShowsTheFocusRingAndMovesNothing` passes unchanged.
- **Non-vacuity**: each test asserts that it read all three themes and a value for every token it checks, so a theme that fails to load cannot pass.
- **Fault check**: Atelier's values as they are today turn the status-fill test red.

## Non-Goals

- A new theme, or the JSON themes and their audit.
- Colour that is not a token value: no component gets an inline colour or a hex value.
- Large text and non-text contrast beyond the focus ring.

## Technical Notes

- Code locators: `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css` (the Palette A token block on `.root-pane`; `.lock-status-indicator` and its state classes; `.track-mute-button`, `.track-solo-button`, `.track-arm-button`; `.mixer-slot-active`; `.closing-progress-step:focused`; `.dialog-pane`), `ui/themes/atelier.css` and `ui/themes/studio-slate.css`; `ThemeManager.Theme`; `ThemeContrastValidator`; `LockStatusIndicator`; `LockStatusIndicatorStyleTest`, `ClosingProgressDialogTest`, `TokenValidationTest`.
- Today's values, computed with the WCAG 2.1 formula that `ThemeContrastValidator` implements:

  | Theme | `-text-on-accent` | over `-ok` | over `-warn` | over `-danger` | `-focus-ring` over `-surface-1` |
  |---|---|---|---|---|---|
  | Onyx Refined (`styles.css`) | `#0B0B0E` | `#5BD2A0`, 10.5:1 | `#E6B450`, 10.3:1 | `#E5484D`, 5.0:1 | `#5C8CFF` over `#15161B`, 5.7:1 |
  | Studio Slate | `#15161B` | `#74C28A`, 8.5:1 | `#E0B764`, 9.6:1 | `#E26060`, 5.2:1 | `#FF7A45` over `#181A20`, 6.7:1 |
  | Atelier | `#FFFFFF` | `#3FA76A`, 3.0:1 | `#B57A1B`, 3.6:1 | `#B5273B`, 6.3:1 | `#244B8C` over `#FFFFFF`, 8.5:1 |

  So today only Atelier's `-ok` and `-warn` pairs fall short, and every focus-ring pair passes.
- The studio engineer's rulings in story 333: the category and its owner (the ruling on app fix pass 1, N5, which also corrects an earlier owner: story 339 does not cover theme contrast), and the focus ring's token values with their contrast under this story (the ruling on app fix pass 2's Java items, §2, which added the step line's ring itself in story 333).
- Design references: UI Design Book §3.1 (colour tokens, the three palettes), §2.2 (information hierarchy via tone, not hue).
- Cross-refs: story 333 (the lock indicator's token pattern; the closing-progress focus ring), story 339 (dialog conformance, which does not own this).

## Status

- Deferred
