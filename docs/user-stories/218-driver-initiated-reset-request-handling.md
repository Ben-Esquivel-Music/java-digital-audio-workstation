---
title: "Driver-Initiated Reset Request Handling for Mid-Session Format Changes"
labels: ["enhancement", "audio-engine", "reliability", "platform"]
---

# Driver-Initiated Reset Request Handling for Mid-Session Format Changes

## Motivation

When a user opens the native driver control panel mid-session (story 212) and changes the buffer size, sample rate, USB streaming mode, or clock source, the driver does not silently apply the new format — it raises a structured "please drop and reopen" signal. ASIO drivers fire `kAsioResetRequest` through the host callback; ASIO drivers fire `kAsioBufferSizeChange` with the new size; CoreAudio fires `kAudioDevicePropertyNominalSampleRate` listeners; WASAPI invalidates the active `IAudioClient` and requires a full reinitialisation. Today the engine has no plan: the next render callback reads stale buffer-size assumptions, fails, and the controller crashes the same way as a device disconnect (story 214) — except in this case the device is fine, the *format* changed.

Every professional DAW handles this gracefully. Cubase displays "Audio device settings changed — restarting engine" and reopens. Pro Tools logs the change, freezes transport for ~200 ms, and resumes. The mechanism is straightforward — listen to the driver's reset signal, drain the render thread, reopen with the new format, restart transport in `STOPPED` state. The cost of *not* handling this is the same crashy experience as a yanked cable for what is in fact a deliberate user action.

Story 214 covers device-loss; this story covers driver-initiated format change while the device is still present.

## Goals

- Extend `AudioDeviceEvent` (story 214) with a new `FormatChangeRequested(DeviceId, Optional<AudioFormat> proposedFormat, FormatChangeReason reason)` record. `FormatChangeReason` is sealed: `BufferSizeChange`, `SampleRateChange`, `ClockSourceChange`, `DriverReset`.
- `AsioBackend` translates `kAsioResetRequest`, `kAsioBufferSizeChange`, `kAsioResyncRequest` into `FormatChangeRequested` events with the appropriate reason. Following the ASIO host-callback contract, the actual reopen happens on a dedicated worker thread, never on the audio callback thread.
- `CoreAudioBackend` installs property listeners for `kAudioDevicePropertyNominalSampleRate`, `kAudioDevicePropertyBufferFrameSize`, and `kAudioDevicePropertyClockSource` and emits the corresponding events.
- `WasapiBackend` listens for `IMMNotificationClient::OnPropertyValueChanged` on the active endpoint and emits `FormatChangeRequested` when the mix format changes.
- `DefaultAudioEngineController` consumes the event: pause transport, drain the render queue (deadline 200 ms), close the stream, re-query device capabilities (story 213), reopen with the proposed format if provided or the existing settings otherwise, and resume in `STOPPED`. While reopening, the UI shows "Reconfiguring audio engine…" in the transport bar.
- Recording: if `FormatChangeRequested` arrives while recording, behave like `DeviceRemoved` for the in-progress take — flush partially captured frames to `.daw/incomplete-takes/`, show a recovery dialog after reopen. *(As landed by story 327: the `.daw/incomplete-takes/` WAV never shipped and is gone — see story 214's "As landed by story 327" note; an in-flight take lost to `DeviceRemoved` is rescued by `RecordCoordinator` as sealed segments under `<project>/audio/takes/`. On this reopen path story 327 only removed the `flushIfNotEmpty` call of step 3 — it flushed a store nothing ever fed — and gives a `FormatChangeRequested` no rescue of its own.)*
- Sample-rate change is treated specially: the project session rate does *not* change. The engine inserts SRC at the device boundary (story 126's `SampleRateConverter`) so a project authored at 48 kHz keeps playing at 48 kHz internally even if the driver is now running at 44.1 kHz. Surface a notification suggesting the user pick the matching project rate from the driver panel.
- Coalesce rapid-fire reset requests (the user spinning a buffer-size dropdown produces multiple events): wait 250 ms after the last event before reopening.
- Tests: a `MockAudioBackend` emits `FormatChangeRequested(BufferSizeChange, 256→512)` mid-render; the controller reopens with 512, transport state is preserved, no audio thread exception is raised. A second test covers the SRC-fallback path when the driver moves from 48k to 44.1k.

## Non-Goals

- Hot-patching the in-flight render graph for the new format (e.g., resizing buffer pools without a stream reopen). The reopen is a clean teardown / rebuild.
- Persisting the driver-initiated change as the new DAW default — settings the user made in the driver panel apply only to that session unless they confirm "save as default" in the audio settings dialog.
- Recovering plugin internal state across the reopen for plugins that are not real-time-state-safe; lookahead buffers and reverb tails are flushed.
- Exposing a "block driver-initiated changes" mode; the driver always wins, the DAW always reconciles.

## Note from Story 338

- **`kAsioResyncRequest` is no longer a `FormatChangeRequested`.** A resync is answered as supported and handled in place: it is counted in the xrun log ("Resync") and judged as one bad answer of the clock-lock poll, and it never closes or reopens the stream (story 338, rulings E37 to E39). The Java selector constants did not match the ASIO SDK's enum, so a real buffer-size change was read as a resync; they now match it (`AsioFormatChangeShimTest.selectorConstantsMatchTheAsioSdkEnum`). A reset request and a buffer-size change keep this story's reopen.
- **While the reopen runs**, the session status strip's Engine cell reads "Engine: reconfiguring…" beside the existing "Reconfiguring audio engine…" notice (ruling E26).
- **A reopen of a stream that was open, and that fails, no longer ends `STOPPED` with "Audio engine reconfigured".** The device is declared lost: `EngineState.DEVICE_LOST`, the notice "`<name>` asked to be reset and could not be reopened — audio stopped. Check that it is connected and powered, then choose Reconnect." with a Reconnect action, and a take in flight sealed as a device loss under the lead "`<name>` could not be reopened after a driver reset during recording" (rulings E113 and E134). A request while no stream is open still reopens nothing and ends "Audio engine reconfigured" (ruling E134). A request that arrives while the device is lost is dropped.
- **The recording bullet above** (a reset while a take records) is not built: a reset during a take is future story [464](future/464-a-driver-reset-during-a-take-seals-the-take-before-the-reopen.md) (ruling E41).
