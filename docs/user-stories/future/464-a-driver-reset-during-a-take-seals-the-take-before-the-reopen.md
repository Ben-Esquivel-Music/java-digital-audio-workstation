---
title: "A Driver Reset During a Take Seals the Take Before the Reopen"
labels: ["bug", "recording", "audio-engine", "reliability", "asio"]
---

# A Driver Reset During a Take Seals the Take Before the Reopen

## Motivation

A driver that asks the host to reset (ASIO's `kAsioResetRequest`) or announces a new buffer size (`kAsioBufferSizeChange`) gets story 218's reopen: `DefaultAudioEngineController.runFormatChangeReopen` runs `performFormatChangeReopen` on the format-change worker, which publishes RECONFIGURING, stops the output and the engine (`stopAndConfirmPumpQuiesced`) and reopens the stream. No step of it asks the record coordinator to end a take first, so a take that is recording when the request arrives has its stream closed and reopened underneath it.

Story 338 changed one end of that path only: when the reopen of a stream that was open fails, the device is declared lost, with its own notice and Reconnect, and a take in flight is sealed as a device loss under the lead "`<name>` could not be reopened after a driver reset during recording" (story 338, rulings E113 and E134). Its studio engineer ruled what a successful reset during a take must do (ruling E41), and story 338's manager deferred it here.

## Goals

The requirement, as story 338's studio engineer ruled it (ruling E41):

- Outside a take, a real buffer-size change and a reset request keep story 218's stop-and-reopen.
- While a take records, the take is first stopped and sealed as the person's Stop seals it, a normal seal and not a rescue, and the reopen runs after that.
- The notice is a WARNING with the action Review: "`<name>` asked to be reset; recording stopped at m:ss.s. The take is saved."

## Constraints

- Never a stream reopen underneath a running take, and never a take that continues across a reopen (ruling E41).
- A reopen of an open stream that fails still ends as ruling E113 rules: a device loss, its notice and Reconnect.
- A resync request reopens nothing (ruling E37); it stays the clock monitor's and the xrun log's.
- A device with no name to show reads "The audio device" (ruling E111), and the device-name rules of future story 470 apply when it is built.
- The record coordinator's state machine stays the only owner of record state (Recording Reliability book §3.2).

## Acceptance Criteria

1. A reset request during a take ends the take before the stream closes: its `take.manifest` reads `sealed-by=stop`, its clips are published as after a Stop, and the WARNING reads exactly as above with Review.
2. The reopen then runs, and the stream comes back as story 218 reopens it.
3. A buffer-size change during a take does the same.
4. Outside a take, a reset request and a buffer-size change behave as they do today.
5. A reset during a take whose reopen fails is sealed by the Stop, and then ends as a device loss (ruling E113).

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A daw-app test with `MockAudioBackend` publishing `FormatChangeRequested` with `DriverReset` during a take: the take is sealed with `sealed-by=stop` before the stream closes, the notice is exact, and the reopen follows.
- The same with `BufferSizeChange`.
- The same with a reopen that fails: Stop's seal first, then DEVICE_LOST with ruling E113's notice.
- `DriverRequestWhileTheDeviceIsGoneTest`, `DeviceLossLeadsDuringATakeTest` and `DefaultAudioEngineControllerTest` stay green.

## Non-Goals

- What a reset request does while the device is lost: it is dropped (story 338).
- The device's name on the automatic device: future story 470.

## Technical Notes

- daw-sdk: `AsioFormatChangeShim` (the selector cases), `AudioDeviceEvent.FormatChangeRequested`, `FormatChangeReason`.
- daw-app: `DefaultAudioEngineController.onFormatChangeRequested`, `runFormatChangeReopen`, `performFormatChangeReopen`; `RecordCoordinator`; `TransportController`.
- Cross-refs: story 218 (the reopen), story 327 (the rescue), story 338 (rulings E37, E41, E111, E113 and E134).

## Status

- Deferred
