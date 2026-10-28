---
title: "The Project Lock's Heartbeat and Release Never Undo a Takeover"
labels: ["bug", "persistence", "locking"]
---

# The Project Lock's Heartbeat and Release Never Undo a Takeover

## Motivation

`ProjectLockManager.heartbeat()` reads `.project.lock`, compares its lockId with this session's, and then rewrites the file with a fresh `lastSeenAt` through the atomic writer (`ProjectLockManager.java:296-320`). `release()` reads the file, compares, and then deletes it (`:356-372`). `synchronized` orders these steps within this process only.

- If another process's `forceAcquire` lands between the heartbeat's read and its write, this session's refreshed lock overwrites it. The takeover is undone: the new holder's next lease check fails with "Lock has been taken over by <this session>", and this session carries on as if it had never been displaced.
- If the takeover lands between release's read and its delete, the new holder's lock is deleted. Its next check fails with "Lock file has been deleted…", and a third session can acquire the folder.

A takeover happens when someone in another session asks for the project's lock, for example by opening the project or by discarding its recovery journal from the Welcome screen, and chooses Take Over (Steal Lock) in the lock-conflict dialog and confirms it: for a lock that looks stale (not refreshed for more than 10 minutes), or, after a stronger warning, for a lock whose holder still looks active. A live session refreshes its lock every 30 seconds, so a takeover of an active lock can land inside one of those heartbeats. A session that was suspended or hung, and then resumes, runs its next heartbeat without knowing it was displaced. The lock is the protection that the class note advertises for local, SMB and NFS folders, where OS advisory locks are not relied on.

## Goals

- **A heartbeat never replaces a lock that it did not read as its own.** The Resolution chooses the mechanism, and it must work on local, SMB and NFS file systems without an OS advisory lock. For example, the heartbeat could refresh liveness in a per-holder file named by its lockId and never rewrite `.project.lock`, with staleness then read from the holder's file.
- **Release deletes only a lock file it read as its own.** For example, it could first move `.project.lock` to a name unique to this release (an atomic rename), read what it moved, delete it when it is ours, and move it back without replacing anything when it is not. The Resolution states the residual this leaves, such as the moment a foreign lock is moved away, and how the new holder's check treats that moment.
- **Staleness detection keeps working.** Staleness detection through `peekLock`, `isStale`, `ProjectHealthScanner` and the Hub keeps working, and lock files written by earlier versions are still read, judged stale by their `lastSeenAt`, and taken over.
- **The class note says so.** `ProjectLockManager`'s class note states the protocol and its residuals.

## Acceptance Criteria

1. A takeover that lands inside a heartbeat leaves the new holder's `.project.lock` byte-identical. `heartbeat()` returns false, this session's status is STOLEN, and the new holder's `verifyOurs()` passes.
2. A takeover that lands inside a release leaves the new holder's `.project.lock` byte-identical, and its `verifyOurs()` passes after the release returns.
3. Without a takeover, the heartbeat still advances liveness and release leaves no lock of this session's.
4. A stale lock file in today's format is still taken over.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Heartbeat race**: use the `LockFileReader` seam (the package-private constructor), or the first step of whatever heartbeat the Resolution builds. The read returns this session's lock, and a second `ProjectLockManager` with another identity force-acquires before the read returns. Assert criterion 1.
- **Release race**: the same seam on release's first step; assert criterion 2.
- **Compatibility**: a today-format lock that is stale by the fake clock is taken over.
- **Fault checks**: restoring the heartbeat's unconditional rewrite turns the heartbeat test red; restoring release's read-then-delete turns the release test red.

## Non-Goals

- The lease checks of a save (story 393) and of the recovered commit (story 332).
- This session's own lock hand-off on close (story 333).
- OS-level locks.

## Technical Notes

- Code locators: `ProjectLockManager.heartbeat`, `release`, `forceAcquire`, `verify`, `writeLockAtomically`, `readLock`, `peekLock`, `isStale`; `ProjectHealthScanner`; `LockStatusIndicator`.
- Story 333's goal "Nothing of this session writes or deletes in a folder after its lock is released" calls `heartbeat()` not synchronized. It now is `synchronized`, as `release()` is, and it returns false once nothing is held, so the in-process race that sentence describes is gone; story 333 should re-judge that sentence. The cross-process race is what this story owns.
- Ruled by the studio engineer in the first fix round of story 332's resolution of PR 988 review 5666358306, found by that round's verification.
- Design references: Persistence Integrity design book §5.3 (the open contract, its LOCK step) and §8 Stage 5 (the lock lifecycle, story 333).
- Cross-refs: story 331, story 332, story 333, story 393.

## Status

- Deferred
