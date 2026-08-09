---
title: "Shortcut Safety and Live Rebinding"
labels: ["bug", "shortcuts", "menus", "usability"]
---

# Shortcut Safety and Live Rebinding

## Motivation

The keyboard is a session hazard and rebinding is cosmetic:

- **Bare single-key accelerators fire while typing.** `register(scene)` puts every binding straight into `scene.getAccelerators()` with no focus-owner guard (`KeyboardShortcutController.java:297`). Defaults include bare SPACE, ESC, R, L, M, I, O (`DawAction.java:18‑37`) and the V/P/E/C/G tool keys (`:173‑182`). The main scene hosts live text inputs — the track-rename TextField (`TrackStripController.java:1661`) and the browser search field (`BrowserPanel.java:227`). Typing a track name containing "r" starts recording. The codebase already knows the fix: the plugin frame's key handler returns immediately when the event target is a `TextInputControl` (`EditorFrameSkin.java:854`) — the global accelerators simply lack the same guard.
- **Rebinding does nothing until restart.** `MainController` constructs the `KeyBindingManager` the scene and menus consume (`MainController.java:863`); `SettingsModel.getKeyBindingManager()` lazily constructs a *second* manager over the same prefs node (`SettingsModel.java:660`), and Settings' `applyKeyBindings` mutates only that copy (`SettingsDialog.java:2163‑2164`). Nothing re-registers scene or menu accelerators, so a rebind is invisible until relaunch — and the old combination keeps firing all session.
- **The Help dialog's shortcut list is hand-typed fiction.** Literal strings ("Record:"/"R", "Save:"/"Ctrl+S" — `HelpDialog.java:103‑104/138`) covering roughly a third of the 92 actions, never consulting `KeyBindingManager.getDisplayText` — incomplete today and wrong after any rebind.
- **The palette is honest but its disabled rendering is dead.** The supplier iterates `DawAction.values()` and skips handler-less actions (`MainController.java:2073‑2092`) — correctly derived from the real handler map — but `CommandPaletteEntry.of` hard-codes `enabled=true` (`CommandPaletteEntry.java:50‑58`), so the palette's disabled-entry rendering is unreachable.
- **Advertised, rebindable, dead.** SET_PUNCH_IN / SET_PUNCH_OUT / TOGGLE_PUNCH and TOGGLE_SESSION_MANAGER are offered in Settings as rebindable shortcuts, yet `buildActionHandlers` maps none of them — binding them produces silence.

For a studio engineer: renaming a track can start recording mid-session, a rebind lies until relaunch, and Help documents bindings that do not exist. A DAW whose rename box can start the transport is not a professional tool.

## Goals

- **Scene-level bare-key focus guard** (book §5.4): modifier-free accelerators are suppressed when the event target / focus owner is a text input or an editing cell; modified combinations (Ctrl/Alt/Shift+key) stay global. The guard lives in exactly one place — the scene-level input seam, following the `EditorFrameSkin.java:854` pattern — never per-control.
- **One `KeyBindingManager`.** Settings mutates the same instance the scene and menus consume; the `SettingsModel` duplicate is retired. The manager is injected, never re-constructed over the same backing store (book §9.6; the repo's capture-singleton-once lesson).
- **Live re-registration on Apply**: the scene accelerator map is rebuilt, MenuItem accelerators are re-projected, and shortcut tooltips refresh; the old combination is inert immediately, the new one fires — no restart. The rewritten apply is atomic: it never leaves the current partial-clear state behind (see Non-Goals for the conflict UX).
- **Help is generated**, grouped by category from the manager's live display text (`KeyBindingManager.getDisplayText`) — the hand-typed table is deleted.
- **The palette is fed from the command-registry seed** — initially the existing handler map, per the book's Stage 1 refinement — with disabled-entry rendering reachable (enablement + human-readable reason). Full menu-only coverage completes in story 345 when the registry generalises.
- **Harness A — command liveness** (book §6.1) lands: every `DawAction` constant is mapped to a handler or exempted in-source with a story reference; every action with a default key combination is registered on the scene; the Settings key-binding catalogue offers only live-or-exempted actions. Ships with exactly two exemptions: the three punch actions (story 328, `RECORDING_RELIABILITY_DESIGN_BOOK.md`, which owns punch arming) and TOGGLE_SESSION_MANAGER (paid by story 345).
- Completes the live-apply half of **existing story 010 — keyboard shortcuts** and the disabled-rendering goal of **existing story 192 — command palette**.

## Goals — Tests

- **Typing-guard test**: type "record" into the track-rename field — the transport is untouched and the text lands intact (fails today); same for the browser search field. Ctrl+S still fires while renaming (modified keys stay global).
- **Rebind round-trip, no restart**: change a binding in Settings and Apply — the new combination fires, the old one is inert, and the menu accelerator text is updated, all against the live scene (fails today).
- **Manager-identity test**: object identity between the manager Settings mutates and the one the scene and menus consume; a source-scan or targeted test asserts the `SettingsModel.java:660` duplicate construction is gone.
- **Help-truth test**: the Help table derives from `KeyBindingManager` display text; a rebind is visible in Help without restart; every registered (non-exempted) action appears.
- **Atomic-apply test**: an apply that aborts mid-way leaves the previous bindings fully intact — never the partial-clear state today's `applyKeyBindings` can produce.
- **Palette disabled-rendering test**: a disabled registry entry renders greyed with its reason (unreachable today via `CommandPaletteEntry.of`'s hard-coded `enabled=true`).
- **Harness A sentinel**: fails the build when a `DawAction` ships unhandled and unexempted; passes today's tree only with the two initial exemptions; carries the family's non-empty scan guard.

## Non-Goals

- **Keybinding-conflict feedback.** Today a conflicting assignment throws uncaught mid-apply after `applyKeyBindings` has already cleared all bindings (`SettingsDialog.java:2163‑2192`, clear at `:2181‑2184`, caller `:1520`) — worse than a silent revert. Story 339 (`FAILURE_SURFACING_DESIGN_BOOK.md`) owns the conflict UX; this story only makes apply atomic so the failure mode cannot corrupt the binding set.
- The **full command registry** and menu-only palette coverage — story 345 (Stage 2) generalises the seed this story lands.
- **Handlers for the punch actions** — story 328 (Book 2) owns punch arming; harness A carries the exemption until it lands.
- **TOGGLE_SESSION_MANAGER's handler** — story 345.
- The **tool selector / Tools menu** — story 345; this story only makes the existing bare tool keys safe to keep.

## Technical Notes

- **Implements Stage 1 of `docs/design/INTERACTION_COMPLETENESS_DESIGN_BOOK.md` — "Shortcut Safety and Live Rebinding"** (§5.4 shortcut-safety contract, §6.1 harness A, §3.3 registry seed). The book's stage order is dependency order — this story runs *first*: it removes the active session hazard, is independent of the arrangement chain, and lands the conformance-harness pattern every later stage (341–343, 345–347) inherits.
- Files: `KeyboardShortcutController.java:297` (focus-guard seam + live re-registration), `MainController.java:863` (manager injection point), `SettingsModel.java:660` (duplicate retired), `SettingsDialog.java:2163‑2192` (apply targets the shared instance, atomically), `HelpDialog.java:103‑138` (generated table), `MainController.java:2073‑2092` + `CommandPaletteEntry.java:50‑58` (palette seed + enablement), `DawAction.java:18‑37/173‑182` (bare-key inventory).
- Guard precedent: `EditorFrameSkin.java:854`. One scene-level seam (book §2.5) — do not scatter per-control guards.
- Harness A extends the repo's `SourceScanSupport` sentinel family (`RunLaterConsolidationTest`, `EveryDialogConformsTest`, …): in-source exemption markers adjacent to the exempted declaration, story-referenced, deleted by the paying story, with a non-empty scan guard (book §6.5 — an exemption is a debt record, not a waiver).
- Cross-refs: existing **010** (live-apply half completed here), existing **192** (palette; coverage completes in 345), story **345** (registry generalisation; pays the TOGGLE_SESSION_MANAGER exemption), story **328** (Book 2 — pays the punch exemption), story **339** (Book 4 — conflict surfacing).
