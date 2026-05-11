---
title: "Browser Panel: Accent-Bar Tabs, Persistent Search, and Audition Button Per Row"
labels: ["enhancement", "ui", "ui-overhaul", "phase-2", "browser"]
---

# Browser Panel: Accent-Bar Tabs, Persistent Search, and Audition Button Per Row

## Motivation

Phase 2 of the UI Design Book §6 migration roadmap. Builds on: #260, #261, #263, #264, #265, #266.

UI Design Book §5.5 ("Browser") points to two specific complaints with the current browser. First, the tab indicator is a 2 px **purple bottom border** (`styles.css:746–750`), which is the same offence as the sidebar's active-item indicator that §5.2 already corrects with a left accent bar. The browser should be consistent with the sidebar: active tab = `-accent` *bar* (left-edge or under-text, but the colour is the design system's `-accent`, not a hue picked for the browser alone).

Second, the browser today does not have a persistent search and rows are not auditionable from the panel — the user has to drag the sample onto a track and then play to hear it. UI Design Book §5.5 specifies:

- Tabs at top (Files / Samples / Presets / Plugins / Recent) — active tab uses `-accent` indicator.
- Persistent search field below the tabs, scoped to the active tab.
- A tree on the left and a list on the right.
- A **preview / audition button on every row** so the user can audition without dragging.

This story upgrades `BrowserPanel.java` and `BrowserPanelController.java` to the §5.5 layout. Sample preview wiring (story 027) is already filed — this story focuses on the **UI** and surfaces the existing preview API on each row.

## Goals

- Update `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/BrowserPanel.java` (or its FXML, if any) so the tab strip uses the unified design system:
  - Tabs are `dawg-button.size-default` toggles (story 264) without borders, in a horizontal group.
  - The active tab carries an `-accent` 2 px **under-text bar** drawn as a child `Rectangle` (per §7.3 — no border swap, no layout shift).
  - **Remove** the purple `bottom-border` style at `styles.css:746–750`.
- Add a persistent search field below the tab strip:
  - Single-row `TextField` styled with `.search-field` (uses the `search` icon from story 265 as a leading affordance).
  - Scope: search filters the active tab's list only. Tab change does not clear the search.
  - Keyboard shortcut: `Ctrl+F` while the browser is focused puts focus into this field.
- Add an "audition" button to every row in the list:
  - At the right edge of each row, a 16 × 16 `play` icon (from story 265).
  - Click → triggers the existing preview engine (story 027's API, or — if 027 has not yet landed — a stub that calls into the existing waveform preview if any). If no preview engine is available, the icon is `:disabled` per the story-264 disabled rule.
  - During playback, the icon swaps to `pause-circle`; click stops the preview.
  - Only one preview plays at a time; starting a new preview stops the previous one (single-channel auditioner).
- Apply the unified row-hover behaviour:
  - Hover: `-surface-3` background. No purple drop-shadow.
  - Selection: `-accent-soft` background. Left edge no longer carries a border.
- Apply mono numerics (story 266) to the right-side cells that show sample metadata (`1.2 MB`, `44.1k`, `24-b`).
- Tests:
  - `BrowserTabIndicatorTest`: render the browser, switch tabs, assert the active tab has a child Rectangle of fill `-accent` width = tab text width, height 2; assert the previously-active tab does *not*.
  - `BrowserSearchScopeTest`: type into the search field with the "Samples" tab active; switch to "Presets"; assert the search field text is preserved but the displayed list is filtered against the presets index, not samples.
  - `BrowserRowAuditionTest`: click the audition glyph on a row, assert the preview engine receives the row's path and `isPlaying() == true`; click again, assert preview stops.
  - `BrowserHoverStyleTest`: simulate `MOUSE_ENTERED` on a row, assert background resolves to `-surface-3` and `Effect == null` (per §7.1 veto).

## Non-Goals

- Implementing the audio preview engine — story 027's territory. This story wires the UI to the existing API or stubs.
- Adding waveform thumbnails on each row — also story 027. This story leaves room for them in the row layout (the cell factory can render a thumbnail behind the metadata when 027 lands).
- Drag-and-drop polish — story 197 covers drag-target feedback project-wide.
- Adding more tabs (Cloud, Project Folder, etc.) — out of scope.
- Replacing the underlying `BrowserPanelController` model logic — the search filter and tab change wire into existing model methods.

## Technical Notes

- The browser is currently a `TabPane` with `Tab` children. Replacing the indicator with a custom Rectangle requires either (a) extending `TabPaneSkin` (heavy) or (b) replacing the `TabPane` with a custom `HBox` + content `StackPane` (lighter; less JavaFX magic; matches the design book's preference for hand-built skins per §2.5). Option (b) is preferred.
- The "single-channel auditioner" is a simple convention — the preview engine kills any previous preview when a new one starts. This avoids the user accidentally layering ten samples on top of each other.
- Tab labels ("Files", "Samples", "Presets", "Plugins", "Recent"), the search-field placeholder, and the audition tooltip come from the existing `Messages.properties` resource bundle; don't hard-code them. Skill §14.
- Reference: UI Design Book §5.5, §7.1 (glow veto), §7.3 (border-swap veto).
