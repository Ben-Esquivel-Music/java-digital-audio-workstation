---
title: "Align Restore Checkpoint Only With What It Does"
labels: ["bug", "persistence", "crash-recovery", "ui"]
---

# Align Restore Checkpoint Only With What It Does

## Motivation

The recovery dialog's second choice is labelled "Restore checkpoint only", but it opens the last clean save, `project.daw`: neither a checkpoint nor the recovery point. Story 332's PR 988 review round 3 made the dialog's explanation say so (for example "Restore checkpoint only opens the last clean save and deletes the journal.", and for a checkpoint recovery point "Checkpoint #N is not deleted."), but the label still promises a checkpoint. The Project Manager design book's §4.6.1 mock shows the same promise: "Restore checkpoint #142 only — discard the 23 minutes since".

Making the choice open the recovery point instead is not the fix. Under today's replay, which rebuilds no journaled change (story 387), opening the recovery point without the journal produces exactly what Recover everything produces, so the two choices would become the same.

## Goals

- **The label says what the choice does.** The choice is relabelled to what it opens, for example "Open last clean save". Its `ButtonData` (`OTHER`) and its result (`RecoveryChoice.CHECKPOINT_ONLY`) are unchanged.
- **Every sentence that names the choice uses the new label**: the dialog's restore sentences, the interrupted-save sentences, and the refusal `OpenRecoveryGate.NO_HEAD_FOR_CHECKPOINT_ONLY`.
- **The design book agrees.** The Project Manager design book's §4.6.1 mock shows the new label and what the choice does.
- **The label changes once.** This story lands before the dialog's literals move to the resource bundle (story 382's extension), so the label and the tests that look it up change once.

## Acceptance Criteria

1. The dialog's second button shows the new label; its `ButtonData` and result are unchanged.
2. No user-facing sentence or message names "Restore checkpoint only".
3. The §4.6.1 mock matches the dialog.
4. No test looks up the old label.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Choices**: `RecoveryDialogOptionsTest`'s test of the four choices asserts the new label with `ButtonData.OTHER` and `CHECKPOINT_ONLY`.
- **No old label**: a scan of `daw-app`'s main sources and `Messages.properties` finds no "Restore checkpoint only", and finds the new label (so an empty or wrong scan cannot pass).
- **Lookups**: `EveryOpenDoorRunsRecoveryFirstTest`, `RecoverSessionIsTheOnePendingSwitchTest` and `LateLoadOutcomeReportingTest` answer the dialog by the new label and pass.

## Non-Goals

- What the choice does, which story 332 settled.
- Moving the dialog's words to the resource bundle (story 382's extension, after this story).
- Opening the recovery point, or anything about replay fidelity (story 387).

## Technical Notes

- Code locators: `RecoveryDialog` (`restoreCheckpointType`, the class Javadoc's list of choices, `restoreCheckpointButtonType`); `Messages.properties` (`recoveryDialog.explain.restore`, `restoreDeletesJournal`, `restoreKeepsJournal`, `restoreNoHead`, `interruptedSaveKeptByRestore`, `interruptedSaveKeptAside`); `OpenRecoveryGate.NO_HEAD_FOR_CHECKPOINT_ONLY`; `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md` §4.6.1 "Recovery dialog (shown when Recover is clicked from §4.1)"; the tests that look the label up: `EveryOpenDoorRunsRecoveryFirstTest`, `RecoverSessionIsTheOnePendingSwitchTest`, `LateLoadOutcomeReportingTest` and `RecoveryDialogOptionsTest`.
- Ruled by the studio engineer in story 332's PR 988 review round 3: the H1 ruling stated the truth in the explanation for that round and filed the relabel as a future story; the verification ordered it before the bundle move.
- Cross-refs: story 298, story 332, story 382 (`docs/user-stories/future/382-save-and-checkpoint-strings-come-from-the-resource-bundle.md`, its recovery-strings extension follows this story), story 387.

## Status

- Deferred
