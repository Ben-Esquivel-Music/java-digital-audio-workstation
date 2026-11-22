---
title: "Every Device-Loss Surface Names the Device the Stream Opened"
labels: ["bug", "audio-engine", "ui", "reliability"]
---

# Every Device-Loss Surface Names the Device the Stream Opened

## Motivation

A sentence the person reads names an audio device through `DeviceNames`: a device with no name, a blank name, the default placeholder (`DeviceId.isDefault()`) or the transport's stand-in for an unnamed device reads "The audio device" at the start of a sentence and "the audio device" inside one (story 338, ruling E111). On the automatic device, the default placeholder, every loss surface therefore uses the unnamed form: the stall and removal notices, the Engine cell ("Device lost: audio device"), the rescued-take leads, the Reconnect notices, the sentences of ruling E126 and the failed return of ruling E129. Only the clock notices name the driver the stream really opened, because they read the device from `ClockLockStatus.device()`, which the driver side resolves (for ASIO, the driver's name even when the stream was asked for the default device). Nothing shown is false, but a person with several interfaces cannot tell which one was lost. Story 338's studio engineer recorded it as a limit with this future story's requirement (ruling E131).

## Goals

- Every loss surface names the device the stream was actually opened on, on every backend that resolves an automatic device, and the name survives the stream's close (ruling E131).

## Constraints

- Never `<default>`, empty quotes, a placeholder or a leading space on any surface (rulings E111 and E131).
- A device with no name to show keeps ruling E111's unnamed forms, and "Audio is now on …" keeps ruling E130's forms when one of its two devices has no name.
- The words of every loss sentence stay as ruled (rulings E4, E5, E7, E27, E96, E113, E125, E126, E129, E130 and E133); only the name changes.
- Nothing reads a device's name on the real-time thread, and nothing on the FX thread waits for a driver.

## Acceptance Criteria

1. On the automatic device, a stall gives a notice, an Engine cell text and a rescued-take lead that carry the resolved device name.
2. On the automatic device, a removal does the same.
3. After the stream's close, the Reconnect notices and the refusal of Record or Play while the device is lost still carry that name.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- The studio engineer's verification: on the automatic device, a stall and a removal whose notice, Engine cell and lead all carry the resolved name (ruling E131), with a test backend that resolves its default device to a named one.
- `DeviceNamesInTheSentencesThePersonReadsTest`, `StalledDeviceLossCopyTest`, `EngineHealthCellsTest` and `ReconnectLostDeviceTest` stay green, with the expectations of the automatic device changed to the resolved name.

## Non-Goals

- A stable hardware identity for two units with the same name: story 352.
- The notices that predate story 338 and show the placeholder: future story 471.

## Technical Notes

- daw-app: `DeviceNames`, `DefaultAudioEngineController` (the stall, removal, reset and Reconnect notices, `anotherDeviceArrivedMessage`, `returnNotReopenedMessage`), `RecordCoordinator` (`deviceLossLead`, `unavailableWhileDeviceLostMessage`, `lossEndedOnAnotherDeviceMessage`, `reconnectedDuringTakeMessage`), `EngineHealthCells`, `ClockStatusIndicator` (how the clock notices name the device).
- daw-core: `AudioEngine.openStreamDevice()` and `lastOpenedStream()`. daw-sdk: `DeviceId`, `ClockLockStatus`.
- Cross-refs: story 338 (rulings E111, E126, E129, E130 and E131), story 352.

## Status

- Deferred
