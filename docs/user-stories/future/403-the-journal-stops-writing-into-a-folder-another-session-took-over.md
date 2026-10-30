---
title: "The Journal Stops Writing Into a Folder Another Session Took Over"
labels: ["bug", "persistence", "journal", "locking"]
---

# The Journal Stops Writing Into a Folder Another Session Took Over

## Motivation

After another session takes over the open project's lock, this session's saves and checkpoints write nothing more into the folder (story 331). Every save path checks the lock again before it writes the project file (`ProjectLockManager.verifyOurs`), and the checkpoints' write guard refuses a session that no longer holds the lock (`ProjectLockManager.verifyHeld`).

The journal does not stop. This session's journal recorder stays on the event bus and keeps appending to this session's segment in the folder's `journal/`. Nothing detaches it when the lock's status turns `LockStatus.STOLEN`: `ProjectJournalCoordinator.detachFromBus` is called only as a close or a door begins (`MainController.prepareDoorJournalClose`, `SessionJournalEnding.detach`). Quit without saving and a stolen-lock Save As still drain the journal into that segment (ruling R7's interim; `ProjectJournalCoordinator.drainWithoutSealFor`). `StolenLockAtExitTest` shows it: it compares the original folder before and after with this session's segment left out (`withoutThisSessionsSegment`).

The other session's next open then reads records it never made. Once story 387 replays state-bearing records, it could rebuild them into that session's project. Story 333's Resolution records this as a known limit (ruling R12 #7), with this story as its owner.

## Goals

- **The loss is known.** This session knows its lease is lost when a heartbeat (`ProjectLockManager.heartbeat`), a save's `ProjectLockManager.verifyOurs` or a close's lock check (`ProjectManager.whenLockChecked`) reports `LockStatus.STOLEN` or finds the lock file removed.
- **From that moment the journal writes nothing into the folder.** The recorder is detached (`ProjectJournalCoordinator.detachFromBus`), and the journal is closed with no further write into the folder: no drain, no seal and no session manifest. Every later close writes nothing into `journal/` either, Quit without saving and a stolen-lock Save As among them.
- **The edits stay here.** Journaling stays paused for the rest of the session, and the edits stay open in this session for ruling R7's Save As.
- **The residual is stated.** The Resolution states the window between the takeover and this session learning of it: at most one `ProjectLockManager.HEARTBEAT_INTERVAL` (30 seconds).

## Acceptance Criteria

1. Once this session has learned that another session took the lock, nothing more is written into the folder's `journal/`: not by an edit, a save attempt, a Quit without saving or a stolen-lock Save As.
2. The loss is learned from whichever comes first of a heartbeat, a save's lock check and a close's lock check, and a lock file removed counts as one another session took.
3. After the loss the project stays open and editable here, with journaling paused, and ruling R7's Save As keeps the edits in a new folder.
4. The Resolution states the residual window, at most one `HEARTBEAT_INTERVAL`.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Takeover, then edits**: another session takes the lock, as `StolenLockAtExitTest` does, and a heartbeat reports it; then edits are made. The folder's `journal/` is byte-identical from the report on.
- **Quit without saving**: `StolenLockAtExitTest` asserts the original folder unchanged including `journal/`, without its `withoutThisSessionsSegment` exclusion.
- **Stolen-lock Save As**: the same for the takeover dialog's Save As; the copy holds the edits.
- **Lock removed**: the same cases with the lock file deleted instead of replaced.
- **Fault checks**, each run once, each turning a test of this story red: the recorder left on the bus after the loss; a drain at Quit without saving.

## Non-Goals

- The heartbeat's and the release's races with a takeover across processes (story 394).
- Learning of a takeover sooner than the next heartbeat or lock check.
- Replay of the journal's records (story 387).

## Technical Notes

- Code locators: `ProjectLockManager` — `heartbeat`, `verifyOurs`, `verifyHeld`, `status`, `HEARTBEAT_INTERVAL`; `LockStatus.STOLEN`; `ProjectManager.whenLockChecked`; `ProjectJournalCoordinator` — `detachFromBus`, `drainWithoutSealFor`, `closeCurrentFor`; `SessionJournalEnding`; `MainController.prepareDoorJournalClose`; `ExitCoordinator.drainMode`; tests `StolenLockAtExitTest`, `StolenLockSaveAsFlowTest`.
- This story lands before story 387, or with it: 387's replay of state-bearing records would rebuild a displaced session's records into the other session's project.
- Ruled by the studio engineer in story 333 (ruling R7's interim; ruling R12 #7; the ruling on the owners, item 3).
- Design references: Persistence Integrity design book §4.7 (the recovery gate on the open pipeline), §5.4 (the exit protocol, ordered).
- Cross-refs: story 331 (the lease checks of the saves and the checkpoints), story 333 (ruling R7's takeover dialog), story 386 (the Journal cell's paused state), story 387 (replay), story 394 (the lock's heartbeat and release).

## Status

- Deferred
