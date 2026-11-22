---
title: "An ASIO Stream Runs the Interface at the Engine's Sample Rate"
labels: ["bug", "audio-engine", "asio", "reliability"]
---

# An ASIO Stream Runs the Interface at the Engine's Sample Rate

## Motivation

An ASIO driver keeps the interface at a rate of its own: the one a host last set with `ASIOSetSampleRate`, or the one the interface's own software chose. `AsioBackend.open` loads the driver, checks the buffer size against the driver's (`requireAcceptedBufferSize`), creates the buffers with the channels and the buffer size (`createBuffers`) and starts the stream. It neither sets nor reads the interface's rate. `ASIOSetSampleRate` is reached from an Audio Settings Apply of the sample rate: `AsioBackend.setSampleRate`, called from `DefaultAudioEngineController.setSampleRate`, whose callers are the Settings dialog's audio reconfigure and its restore after a failed one. `asioshim_getSampleRate` is read by `AsioBackend.supportedSampleRates` alone, when the driver accepts none of the standard rates.

The engine opens every stream at its own format. A session takes that format from the saved settings: the start-up request carries `SettingsModel.getSampleRate()` (`MainController.audioConfigurationRequest`), which is 96 kHz until a rate is saved (`SettingsModel.DEFAULT_SAMPLE_RATE`), and `DefaultAudioEngineController.applyConfiguration` sets the engine's format from the request. The first-run wizard's steps do not include the sample rate (`FirstRunWizard`). The engine advances the transport by its own rate (`RenderPipeline`). A take is written at the engine's format (`RecordCoordinator` hands `audioEngine.getFormat()` to the `RecordingPipeline`). The clock badge names the engine's rate (story 338, ruling E170).

So when the interface runs at another rate, everything the engine renders plays at a speed of the interface's rate divided by the engine's. Audio the engine renders at 96 kHz plays at half speed and an octave low on an interface that runs at 48 kHz, and a take that interface captures at 48 kHz is written as 96 kHz. Nothing on screen says so. A first session at the rate used until one is saved meets it on any interface that runs at another rate. So does a later session, after another application or the interface's own software has changed the interface's rate. Audio Settings asks the driver for a rate while a stream is open on the interface and refuses a change with no stream open (story 338, ruling E169; future story 475). Choosing the saved rate is no change to apply, so a person whose saved rate is right but whose interface was changed elsewhere has to apply another rate and then the saved one again.

## Goals

- A stream that opens on an ASIO interface runs the interface at the engine's sample rate when the interface can run at it.
- When the interface does not run at the engine's rate at the open (a driver that refuses the rate, or an interface that follows an external clock at another rate, for instance), the outcome is story 218's rule for a sample-rate change: the project keeps its rate, what the person hears plays at its own speed and pitch through a conversion at the device boundary, and the person is told the interface's rate.
- A take recorded on such a stream plays back at the speed and pitch at which it was performed.
- The clock badge names the rate the interface runs (story 338, rulings E142 and E170).

## Constraints

- A stream on an interface that already runs at the engine's rate opens as today, with no notice.
- The engine's rate stays the one Audio Settings saves (96 kHz until one is saved), and the project's sample rate does not change by itself (story 218).
- An Audio Settings Apply of a rate while a stream runs on the interface keeps today's behaviour (story 258).
- No driver call runs on the audio callback thread or the JavaFX thread, and none waits without a bound.

## Acceptance Criteria

1. An interface that runs at 48 kHz and can run at 96 kHz, and an engine at 96 kHz, the rate used until one is saved: from Play the interface runs at 96 kHz, playback is at its own speed and pitch, and the clock badge reads "… 96k" once read.
2. An interface that cannot run at the engine's rate at the open (one that follows a 44.1 kHz word clock, with the engine at 48 kHz, for instance): playback is at its own speed and pitch, the person is told the interface's rate, and the clock badge names it.
3. A take recorded in case 2 plays back at its own speed and pitch.
4. An interface that already runs at the engine's rate: the stream opens as today, with no notice.
5. On hardware (the owner's): an interface set to 44.1 kHz in its own software, with 48 kHz saved in Audio Settings: Play gives playback at its own speed and pitch, and the badge reads "… 48k".

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a Windows or a non-Windows host.

- A daw-sdk test with a fake ASIO capability shim whose rate differs from the engine's when a stream opens: the stream runs the shim at the engine's rate.
- A daw-sdk test with a fake shim that refuses the engine's rate: the stream opens and the engine learns the interface's rate.
- A daw-app test with a backend fake that opens at another rate than the engine's: the conversion is in place, the person is told the interface's rate, the project's rate is unchanged, and `ClockStatusIndicator` names the interface's rate after the read and is hidden before it.
- The same with the two rates equal: today's open, with no notice.
- `AsioBackendCapabilityTest`, `AsioBackendDriverLifecycleTest`, `DefaultAudioEngineControllerTest` and `ClockStatusIndicatorTest` stay green.

## Non-Goals

- A rate changed in the driver's own control panel during a stream: future story 474.
- Audio Settings with no stream open on the interface: future story 475.
- WASAPI and CoreAudio, whose streaming is not built.

## Technical Notes

- daw-sdk: `AsioBackend` (`open`, `setSampleRate`, `supportedSampleRates`), `AsioCapabilityShim` (`setSampleRate`, `getSampleRate`, `canSampleRate`).
- daw-core/native/asio: `asioshim.cpp` (`asioshim_setSampleRate`, `asioshim_getSampleRate`).
- daw-app: `MainController.audioConfigurationRequest`, `SettingsModel`, `DefaultAudioEngineController` (`applyConfiguration`, `setSampleRate`), `ClockStatusIndicator`.
- daw-core: `RenderPipeline`, `RecordingPipeline`.
- Cross-refs: story 126 (the conversion at the device boundary), story 218 (its sample-rate rule), stories 220, 258 and 310; story 338 (rulings E142, E168, E169 and E170); future stories 474 and 475.

## Status

- Deferred
