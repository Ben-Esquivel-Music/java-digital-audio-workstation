---
title: "Record Inside a Post-Roll Tail Ends the Tail"
labels: ["bug", "recording", "transport"]
---

# Record Inside a Post-Roll Tail Ends the Tail

## Motivation

With post-roll on, the user's Stop keeps the transport playing for the tail (`Transport.requestStop()` sets the post-roll flag and goes to PLAYING). Record pressed while that tail plays starts a take, and the take records with the post-roll flag still set:

- `Transport.record()` sets the play-start anchor and the RECORDING state, and touches neither `inPostRoll` nor `inPreRoll`. Only `play()`, `stop()`, `playWithPreRoll()`, `clearPreRollPostRoll()` and `finishPostRoll()` clear the flag.
- The take's start cancels the tail's timer and nothing else: `RecordCoordinator.announceRecordingStarted` calls `TransportController.cancelPostRollForRecording()`, which stops and drops `postRollTimer`.

So for the whole take `Transport.isInPostRoll()` reads true, and so does `Transport.isInputCaptureGated()` (`inPreRoll || inPostRoll`), which says the input must not be captured.

This is latent today: `isInputCaptureGated` has no production consumer. The Recording Reliability design book says so ("`Transport.isInputCaptureGated` still has no consumer"), and story 328's as-landed notes leave pre/post-roll gating to story 134's surface. It turns live the day a capture path consults the flag: a take Record started inside a tail would be gated for its whole length and record silence. Code that reads the flag today has to work around it. Story 333's close (`TransportController.stopForClose`, PR #989 review 5704604621) must give such a take its Stop even though the transport reads "in post-roll", and must not rely on the flag being cleared.

One Javadoc already states the consumer as if it existed: `TransportController.playWithPreRoll` says the transport's `isInputCaptureGated()` flag is true during the pre-roll window and "the recording pipeline reads it to suppress capture". No production code reads it.

Found by the studio engineer's ruling on PR #989 review 5704604621 (story 333), out-of-scope item (a), and filed here because no open story owns it. Story 134, which introduced the flag, has landed.

## Goals

- **Record ends the tail.** When the transport enters RECORDING from a post-roll tail, the post-roll flag is cleared on that transition, so `isInPostRoll()` and `isInputCaptureGated()` read false while the take records. The tail's timer is cancelled as it is today.
- **Stop is unchanged for the user.** The take's Stop keeps its post-roll behaviour: an audio or mixed take stops the transport through its pipeline, and a MIDI-only take's Stop enters a new tail when a post-roll is configured.
- **The close still stops the take.** `TransportController.stopForClose` keeps its decision table. Its carve-out for a take still being prepared in post-roll, a running or a paused tail, is reviewed once Record no longer leaves the flag set, and its Javadoc and story 333's Design §3.8 bullet are kept true.
- **The documentation is true.** `TransportController.playWithPreRoll`'s Javadoc no longer names a consumer that does not exist. If this story adds a consumer instead, the Javadoc names it.

## Acceptance Criteria

1. Record pressed while a post-roll tail plays, audio, MIDI-only or mixed, leaves the transport RECORDING with `isInPostRoll()` and `isInputCaptureGated()` both false for the whole take.
2. The take's Stop behaves as before this story, for every take kind, with and without a post-roll configured.
3. Every `StopForCloseTest` and `TakeAtExitInEveryRecordingStateTest` take-in-tail test still gives the take its Stop and keeps it; their fixture checks that the post-roll flag is still set while the take records (`StopForCloseTest.recordInsideARunningPostRoll`, `TakeAtExitInEveryRecordingStateTest.recordInsideARunningPostRoll`) are changed to assert this story's criterion 1 instead. Any change to `stopForClose`'s carve-out is ruled by the studio engineer first.
4. No Javadoc claims a reader of `isInputCaptureGated()` that the code does not have.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Record inside the tail clears the flag**: post-roll on, Play, Stop (the tail plays), Record. Once the take records, `isInPostRoll()` and `isInputCaptureGated()` are false. One test each for an audio, a MIDI-only and a mixed take, on the real `TransportController` as `RecordingInFlightFixture` drives it.
- **Stop after it**: the take's Stop then gives the same transport states and the same single `TransportEvent.Stopped` as a take Record started outside any tail.
- **Fault check**, run once: `Transport.record()` without the clear turns the first test red.

## Non-Goals

- Adding a consumer of `isInputCaptureGated()` to the capture path. That is a separate decision about what pre-roll and post-roll capture.
- Pause inside a tail and its timer (story 408).
- Per-side pre-roll and post-roll flags (story 374).

## Technical Notes

- Code locators: `Transport` — `record()`, `requestStop()`, `finishPostRoll()`, `isInPostRoll()`, `isInputCaptureGated()`, the `inPostRoll` field; `TransportController` — `stopForClose()`, `finishPostRoll()`, `cancelPostRollForRecording()`, `playWithPreRoll()` (its Javadoc); `RecordCoordinator.announceRecordingStarted`; tests `StopForCloseTest`, `TakeAtExitInEveryRecordingStateTest`, `PreRollPostRollControlsTest`.
- Ruled by the studio engineer on PR #989 review 5704604621 (story 333): out-of-scope item (a), latent, story 134's pre/post-roll surface. `stopForClose` must not rely on the flag being cleared.
- Design references: Recording Reliability design book §3.2 "The record state machine", and §5.6 "Workflow gating contract", whose punch-in/out row notes that `Transport.isInputCaptureGated` still has no consumer.
- Cross-refs: story 134 (pre-roll and post-roll), story 236 (the transport's pre/post-roll controls), story 328 (count-in, punch and loop), story 333 (the close's Stop), story 374 (per-side flags), story 408.

## Status

- Deferred
