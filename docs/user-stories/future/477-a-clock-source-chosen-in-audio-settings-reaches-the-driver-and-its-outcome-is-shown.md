---
title: "A Clock Source Chosen in Audio Settings Reaches the Driver and Its Outcome Is Shown"
labels: ["bug", "audio-engine", "asio", "ui"]
---

# A Clock Source Chosen in Audio Settings Reaches the Driver and Its Outcome Is Shown

## Motivation

Audio Settings sends a clock source when the person chooses it in the Clock source list (story 216), with nothing to Apply. The choice runs on a worker of the dialog (`SettingsDialog.startSerializedAudioUtilityOperation`). That worker first waits for the dialog's other audio work (`audioControllerOperationLock`) and for its device enumeration to give way (`DeviceEnumerationTask.cancelAndAwait`); the dialog runs that enumeration when it opens and again after its audio work. Closing the dialog, by OK, Cancel or its close box, waits for an outstanding audio reconfigure (`applyAndCloseWhenReady`, `confirmClose`) but not for a clock-source choice, and the close interrupts every audio utility worker (`closeBackgroundWork`). So:

- a choice still waiting when the dialog closes is not sent, and nothing says so (story 338, ruling E167);
- a switch that is sent and that the driver refuses, or that fails, after the dialog has closed has its notice in the closed dialog (`SettingsShell.showOperationNotice`), where nobody sees it, because each open builds a new dialog (`MainController.createSettingsDialog`); it is also written to the log ("Clock source selection failed").

In both cases the interface keeps the source it had. A person who chooses the word clock and closes Audio Settings at once can go on to record with the interface on the source it had, believing it follows the house clock. The sign on screen is the clock badge, which goes on naming the source the interface kept (story 338, rulings E138 and E171). Story 338's owner's checks wait until the Clock source list can be opened again before they close the dialog (ruling E167).

## Goals

- A clock source chosen in Audio Settings is sent to the driver whether or not Audio Settings has closed by then.
- A switch that the driver refuses, or that fails, is shown to the person whether or not Audio Settings is still open, with the source chosen and the device.
- The clock badge follows the interface's source as it does today (story 338, rulings E138 and E164).

## Constraints

- No driver call runs on the JavaFX thread or the audio callback thread, and none waits without a bound (story 316).
- Closing Audio Settings does not wait without a bound for a clock-source choice.
- A switch made while a stream runs on the interface keeps story 338's allowance for it (rulings E107, E137 and E138).
- While Audio Settings is open, its Clock source list stays unavailable from a choice until the switch has been answered, as today.

## Acceptance Criteria

1. A clock source chosen while the dialog's device enumeration still runs, with Audio Settings closed at once by OK, by Cancel or by its close box: the interface runs on the chosen source, and the clock badge names it once read.
2. A choice that the driver refuses, with Audio Settings closed before the refusal: the window shows the refusal, naming the source and the device.
3. A choice made with Audio Settings left open until the switch is answered: as today.
4. On hardware (the owner's): during playback, choose the word clock in Audio Settings and press OK at once: the interface follows the word clock and the badge names it; on a driver that refuses a word clock with no cable in, the same with the cable out shows the refusal in the window.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A daw-app test of Audio Settings with a controller fake whose clock-source selection is held until released, chosen while a device enumeration runs: the dialog closes, and the selection reaches the controller afterwards.
- A daw-app test with a controller fake that refuses the selection after the dialog has closed: the window's notification sink gets one notice that names the source and the device.
- `SettingsDialogTest`, `SettingsDialogApplyBehaviorTest`, `ClockStatusIndicatorTest` and `ClockBadgeDuringATakeTest` stay green.

## Non-Goals

- A clock source switched in the interface's own software (story 338, ruling E164).
- Audio Settings with no stream open on the interface, where it lists no clock source: future story 475.
- The dialog's other audio utilities, the test tone and the driver's control panel.

## Technical Notes

- daw-app: `SettingsDialog` (`selectClockSource`, `startSerializedAudioUtilityOperation`, `closeBackgroundWork`, `applyAndCloseWhenReady`, `confirmClose`, `showAudioOperationFailure`), `SettingsShell.showOperationNotice`, `DefaultAudioEngineController.selectClockSource`, `MainController.createSettingsDialog`, the window's notification sink.
- daw-sdk: `AsioBackend.selectClockSource`.
- Cross-refs: stories 216 and 222; story 338 (rulings E107, E137, E138, E164, E167 and E171, and E167's Known limit); future story 475.

## Status

- Deferred
