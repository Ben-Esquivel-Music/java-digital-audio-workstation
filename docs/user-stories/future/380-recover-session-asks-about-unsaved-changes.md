---
title: "Recover Session Asks About Unsaved Changes"
labels: ["bug", "persistence", "crash-recovery", "project-load"]
---

# Recover Session Asks About Unsaved Changes

## Motivation

Recover Session replaces the open project without asking about its unsaved changes, and it does so after a gap in which the user can keep editing. `ProjectLifecycleController.beginRecovery` refuses only while another switch is pending or a recording or a take is in progress, then starts the recovery scan on a worker; it shows no unsaved-changes prompt (the same on `main` before story 331). Every load the recovery ends with — the recovered project, the checkpoint-only load, and the last clean save after a failed scan, a failed write or a failed replay — passes `whenNoAppWriteIsPending`, which refuses a pending switch, a recording or a take being written, and waits for the application's saves, but never looks at the dirty flag. The main window stays live through the scan, the `RecoveryDialog`, the journal's delete and the replay; Recover is offered from the Welcome window, which does not block the main window either. So an edit made to the project on screen before Recover, or while it runs, is replaced with no prompt.

Story 331 fixed the same gap for Restore from Archive (ruling K3): the door records the open project and its edit revision when it is answered, and the late load asks again — `The archive was restored to <destination>. Do you want to save before opening it?` — when the project is dirty and is no longer that instance at that revision. The studio engineer ruled there that the recovery loads must not take K3's rule in story 331 and recorded "re-judge recovery late loads against edits made during the scan" as a future story.

## Goals

- **Recover asks before it starts.** Recover Session passes the same close judgment as the other doors — the take refusal, then, for a dirty project, Save, Discard or Cancel — and records the answer: the open project and its edit revision.
- **Each late load asks again about edits made since** (K3's rule): when the project is dirty and is not the same instance at the same revision, the load asks before it replaces it. Save is the one save request, and the load runs again once it is `Saved`; Discard loads; Cancel, a failed save or a cancelled location prompt leaves the open project and says what the recovery already did and where — the recovered changes written to `project.daw`, the journal discarded — with a way to open the project later.
- **No edit, no second prompt.** The same instance at the same revision loads without asking.

## Acceptance Criteria

1. Recover Session on a dirty project asks Save / Discard / Cancel before the scan starts; Cancel starts nothing.
2. An edit made while the scan, the `RecoveryDialog`, the journal's delete or the replay runs is asked about before the recovered or last-clean project replaces the open one.
3. Cancel at that late prompt leaves the open project and its edit, keeps what the recovery wrote, and shows one message saying what was done and how to open it.
4. With no edit since the answer, no second prompt is shown.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Asked at the start**: a dirty project, Recover, Cancel: no recovery worker starts and the edit is still in memory.
- **Edit during the scan**: with the scan held (`setBeforeRecoveryWork`), an edit; on release the late load asks; Discard loads the recovered project; Cancel keeps the edit and shows the message.
- **Edit during the replay**: the same with REPLAY_ALL's replay held; after Cancel the recovered `project.daw` is on disk and the message names it.
- **No edit**: Recover on a clean project, no edit during the scan: the recovered project opens with no prompt.

## Non-Goals

- The recovery gate on every open path, the session seal and default-on journaling (story 332).
- The recovery dialog's choices and the replay itself (story 298).

## Technical Notes

- Code locators: `ProjectLifecycleController.beginRecovery`, `scanForRecovery`, `presentRecovery`, `openRecoveredProject`, `openLastCleanSaveAfterFailedScan`, `openLastCleanSaveAfterRecoveredWriteFailed`, `openLastCleanSaveAfterFailedReplay`, `whenNoAppWriteIsPending`, `whenProjectMayClose`; K3's `DoorAnswer`, `judgeTheAnsweredSwitch` and `switchIfTheAnswerCovers`; the test seam `setBeforeRecoveryWork`.
- Ruled by the studio engineer in story 331: ruling K3 (re-judge a late load against edits made since the answer) and its "must not do" list, which leaves the recovery loads to a future story; ruling L3 made Recover Session the one pending switch while it runs.
- Design references: Persistence Integrity design book §4.7 (the recovery gate), §5.3 (the open contract).
- Cross-refs: story 298, story 331, story 332.

## Status

- Deferred
