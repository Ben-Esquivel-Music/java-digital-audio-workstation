---
title: "A Disk Open Whose Journal Cannot Start After Its Target Is Installed Opens Writable With Journaling Paused"
labels: ["bug", "persistence", "project-load", "journal"]
---

# A Disk Open Whose Journal Cannot Start After Its Target Is Installed Opens Writable With Journaling Paused

## Motivation

In a gated disk open the journal starts at the open contract's step 6, JOURNAL (Persistence Integrity design book §5.3). By then the recovery decision has been applied, and `prepareLoadedProject` has installed the target project, B, on the FX thread: its model, undo manager, history panel and audio generation. When `ProjectJournalCoordinator.openForRequired` then throws, `ProjectLifecycleController.beginGatedLoad` shows "Open failed: Journaled persistence could not start for <folder>: <reason>", and its `finally`, which sees an open that was never marked installed, abandons B in the project manager (`ProjectManager.closeProject()`, which closes it without saving; story 333 deleted `abandonProject`, which did this before).

The user is left looking at B's model with no lease, no journal and no current project: the folder is no longer locked by this session, and a Save does not write B's folder. If the dialog's choice was Recover everything, the recovered project was already committed to `project.daw` and the journal cleared, but the ERROR does not say that the recovered work was saved. No recovery evidence is at risk: the decision is committed, and a journal start that fails removes what it made (story 332, M1 resolution). Story 332's PR 988 review round 3 kept the refusal and made its ERROR name the cause; this story replaces the refusal. New Project already behaves the way this story asks: when its journal cannot start, the project stays open and the ERROR reads "New project created; journaled persistence is paused until this project is reopened. <reason>".

## Goals

- **The open finishes writable, with journaling paused.** A JOURNAL failure after installation completes B's installation coherently: B's lease, model and UI cascade stand, B is the project manager's current project, and Save writes B's folder.
- **The outcome is announced with its cause.** One qualified announcement names the cause and says "Journaled persistence is paused until this project is reopened". After a Recover everything it also says that the recovered work was saved to `project.daw`.
- **The strip shows the pause.** The status strip's Journal cell shows that journaling is paused for B.
- **Every door.** The same holds for each gated disk-open door and for the migration roll-back, door by door, as `EveryOpenDoorRunsRecoveryFirstTest` covers them.

## Acceptance Criteria

1. When the journal cannot start after installation (for example because `journal/` is a file), File ▸ Open of B leaves B on screen as the project manager's current project, locked by this session and writable, and Save writes B's `project.daw`.
2. One announcement names the cause and says that journaled persistence is paused until the project is reopened; no `Open failed: …` ERROR is shown.
3. After a Recover everything whose journal then cannot start, the announcement says that the recovered work was saved to `project.daw`.
4. The Journal cell shows the pause until B is closed or reopened.
5. Criteria 1–4 hold for every door in `EveryOpenDoorRunsRecoveryFirstTest`'s matrix and for the migration roll-back.
6. Reopening B once the cause is fixed starts its journal normally.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Door matrix**: for each gated disk-open door, with the coordinator's real failure (an obstructed `journal/` directory, as `ProjectJournalCoordinatorTest.requiredStartupReportsARealJournalDirectoryFailure` builds it): B is current, locked and writable; the announcement and the Journal cell are as in criteria 2 and 4; a Save writes B's `project.daw`.
- **After Recover everything**: an owed journal, Recover everything, then the journal start fails: `project.daw` holds the recovered project and the announcement says so.
- **Reopen**: the obstruction is removed and B reopened: the journal starts and the Journal cell is normal.
- **After Continue** (story 333's known limit on the Journal cell after Continue): the pause is the Journal cell's one state for every project this session holds writable with journaled persistence on and no journal of its own, so after a Save As Continue whose original journal is still being closed (`SaveAsFlowTest`'s held seal), the cell shows the pause from the copy's installation on and nothing of the original's journal.
- **Behind a hung close** (story 333's known limit on journal opens behind a hung close): with another folder's journal close held, as a Save As Continue leaves it, Close Project and reopen the copy: `ProjectJournalCoordinator.openForRequired` waits at most 10 seconds, off the FX thread (javafx-application-design §11, “Threading”), and the open then finishes writable with journaling paused, announced with that cause, and no journal opens for the copy later in the session.

## Non-Goals

- Failures before the target is installed: the parse, the migration and the lock prompt (story 361).
- The journal of a project that is abandoned (story 362).
- New Project, which already opens with journaling paused.

## Technical Notes

- Code locators: `ProjectLifecycleController.beginGatedLoad` (the `prepareJournalOpen.accept(folder)` call after `prepareLoadedProject`, its `catch (Throwable)` that shows "Open failed: ", and its not-installed `finally`), `prepareLoadedProject`, `finishPreparedLoad`, `PAUSED_JOURNAL_MESSAGE`, and `openCreatedJournal` (New Project's precedent); `ProjectJournalCoordinator.openForRequired`; `ProjectManager.closeProject` (called by the not-installed `finally`); `ProjectOperationProgress` (the Journal cell).
- Sibling of story 361 (`docs/user-stories/future/361-failed-open-leaves-project-state-consistent.md`). Boundary: story 361 owns failures before installation, after which the project on screen stays the current one; this story owns the step-6 JOURNAL failure after installation. Both change `beginGatedLoad`'s not-installed `finally`, toward different end states, so they must land in order, and the second re-runs the first's tests. In the same pass, re-judge story 361's Motivation and code locators, which describe `loadProjectFromPath` as it was before story 332's `ProjectManager.PreparedOpen` flow. Story 362 is the journal-close sibling.
- Ruled by the studio engineer in story 332's PR 988 review round 3: the M1 ruling kept the refusal for that round and filed this end state as its one deferral; the verification's conformance pass set the boundary with story 361.
- Design references: Persistence Integrity design book §5.3 (the open contract), §6.2 (failure surfacing).
- Cross-refs: story 332, story 361, story 362.

## Status

- Deferred
