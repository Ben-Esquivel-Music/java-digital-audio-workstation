---
title: "A Save Checks Its Lease Again Immediately Before It Replaces project.daw"
labels: ["bug", "persistence", "locking"]
---

# A Save Checks Its Lease Again Immediately Before It Replaces project.daw

## Motivation

Story 331's save checks the project lock once, before it renders. `ProjectManager.write` runs in the order of `ProjectManager`'s class note ("The save"; rulings F1, F3, F10, G9): the lock check `lockManager.verifyOurs()` (`ProjectManager.java:1146`), then the render `job.document().toXml()` (`:1154`), then the backup the open owes, a durable copy (`backUpIfOwed`, `:1165`), then the head write (`replaceProjectFile`, `:1166`). A session that takes the folder over during the render or during the backup's copy is not seen, so the head write lands in the new holder's folder. When it lands after the new holder's open has read `project.daw`, the new holder's next save replaces it, although this save reported Saved. Nothing tells the user that this save went into the new holder's folder: the session sees the takeover only as a lost lock. Its next heartbeat, or another lease check such as a checkpoint's, marks the lock stolen, and the lock badge then reads "Lock stolen" (the heartbeat runs every 30 seconds and the badge repaints every 5, so this is normally within about 35 seconds of the takeover); its next save fails. Rendering a large project, or making a durable copy on a network share, takes long enough for this to happen.

Story 332's resolution of PR 988 review 5666358306 gave the recovered commit the opposite order: it verifies the open's lease after its backup, immediately before the replace (`OpenRecoveryGate.replaceHead`, `ProjectLifecycleController.writeRecoveredProjectFile`). Story 332's Known limits record that the save's gap is wider.

## Goals

- **The save checks again after its render and immediately before its head write.** The save verifies the lease again after its render, before it writes anything, and once more immediately before the head write, after any owed backup. The existing check before the render stays, so a save that is not ours fails fast.
- **A failed check fails the save as itself.** The outcome is `SaveOutcome.Failed` with the `LockStolenException`, or the check's plain `IOException`, as its cause. Nothing more is written and the project stays dirty. A `LockStolenException` writes no safety checkpoint (`failedAfterTheRender`, as today). A check that fails before the backup leaves the backup owed.
- **The failure text stays true.** `ProjectLifecycleController.saveFailureMessage` ends a stolen-lock failure with "— nothing was written". When the backup was written before the last check failed, the text says instead that `project.daw` was not replaced. If story 333's Save-As flow has landed, it carries the same truth.
- **The class note says so.** `ProjectManager`'s class note ("The save") states the new order and the residual: a takeover between the last check and the head's rename is not seen.
- **Every head write that checks the lease is swept.** The other head writes that check the lease get the same shape, for example `saveForClose`'s metadata-only branch (`verifyOurs`, then `writeProjectFile`).

## Acceptance Criteria

1. A takeover during the owed backup's copy gives `Failed(LockStolenException)`. `project.daw` and the new holder's `.project.lock` are byte-identical, the backup exists, no safety checkpoint is written, the project is dirty, and the text says `project.daw` was not replaced.
2. A takeover during the render gives `Failed`, and nothing is written: no backup, and `project.daw` is byte-identical. The backup is still owed.
3. With the lease held, saves behave as today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Takeover during the backup**: open a schema-1 project, which owes the migration backup. Give `ProjectManager` an `AtomicFileWriter` whose `Mover` moves the backup into place and then force-acquires the folder through a second `ProjectLockManager` with another identity. Save, and assert criterion 1.
- **Takeover during the render**: through a render seam (add one if none exists) that force-acquires the folder, assert criterion 2.
- **Fault checks**: removing the check before the head write turns the first test red; removing the check after the render turns the second red.

## Non-Goals

- The Save-As flow for a stolen lock (story 333).
- The non-atomic gap between the last check and the rename, which is accepted for every lease check (story 332, Known limits).
- The lock's heartbeat and release (story 394).
- The recovered commit (story 332).

## Technical Notes

- Code locators: `ProjectManager.write`, `backUpIfOwed`, `failedAfterTheRender`, `replaceProjectFile`, `saveForClose`; `ProjectLockManager.verifyOurs`; `ProjectLifecycleController.saveFailureMessage`.
- The precedent is `OpenRecoveryGate.replaceHead`, which hands the commit the lease check to run as its last step before the replace and lets a failed check leave as itself.
- Ruled by the studio engineer in the first fix round of story 332's resolution of PR 988 review 5666358306, found by that round's verification.
- Design references: Persistence Integrity design book §5.2 (the write contract, its head row) and §8 Stage 3 (atomic saves, story 331).
- Cross-refs: story 331 (the order), story 332, story 333 (typed stolen-lock handling during save; this story adds no new outcome type), story 383 (the lease question belongs among its refusal questions before the owed backup; whichever story lands second keeps both checks), story 394.

## Status

- Deferred
