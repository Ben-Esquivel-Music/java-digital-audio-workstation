---
title: "No Notice Shows an Engine Message, a Class Name or the Placeholder Device"
labels: ["bug", "error-handling", "notifications", "audio-engine", "recording"]
---

# No Notice Shows an Engine Message, a Class Name or the Placeholder Device

## Motivation

Story 338's notices give the person fixed words: a failed Reconnect says one of four reasons and sends the engine's own message to the log only (story 338, ruling E112), and a device with no name to show reads "The audio device" (ruling E111). Notices that predate story 338 still put the engine's own message, a class name or the default placeholder device name (`<default>`) in front of the person. Story 338's review listed them, and its manager filed them here:

- `TransportController.streamRefusalMessage`: "Audio output refused for … — backend '…' device '…' could not start: `<engine message>`. …", whose device part can be the placeholder.
- `DefaultAudioEngineController.applyConfigurationWithPermit`: "Audio output could not be started: `<engine message>`" and "Audio configuration could not be applied: `<engine message>`".
- `DefaultAudioEngineController.runFormatChangeReopen`: "Audio engine reconfiguration failed: `<engine message>`".
- `DefaultAudioEngineController.refuseFormatChangeReopen`: "Audio device reconfiguration was refused because the render pump is still active: …".
- `RecordCoordinator.reportTakeCompletionFailure`, `ioReason`, `shortDescription` and `abortRecordingTake`, which put the failure's own message, its class name, or both into a notice.
- `MainController.applyStartupAudioSettings` ("Startup audio configuration failed for backend '…' device '…': `<engine message>`. …"), `MainController.pluginGraphChanged` ("Plugin audio output could not start: `<engine message>`") and `MainController.ensureRenderQueue` ("Render failed: … (`<message, or the exception's class name>`)").
- `CaptureFlushService.flagNarrowRoute`: "Track 'T', device 'D': … exceeds N opened input channels; this track captures silence", whose 'D' can be the placeholder.

## Goals

- No notice shows an engine message, a class or method name, or the placeholder device name.

## Constraints

- The engine's own message goes to the log, as ruling E112 rules for the Reconnect notices.
- A device with no name to show reads ruling E111's unnamed forms.
- The routing-mismatch sentence keeps its meaning for a route found too narrow (ruling E54).
- A notice keeps its level and its action.
- The tests that pin the present texts change with them, among them `ClockBadgeDuringATakeTest.anApplyWhoseRestartFailsAfterItsOpenShowsTheStartingViewThenTheClosedOne` ("Audio output could not be started: " followed by the refusal's message) and `DefaultAudioEngineControllerTest` ("Audio configuration could not be applied: ").

## Acceptance Criteria

1. Each site above, driven to its failure with an exception whose message names a class, a method or `<default>`, shows a notice that contains none of them.
2. A device with no name to show is named as ruling E111 says.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A sweep test over the notices the sites above raise: no notice text contains the failure's message, a class or method name, or the placeholder device name.
- `DeviceNamesInTheSentencesThePersonReadsTest` and `FailureCopyTest` stay green.

## Non-Goals

- The device the automatic device resolves to: future story 470.
- Notification levels and rate limiting: story 339.

## Technical Notes

- daw-app: `TransportController`, `DefaultAudioEngineController`, `RecordCoordinator`, `MainController`, `DeviceNames`.
- daw-core: `CaptureFlushService.flagNarrowRoute`, `InputNames`.
- Cross-refs: story 338 (rulings E54, E111 and E112), story 339.

## Status

- Deferred
