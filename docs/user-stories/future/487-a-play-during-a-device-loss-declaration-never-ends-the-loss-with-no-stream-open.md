---
title: "A Play During a Device-Loss Declaration Never Ends the Loss With No Stream Open"
labels: ["bug", "audio-engine", "reliability"]
---

# A Play During a Device-Loss Declaration Never Ends the Loss With No Stream Open

## Motivation

A device loss is declared by `DefaultAudioEngineController.declareDeviceLost`: it marks the loss as being declared and then, in `closeTheLostStream`, stops the lost stream's output (`audioEngine.stopAudioOutput()`), stops the engine (`audioEngine.stop()`) and publishes DEVICE_LOST. A Play opens its stream on the JavaFX thread without the controller's monitor, and every stream open is announced through `DefaultAudioEngineController.onStreamOpened`, which ends a standing loss by publishing STOPPED when the engine is DEVICE_LOST.

A traced suspicion that nobody has proved (story 338's review, the fact check of the studio engineer's consult 47): a Play whose open lands between the declaration's `stopAudioOutput()` and its `stop()` leaves its new stream pending its release, so no stream is open; its announcement then ends DEVICE_LOST with no stream open, and Reconnect (`reconnectLostDevice`) and the lost device's return, both of which act only while the engine is DEVICE_LOST, then do nothing. The loss would end on screen with no stream open, and neither Reconnect nor the lost device's return would act.

## Goals

- A stream open that lands while a device loss is being declared never leaves the engine out of DEVICE_LOST with no stream open: once the declaration ends, either the engine is DEVICE_LOST, and Reconnect and the lost device's return act as for any loss, or a stream is open.

## Constraints

- The loss's notice, its cause and the take's rescue are unchanged (story 338, rulings E4, E5 and E6; story 327).
- No new words.
- The JavaFX thread blocks on nothing new, and no lock order of the engine or the controller changes.

## Acceptance Criteria

1. A test lands a Play's open between the declaration's close of the lost stream's output and its stop of the engine: afterwards the engine is DEVICE_LOST and a Reconnect reopens the device, or a stream is open and the engine is STOPPED or running.
2. A Play that opens before or after the declaration behaves as today.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- Criterion 1 places the open between the two steps deterministically, with no timed wait.
- `DefaultAudioEngineControllerTest`, `DeviceLossReactionsTest`, `ClockBadgeWhileALossIsDeclaredTest` and `TransportControllerTest` stay green.

## Non-Goals

- An OS device-change watcher: future story 461.

## Technical Notes

- daw-app: `DefaultAudioEngineController` (`declareDeviceLost`, `closeTheLostStream`, `onStreamOpened`, `reconnectLostDevice`, the lost device's return), `TransportController` (the Play's open).
- daw-core: `AudioEngine` (`stopAudioOutput`, `stop`, the stream states, `RELEASE_PENDING` among them).
- Cross-refs: story 327; story 338 (rulings E4 to E7); future story 461.

## Status

- Deferred
