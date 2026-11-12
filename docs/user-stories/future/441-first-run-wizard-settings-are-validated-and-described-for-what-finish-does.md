---
title: "First-Run Wizard Settings Are Validated on the Wizard's Rows and Described for What Finish Does"
labels: ["bug", "first-run", "settings"]
---

# First-Run Wizard Settings Are Validated on the Wizard's Rows and Described for What Finish Does

## Motivation

The first-run wizard (`FirstRunWizard`, story 309) shows the settings catalogue's own rows, the default tempo (`project.defaultTempo`) among them. Its Finish applies the collected values through a Settings dialog that is never shown: `MainController.applyWizardEdits` stages them as pending edits and runs `SettingsDialog.applyBulkSettingsAsync`. Story 335 made that Finish a bulk Apply (its studio ruling 2, Q1), so it never writes the tempo of a project that is not an untouched Untitled one. Story 335's early studio-engineer review (NIT-2) found two things the wizard now gets wrong:

- **The tempo row promises a write that Finish does not make.** The wizard shows the Settings dialog's description: `Tempo of new projects, in BPM. Editing it also sets the open project's tempo, as one undoable step, unless that project has tempo changes.` When the wizard is re-run from Settings ▸ General ▸ Startup over a saved song, Finish never writes that song's tempo. The user learns this only afterwards, from the INFO notice `Default tempo saved for new projects. The open project's tempo was not changed.`
- **A value the Apply rejects is dropped silently.** A wizard tempo such as `abc` or `1000` is rejected on the row of the dialog nobody sees (`SettingsDialog.applyDefaultTempo`). The model is not written, and the wizard reports success. This is the silent drop story 335 removed from the Settings dialog, on another surface.

Story 313 made Finish contain an apply that throws. A value the Apply rejects without throwing is outside that story.

## Goals

- **A wizard value the Apply would reject is rejected on the wizard's own row.** The row shows the same message the Settings dialog shows (story 335, ruling R5), and keeps the typed text. While a row is rejected, Finish neither closes the wizard nor records the first-run outcome, and focus moves to the rejected row.
- **The wizard describes what its Finish does.** The tempo row states that the value is the tempo of new projects, and that Finish changes the open project only when it is an untouched Untitled one. The studio engineer rules the exact text, and whether the wizard uses its own description key.
- **Every other wizard row is held to the same rule.** This applies to any row whose Settings description names an effect a bulk Finish does not have.

## Acceptance Criteria

1. Finish with an invalid tempo leaves the wizard open, shows the ruled message on the tempo row and writes nothing.
2. After the value is corrected, Finish applies, closes the wizard and records the outcome.
3. The wizard's tempo row describes what Finish does to the open project.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- Finish through the host dialog with the tempo `abc`: the wizard stays open, and its tempo row shows `"abc" is not a tempo. Enter a number from 20 to 999, for example 120 or 128.5.` with the text kept. The model's default tempo is unchanged, and the first-run-completed flag is not set. Again with `1000` and with a blank value, each with its message.
- Then the tempo `130`: Finish applies it, closes the wizard and sets the flag.
- The wizard's tempo row shows the ruled description, not the Settings dialog's.
- Fault check, run once, turning a test red: the wizard's own validation removed, so Finish closes over a rejected value.

## Non-Goals

- The wizard host's dismissibility, Finish's error containment and outcome ordering: story 313, landed.
- Making Finish write a saved project's tempo. Finish stays a bulk Apply (story 335, ruling 2, Q1).
- The restart notice for a backend choice: story 313.

## Technical Notes

- daw-app: `settings/FirstRunWizard` (the rows it shows, `currentValue`, Finish), `FirstRunWizardHost` (`setOnFinishedAsync`, the outcome), `MainController.showSetupWizard` and `applyWizardEdits`, `SettingsDialog` (`applyBulkSettingsAsync`, `applyDefaultTempo`), `settings/SettingsShell` (`rejectSetting`, `firstRejectedSetting`), `settings/SettingRow` (`reject`, `rejectionProperty`).
- Strings: `project.defaultTempo.description` and the `settings.reject.tempo.*` texts in `Messages.properties`.
- Book: `SETTINGS_VIEW_DESIGN_BOOK.md` §4.D (wizard for first run) and §5.4 (the rejected row, its as-built note); `FAILURE_SURFACING_DESIGN_BOOK.md` §2.5 (record an outcome only after it happened).
- Cross-refs: story 309 (the wizard), story 313, story 335 (rulings R4, R5 and ruling 2, the early engineer review's NIT-2).

## Status

- Deferred
