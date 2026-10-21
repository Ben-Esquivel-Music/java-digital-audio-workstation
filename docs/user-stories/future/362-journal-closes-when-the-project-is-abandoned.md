---
title: "The Journal Closes When the Project Is Abandoned"
labels: ["bug", "persistence", "journal", "project-load"]
---

# The Journal Closes When the Project Is Abandoned

## Motivation

Story 298's write-ahead journal is opened for each project folder through `ProjectLifecycleController.notifyJournalOpen`, which runs MainController's journal-open hook: the hook captures the outgoing `WorkingSession` on the FX thread and calls `ProjectJournalCoordinator.reopenFor(projectDir, outgoing)` on a virtual thread, which closes the open journal, seals its segments into that session, and opens the new folder's journal. Apart from that hook, the journal is closed only by the window's teardown, which detaches the recorder from the bus on the FX thread (`detachFromBus`) and then calls `closeCurrent` on a virtual thread.

Some doors abandon the open project for an Untitled Project that has no folder, so they never reach the hook:

- **Import DAWproject Exchange** (`onImportSession`) calls `resetProjectState()` and installs a new Untitled Project.
- **A migration Roll back with no `.bak`** (`rollbackMigration`) does the same: the migrated project lived only in memory, so abandoning it is the whole roll-back. Since story 329 the load that showed the migration dialog opens no journal for the folder it abandoned, because the journal opens only for the open that is still current.
- **A New Project whose folder could not be created** keeps its in-memory Untitled Project but calls the hook only when `createdOnDisk` is true.

In each case the previous project's journal stays open. `JournalEventRecorder` records from the application `EventBus`, so with journaled persistence enabled the Untitled project's edits are journaled into the previous project's `journal/` folder — events that belong to no project in that folder. This predates story 329 for Import; the Roll back with no `.bak` ran the same way at the base commit, inside a migration dialog that could not close.

## Goals

- **Abandoning a project closes its journal.** Wherever the open project is abandoned without a replacement folder, a journal-close hook runs `ProjectJournalCoordinator.closeCurrent(outgoing)`, with the outgoing `WorkingSession` captured on the FX thread and the close and seal run off it, as the window's teardown does.
- **No event of one project reaches another project's journal.** The recorder stops recording into the previous project's journal before the Untitled project's first edit can be recorded.

## Acceptance Criteria

1. With journaled persistence enabled, Import DAWproject Exchange over an open project closes that project's journal and seals it into the outgoing working session; an edit made afterwards in the imported project adds nothing to the previous project's `journal/` folder, and `ProjectJournalCoordinator.isOpen()` is false.
2. The same holds after a confirmed migration Roll back with no `.bak`, and after a New Project whose folder could not be created.
3. The recorder is detached from the bus on the FX thread before the door installs the Untitled Project; the seal, which is I/O, runs on a virtual thread, and the coordinator's lifecycle lock still keeps a later open from interleaving with it.
4. Doors that replace the project with another folder are unchanged: they still go through `reopenFor`, and each opens the journal at most once. The window's teardown is unchanged.
5. With journaled persistence disabled, or with no journal open, the hook does nothing.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Import**: open project A with journaled persistence enabled, import a DAWproject Exchange file, make one edit; A's `journal/` gains no event, the coordinator reports no open journal, and A's working session holds A's sealed segments.
- **Roll back with no `.bak`**: a migrated open with no backup, Roll back confirmed, one edit afterwards; the previous project's `journal/` gains no event.
- **New Project failure**: the new project's folder cannot be created; the previous project's journal is closed.
- **Unchanged doors**: opening project B after A closes A's journal and opens B's exactly once, as before.

## Non-Goals

- Turning journaled persistence on by default (story 332).
- A journal for the Untitled project before its first save.
- The project manager's state after a failed open (story 361).

## Technical Notes

- Code locators: `ProjectLifecycleController.notifyJournalOpen` and `setJournalOpenHook`; `ProjectLifecycleController.resetProjectState`, called from `onImportSession` and from `rollbackMigration`'s no-backup branch; `ProjectLifecycleController.onNewProject` (`createdOnDisk`); MainController's journal-open hook (`reopenFor`) and its window teardown (`detachFromBus`, then `closeCurrent`); `ProjectJournalCoordinator.openFor`, `reopenFor`, `closeCurrent` and `detachFromBus`.
- A Roll back with no `.bak` returns `true` from `loadProjectFromPath`, although the project it loaded was abandoned; the SUCCESS `Discarded in-memory migration; the on-disk file is still the original (v…)` says what happened. Callers of the result are not changed by this story.
- Design references: Persistence Integrity design book §4.7 (the recovery gate on the open pipeline) and §8 Stage 4 (story 332); Project Manager design book (the journal, story 298).
- Cross-refs: story 298 (write-ahead journal), story 329 (the journal opens only for the open still current), story 332 (journaled persistence on by default), story 361 (a failed open).

## Status

- Deferred
