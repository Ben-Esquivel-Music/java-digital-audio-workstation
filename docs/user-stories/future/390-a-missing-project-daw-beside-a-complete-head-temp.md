---
title: "A Missing project.daw Beside a Complete Head Temp"
labels: ["bug", "persistence", "crash-recovery", "project-load"]
---

# A Missing project.daw Beside a Complete Head Temp

## Motivation

A project folder can hold a complete atomic-writer temp of `project.daw` and no `project.daw`: for example after a crash during a new project's first save, once its temp was complete and before it was moved into place, or after `project.daw` was moved away by hand or by a sync tool. `RecoveryTemps.inspect` compares each head temp's last-modified time with `project.daw`'s, and it calls `Files.getLastModifiedTime(head)` outside the `try` block that guards the rest of that check. With no `project.daw`, that call throws `NoSuchFileException`, the recovery gate fails before any dialog, and the open ends with an `Open failed: …` ERROR. Nothing is deleted, so this is safe, but the project cannot be opened without moving files by hand, and the complete temp, the newest state of the project, is never offered.

The dialog is not ready for the case either. Its Recover everything sentence for a promoted interrupted save, "Recover everything makes the interrupted save the project and keeps a backup of the last clean save, in a file beside it whose name starts with "project.daw.recovered".", would be false with no `project.daw`, and its interrupted-save sentence is omitted whenever `project.daw` is missing.

## Goals

- **A complete temp is evidence even without a head.** When `project.daw` is missing, a complete head temp is treated as newer-head evidence, so the gate offers it instead of failing.
- **The dialog has true words for it.** The dialog says that Recover everything makes the interrupted save the project, with no backup to keep, and that Restore checkpoint only cannot open the project and keeps everything.
- **Nothing is deleted without a copy.** As for every interrupted save (story 332), no choice deletes the temp without a copy, and Restore checkpoint only refuses before deleting anything.

## Acceptance Criteria

1. A writable open of a folder with a complete head temp and no `project.daw` shows the recovery dialog, not an ERROR.
2. Every sentence of that dialog is true: Recover everything writes the temp's bytes as `project.daw`, with no backup mentioned, and Restore checkpoint only cannot open the project.
3. Recover everything writes the temp's bytes as `project.daw`, removes the temp, and announces "Recovered an interrupted save".
4. Restore checkpoint only is refused with nothing deleted: the temp and any journal stay byte-identical.
5. With an owed journal beside the temp, the dialog describes both, and no choice deletes the temp without a copy.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Temp only, no head**, through `OpenRecoveryGate`: the plan promotes the temp; Recover everything writes its bytes as `project.daw` and the temp is gone; Restore checkpoint only leaves the folder byte-identical.
- **Through a real door**: File ▸ Open of such a folder shows the dialog, and Recover everything opens the project with the criterion-3 announcement.
- **Dialog words**: `RecoveryDialogOptionsTest`'s promoted-interrupted-save case with no last clean save has the exact explanation, and no sentence mentions a backup.

## Non-Goals

- Temps of files other than `project.daw`, which the gate already handles (story 332).
- Read-only opens, which still touch nothing.

## Technical Notes

- Code locators: `RecoveryTemps.inspect` (its `Files.getLastModifiedTime(head)` call), `RecoveryScanner.plan` and its `newerHead`, `RecoveryDialog` (`recoverSentence`, `interruptedSaveSentence` and its Javadoc), `OpenRecoveryGate.restoreCheckpointOnly` and `recoverEverything`, `ProjectLifecycleController.writeRecoveredProjectFile` (it backs up only a file that exists).
- Ruled by the studio engineer in story 332's PR 988 review round 3: the H1 ruling's fourth deferral.
- Design references: Persistence Integrity design book §4.7 (the recovery gate), §5.5 (the recovery decision table); story 332's goal that the gate handles the temps of story 331's writer.
- Cross-refs: story 331 (`AtomicFileWriter`), story 332.

## Status

- Deferred
