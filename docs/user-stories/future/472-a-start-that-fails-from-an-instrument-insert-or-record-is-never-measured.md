---
title: "A Start That Fails from an Instrument Insert or Record Is Never Measured"
labels: ["bug", "audio-engine", "ui", "concurrency", "asio"]
---

# A Start That Fails from an Instrument Insert or Record Is Never Measured

## Motivation

The transport bar's clock badge, `ClockStatusIndicator`, reads the open stream's lock status on every FX pulse and notices a new stream by the serial of the engine's `OpenedStream`, which the engine publishes before the stream reads as open. A stream that opens and then fails to start is, for the badge, a stream that opened and closed at once: it ends in the dim closed view of the device that stream opened, or hidden (story 338, ruling E143). Most paths that start a stream cannot show more than that: Play and pre-roll run on the FX thread, so no pulse runs during the start, and a Settings apply, the driver's format-change reopen and Reconnect hold the engine controller's monitor across the restart, so the badge's read, which waits on that monitor (`DefaultAudioEngineController.clockSources` is `synchronized`), lands on the closed stream.

Two paths start the stream off the FX thread without that monitor: putting an instrument on a channel while the transport is stopped (`MainController.pluginGraphChanged`, on the virtual thread `plugin-audio-output`), and pressing Record (the record coordinator's `daw-record-input-check` thread). Story 338 accepted them as a recorded limit, in its studio engineer's words (ruling E146):

"Two actions start the audio stream without waiting for the engine controller: putting an instrument on a channel while the transport is stopped, and pressing Record. If such a start fails after the stream has opened, the clock badge can show the stream's live view (checking, or bright for an internal clock) until the failure lands, and then settles as after any failed start. Nothing in that stretch waits on the driver, so in practice this is at most a flicker beside the 'could not be started' notice. 'UNLOCKED' and an 'External clock lost' notice would need the start to run on for two seconds after the open. No hardware is known to do that, and a review probe produced it only by stretching the start and injecting the status. Even then no take has begun, so nothing is marked and nothing is written to disk."

## Goals

- A stream started by putting an instrument on a channel or by pressing Record, whose start fails after its open, never shows a measured clock view, a clock notice or a take mark.

## Constraints

These four must never happen, on any path (ruling E146):

- a take mark or a manifest clock event for a recording that did not begin;
- a clock notice that says "Recording continues" or "the take is marked" for a recording that did not begin;
- the measured or UNLOCKED view after the failure has landed: the end state is ruling E143's, the dim view of the device that stream opened, or hidden;
- the clock of a device that stream did not open.

And:

- No lock is taken on the FX pulse: the badge's look stays lock-free and makes no driver call.
- No deadlock with the engine's lifecycle lock.
- Ruling E143 stands on every other path: Play, pre-roll, Apply, the format-change reopen and Reconnect.

## Acceptance Criteria

1. On each of the two paths, a start that fails after its open, stretched to 1.5 s with a CHECKING status and with an UNLOCKED status inside the failing start, gives the starting view and then the dim closed view, and with an internal source, the starting view and then the dim closed view, never the bright view; each with one read, no notice and no mark.
2. On each of the two paths, a start that fails at production timing ends as ruling E143 rules, with no notice and no mark.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- For each path, the stretched failing start of story 338's review probe `probeAnUnguardedOffFxStartThatFails` (a refusing backend that holds its start open, sets the lock status inside it and then throws; the probe is not in the tree): the starting view, then the dim closed view, one stream read, no notice, no mark.
- `ClockBadgeDuringATakeTest`, `ClockStatusIndicatorTest`, `TakeClockMarksOfTheTransportTest`, `AudioEngineOpenedStreamTest` and `AudioEngineLifecycleLockContractTest` stay green.

## Non-Goals

- The failing start on the paths that hold the engine controller's monitor or run on the FX thread: story 338 built and pinned them.

## Technical Notes

- daw-app: `ClockStatusIndicator` (`decide`, `look`, `noticeIfNew`, `announce`), `MainController.pluginGraphChanged`, `RecordCoordinator` (the record start and `noteClockEvent`), `DefaultAudioEngineController.clockSources`.
- daw-core: `AudioEngine.lastOpenedStream()`, `startAudioOutputLocked`, `OpenedStream`.
- Cross-refs: story 338 (rulings E119, E136, E142, E143 and E146).

## Status

- Deferred
