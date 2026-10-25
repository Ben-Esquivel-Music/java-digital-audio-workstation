---
title: "Save and Checkpoint Strings Come From the Resource Bundle"
labels: ["enhancement", "i18n", "persistence", "status-strip"]
---

# Save and Checkpoint Strings Come From the Resource Bundle

## Motivation

The `javafx-application-design` skill's §14 "Accessibility, internationalization, and quality" says: "Pull all user-facing strings from **`ResourceBundle`** (`Messages_en.properties`, `Messages_de.properties`, …); never hard-code text in code or FXML." Story 329's open announcement does: `ProjectAudioText` reads `ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT)`. The strings story 331 added are literals in their classes, as most of the code around them already was: `SnapshotsController`, `SessionStatusStripSkin` and `CheckpointStatusListener` read no resource bundle, and `ProjectLifecycleController` reads only story 329's strings through `ProjectAudioText`. Found by story 331's skill review; nothing is visible yet, because only the root `Messages.properties` ships, but once a translation ships the open announcement is translated and every story-331 string stays English.

The strings, by owner:

- `ProjectLifecycleController`: the save failures (`saveFailureMessage`, `checkpointName`), the door refusals (`Still saving — <door> continues when the save finishes.`, `STILL_EXTRACTING_MESSAGE`, `STILL_RECOVERING_MESSAGE`, `doorDialogStillOpenMessage`), `lateSwitchRefusedMessage`, the restored-archive prompt and INFO (`restoredArchivePromptText`, `restoredArchiveNotOpenedMessage`), and `CONTINUE_QUESTION`.
- `ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME` (`Saving project…`).
- `SnapshotsController`: `KEPT_IN_MEMORY_UNSAVED_MESSAGE`, `KEPT_IN_MEMORY_READ_ONLY_MESSAGE`.
- `CheckpointStatusListener`: `Checkpoint failed: <cause>` and the streak warning (`streakWarning`).
- `SessionStatusStripSkin`: the Saved cell's text, tooltip and accessible text (`savedText`, `savedDetail`, `spokenSavedState`).

## Goals

- **Every string story 331 added is a key in `Messages.properties`,** read through a text helper like `ProjectAudioText`, with the ruled wording as the root value, so the tests' exact-string assertions still hold.
- **Times and dates stay as ruled.** The clock and date patterns (`HH:mm`, `HH:mm:ss`, `yyyy-MM-dd`) and the checkpoint numbering stay `Locale.ROOT`, as story 331 rules them; only the words move.

## Acceptance Criteria

1. None of the strings listed above is a literal in its class; each comes from `Messages.properties` through one helper.
2. With only the root bundle, every string is byte-identical to today's.
3. A test bundle for another locale changes every one of them on screen.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Root wording**: the existing exact-string tests of story 331 (`SaveOutcomeNotificationTest`, `SavedCellTextAndSeverityTest`, `DoorDialogIsTheOnePendingSwitchTest`, `RecoverSessionIsTheOnePendingSwitchTest`, `RestoredArchiveAsksAgainTest`, `CreateCheckpointKeptInMemoryTest`, `LastGoodCheckpointTest`) pass unchanged.
- **Keys exist**: a test resolves every key the helper is asked for in the root bundle.
- **Translated**: with a test bundle for another locale, a failed save's ERROR, a door refusal and the Saved cell show that bundle's text.

## Non-Goals

- Translating the application.
- Strings older than story 331 in the same classes, unless the helper makes moving them free.

## Technical Notes

- Code locators: `ProjectAudioText` (the precedent helper), `daw-app/src/main/resources/com/benesquivelmusic/daw/app/i18n/Messages.properties`, and the classes listed above.
- Found by story 331's skill review (`javafx-application-design` §14), recorded as a future story.
- Cross-refs: story 329 (`ProjectAudioText`), story 331.

## Status

- Deferred
