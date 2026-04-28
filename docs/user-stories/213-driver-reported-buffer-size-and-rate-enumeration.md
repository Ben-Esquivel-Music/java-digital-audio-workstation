---
title: "Driver-Reported Buffer Size, Granularity, and Sample-Rate Enumeration"
labels: ["enhancement", "audio-engine", "ui", "platform"]
---

# Driver-Reported Buffer Size, Granularity, and Sample-Rate Enumeration

## Motivation

`AudioSettingsDialog` (story 098) hardcodes the buffer-size dropdown to `{32, 64, 128, 256, 512, 1024, 2048}` and the sample-rate dropdown to `{44100, 48000, 88200, 96000, 176400, 192000}`. Real audio drivers do not honor that menu. ASIO drivers report a four-tuple via `ASIOGetBufferSize(min, max, preferred, granularity)` — for instance a multi-channel USB driver might allow only `{64, 128, 256, 512}` or might have a non-power-of-two granularity (96, 192, 288, …). Many drivers reject `ASIOSetSampleRate()` for any rate not in their `ASIOCanSampleRate()` whitelist, which can vary by USB streaming mode and by which inputs are armed. Selecting an unsupported value today produces a hard `ASE_InvalidMode` failure and the engine falls back silently to `JavaxSoundBackend` — exactly the kind of silent quality regression story 130 was designed to avoid.

WASAPI in shared mode is fixed at the OS-mixer rate; in exclusive mode it accepts anything the device reports via `IAudioClient::IsFormatSupported`. CoreAudio exposes `kAudioDevicePropertyAvailableNominalSampleRates` and `kAudioDevicePropertyBufferFrameSizeRange`. JACK is whatever the server is running. All four backends therefore have an authoritative source — the dialog must consult it instead of inventing a menu.

## Goals

- Add `BufferSizeRange(int min, int max, int preferred, int granularity)` and `Set<Integer> supportedSampleRates(DeviceId)` to `AudioBackend` (story 130) so each backend reports the device's actual capabilities.
- `AsioBackend` implements both via the FFM bindings already established in story 130 (`ASIOGetBufferSize`, `ASIOCanSampleRate` probed across the canonical rate list).
- `WasapiBackend` distinguishes shared vs exclusive mode and reports the correct constraint set per mode; the dialog learns the mode from a checkbox.
- `CoreAudioBackend` reads the property listeners; `JackBackend` reports `{jackBufferSize}` and `{jackSampleRate}` as singletons (those are server-controlled).
- `AudioSettingsDialog` populates the buffer-size dropdown from `BufferSizeRange` — if the driver reports `min=64, max=512, granularity=64`, the dropdown shows `{64, 128, 192, 256, 320, 384, 448, 512}`. Preferred value is preselected.
- Sample-rate dropdown is filtered to the union of canonical rates and `supportedSampleRates`, with unsupported rates greyed and tooltipped ("not supported by current device").
- The dialog refreshes both lists when the user changes the device, the WASAPI mode toggle, or returns from the native control panel (story 212).
- If a persisted setting from `~/.daw/audio-settings.json` is no longer in the supported set (e.g., the user changed the driver mode), fall back to `preferred` and notify via `NotificationManager`.
- Tests: a fake backend exposing `BufferSizeRange(96, 384, 192, 96)` produces a dropdown of `{96, 192, 288, 384}`; persisted unsupported rate triggers fallback-with-notification path.

## Non-Goals

- Auto-tuning the buffer size for the user's CPU profile; the user picks from the driver-allowed set.
- Bypassing driver constraints by reformatting buffers in the DAW (e.g., feeding the driver 256 frames internally when it only accepts 128) — that belongs in a separate "buffer regrouping" story.
- Reporting non-PCM formats (DSD, encoded passthrough).
- Discovering rates the driver supports but does not advertise.
