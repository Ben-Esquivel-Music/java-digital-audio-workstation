---
title: "The Lifecycle Lock Contract Test Follows Control Flow"
labels: ["test", "audio-engine", "concurrency"]
---

# The Lifecycle Lock Contract Test Follows Control Flow

## Motivation

`AudioEngineLifecycleLockContractTest` decides what an `AudioEngine` method calls while it holds `lifecycleLock` by reading the method's instructions in bytecode order (`lockRegionElements`): an acquisition (`isAcquisition`: `lock`, `lockInterruptibly` or `tryLock`) opens the region, and the first `unlock` closes it (`if (name.equals("unlock")) { depth = Math.max(0, depth - 1); continue; }`). javac copies a `finally` before every exit of its `try`, so the first copy's `unlock` is not always the end of the code that runs under the lock:

- a call placed after an early `return` inside the `try` follows the first copy of the `finally` and is read as outside the lock;
- in a `try`/`catch`/`finally` acquirer, the `catch` handler follows the `finally`'s copy for the normal exit, so a call in the handler is read as outside the lock too — for example `bindFailedStreamStart(failure)` in the `catch` of `startAudioInputOutput` and of `startAudioOutputOwned`.

The test's own Javadoc names the early-return case, and story 337 moved `closeHaltedStream`'s body into `closeHaltedStreamLocked`, which `REQUIRED_ROOTS` names, to stay clear of it. Story 337's code review round found that nothing is missed today, because other acquirers reach the same callees inside their regions (`startAudioOutput`'s nested `try`/`catch` reaches `bindFailedStreamStart`), but a call added in either position would escape the check.

## Goals

- **The scan attributes every instruction that can run while the lock is held to the locked region**, on every path from the acquisition to its release: after an early return inside the `try`, in a `catch` handler, and in each copy of the `finally` up to its `unlock`.
- **Nothing that runs after the release joins the region**: the public wrappers' post-unlock `announcements.deliver()` stays outside it, and the refused branch of a `tryLock` acquirer stays as the test treats it today or is excluded.

## Acceptance Criteria

1. A fixture method with a call after an early `return` inside its `try` has that call reported as made under the lock.
2. A fixture method with a call in a `catch` handler of a `try`/`catch`/`finally` acquirer has that call reported as made under the lock.
3. The existing fixtures and the engine's own methods still pass, with every allow-list unchanged unless a call the old scan missed now shows, in which case the story names it.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- One new fixture per case above, each red against the current scan and green with the change.
- `AudioEngineLifecycleLockContractTest` stays green on `AudioEngine`.

## Non-Goals

- Changing what `AudioEngine` does under the lock, or its acquirers: future story 455 owns the FX thread's waits.

## Technical Notes

- daw-core test: `AudioEngineLifecycleLockContractTest` — `lockRegionElements`, `lockRegionCalls`, `isAcquisition`, `countAcquisitions`, `REQUIRED_ROOTS`, `everyLockAcquisitionIsPairedWithAnUnlock()`, and the class Javadoc's "Regions are read in bytecode order, not over a CFG".
- daw-core: `AudioEngine.startAudioInputOutput`, `startAudioOutputOwned`, `startAudioOutput`, `closeHaltedStream` and `closeHaltedStreamLocked`.
- The Class-File API (JEP 484, final in JDK 24) gives each method's exception table and labels.
- Cross-refs: story 337 (its code review round; ruling E69's `tryLock` acquirer).

## Status

- Deferred
