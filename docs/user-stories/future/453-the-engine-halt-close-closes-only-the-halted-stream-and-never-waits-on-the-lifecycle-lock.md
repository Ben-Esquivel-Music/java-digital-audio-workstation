---
title: "The Engine-Halt Close Closes Only the Halted Stream and Never Waits on the Lifecycle Lock"
labels: ["bug", "audio-engine", "concurrency", "javafx", "reliability"]
---

# The Engine-Halt Close Closes Only the Halted Stream and Never Waits on the Lifecycle Lock

## Motivation

Story 337 closes the stream of a render drive that halted (its ruling E11, step 2). The window's `AudioHealthDrain` asks `HaltResponse.closeStreamForHalt()` on the halt's frame and, while a take defers the close, `retryCloseStreamForHalt()` on every later frame (ruling E66). Both reach `AudioEngine.stopAudioOutput()` on the FX thread, through `TransportController.closeStreamAfterEngineHalt()` and `retryCloseStreamAfterEngineHalt()`, and both skip the close only when `AudioEngine.isRenderDriveAlive()` reads `true` (`MainController.closeStreamForEngineHalt()` and `retryCloseStreamForEngineHalt()`).

A reopen can run on another thread meanwhile: Restart Audio Engine's virtual thread (`daw-audio-engine-restart`), Settings ▸ Apply's `settings-audio-reconfigure` worker, or the format-change reopen. While it opens a stream it holds the engine's `lifecycleLock`, and `isRenderDriveAlive()` reads `false` until its new pump runs. If the take's deferral ends in that window, the next frame's retry calls `stopAudioOutput()`, which waits for the lock on the FX thread until the open ends and then closes the stream that was just reopened. The restart then reads as failed (story 337, ruling E62) when the close lands before its second `isStreamOpen()` read, or leaves no stream open when it lands after; Play reopens it either way. Story 337 records this as a Known limit (its review round 2, finding CODE2-6).

## Goals

- **The halt close closes only the halted stream.** The halt record already carries the stream generation as its subject (`EngineStreamPump.halt` records `streamGeneration`), and `AudioEngine.getStreamGeneration()` reads the open one. The close names the halted generation; the engine checks it under `lifecycleLock` and closes nothing when it differs, so a stream reopened since the halt, by any thread, is never closed by the halt's close.
- **The FX thread never waits on the lifecycle lock for it.** When the lock is held, the close reports that it could not act yet, and the drain retries on the next frame, still scoped to the halted generation.
- **A reopen during an owed close is a successful restart.** A Restart Audio Engine whose reopen lands while the halt's close is owed leaves the reopened stream open, so ruling E62 reads it as a success and the engine-fault fact clears.

## Acceptance Criteria

1. With a halt close owed, a stream reopened on another thread is never closed by the drain, whether the drain's frame runs before, during or after the reopen.
2. No drain frame waits on `lifecycleLock`.
3. The halted stream is still closed once the take allows it (story 337, ruling E11 step 2), and a retry still never restarts a failed input cleanup (rulings E66 and E68).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A daw-core test: a generation-scoped close of a generation that is no longer open closes nothing; one of the open generation closes it.
- A daw-core test: while another thread holds `lifecycleLock` inside an open (a backend whose open blocks, as `AudioEngineOutputTest`'s fixtures do), the close returns at once, reporting that it could not act, and the reopened stream stays open after the open ends.
- A daw-app drain test: a retry that could not act keeps the close owed; a retry that finds a newer generation clears it.
- A real-wiring test or source pin that the window's halt response passes the halted generation.

## Non-Goals

- What the halt notice says, and the halt rules themselves: story 337.
- The FX thread's other `stopAudioOutput()` and `startAudioOutput()` callers, such as the user's Stop and Play.

## Technical Notes

- daw-app: `AudioHealthDrain` (`closeOwed`, `reactToHalt`, `closeTheHaltedStream`, `retryTheHaltedStreamClose`, `HaltResponse`); `MainController.closeStreamForEngineHalt()` and `retryCloseStreamForEngineHalt()`; `TransportController.closeStreamAfterEngineHalt()` and `retryCloseStreamAfterEngineHalt()`; `RecordCoordinator.isInputStreamStopDeferred()` (story 337, rulings E66 and E68).
- daw-core: `AudioEngine.stopAudioOutput()` takes `lifecycleLock`; `getStreamGeneration()`; `stopOwnedAudioStream(Object generation)` is the generation-scoped precedent, but it also stops the engine, which the halt close must not do; `isRenderDriveAlive()`; `EngineStreamPump.halt`.
- With the studio engineer, this story rules which generation the drain uses when the halt's sample was lost to a full lane ring, which counts the record without a sample.
- Cross-refs: story 337 (rulings E11, E62, E66 and E68; its Known limits); story 338, whose watchdog's restart offer reuses `MainController.restartAudioEngine()`.

## Status

- Resolved in story 337's code review round (story 337, ruling E69).

The round built this story's three Goals by another mechanism than the first Goal names. The fence is the halted pump's own halt fact, not the stream generation, because a resume keeps the generation and builds a new drive: `EngineStreamPump.hasHalted()`, read by `AudioEngine.isRenderDriveHalted()`, which `MainController`'s halt response asks before it stops, closes or retries anything. The close is `AudioEngine.closeHaltedStream()`, which takes `lifecycleLock` with `tryLock`, answers `HaltedStreamClose.LIFECYCLE_BUSY` at once while another lifecycle step holds it, so the drain keeps the close owed for its next frame, and asks `isRenderDriveHalted()` again with the lock held, answering `NOT_HALTED` for a stream reaped, replaced, paused or closed since. The halt's Stop closes nothing itself. The three Acceptance Criteria hold, the first save one narrow race that story 337 records as a Known limit (its ruling E97): an owed close can close a later stream whose own drive has halted too. The tests are `AudioEngineRenderDriveHaltTest`'s `closingTheHaltedStream…` tests and `aHaltedDriveReadsAsHaltedOnlyUntilItIsReapedPausedOrClosed`, `AudioHealthDrainTest.aHaltWhoseStreamWasAlreadyReplacedStopsAndClosesNothingAndShowsTheHaltWithoutAContextSentence`, `TransportControllerTest.anEngineHaltCloseLeavesAStreamWhoseDriveIsAliveOpen`, and `FailureSpineWiringSourceTest`'s pins of the window's fences. The Technical Notes' open question, which generation to use when the halt's sample was lost, no longer arises. The Non-Goal, the user's Stop and Play on the FX thread, is future story [455](455-stop-and-play-never-wait-on-the-engine-lifecycle-lock-behind-a-reopen.md).
