---
title: "A Sample Rate Set in an ASIO Driver's Own Panel Reaches the Engine"
labels: ["bug", "audio-engine", "asio", "reliability"]
---

# A Sample Rate Set in an ASIO Driver's Own Panel Reaches the Engine

## Motivation

A person can change the sample rate in an ASIO driver's own control panel during a session. The driver tells the host through `ASIOCallbacks::sampleRateDidChange`, which the native shim forwards as a reset request (`daw-core/native/asio/asioshim.cpp`, `shimSampleRateDidChange`), or through `kAsioResetRequest`. `AsioFormatChangeShim` publishes every reset request as `FormatChangeRequested` with `FormatChangeReason.DriverReset` and no proposed format, so `DefaultAudioEngineController.performFormatChangeReopen` reopens the stream at the engine's current format. Its sample-rate branch, which carries story 218's rule (the project keeps its rate, a conversion at the device boundary, and the notice "Driver moved to <n>kHz — SRC inserted at device boundary. Pick the matching project rate from the driver panel to avoid SRC."), runs only for `FormatChangeReason.SampleRateChange`, which no ASIO path publishes. `AsioBackend.open` neither sets nor reads the device's rate: `ASIOSetSampleRate` is reached only from an Audio Settings Apply of the sample rate (`AsioBackend.setSampleRate`), and `asioshim_getSampleRate`, which the shim's README lists for the controller after a driver-initiated reset, is not called on the reset path.

So after such a change the engine can render and capture at its own rate while the device clocks the audio at the rate its panel set. 48 kHz audio clocked out at 44.1 kHz plays about 8 % slow and about 1.5 semitones flat, and a take captured at 44.1 kHz can be written as 48 kHz. The person sees only "Reconfiguring audio engine…" and "Audio engine reconfigured", and the clock badge, which names the rate the engine opened the stream at, keeps naming the engine's rate (story 338, ruling E168).

## Goals

- After an ASIO driver's reset request, the engine knows the rate the device runs.
- When that rate differs from the engine's, the outcome is story 218's rule for a sample-rate change: the project keeps its rate, what the person hears plays at its own speed and pitch through a conversion at the device boundary, and the person is told the device's new rate.
- A take recorded on that stream plays back at the speed and pitch at which it was performed.
- The clock badge names the rate the device runs: hidden from the reopen until the new stream's read answers, then that rate (story 338, ruling E142).

## Constraints

- A reset request after which the device still runs at the engine's rate keeps today's reopen and its notices, "Reconfiguring audio engine…" and "Audio engine reconfigured".
- A reopen of an open stream that fails still ends as story 338's ruling E113 rules: a device loss, its notice and Reconnect.
- A reset request during a take is future story 464's: the take is sealed before the reopen.
- The project's sample rate does not change by itself (story 218).
- No driver call runs on the audio callback thread or the JavaFX thread, and none waits without a bound.
- The Xruns count restarts at the reopen, as for any reset request (story 338, ruling E148).

## Acceptance Criteria

1. A driver whose rate moves from 48 kHz to 44.1 kHz and that then asks to be reset: the reopened stream plays the project's 48 kHz audio at its own speed and pitch on the 44.1 kHz device, the person is told the device's new rate, and the clock badge is hidden from the reopen until the new stream's read answers, then reads "… 44.1k".
2. A take recorded after that reset plays back at its own speed and pitch.
3. A reset request with the device still at the engine's rate reopens as today.
4. On hardware (the owner's): story 338's clock check 10 shows the notice, playback at its own pitch, and the badge's new rate.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A daw-sdk test with a fake ASIO capability shim whose rate changes before a `kAsioResetRequest`: the engine learns the device's rate at that request.
- A daw-app test with a backend fake that runs at another rate after a reset request: the reopen follows the Goals (the conversion in place, the notice, the project's rate unchanged), and `ClockStatusIndicator` names the device's rate after the read and is hidden before it.
- The same with the device's rate unchanged: today's reopen and notices.
- `DefaultAudioEngineControllerTest`, `ClockStatusIndicatorTest` and `AsioFormatChangeShimTest` stay green.

## Non-Goals

- The rate an ASIO open asks of the device at the start of a session: no open sets or reads it today, and a device that another application left at another rate is future story 476's requirement.
- WASAPI and CoreAudio, whose streaming is not built.

## Technical Notes

- daw-sdk: `AsioFormatChangeShim` (its `kAsioResetRequest` case), `AsioBackend` (`open`, `setSampleRate`, `supportedSampleRates`), the ASIO capability shim.
- daw-core/native/asio: `asioshim.cpp` (`shimSampleRateDidChange`, `asioshim_getSampleRate`).
- daw-app: `DefaultAudioEngineController.performFormatChangeReopen` (steps 5 and 7), `ClockStatusIndicator`.
- Cross-refs: story 126 (the conversion at the device boundary), story 218 (the reopen and its sample-rate rule), story 338 (rulings E113, E142, E148 and E168), future story 464.

## Status

- Deferred
