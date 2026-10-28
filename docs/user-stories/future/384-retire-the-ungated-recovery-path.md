---
title: "Retire the Ungated Recovery Path"
labels: ["bug", "persistence", "crash-recovery", "project-load"]
---

# Retire the Ungated Recovery Path

## Motivation

Story 332 put every disk open behind one recovery gate. `MainController` always calls `ProjectLifecycleController.enableRecoveryGate`, and from then on Recover Session, Continue, Hub, File ▸ Open and archive restore all reach `OpenRecoveryGate.run` inside `ProjectManager.prepareOpen`, on the open's worker. The controller still keeps the path it used before the gate, which it takes only when `enableRecoveryGate` was never called. Production never takes it:

- `beginRecovery`'s ungated branch: `scanForRecovery` → `presentRecovery` → `replayThenOpen` → `replayJournal`;
- `presentRecovery`'s Restore checkpoint only: `discardJournalThenLoad`;
- the ungated branch of `loadAttempt`, which abandons the open project and then calls `ProjectManager.openProject` with no gate;
- `openRecoveredProject`, which announces through `new OpenDoor.JournalReplay(applied, 0, replayUnread)`.

Tests still drive this path or pin its source instead of the gate, for example `FailedPublishLeavesTheProjectFileWholeTest`, `UnreadItemsQualifyTheOpenAnnouncementTest`, `RecoverSessionIsTheOnePendingSwitchTest`, `LateLoadOutcomeReportingTest`, `MissingAssetsOnOpenTest`, `ProjectChangeWhileRecordingTest` and `ProjectChangeWhileTakeIsWrittenTest`, which drive it; `LateSwitchWaitsForAppWritesTest`, which calls `openRecoveredProject` directly; `MigrationRollBackAndRecoveryGuardTest`, which calls `beginRecovery` on a controller with no gate; and `SourcePinnedWiringTest`, which pins the body of `discardJournalThenLoad`. The implementer finds the complete set by searching the tests for the methods this story removes and for `beginRecovery` on a controller without `enableRecoveryGate`.

Story 332's PR 988 review round 3 gave this path the new `RecoveryDialog` body, but its actions do not do what that dialog says. The round's verification found these contradictions (the last was recorded later, by story 332's resolution of PR 988 review 5666358306):

- Its Restore checkpoint only deletes `journal/` recursively, then loads a `project.daw` that may be missing, while the dialog says "Restore checkpoint only cannot open this project because project.daw is missing; the journal is kept if you choose it."
- Its Recover everything plans the replay again on its own (`new JournalReplayer().recover(ctx)`) instead of executing the plan the dialog described, and the plan it shows reads no atomic-writer temps: `scanForRecovery` passes `new RecoveryTemps(List.of(), null)`.
- After a failed replay or a refused write it opens the last clean save writable, where the dialog and story 332 say the fallback is read-only.
- It still announces "Recovered N changes from the journal" for changes the dialog said would not be in the recovered project.
- Its Recover everything replaces the head with no lease check before the replace: `replayJournal` hands `writeRecoveredProjectFile` a no-op check (`ProjectLifecycleController.java:3589`), where the gate's commit verifies the open's lease after its backup, immediately before the replace.

None of this reaches a user today. But a test that passes on this path proves nothing about production, and these classes pin behaviour that production no longer has.

## Goals

- **No recovery runs outside the gate.** Remove `beginRecovery`'s ungated branch, the methods only it reaches, and the ungated branch of `loadAttempt`. A controller that was never given a gate does not recover some other way; the Resolution states whether the gate becomes construction-time wiring or such a controller refuses Recover Session.
- **The tests drive the gate.** Every test that drives this path uses `enableRecoveryGate`, and every source pin on a removed method moves to the gated path or goes with it. Each behaviour they pin is pinned on the gated path where production has it, and the Resolution names, with the reason, each one that goes with the ungated path.
- **Absence is `Optional`.** Once the null-passing call is gone, `RecoveryTemps.newerHead` becomes `Optional<Path>`, the house convention for absence, and the tests that build `RecoveryTemps` by hand (for example `RecoveryPlanTest`) follow.
- **Story 380 follows the gated path.** Story 380's code locators and binding tests name this path; they are re-pointed at the gated path, or 380 lands after this story (see 380's sequencing note).

## Acceptance Criteria

1. `ProjectLifecycleController` has no `scanForRecovery`, `presentRecovery`, `replayThenOpen`, `replayJournal` or `discardJournalThenLoad`, and no load that bypasses `OpenRecoveryGate`.
2. Every test that drove this path calls `enableRecoveryGate`, every source pin on a removed method is moved to the gated path or removed, and the Resolution lists each assertion that was moved to the gated path or removed, with the reason.
3. Through `beginRecovery` with the gate, Restore checkpoint only on a project with an owed journal and no `project.daw` leaves `journal/` byte-identical and shows one ERROR that names the refusal.
4. Through `beginRecovery` with the gate, a Recover everything whose replay cannot start opens the last clean save read-only, opens no journal and leaves the evidence byte-identical.
5. `RecoveryTemps.newerHead()` returns `Optional<Path>`, and no caller passes `null`.
6. Story 380's Technical Notes name only gated-path methods, or its sequencing note still says it lands after this story.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **No ungated recovery**: a source scan of `ProjectLifecycleController` finds none of the removed method names, and finds `beginGatedLoad` (so an empty or wrong file cannot pass).
- **Restore without `project.daw` keeps the journal**: criterion 3, through Recover Session with the gate enabled.
- **A failed replay opens read-only**: criterion 4, with the session base changed after the session opened.
- **Migrated suites**: every such test passes with the gate enabled.
- **Announcement**: a Recover everything through `beginRecovery` that replays changes it cannot rebuild shows the WARNING "Recovered the project from its recovery point; …could not be rebuilt", never "Recovered N changes from the journal".

## Non-Goals

- What Recover Session asks about unsaved changes (story 380).
- The read-only fallback's wording (story 385).
- The recovery dialog's content and the gate's own behaviour (story 332).

## Technical Notes

- Code locators: `ProjectLifecycleController` — `beginRecovery`, `scanForRecovery`, `presentRecovery`, `replayThenOpen`, `replayJournal`, `discardJournalThenLoad`, `openRecoveredProject`, `openLastCleanSaveAfterFailedScan`, `openLastCleanSaveAfterRecoveredWriteFailed`, `openLastCleanSaveAfterFailedReplay`, the ungated branch of `loadAttempt`, the worker names `RECOVERY_DISCARD_WORKER` and `RECOVERY_REPLAY_WORKER` (only this path starts those workers), and `enableRecoveryGate`; `MainController` (its `enableRecoveryGate` call); `RecoveryTemps` and `RecoveryScanner.plan`.
- The gated path: `beginRecovery` → `whenNoAppWriteIsPending` → `loadProjectFromPath` → `loadAttempt` → `beginGatedLoad` → `ProjectManager.prepareOpen` → `OpenRecoveryGate.run`.
- Ruled by the studio engineer in story 332's PR 988 review round 3: the CI ruling left this path's code alone and deferred its retirement; the verification recorded the dialog contradictions above and the null `newerHead` call.
- Design references: Persistence Integrity design book §4.7 (the recovery gate), §5.3 (the open contract), §5.5 (the recovery decision table).
- Cross-refs: story 298, story 332, story 380 (`docs/user-stories/future/380-recover-session-asks-about-unsaved-changes.md`). Boundary: this story removes the ungated path and moves its tests to the gate; story 380 adds the unsaved-changes prompts, on the gated path.

## Status

- Deferred
