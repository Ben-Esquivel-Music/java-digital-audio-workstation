---
title: "Discard at the In-App Doors Keeps the Discarded Work as the Exit's Discard Does"
labels: ["bug", "persistence", "journal", "crash-recovery"]
---

# Discard at the In-App Doors Keeps the Discarded Work as the Exit's Discard Does

## Motivation

The user answers Discard at an in-app door that replaces the open project: New Project, Open Project (from File ▸ Open Project…, the Hub or the Welcome screen) or Restore from Archive. The door's prompt is `ProjectLifecycleController.askAboutUnsavedChanges` with the doors' texts (`UnsavedChangesPrompt.DOOR`, or `UnsavedChangesPrompt.door(…)`), and a Discard lets the door go on. The door then seals the outgoing journal while it still holds records after its last checkpoint marker's capture boundary: `MainController.prepareDoorJournalClose` detaches the recorder and returns the close, which runs `ProjectJournalCoordinator.closeCurrentFor`, with no closing checkpoint. So that project's next open shows the recovery dialog for the work the user chose to discard, and the Welcome screen lists it under Recover (`ProjectHealthScanner.isRecoverable`, which classifies a project from its journal's seals and capture points).

The exit's Discard (story 333, ruling R3) first writes a closing checkpoint of the discarded state, where the journal holds a record no capture covers or the revision is not checkpointed (`ProjectFolderCloser.ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED`), and only then seals. Its next open is plain, and the work stays in that checkpoint. Story 333's Resolution records the difference as a known limit (ruling R12 #11), with this story as its owner.

## Goals

- **Every in-app door's Discard leaves the folder as the exit's Discard does**: the closing checkpoint under the same trigger, then the seal.
- **The next open is plain.** It shows no recovery dialog, and the Welcome screen does not list the project under Recover.
- **Nothing else changes.** Save, Cancel and the doors' order of steps are as today, so a door that fails before it replaces the project still leaves the old project open (stories 332 and 361).
- **Import stays where it is.** Import DAWproject Exchange's abandonment stays story 362's.

## Acceptance Criteria

1. With unsaved changes and journal records after the last capture, New Project's Discard writes one closing checkpoint of the discarded state, then seals the journal. The closed project's next open shows no recovery dialog, and `ProjectHealthScanner.isRecoverable` is false for its folder.
2. The same holds for File ▸ Open Project…, an open from the Hub, an open from the Welcome screen and Restore from Archive.
3. When the trigger does not hold — no record outside a capture and the revision checkpointed — a door's Discard writes no closing checkpoint, as the exit's does not.
4. Save and Cancel at each door behave as today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Door matrix**: for each door of criteria 1 and 2, as `EveryOpenDoorRunsRecoveryFirstTest` drives the open doors: an edit after a save, then the door's Discard. One closing checkpoint of origin `CheckpointOrigin.CLOSING` holds the discarded edit, the seal follows it, `ProjectHealthScanner.isRecoverable` is false for the folder, and its reopen shows no recovery dialog.
- **Nothing owed**: Discard right after a save, with no record since: no closing checkpoint is written.
- **Answers unchanged**: Save and Cancel at each door, as today.
- **Fault check**, run once, turning the door matrix red: a door's journal close that seals without the closing checkpoint.

## Non-Goals

- Import DAWproject Exchange's abandonment (story 362).
- The exit's Discard (story 333, ruling R3), which this story matches and does not change.
- The rule that decides when a project needs recovery (story 389).

## Technical Notes

- Code locators: `MainController.prepareDoorJournalClose`; `ProjectJournalCoordinator` — `detachFromBus`, `closeCurrentFor`; `ProjectLifecycleController` — `whenProjectMayClose`, `proceedUnlessCancelled`, `switchIfTheAnswerCovers`, `askAboutUnsavedChanges`, `UnsavedChangesPrompt`, `replaceAfterJournalClose`, `beginGatedLoad`, `startArchiveRestore`; `ProjectFolderCloser.ClosingCheckpoint`; `ProjectManager.writeClosingCheckpoint`; `CheckpointOrigin.CLOSING`; `ProjectHealthScanner.isRecoverable`; `WelcomeView`; tests `EveryOpenDoorRunsRecoveryFirstTest`, `CleanExitOpensPlainlyTest` (the exit's Discard and its plain next open).
- Ruled by the studio engineer in story 333: ruling R12 #11 left alignment or acceptance open, and the ruling on the owners, item 4, rules alignment, because a recovery offer for work the user discarded teaches users to dismiss the recovery dialog. The same ruling considered and rejected routing the in-app doors through the close-then-continue primitive (story 405): it closes the old project before the new one's lock and recovery are decided, which breaks the doors' order in which a failure leaves the old project open.
- Design references: Persistence Integrity design book §4.7 (the recovery gate on the open pipeline), §5.4 (the exit protocol, ordered, and its closing checkpoint).
- Cross-refs: story 332 (the recovery gate), story 333 (ruling R3's closing checkpoint; ruling R12 #11), story 361 (a failed open), story 362 (the journal of an abandoned project), story 389 (one recovery detection rule).

## Status

- Deferred
