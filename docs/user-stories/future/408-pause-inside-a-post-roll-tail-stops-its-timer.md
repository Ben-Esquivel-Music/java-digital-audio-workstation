---
title: "Pause Inside a Post-Roll Tail Stops Its Timer"
labels: ["bug", "recording", "transport"]
---

# Pause Inside a Post-Roll Tail Stops Its Timer

## Motivation

With post-roll on, the user's Stop keeps the transport playing for the tail and arms a timer that ends it (`TransportController.stop()` → `Transport.requestStop()`, then a `PauseTransition` in `postRollTimer` whose handler runs `finishPostRoll()` while `postRollTimer == timer`). Pause pressed inside that tail pauses the transport and leaves the tail half-ended:

- `TransportController.pause()` runs `core.pause()`, and `Transport.pause()` sets PAUSED. Neither touches the post-roll flag or `postRollTimer`, so the timer keeps running and `Transport.isInPostRoll()` stays true. `TransportController.finishPostRoll()`'s Javadoc states that a Pause inside the tail does not take the transport out of post-roll.
- When the timer fires, `finishPostRoll()` finds the flag still set and stops the paused transport. The transport goes PAUSED → STOPPED, the playhead returns to the play-start anchor when return-to-start is on, a `TransportEvent.Stopped` is published and the status bar reads "Stopped" — the tail's length after the user's Stop, with no further gesture from the user.
- Record with a count-in is allowed from that paused tail: `RecordCoordinator.countInScheduleFor` gives no count-in only over a PLAYING or RECORDING transport. The tail's timer is cancelled only when the take is announced (`RecordCoordinator.announceRecordingStarted` → `TransportController.cancelPostRollForRecording()`), after the count-in. So the old timer can fire during the count-in: the transport goes PAUSED → STOPPED, a spurious Stopped is announced and the status bar reads "Stopped" while the count-in is still clicking.

This is plausible from reading the code, not verified by a test. Found by the studio engineer's ruling on PR #989 review 5704604621 (story 333), out-of-scope item (b), and filed here because no open story owns it. Story 134, which introduced the post-roll, has landed, and story 328's count-in does not touch the tail's timer.

## Goals

- **Pause ends the tail's timer, or the timer respects the pause.** Which one is ruled by the studio engineer when the story is taken up: Pause inside a tail either ends the post-roll — its flag cleared and its timer stopped — or holds the timer and the tail resumes on Play.
- **A count-in from a paused tail is never interrupted.** No timer from an earlier tail fires during a count-in or during the take it starts.
- **A paused transport never stops by itself.** It stays PAUSED until the user acts.
- **The documentation is true.** `TransportController.finishPostRoll()`'s Javadoc and `stopForClose()`'s description of a paused tail match the behaviour this story lands.

## Acceptance Criteria

1. Post-roll on, Play, Stop (the tail plays), Pause: after the tail's length has passed, the transport is still PAUSED at the position where it paused, and no Stopped was published since the Pause.
2. The same, then Record with a count-in before the tail's length has passed: the count-in completes, the take records, and no Stopped and no "Stopped" status text appear between Record and the take's start.
3. `StopForCloseTest`, `TakeAtExitInEveryRecordingStateTest` and `PreRollPostRollControlsTest` stay green.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **A paused tail stays paused**: acceptance criterion 1 on the real `TransportController`, with a short post-roll (one bar at 240 BPM, 1 s) and a bounded wait past it, the guard longer than the inner wait.
- **A count-in from a paused tail**: acceptance criterion 2, the transport's STATE changes and the published `TransportEvent`s recorded as they fire.
- **Fault check**, run once: `TransportController.pause()` as it is before this story turns both tests red.

## Non-Goals

- Record inside a playing tail leaving the post-roll flag set (story 407).
- Pausing during a take, which stays refused (`TransportController.pause()` returns while a recording is in flight).
- Per-side pre-roll and post-roll flags (story 374).

## Technical Notes

- Code locators: `TransportController` — `pause()`, `stop()`, `finishPostRoll()`, `cancelPostRollForRecording()`, `stopForClose()`, the `postRollTimer` field; `Transport` — `pause()`, `requestStop()`, `finishPostRoll()`, `isInPostRoll()`; `RecordCoordinator` — `countInScheduleFor`, `announceRecordingStarted`; tests `StopForCloseTest`, `PreRollPostRollControlsTest`, `CountInRecordStartTest`.
- Ruled by the studio engineer on PR #989 review 5704604621 (story 333): out-of-scope item (b), plausible from reading the code and not verified by a test, story 134/328 territory.
- Design references: Recording Reliability design book §3.2 "The record state machine" and the as-landed count-in notes under "Stage 6 — Capture Workflows: Count-In, Punch, Loop Takes, Comping (story 328)".
- Cross-refs: story 134 (pre-roll and post-roll), story 236 (the transport's pre/post-roll controls), story 328 (count-in), story 333 (the close's Stop), story 374, story 407.

## Status

- Deferred
