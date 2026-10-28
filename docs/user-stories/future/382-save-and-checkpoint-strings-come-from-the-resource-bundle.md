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

## Extension: Recovery and Journal Strings (Story 332)

Story 332's PR 988 review round 3 moved every recovery-dialog sentence it added or changed into `Messages.properties`, but it kept these user-facing strings as literals in code, a deviation from the same §14 rule that its rulings accepted for that round. This extension adds them to this story, with the same helper and the same `Locale.ROOT` rule for times and numbers.

The strings, by owner:

- `RecoveryDialog`: the title "Recover unsaved work", the header "This project did not close cleanly", and the four button labels "Recover everything", "Restore checkpoint only", "Inspect events…" and "Cancel".
- `ProjectLifecycleController`: the Inspect events title "Captured events", and New Project's journal failure, "New project created; journaled persistence is paused until this project is reopened. " followed by the reason.
- `ProjectJournalCoordinator`: "Journaled persistence could not start for <folder>: <reason>", which the user reads after "Open failed: ", and "Journaled persistence could not start: <message>".
- `OpenRecoveryGate`: `NO_HEAD_FOR_CHECKPOINT_ONLY`, the failure text built when the interrupted save cannot be copied ("The interrupted save <temp name> could not be copied to <folder> (<reason>); nothing was deleted"), and the snapshot name `INTERRUPTED_SAVE_SNAPSHOT` ("Interrupted save").

Out of this extension: failure texts that `daw-core` builds and the user reads inside these messages, for example the session-base read-back and first-record texts after "Open failed: " and the damaged-segment reason inside "Recovery failed (…)". `daw-core` has no bundle, and wording them in `daw-app` first needs `daw-core` to report them as typed reasons, as `RecoverySummary.CannotReplay` already does for the dialog, so they stay English here.

Two of them need more than a move:

- **The snapshot name and the bundle sentences are coupled.** Three bundle sentences tell the user to look for a file whose name contains "interrupted-save". That fragment is what `SnapshotStore`'s file-name slug makes of `INTERRUPTED_SAVE_SNAPSHOT`, so translating the snapshot name changes the file name; the sentences must stay true for every bundle.
- **The title and header can be false.** "Recover unsaved work" and "This project did not close cleanly" are shown even after a sealed close that still owes a decision, so they need wording that depends on the summary, not only a move.

Goals and acceptance:

1. None of the strings listed in this extension is a literal in its class; each comes from `Messages.properties` through the story's one helper, and with only the root bundle each is byte-identical to today's, except the title and header in the sealed case.
2. After a sealed close that still owes a decision, the title and header do not say that the project did not close cleanly.
3. For every bundle, the file name of an "Interrupted save" snapshot contains the fragment the bundle sentences name.
4. The relabel of Restore checkpoint only (story 388) lands first, so its label moves once.

Tests, binding as above (headless, none assuming a non-Windows environment):

- **Root wording**: `RecoveryDialogOptionsTest`, `RecoveryDialogStringsTest`, `ProjectJournalCoordinatorTest`, `ProjectReplacementJournalSafetyTest` and `OpenRecoveryGateTest` pass with their exact strings, and the tests that answer the dialog by a button label (for example `EveryOpenDoorRunsRecoveryFirstTest`) still find it.
- **Translated**: with a test bundle for another locale, the dialog's title, header and buttons, the New Project journal ERROR and the Restore refusal show that bundle's text.
- **Coupling**: with a test bundle that translates the snapshot name, the gate's kept interrupted save is in a file whose name contains the fragment the dialog sentence names.
- **Sealed header**: a summary from a sealed journal that owes a decision renders a title and header that do not claim an unclean close.

Code locators: `RecoveryDialog` (the four `ButtonType`s, `setTitle`, `setHeaderText`), `ProjectLifecycleController.showInspectEvents` and `openCreatedJournal`, `ProjectJournalCoordinator.openForRequired` and `showRecorderFailure`, `OpenRecoveryGate` (`NO_HEAD_FOR_CHECKPOINT_ONLY`, `keepInterruptedSave`, `INTERRUPTED_SAVE_SNAPSHOT`), `SnapshotStore` (its slug and `createSnapshotFromFile`), and `Messages.properties` (`recoveryDialog.explain.interruptedSaveKeptByRestore`, `interruptedSaveKeptByBoth`, `interruptedSaveKeptAside`). Found by the conformance review of story 332's PR 988 review round 3 (`javafx-application-design` §14) and ruled by the studio engineer as an extension of this story; it depends on story 388 (`docs/user-stories/future/388-align-restore-checkpoint-only-with-what-it-does.md`). Cross-refs: story 332, story 387 (the sealed-close prompt).

## Status

- Deferred
