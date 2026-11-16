---
title: "The Halt Notice Says Recording Was Stopped Only for a Take It Ended"
labels: ["bug", "audio-engine", "recording", "diagnostics"]
---

# The Halt Notice Says Recording Was Stopped Only for a Take It Ended

## Motivation

When the render drive halts, story 337's audio health drain stops the transport and posts one notice: an ERROR, or the FATAL copy for a `VirtualMachineError`. The notice joins a base sentence, a context sentence and, in the ERROR, a next step (`FailureCopy.audioHalted` and `audioHaltedFatal`, which both take the context sentence from `haltContext(HaltContext)`). Before story 337's code review round built this story (Status), the context was chosen by `MainController.stopTransportForEngineHalt()` before the halt's Stop ran: `boolean recording = transport.isRecordingInFlight();`, then `return recording ? HaltContext.RECORDING : rolling ? HaltContext.PLAYBACK : HaltContext.IDLE;`.

`RecordCoordinator.isRecordingInFlight()` is `pendingStart != null || (recordingPipeline != null && recordingPipeline.isActive()) || !activeMidiRecorders.isEmpty()`. It answers `true` for a take still being prepared, for an audio or mixed take counting in, whose pipeline is installed and active before the count-in, and for a MIDI-only take counting in, whose recorders are anchored at the count-in's start. The halt's Stop, `TransportController.stopForEngineHalt()`, is the normal Stop, and it cancels such a take instead of ending it:

- a take still being prepared through `RecordCoordinator.cancelPendingStartByUser()`, which sets the status "Recording cancelled — no take was started";
- a count-in through the `CountInEnd.CANCELLED` branch of `RecordCoordinator.stopAudioTake()`, which sets "Count-in cancelled — no take was started".

The notice still carried `failure.audio.halted.recording`, "Recording was stopped; the take keeps what was recorded before the audio failed.", so it sent the engineer to look for a take that did not exist. When Record had been pressed during playback, the same Stop also stopped playback, and the notice did not say "Playback was stopped.". Nothing captured was lost: nothing had been captured. Story 337 recorded this as a Known limit, and its studio engineer ruled the copy (story 337, ruling E101). This story was written to build it.

## Goals

The requirement is story 337's ruling E101:

- **The context sentence says only what the halt's own Stop did**, in the notice's ERROR copy and its FATAL copy alike.
- It carries `failure.audio.halted.recording` ("Recording was stopped; the take keeps what was recorded before the audio failed.") exactly when that Stop ended a take that had started recording, meaning the take was past its preparation and any count-in. That covers audio, mixed and MIDI-only takes, punch-in and loop-record included, whether or not anything had been captured yet. A count-in that fired before the halt's Stop has started its take.
- Otherwise it carries `failure.audio.halted.playback` ("Playback was stopped.") when that Stop stopped a playing transport, a post-roll tail included, and that holds when the same Stop also cancelled a take still being prepared.
- It carries no context sentence when that Stop only cancelled a take still being prepared over a stopped transport, or cancelled a count-in (audio, mixed or MIDI-only), or when no Stop ran. No Stop runs when nothing was rolling, when a take was only being written or cleaned up after an earlier Stop, or when the halted stream was already replaced (story 337, ruling E70).
- The notice never carries the recording sentence for a take whose start or count-in the Stop cancelled, which the status bar reports as "no take was started". It never says "Playback was stopped." for a count-in or a stopped transport. It never leaves out the recording sentence when the Stop ended a started take.

## Constraints

- The halt's Stop does what it does today. It runs once, only while the open stream's drive is the drive that halted (story 337, ruling E69). It cancels a take still being prepared or counting in, ends a recording take through its normal finalization, keeping what it captured (ruling E11), and ends a post-roll at once (ruling E67).
- The halt closes no stream it does not own: its Stop closes nothing itself, and only the halt's own close closes the halted stream (ruling E69).
- No new notice and no new copy key. Each halt still posts one notice with its action (Restart Audio Engine, or Open Log Folder for the FATAL copy; ruling E13). Its base sentence (`failure.audio.halted` or `failure.audio.halted.named`, or `failure.fatal` or `failure.fatal.memory`) and its next step (`failure.audio.halted.next` or `failure.audio.halted.next.named`, none for the FATAL copy) are unchanged.

## Acceptance Criteria

1. A halt whose Stop ended a take that had started recording carries `failure.audio.halted.recording`: an audio, mixed or MIDI-only take, punch-in and loop-record included, with or without captured audio, and a take whose count-in fired before the Stop.
2. A halt whose Stop cancelled a take still being prepared while the transport was playing, after Record pressed during playback or inside a post-roll tail, carries `failure.audio.halted.playback`.
3. A halt whose Stop cancelled a take still being prepared over a stopped transport carries no context sentence, whether the halted drive is the stream open before Record or the take's own new stream.
4. A halt whose Stop cancelled a count-in, audio, mixed or MIDI-only, carries no context sentence.
5. With no take in flight, a halt whose Stop stopped playback, a post-roll tail included, carries `failure.audio.halted.playback`, and a halt with nothing rolling carries no context sentence. A halt while a take is only being written or cleaned up after an earlier Stop carries no context sentence, or `failure.audio.halted.playback` when its Stop stopped playback started meanwhile. A halt whose stream was already replaced carries no context sentence (story 337, ruling E70).
6. The FATAL copy of a `VirtualMachineError` halt carries the same context sentence, or none, as the ERROR copy in each of criteria 1 to 5.
7. Wherever the halt's Stop runs, it runs once and leaves the take cancelled or ended as it does today; in every case the halt closes no stream but the halted one, and exactly one halt notice is posted, with its base sentence, next step and action unchanged.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- For each take state below, a daw-app test that halts the render drive with the record coordinator really in that state, so that the test exercises the real choice of the context sentence, not a halt response that answers one, and asserts the notice's whole text:
  - a take being prepared over a stopped transport, its stream held open by a graph instrument: no context sentence;
  - a take being prepared after Record pressed during playback, with and without a post-roll configured: "Playback was stopped.";
  - a take being prepared whose own new stream halted before the take was ready, over a stopped transport: no context sentence;
  - an audio take, a mixed take and a MIDI-only take each counting in: no context sentence;
  - a take whose count-in fired before the halt's Stop: the recording sentence;
  - a recording audio take with nothing captured yet, one with captured audio, a punch-in take, a loop-record take, a mixed take and a MIDI-only take: the recording sentence;
  - playback with no take in flight, and a post-roll tail after a Stop: "Playback was stopped.";
  - nothing rolling and no take in flight, the stream held open by a graph instrument: no context sentence;
  - a take still being written after its Stop, and a cancelled start still being cleaned up, over a stopped transport: no context sentence.
- The same states with a `VirtualMachineError` halt: the FATAL copy with the same context sentence, or none.
- For each state in which the halt's Stop runs, a test that it ran once and left the take cancelled or ended as a Stop leaves it today; for each state, that the halt closed no stream but the halted one and that exactly one halt notice was posted.
- `AudioHealthDrainTest` (among them `aHaltWhoseStreamWasAlreadyReplacedStopsAndClosesNothingAndShowsTheHaltWithoutAContextSentence`), `TransportControllerTest` (among them `aStopForAnEngineHaltWhileATakeIsPreparedClosesNoStream`), `RecordStartPreparesTheTakeOffTheFxThreadTest`, `CountInRecordStartTest`, `FailureCopyTest` and `FailureSpineWiringSourceTest` stay green.

## Non-Goals

- The halt rules themselves (the Stop, the close, the halt fence and the latch): story 337.
- The status bar's texts.

## Technical Notes

- daw-app: `MainController.stopTransportForEngineHalt()`; `HaltContext`; `FailureCopy.audioHalted`, `audioHaltedFatal` and `haltContext`; `AudioHealthDrain.reactToHalt`; `RecordCoordinator.isRecordingInFlight()`, `isPreparingTake()`, `cancelPendingStartByUser()`, `stopAudioTake()` and `endCountIn()`; `TransportController.stopForEngineHalt()` and `stopTransport(Runnable)`.
- `Messages.properties`: `failure.audio.halted.playback` and `failure.audio.halted.recording`.
- `AudioHealthDrainTest`'s halt tests set the context through a scripted halt response (`ScriptedHaltResponse`), and `TransportControllerTest.aStopForAnEngineHaltWhileATakeIsPreparedClosesNoStream` drives only `TransportController.stopForEngineHalt()`, so no test pins the context a take being prepared or counting in gets today. `CountInRecordStartTest.aStopDuringAnAudioCountInCancelsItAndPublishesNoClip` and `aStopDuringAMidiOnlyCountInCancelsItAndNoTakeWasStarted` pin what the same Stop does without a halt.
- Cross-refs: story 337 (rulings E11, E13, E45, E67, E69, E70 and E101; its Known limits).

## Status

- Resolved in story 337's code review round (story 337, ruling E101).

The round built this story's Goals in the halt's own Stop rather than in `MainController.stopTransportForEngineHalt()`, which the Technical Notes name first. `TransportController.stopForEngineHalt()` now answers the `HaltContext` of what its Stop did: `RECORDING` when that Stop ended a take that had started recording, as `RecordCoordinator.stopAudioTake()` now reports it, read after the Stop has reconciled any count-in, so a count-in that fired before the Stop counts as started; otherwise `PLAYBACK` when the transport was not stopped and the Stop cancelled no count-in, which it reads before the Stop on the same FX turn; otherwise `IDLE`. It runs its Stop exactly when the window ran it before, when the transport is rolling or a take is in flight, and `MainController.stopTransportForEngineHalt()` keeps its halted-drive fence and null checks and hands that answer on. `HaltContext`, `FailureCopy` and `AudioHealthDrain` changed in their Javadoc only; no copy key and no notice was added. Story 337's Verification (its code review round's ruling E101 bullet) gives the tests and their runs. The Goals — Tests were not all built as worded:

- Only one test halts the render drive for real and reads the notice's whole text, in the ERROR and the FATAL copy: `CountInRecordStartTest.aRealHaltDuringACountInShowsTheHaltNoticeWithNoContextSentence`, for a MIDI-only take counting in. A real halt in a test throws from the engine's recording callback, which an audio take's capture also uses. Every other state is driven through the halt's own Stop on a real `RecordCoordinator` in that state, asserting the context it answers, and the text for each context is pinned by `FailureCopyTest` in both copies and by `AudioHealthDrainTest`.
- A take being prepared over a stopped transport is tested with the take's own new stream as the halted drive (`TransportControllerTest.aStopForAnEngineHaltWhileATakeIsPreparedClosesNoStream`). The case whose stream a graph instrument holds, before the take's stream opened, has no test of its own: the existing test seams cannot hold that window, and the choice reads no stream fact.
- Nothing rolling and no take in flight is tested with an audition stream, not a graph instrument (`TransportControllerTest.aStopForAnEngineHaltOverAStoppedTransportClosesNoStream`); the choice is the same.
- A recording audio take with nothing captured yet is tested as a punch-in take before its punch-in point and a loop-record take that waits for no block (`TransportControllerTest.aStopForAnEngineHaltEndsARecordingAudioTakeAndAnswersThatRecordingWasStopped`), not as a take whose blocks failed from the first render, which needs a real halt.
- The FATAL copy is read from a real state only for that count-in; for the other contexts it is pinned through `FailureCopyTest` and `AudioHealthDrainTest`.
- That the Stop ran once and closed no stream is asserted per state at the halt's Stop; exactly one notice, and the close of the halted stream, only in the real-wiring test, and for the drain in `AudioHealthDrainTest`.

The Technical Notes describe the code before the round: no test then pinned the context a take being prepared or counting in got. Ruling E101 as first written did not name a paused transport. Story 337's studio engineer ruled it in the acceptance read of the build, as built: a paused transport is held playback, which the halt's Stop ends. So a halt over one answers "Playback was stopped." in two cases. With no take in flight, the stream then held by a graph instrument (without one, Pause pauses the stream and a halt reads as replaced). With a take being prepared after Record over the paused transport, whichever drive halted. A count-in over a paused transport answers no context sentence.
