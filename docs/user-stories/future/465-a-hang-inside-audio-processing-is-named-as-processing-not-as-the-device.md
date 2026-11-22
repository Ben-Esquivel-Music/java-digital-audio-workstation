---
title: "A Hang Inside Audio Processing Is Named as Processing, Not as the Device"
labels: ["bug", "audio-engine", "error-handling", "reliability"]
---

# A Hang Inside Audio Processing Is Named as Processing, Not as the Device

## Motivation

Story 338's heartbeat watchdog watches one heartbeat per stream: the device-callback period count where the backend reports one (PortAudio, ASIO), and the render drive's beats otherwise (story 338, ruling E1). A hang inside audio processing, a `processBlock` that does not return, is therefore misnamed, a limit story 338 accepted (ruling E58):

- On Java Sound and the Mock backend the render drive stops beating, so the watchdog declares the device lost: the person reads "`<name>` stopped responding — audio stopped. Check that it is connected and powered, then choose Reconnect.", and Reconnect cannot cure a hang inside processing.
- On PortAudio and ASIO the device keeps calling back, so no verdict comes; the device plays silence from an empty output buffer, and the hang shows only as climbing "Device played silence (output buffer empty)" entries in the xrun log.

## Goals

- A hang inside audio processing is named as processing, with Restart Audio Engine, and not as the device (ruling E58).

## Constraints

- The Engine cell's loss tooltip "`<name>` stopped responding or was disconnected. Audio is stopped. Click to reconnect, or choose another device in Audio Settings." stays as it is (ruling E58).
- A device that stops calling back while processing runs is still the stall verdict, with Reconnect (rulings E4 and E6).
- One heartbeat substrate serves every backend; no stall detector per backend (Failure Surfacing book §9, item 14). The watchdog observes the render drive and never runs on it.
- The render thread records only: no lock, no allocation, no logging and no publisher on it (Failure Surfacing book §2.3).

## Acceptance Criteria

1. On a backend without device periods, a `processBlock` that does not return gives a notice that names audio processing and offers Restart Audio Engine, and no "stopped responding" notice and no DEVICE_LOST.
2. On a backend with device periods, the same hang gives the same notice.
3. A device whose callbacks stop while processing runs normally is still declared lost, with Reconnect.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A test with a render graph whose processing blocks without returning, on a test backend without device periods and on one with them: the processing notice with Restart Audio Engine, never "stopped responding".
- A test that a device whose callbacks stop, with processing healthy, is still the stall verdict.
- `EngineHealthMonitorTest`, `EngineHeartbeatWatchdogTest` and `RealTimeSafeContractTest` stay green.

## Non-Goals

- A render drive that halts itself after a second of faulted blocks: story 337's halt treatment, unchanged.

## Technical Notes

- daw-app: `EngineHeartbeatWatchdog`, `DefaultAudioEngineController.onHeartbeatStalled`, `XrunFeed`, `EngineHealthCells`.
- daw-core: `EngineStreamPump`, `AudioEngine.isRenderDriveAlive()`, `AudioHealthRecord.renderDriveBeats()` and `deviceCallbackPeriods()`.
- Cross-refs: story 337 (the halt treatment and Restart Audio Engine), story 338 (rulings E1, E4, E6 and E58).

## Status

- Deferred
