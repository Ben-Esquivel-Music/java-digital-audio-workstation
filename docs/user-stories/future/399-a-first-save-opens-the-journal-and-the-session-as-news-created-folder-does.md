---
title: "A First Save Opens the Journal and the Session as New's Created Folder Does"
labels: ["bug", "persistence", "journal", "crash-recovery"]
---

# A First Save Opens the Journal and the Session as New's Created Folder Does

## Motivation

A project that has never been saved has no folder. The application starts on one — `MainController` builds an in-memory `Untitled Project` at launch — and Close Project leaves one (`ProjectLifecycleController.installFreshUntitledProject`). So a common session starts by capturing the idea first and saving it later. That first save — File ▸ Save or Ctrl+S on a project never saved — asks for a location, creates the folder off the FX thread and submits the save into it (`ProjectLifecycleController.saveIntoANewFolder`, then `saveIntoTheCreatedFolder`, which calls `submitSave`). It does nothing more:

- **No journal.** New's created folder opens its journal once its initial save has landed (`newProjectFolderCreated` → `openCreatedJournal`, on the `daw-new-project-journal` thread), so journaled persistence, on by default since story 332, covers New's project from its first save on. A first save's project runs with no journal until it is next opened.
- **No session and no snapshot registration.** New first runs the open project's folder-changed hook (`projectFolderChangedHook`; in the application `MainController.projectFolderChanged`, which registers the folder's `checkpoints/` and `snapshots/` with the snapshot browser through `SnapshotsController.registerCurrentProjectDirectory()` and opens the session manifest through `refreshSessionManager(true)`). A first save runs neither. A snapshot browser that is already open lists the project's checkpoints and snapshots only once the browser is reopened (`SnapshotsController.openBrowser` registers the folder again) or the project is.

The saves themselves are written as usual. But until the project is next opened, a crash has no journal to recover edits from. Story 333's Resolution records this as the known limit “A first save sets up no journaled session until the project is next opened”, with this story as its owner.

## Goals

- **A first save opens the journal as New's created folder does.** Once a first save's save has landed in its created folder, and the open project is still the one it saved, the project's journal opens off the FX thread through the same opener New's `openCreatedJournal` uses (`prepareJournalOpen`, which `MainController` wires to `ProjectJournalCoordinator.openForRequired`). A journal that cannot open leaves the project open and saved, with journaled persistence paused until it is reopened, and says so in one ERROR, in the shape of New's ("New project created; journaled persistence is paused until this project is reopened. " followed by the reason) but worded for a save; the studio engineer rules the text.
- **A first save sets up the session as New does.** The created folder's `checkpoints/` and `snapshots/` are registered with the snapshot browser, so a browser already open lists them at once, and the session manifest opens; the labels and the lock indicator follow, as New's folder-changed hook makes them.
- **Journaling off stays off.** With journaled persistence turned off, a first save opens no journal, as New's opens none then (`openForRequired` is a no-op while the setting is off).
- **The rest of the first save is unchanged.** Its location prompt, its folder creation off the FX thread, its pending switch (`PendingSwitch.CreatingFolder`, door `Save Project`), the coalescing of save requests made while its folder is created (`coalescedIntoTheFirstSave`) and its failure path stay as story 333 made them.
- **Story 333's known limit on a first save's journal closes.** Its Resolution marks the limit as closed by this story.

## Acceptance Criteria

1. After a first save into a chosen location lands, a journal is open for the created folder; after an edit and a simulated crash, the recovery gate offers recovery when the project is reopened.
2. After a first save, the created folder has a session manifest, and a snapshot browser that was open before the save lists the folder's checkpoints without being reopened.
3. A journal that cannot open after a first save leaves the project open and saved, with no journal, and shows one ERROR with the ruled text.
4. With journaled persistence off, a first save opens no journal.
5. Save As's adopted copy after Continue still opens no journal in this session (story 333, ruling 2 OQ-1, constraint 1).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Journal after a first save**: a never-saved project, a first save into a chosen parent, then an edit; a crash-shaped reopen (the lock left, the journal unsealed with a record after its last checkpoint marker) shows the recovery dialog, as story 332's crash-shaped tests do for the open paths.
- **Off the FX thread**: the journal's open after a first save makes no file-system call on the FX thread, alongside `NewFirstSaveAndOpenMakeNoFileSystemCallOnTheFxThreadTest`'s first-save case.
- **Session and browser**: the session manifest exists after the first save, and a browser opened before it lists the created folder's checkpoints after it.
- **Journal failure**: an opener that throws once: the ERROR exactly, the project open and saved, `ProjectJournalCoordinator.isOpen()` false.
- **Journaling off**: no journal after the first save.
- **Save As unchanged**: the existing `SaveAsFlowTest` cases of a Continue, which assert that no journal is ever opened for the copy, pass unchanged.
- **Fault check**: the first save's journal open removed turns the first test red.

## Non-Goals

- New's created folder, which already opens both.
- What the first save asks and where it saves (story 333, under story 331's ruling L4).
- The strip's journaling-paused state.

## Technical Notes

- Code locators: `ProjectLifecycleController` — `saveIntoANewFolder`, `saveIntoTheCreatedFolder`, `submitSave`, `coalescedIntoTheFirstSave`, `newProjectFolderCreated`, `openCreatedJournal`, `prepareJournalOpen`, `projectFolderChangedHook`; `MainController` — `projectFolderChanged`, `refreshSessionManager`, the `enableRecoveryGate` wiring; `SnapshotsController` — `registerCurrentProjectDirectory`, `openBrowser`; `ProjectJournalCoordinator` — `openForRequired`, `isOpen`.
- Where the journal opens matters. The folder-changed hook opens no journal on purpose: Save As's adoption of its copy runs the same hook, and nothing may open a journal for a copy adopted after Continue later in the session — not a retry, not the hook, not a later save (story 333, ruling 2 OQ-1, constraint 1; `MainController.projectFolderChanged`'s Javadoc says so). So the first save opens its journal from its own completion, as New does from its initial save's outcome, not from the hook and not from every save.
- The studio engineer's stage-4 review of story 333 offered running the folder-changed hook after a first save's create as an optional narrowing; story 333 deferred it, because the hook opens no journal and so would not close the part that matters for a crash. The studio engineer then asked for this story (the ruling on app fix pass 1, S1), with the known limit's text: “A first save sets up no journaled session until the project is next opened: it opens no journal and no session manifest (unchanged from story 332; New's created folder opens both), and a snapshot browser already open lists the project's checkpoints and snapshots only once the browser or the project is reopened. The saves themselves are written as usual, but until that next open a crash has no journal to recover edits from.”
- Design references: Persistence Integrity design book §2.5 (durability is default-on; opt-out is for experts), §5.3 (the open contract, ordered).
- Cross-refs: story 332 (journaled persistence on by default), story 333 (the first save's location prompt as a pending switch, story 331's ruling L4; its known limit on a first save's journal), story 331, story 386 (a journal that cannot start after an open's installation: journaling paused, with its wording).

## Status

- Deferred
