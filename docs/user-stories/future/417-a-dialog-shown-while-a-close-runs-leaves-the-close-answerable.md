---
title: "A Dialog Shown While a Close Runs Leaves the Close Answerable"
labels: ["persistence", "ux", "accessibility"]
---

# A Dialog Shown While a Close Runs Leaves the Close Answerable

## Motivation

While a close of the open project runs, the window is input-inert (story 333): `WindowInputFence` fences every window shown while it is up unless it is one of the close's own marked surfaces or a window such a surface owns, so a window that work shows then is fenced, and at most its close box answers. A JavaFX dialog is application-modal by default (`Dialog`'s constructor runs `initModality(Modality.APPLICATION_MODAL)`), and showing an application-modal stage disables every other stage at the platform level: `WindowStage.setVisible` runs `windowsSetEnabled(false)` for `Modality.APPLICATION_MODAL`, and `GlassStage.windowsSetEnabled` calls `setPlatformEnabled` on every other window showing then. So a dialog that work shows after the Closing dialog appeared also disables the Closing dialog until it is closed: Don't quit and Quit anyway cannot be clicked or reached from the keyboard, and where the close waits for that dialog's answer, the exit cannot end except through the dialog's close box. A dialog that shows before the Closing dialog is disabled by the Closing dialog in turn, so the close stays answerable then; but it still answers an assistive technology's action, which reaches its buttons with no input event and so passes the fence, and a window that answer opens after the Closing dialog appeared disables the Closing dialog in its turn. Story 333's ruling R-1.6 (PR #989 review 5711460812) rules the category: while a close runs, work that lands on the FX thread — started before the close, or not by the user — shows no window of its own; a question takes the answer its Cancel gives, without being asked, a report goes to the log instead of a dialog, and the Welcome and Hub windows are not shown again. A dialog already up when the close begins still answers through the fence, but a window its answer would open while the close runs is not shown either: a question takes its Cancel, and a report goes to the log.

That round closed the instances it found where the close waits for the answer. Recover Session's recovery dialog, which an exit waiting on the pending switch would otherwise wait for, is answered with Cancel without being shown, logged at INFO, and the Welcome window is not shown again after it (`ProjectLifecycleController.recoveryRefusedWhileClosing`, read by `chooseRecoveryOnFx` and `presentRecovery`; `reshowWelcomeAfterRecovery`). The migration report of a gated open's install, which an exit waiting on that install would otherwise wait for, is not shown and goes to the log at INFO: Roll back is not offered, `project.daw` keeps its older version until the project is saved, and an open before then shows the report again (`ProjectLifecycleController.maybeShowMigrationReport`). The Import Summary of an Import DAWproject Exchange whose replacement an exit waits for is not shown and goes to the log at INFO with its text (`ProjectLifecycleController.installImportedSessionAfterClose`). The Project Locked dialog of a project another session holds, which work an exit waits for meets (a gated open, a recovery discard or a Save As copy taking its lock), is not shown: its Cancel is taken without asking and logged at INFO, so that work ends as its Cancel ends it — the open fails, the recovery journal is kept, or the copy stays written but unopened, in Recent Projects — and the lock is left to its holder (`LockConflictDialog.resolve`, which reads `ProjectLifecycleController.closingDoor`). A Save As that an exit waits for, whose target the plan refuses, shows neither its refusal nor its chooser again: the refusal goes to the log at INFO, and the Save As ends as its chooser's Cancel ends it, with nothing copied; a close's own Save As still asks again (`SaveAsFlow.targetRefused`, which reads `SaveAsFlow.Lifecycle.closingDoor`). A dialog of such work that was already up when the close began still answers through the fence, an assistive technology's action reaching it, but a window its answer would open while the close runs is not shown: the recovery dialog's Inspect events… shows no Captured events dialog, whose text goes to the log at INFO, and the recovery ends as its Cancel ends it (`ProjectLifecycleController.showInspectEvents`); the Project Locked dialog's Take Over (Steal Lock) asks no take-over confirmation, whose Cancel is taken and logged at INFO, so the lock is not taken over and that work ends as the Project Locked dialog's Cancel ends it (`LockConflictDialog.confirmTakeOver`); and the migration report's Roll back… asks no confirmation, whose Cancel is taken and logged at INFO, so nothing is rolled back and the report stays open (`MigrationReportDialog.confirmRollback`, which reads `ProjectLifecycleController.closingDoor`). A door's own unsaved-changes prompt that was already up when the close began, with no switch pending, opens nothing either: its Save or Discard, which would save or replace the project the close is closing or open the door's file dialog, is refused at FINE and does what its Cancel does (`ProjectLifecycleController.proceedUnlessCancelled`, `switchIfTheAnswerCovers`). The rest of the category still shows its dialog, whether or not the close waits for the answer:

- the insert rack's "Could not load plugin" Alert for a plug-in load that fails while the window is inert (`InsertEffectRack`, the load worker's failure branch, through `showPickerError`);
- the plug-in browser's install dialogs for a folder install that finishes then (`PluginBrowser.installFromFolder`, its FX continuation: `openInstallDialog` and the skipped-JARs `DawgDialog.info`);
- the missing-assets report window of an open that an exit waits to install (`OpenProjectAudio.announce` → `presentReport`, `MissingAssetsDialog.present`): non-modal, so the close stays answerable, but fenced, and it can take the focus from the Closing dialog; its notification's Review… can present it once the window is live;
- the floating panel windows shown when an open that an exit waits to install applies its project's layout (`ProjectLifecycleController.finishPreparedLoad` → `LayoutManager.fromJson` → `DockManager.applyLayout` → `MainController`'s dock host, `ensureFloating`): non-modal, so the close stays answerable, but fenced, and one shown then can take the focus from the Closing dialog;
- any other instance not found yet.

Story 333 records this as a known limit, "A dialog that work landing during a close shows", owned by this story.

## Goals

- **Every instance follows R-1.6**: while a close runs, a question takes its Cancel unasked, and a report goes to the log or to the in-window notification bar.
- **A sweep finds the instances.**
- **A guard keeps a later instance from disabling the close's surface.**

## Acceptance Criteria

1. While a close waits, a rack load that fails shows no Alert. Its failure is logged with the entry's class name.
2. A folder install that finishes while a close waits shows no install dialog.
3. A new FX continuation that shows a window without reading the gate fails the guard.
4. Outside a close, each dialog shows as before.
5. The story-333 known-limit line is made unconditional.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- On `ExitFixture`'s real close, past the surface: each landing adds no window, counted by a `Window.getWindows()` listener that hides what it counts on a later turn (ruling-4 H2).
- The same landing outside a close adds one window.
- Fault checks, each run once, each turning its test red: each instance's read of the gate, or of the lifecycle controller's `closing`, removed.

## Non-Goals

- The close's own marked surfaces.
- A dialog already up when the close begins, and what its own answer does: the raise fences it, the Closing dialog shows after it and disables it, so the close stays answerable, and an assistive technology's action still answers it through the fence. A window that answer would open while the close runs is not a Non-Goal: it is not shown, a question taking its Cancel and a report going to the log; story 333 closed the instances it found (Captured events, the take-over confirmation, the roll-back confirmation), and any other is this story's. What that answer does is not a Non-Goal either where it would start a project action of its own that the close does not wait for: story 333 refuses a door's own unsaved-changes prompt's Save or Discard at FINE while the close runs, and the door does what its Cancel does (ruling 8).

## Technical Notes

- Ruling (studio engineer, PR #989 review 5711460812, ruling 5 J1, R-1.6, ruling 6 R6-1, ruling 7, which amends R-1.6's case of a dialog already up when the close begins, and ruling 8, which bounds that case where its answer would start a project action of its own): the category, its outcome, and the scope of that round — the instances it found where the close waits for the answer closed there, the rest owned here.
- Two shapes for the guard:
  - (a) A source sentinel: an FX continuation of worker work (`postFx`, `Platform.runLater`, `FxDispatcher.runOnFx`) that reaches `show` or `showAndWait` reads the gate, or `closing`, first.
  - (b) A fence-level guard, which must answer the dialog with its Cancel and never hide its stage underneath it.
  - (a) is preferred.
- Code locators: `WindowInputFence` (`raise`, its watch for windows shown while it is up, `markCloseSurface`), `ClosingProgressDialog`, `ProjectLifecycleController` (`closing`, `recoveryRefusedWhileClosing`, `chooseRecoveryOnFx`, `presentRecovery`, `showInspectEvents`, `reshowWelcomeAfterRecovery`, `maybeShowMigrationReport`, `installImportedSessionAfterClose`, `closingDoor`, `proceedUnlessCancelled`, `switchIfTheAnswerCovers`), `LockConflictDialog` (`resolve`, `refusedWhileClosing`, `confirmTakeOver`, `takeOverRefusedWhileClosing`), `SaveAsFlow` (`targetRefused`, `Lifecycle.closingDoor`), `MigrationReportDialog` (`confirmRollback`, `showUnlessSuppressed`), `InsertEffectRack.showPickerError`, `PluginBrowser.installFromFolder`, `PluginInstallPanel.showDialog`, `MainController.projectActionsAllowed`; JavaFX's `Dialog` (its default modality), `WindowStage.setVisible` and `GlassStage.windowsSetEnabled`.
- Design references: Persistence Integrity book §2.7 "Exit is a protocol, not an event", §5.4 "The exit protocol (ordered)"; Failure Surfacing book §2.4 "Every dialog is dismissible, by construction".
- Cross-refs: story 333 (the exit protocol and its known limits), future story 398 (the application-stop fallback for pending switches and open prompts).

## Status

- Deferred
