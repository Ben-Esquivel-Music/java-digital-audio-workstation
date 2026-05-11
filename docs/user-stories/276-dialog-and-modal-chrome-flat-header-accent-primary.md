---
title: "Dialog and Modal Chrome: Flat Header, Accent Primary Button, Tokenized Section Headers"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "dialogs"]
---

# Dialog and Modal Chrome: Flat Header, Accent Primary Button, Tokenized Section Headers

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #264, #265, #266, #272 (the inspector takes over many former dialogs; only true "transient task" dialogs remain).

UI Design Book §5.9 ("Dialog / modal") fixes three problems with current dialog chrome:

1. **Gradient header.** `styles.css:951–953` paints the dialog title bar with a gradient. §3.4's elevation system says shadow is information; gradients on titles are pure decoration. Flatten to `-surface-1`.
2. **Default button is green.** `styles.css:1001–1011` paints the default ("primary") dialog button green. §3.1 says the *one* accent is the primary action / selection colour. Default button changes to `-accent` (Onyx Refined indigo). Green is reserved for `-ok` semantic colour (autosave success, signal-in-safe-range). Conflating "default button" with `-ok` was a category error.
3. **Section headers are purple.** §1.6 / §7.6. Section headers in `.dialog-section-header` use a saturated colour. §5.9 says: `-text-mute`, label-small (10 px, uppercase, +12 % tracking), per §3.2.

UI Design Book §5.9 also tightens the layout contract:
- Centred modal, 480 / 640 / 800 px wide, 24 px padding.
- Header: title (H1 weight 600, no decoration), close glyph (`x` icon, secondary).
- Body: one column unless there is a clear reason for two; sections separated by tokenized section headers.
- Footer: Cancel on the left (text button, no border), primary action on the right (filled `-accent`).

This story upgrades every existing dialog in the codebase — `AudioSettingsDialog`, `BackupSettingsDialog`, `AtmosSessionConfigDialog`, `ChannelCpuBudgetDialog`, `ArchiveSummaryDialog`, the Plugin Manager dialog (when present), Preferences, and any wrappers around `Alert` / `Dialog`. It also adds a shared `DawgDialog` skeleton so future dialogs can't drift again.

## Goals

- Create `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/dialogs/DawgDialog.java` — a base class (or composable builder) that produces a `Dialog<R>` with the §5.9 chrome:
  - Flat `-surface-1` header containing title (H1) and `x` icon close.
  - 24 px padding around body.
  - Footer with secondary-on-the-left, primary-on-the-right convention.
  - Methods: `sized(SMALL | MEDIUM | LARGE)` (480 / 640 / 800 px wide), `addSection(String header, Node body)`, `primary(String label, Runnable action)`, `secondary(String label, Runnable action)`.
- Add `.dawg-dialog`, `.dawg-dialog-header`, `.dawg-dialog-body`, `.dawg-dialog-footer`, `.dawg-dialog-section-header` rules to `daw-app/src/main/resources/com/benesquivelmusic/daw/app/ui/styles.css`. Section header rule consumes `-text-mute` and matches §3.2's label-small style.
- **Remove** the gradient from any existing `.dialog-header` rule. Header background = `-surface-1`. (Drop the existing rule at `styles.css:951–953`.)
- **Remove** the green from any existing `.dialog-default-button` / `.button.default` rule. Primary button = `-accent` fill. (Drop the existing rule at `styles.css:1001–1011`.)
- **Remove** the purple from `.dialog-section-header` / `.section-header` / any sibling. Section header colour = `-text-mute`.
- Migrate each existing dialog to extend / consume `DawgDialog`:
  - `AudioSettingsDialog.java`
  - `BackupSettingsDialog.java`
  - `AtmosSessionConfigDialog.java`
  - `ChannelCpuBudgetDialog.java`
  - `ArchiveSummaryDialog.java`
  - Any Plugin Manager / Preferences dialog wrappers
  - Any `Alert.AlertType.CONFIRMATION` / `INFORMATION` / `ERROR` wrapper helpers — wrap to apply the same chrome
  
  The dialog *contents* (forms, fields, controls) are preserved; only the chrome and the primary-button styling change.
- All dialog buttons use `dawg-button.size-default` from story 264. Primary buttons carry the additional `.primary` modifier that resolves to `-accent` fill via the unified button system.
- Drop the close glyph if `cancel-button-on-left` is present (per §5.9: "Close glyph in the header is *secondary* — Cancel and Esc are the primary dismiss paths"). The close glyph stays for *informational* dialogs without a Cancel button; otherwise prefer Cancel.
- Tests:
  - `DialogChromeTest`: build a `DawgDialog` with one section and primary+secondary footer; assert no gradient `-fx-background-image` on the header; assert primary button's background resolves to `-accent`; assert section-header text-fill resolves to `-text-mute`.
  - `LegacyDialogStylesGoneTest`: parse `styles.css`, assert there is no `linear-gradient` declaration scoped to `.dialog-header` (or any `.dialog-*` selector). Assert there is no rule painting `.dialog-default-button` / `.button.default` with a green token (`-ok`) or literal green hex.
  - `EveryDialogConformsTest`: at startup, scan `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/` for classes whose name ends in `Dialog`; for each, assert it either (a) extends `DawgDialog` or (b) is excluded via a sentinel `@LegacyDialog` annotation with a TODO comment. The annotation prevents drift and forces the migration to be visible.

## Non-Goals

- Replacing JavaFX's built-in `Stage`-based modal mechanism — the dialogs continue to use `Dialog<R>` / `Stage`. Only the chrome changes.
- Moving dialog state into the inspector — the inspector is per-selection; dialogs are *transient tasks* (settings, plugin manager, exports). Story 272 is clear about the split.
- Custom titlebar / window decorations on the OS shell — that is an OS-level concern outside the design book's scope.
- Adding tabs to dialogs (`AudioSettingsDialog` currently has multiple panes) — keep existing tab structure; only chrome changes.

## Technical Notes

- JavaFX's `Dialog<R>` provides a `DialogPane` whose `headerText` / `graphic` / `content` / `expandableContent` slots can be configured to match the §5.9 layout. `DawgDialog` wraps a `DialogPane`; consumers don't need to know which JavaFX class is the root.
- For `Alert`-derived dialogs (`Alert.AlertType.CONFIRMATION`, etc.), the wrapper applies the chrome by replacing the `Alert`'s `DialogPane` styling. Document this as a known JavaFX-specific quirk.
- All `DawgDialog` chrome strings ("Cancel", "Close", "OK", default primary labels for confirmation/info/error dialogs) come from the existing `Messages.properties` resource bundle. Caller-supplied content (titles, prompts, error details) is whatever the caller passes in — already localized at the call site. Skill §14.
- Reference: UI Design Book §3.1, §3.2, §3.4, §5.9, §7.6 (saturated-section-header veto — implicit AC).
