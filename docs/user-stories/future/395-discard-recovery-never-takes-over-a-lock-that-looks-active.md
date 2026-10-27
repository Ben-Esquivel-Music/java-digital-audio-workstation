---
title: "Discard Recovery Never Takes Over a Lock That Looks Active"
labels: ["bug", "persistence", "locking", "crash-recovery", "project-hub"]
---

# Discard Recovery Never Takes Over a Lock That Looks Active

## Motivation

When a project's recovery journal owes a decision, the Welcome screen lists the project under Recover, with a Recover session button and a Discard recovery button (`WelcomeView.java:250-261`, `ProjectHealthScanner.isRecoverable`, `ProjectHealthScanner.java:174-188`). For a project that has a journal, that rule reads the journal, not the lock. A session that is journaling the project keeps its journal unsealed until it closes, so a project that is open right now in another session can be listed too. That is deliberate. The seal is a positive fact (Persistence Integrity design book §2.6), and a lock can still look active for up to 10 minutes after its session crashed, as long as the holder's clock agrees with this computer's (`ProjectLockManager.STALE_THRESHOLD`; §1.6).

Discard recovery asks the user to confirm, then takes the project's lock and deletes `journal/` (`ProjectLifecycleController.discardRecovery` and `discardConfirmedRecovery`, `ProjectLifecycleController.java:3766-3824`). It takes the lock through `ProjectManager.prepareLockedAccess`, the same negotiation an open uses. So when another session holds the lock, the lock-conflict dialog offers Take Over (Steal Lock) whether or not the lock looks stale. If the user accepts the stronger warning for an active lock, Discard takes over the folder and deletes the running session's journal while that session is still writing it. That journal is the session's protection against a crash. (The file system may not let every file go at once while the session holds a segment open.) The running session finds out that its lock was taken at its next heartbeat or save, and by then its journal may already be gone.

An open can take over the same lock, but it orders the steps differently. It decides the lock first and then runs the recovery gate, so the recovery dialog describes the journal as it is after the lock decision (`ProjectManager.prepareOpen`; §5.3, LOCK before RECOVER; §5.5's last row, "lock wins", after which the recovery gate re-runs). Discard runs no gate. It acts on a confirmation the user gave before the lock was decided, and that Welcome screen may have been built before the other session opened the project. Recovery must not depend on which door the user walked through (§2.6).

## Goals

- **Discard recovery never takes over a lock that looks active.** When another session holds the project's lock and the lock is not stale, Discard deletes nothing and takes over nothing. It shows one WARNING. The WARNING names the holder (user@host) and says when the lock was last refreshed. It says the journal was kept, and that Recover session opens the project, where the lock is decided first and the recovery dialog then describes the journal as it is. The Resolution chooses the mechanism. For example, Discard could negotiate its lock with a conflict policy of its own that offers Take Over only for a stale lock.
- **A stale lock is still taken over.** For a lock not refreshed for more than 10 minutes, Discard keeps today's behaviour: the lock-conflict dialog offers Take Over, and the journal is deleted under the lock Discard took. Open Read-Only and Cancel still keep the journal.
- **The residual is stated.** Staleness is a timing heuristic that misclassifies in both directions (design book §9 item 6), so a lock can look stale while its session still runs. The holder can refresh a stale lock between Discard's read of it and the takeover, for example when a suspended computer wakes. And a holder whose clock runs more than 10 minutes behind this computer's writes a lock that looks stale the whole time it runs; on a shared network volume the holder is often another computer. The Resolution states both windows and what Discard does in each, for example reading the lock again immediately before the takeover, or treating a lock whose last-refreshed time changes while Discard waits as active, whatever the clocks say.
- **The book says so.** §5.5 states that Discard recovery follows its last row: when a session that looks active holds the lock, the lock wins and Discard points to Recover session.

## Acceptance Criteria

1. Suppose another session holds the project's lock, the lock is not stale, and the journal is unsealed. Discard recovery then leaves `.project.lock`, `journal/` and `project.daw` byte-identical, even when the lock-conflict handler answers Take Over, and shows one WARNING that names the holder, says when the lock was last refreshed, says the journal was kept, and points to Recover session.
2. With the same lock stale, Discard takes it over and deletes `journal/`, as today.
3. Open Read-Only and Cancel leave `.project.lock` and `journal/` byte-identical, as today.
4. For the project in criterion 1, Recover session still reaches the lock-conflict dialog, and after Take Over the recovery dialog is built from the journal as it is after the takeover.
5. §5.5 describes the behaviour the code implements.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Active lock**: a second `ProjectLockManager` with another identity holds the folder with a fresh lock, beside an unsealed journal. Drive `discardConfirmedRecovery` with a lock-conflict handler that answers Take Over to every conflict, and assert criterion 1. This test replaces `LockedRecoveryDiscardTest.explicitTakeoverAllowsDiscardOfAFreshCrashedSession`, which asserts today's takeover; its new name does not call the holder crashed, because a fresh lock cannot be told apart from a running session's.
- **Stale lock**: the same setup, with the lock written by a manager whose clock is set back by more than `STALE_THRESHOLD`; assert criterion 2.
- **Read-only and Cancel**: criterion 3, one case for each answer. `LockedRecoveryDiscardTest.foreignWritableSessionKeepsItsEvidenceWhenDiscardIsNotWritable` already runs both answers against a fresh lock held by another lock manager and checks the journal's files; it gains a byte comparison of `.project.lock`.
- **Open door**: criterion 4, through the gated open (`enableRecoveryGate`), with the recovery chooser recording the summary it is shown.
- **Fault check**: letting Discard take over an active lock again turns the active-lock test red.

## Non-Goals

- Which projects the Welcome screen lists under Recover. For a project with a journal, the row follows the journal, whose seal is a positive fact (§2.6); a project with no journal is listed by a stale lock, and a lock can look active for up to 10 minutes after its session crashed (§1.6). Story 389 owns the detection rule.
- The open path's lock negotiation and the lock-conflict dialog's wording (story 187).
- The heartbeat and release races (story 394) and a save's lease checks (story 393).

## Technical Notes

- Code locators: `ProjectLifecycleController.discardRecovery`, `discardConfirmedRecovery`, `deleteJournalDirectory`; `ProjectManager.prepareLockedAccess`, `acquireTarget`, `prepareOpen`; `LockConflictDialog`; `ProjectLockManager.tryAcquire`, `forceAcquire`, `isStale`, `STALE_THRESHOLD`; `ProjectHealthScanner.isRecoverable`; `WelcomeView` (the Discard recovery button); `LockedRecoveryDiscardTest`.
- The refusal's common case: a session that crashed less than 10 minutes ago leaves a lock that still looks active. Discard refuses and points to Recover session. There the user can take over after the stronger warning and then choose Restore checkpoint only in the recovery dialog. That door is not safer for a session that is in fact still running: after Take Over, Restore checkpoint only deletes that session's journal too, and Recover everything writes the journal's edits into `project.daw` before it clears the journal. What that door adds is the order §5.5's last row requires: the user decides about the journal after the lock is decided, from a dialog that describes the journal as it is then. The Resolution may treat a lock written on this computer by a process that is no longer running as not active; if it does, it states how it handles a reused process id.
- Ruled by the studio engineer in the prose verification of story 394's wording, during story 332's resolution of PR 988 review 5666358306. The behaviour predates that resolution, which did not change it. This story deliberately reverses one part of an earlier resolution: story 332's row for PR 988 review comment 4382168542, "discard deletes a live journal", which says "explicit Take Over remains available for fresh crash locks". The 4382168542 resolution gave Discard its lock negotiation (before it, Discard deleted `journal/` holding no lock at all) and kept Take Over for a fresh lock so that a session that crashed minutes ago could still be cleared. But a fresh lock cannot be told apart from a running session's: the holder refreshes it every 30 seconds (`ProjectLockManager.HEARTBEAT_INTERVAL`), and nothing checks whether the holder still runs. The stronger take-over warning asks the user to be sure the other session is no longer in use, but it speaks of concurrent saves, not of deleting that session's journal. What the row kept stays available through Recover session, which reaches the same Take Over and then offers Restore checkpoint only. `LockedRecoveryDiscardTest.explicitTakeoverAllowsDiscardOfAFreshCrashedSession` pins today's takeover and is replaced by the active-lock test; a resolver who sees it turn red is seeing this story's change, not a regression. `LockedRecoveryDiscardTest.foreignWritableSessionKeepsItsEvidenceWhenDiscardIsNotWritable` already covers criterion 3 for the journal.
- Design references: Persistence Integrity design book §2.6 (every open is a potential recovery), §5.3 (the open contract, its LOCK and RECOVER steps), §5.5 (the recovery decision table, its last row); §1.6 and §9 item 6 (the 10-minute stale-lock window, a timing heuristic).
- Cross-refs: story 187, story 298, story 332, story 389, story 393, story 394.

## Status

- Deferred
