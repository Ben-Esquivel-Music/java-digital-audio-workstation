---
title: "A Forced Journal Flush Reports Its Failure and Keeps the Records It Took"
labels: ["bug", "persistence", "journal", "failure-surfacing"]
---

# A Forced Journal Flush Reports Its Failure and Keeps the Records It Took

## Motivation

`JournalWriter.forceFlush()` drains every buffered record on a virtual thread named `daw-journal-force-flush` and joins it. Its Javadoc says that after it returns, count-in equals count-on-disk for all records offered before the call, and the comment in its catch block says the unchecked exception it throws is there "so the joining caller sees it". Neither holds when the flush fails or the caller is interrupted:

- An exception thrown on the flusher thread ends that thread. `Thread.join()` does not rethrow it, so `forceFlush()` returns normally, and the failure reaches only the thread's uncaught-exception handler.
- `drainUntilEmpty()` takes a batch out of the buffers (`takeBatch()`) and then appends it. When `appendBatchAndMaybeRotate` throws, that batch is neither put back nor appended again. The writer thread's own path retries a failed append (`appendWithRetry`, "Never lose the batch"); the forced flush does not.
- A caller interrupted while it joins returns at once, with the flush still running.

The Javadoc names this method the escape hatch for a disk that does not respond. No production path calls it yet; its first caller, `ProjectJournalCoordinator.flushOpenJournal()` (story 334), is called only by tests (story 433's listing barrier, through `ExitFixture.awaitJournalWrites()`). Once a surface uses it as the escape hatch, a failed flush would lose records and report success.

Found by the verification of story 334's round 6 (ruling R-T1) and deferred there: it is not on the path that ruling fixed.

## Goals

- A forced flush that fails throws to its caller, with the cause; one whose caller is interrupted says so to that caller.
- A batch a forced flush took and could not append is not lost: it is appended again, as the writer thread's retry does, or put back ahead of every later record.
- `ProjectJournalCoordinator.flushOpenJournal()` passes the failure on, and its Javadoc says what it guarantees when the flush fails.

## Acceptance Criteria

1. A forced flush whose append throws makes `forceFlush()` throw, and after a later successful flush the journal holds every record offered, once each, in sequence order.
2. A caller interrupted during `forceFlush()` never returns as if the records were on disk.
3. `flushOpenJournal()` throws when the flush fails.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- With a segment syncer that fails once, `forceFlush()` throws, and after the next successful flush the journal holds every offered record once.
- A `forceFlush()` caller interrupted while the flush is held does not return normally.
- Fault check, run once, turning a test red: the failed batch dropped again.

## Non-Goals

- A "flush now" control in the UI.
- The writer thread's, `sealAndClose`'s and `close()`'s own drains, which also call `drainUntilEmpty()`.

## Technical Notes

- daw-core: `JournalWriter.forceFlush`, `drainUntilEmpty`, `takeBatch`, `appendBatchAndMaybeRotate`, `appendWithRetry`; `JournalSegment.Syncer` (package-private, reachable from the journal package's tests).
- daw-app: `ProjectJournalCoordinator.flushOpenJournal`, `ExitFixture.awaitJournalWrites`.
- Cross-refs: story 298 (the journal), story 433, story 334 (ruling R-T1).

## Status

- Deferred
