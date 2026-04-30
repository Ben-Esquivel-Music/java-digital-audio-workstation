---
title: "FFM Bridge for ASIOGetChannelInfo Driver-Reported Channel Names and Wire MixerView Suppliers"
labels: ["bug", "audio-engine", "ffm", "native", "windows", "asio", "ui", "mixer"]
---

# FFM Bridge for ASIOGetChannelInfo Driver-Reported Channel Names and Wire MixerView Suppliers

## Motivation

Story 215 specifies that the mixer's I/O routing dropdowns should display the driver-reported channel names ("Mic/Line 1", "Hi-Z Inst 3", "S/PDIF L", "Phones 1 L") rather than the generic "Input N" labels. The story called for two things:

1. Each `AudioBackend` overrides `inputChannels(DeviceId)` and `outputChannels(DeviceId)` to return real `AudioChannelInfo` records sourced from the platform API (ASIO, CoreAudio, WASAPI, JACK).
2. `MixerView` consumes those lists via `setInputChannelInfoSupplier` / `setOutputChannelInfoSupplier`.

Today only half of (2) is done: `MixerView` has the supplier setters and the rendering code that auto-groups stereo pairs and shows a `ChannelKind` icon, but **nothing in the codebase ever calls those setters** — `grep -rn 'setInputChannelInfoSupplier' daw-app daw-core` shows only the declaration. (1) is also unimplemented: `AudioBackend.inputChannels(DeviceId)` is the default returning `List.of()`, and no subclass overrides it. The result is that on every backend — including the user's primary ASIO/multi-channel USB target — the routing dropdowns still show "Input 1", "Input 1-2", etc., and the carefully designed channel-icon + stereo-pair logic in `MixerView` is dead code.

The ASIO SDK exposes `ASIOError ASIOGetChannelInfo(ASIOChannelInfo* info)` returning per-channel `name`, `type`, `isActive`, and `channelGroup`. With the shim infrastructure introduced by stories 220 / 221 / 222 already in `daw-core/native/asio/`, adding the channel-info call is a small extension.

## Goals

- Extend `daw-core/native/asio/` with `int asioshim_getChannelInfo(int channelIndex, int isInput, ASIOChannelInfoCStruct* outInfo)` mirroring the SDK's `ASIOChannelInfo`. Returns 1 on `ASE_OK`, 0 on out-of-range or unknown channel.
- Add `int asioshim_getChannelCount(int* outInputs, int* outOutputs)` returning the result of `ASIOGetChannels(numInputs, numOutputs)` so the controller knows how many indices to enumerate.
- Implement `AsioBackend#inputChannels(DeviceId)` and `AsioBackend#outputChannels(DeviceId)` as overrides that:
  1. Call `asioshim_getChannelCount` to get the count.
  2. For each index call `asioshim_getChannelInfo(index, 1 /*isInput*/, ...)` (or `0` for output).
  3. Decode the 32-byte ASCII `name` field, classify into `ChannelKind` using the heuristic table from story 215 (`/mic|line/i → Mic|Line`, `/inst|hi.?z/i → Instrument`, `/spdif|adat|aes/i → Digital`, `/main|monitor/i → Monitor`, `/phone|headphone/i → Headphone`, fall back to `Generic`).
  4. Build `AudioChannelInfo(index, displayName, kind)` records and return as an unmodifiable `List`. Inactive channels (`isActive == 0`) are still included, but `MixerView` already greys them.
- When the `asioshim` library is absent, both methods continue to return `List.of()` (matching the inherited default), so the dropdown falls back to "Input N" labels exactly as today.
- Implement the same overrides for `WasapiBackend` (using `IPart::GetName` per endpoint) and `CoreAudioBackend` (using `kAudioObjectPropertyElementName` per channel) to round out the cross-platform contract; `JackBackend` queries port aliases. These are smaller because the WASAPI / CoreAudio FFM shims already exist for the format-change path; reuse the same `Linker.nativeLinker()` arena.
- Wire the suppliers in production: `MainController` (or `MixerViewBuilder` if a small extraction makes sense) calls `mixerView.setInputChannelInfoSupplier(() -> backend.inputChannels(activeDeviceId))` and the matching output setter immediately after the audio engine opens, and re-invokes those setters on each `FormatChangeRequested` event so a driver-side channel rename is reflected without restarting the DAW.
- Persistence: extend `Track.inputRouting` / `Track.outputRouting` (story 092) with a `channelNameSnapshot` field carrying the `displayName` at save time. On project load, if the live driver reports a different name at the same index, prefer the live name and surface a one-shot `NotificationManager` warning per project ("Channel names changed since last save: 'Mic 3' → 'Hi-Z Inst 3'").
- Tests:
  - Unit test mocks the FFM resolution: `getChannelCount` returns `(8, 4)`, `getChannelInfo` returns synthetic names including a stereo pair "Mic 1 L"/"Mic 1 R" and a "S/PDIF L"/"S/PDIF R"; assert `inputChannels` returns 8 entries with the expected `ChannelKind` classification.
  - Unit test asserts `MixerView` dropdown auto-groups "Mic 1 L"+"Mic 1 R" into a single "Mic 1 (Stereo)" stereo entry per story 215's existing logic.
  - Unit test asserts a project saved with name "Mic 3" and loaded against a driver reporting "Hi-Z Inst 3" surfaces exactly one notification, not one per track.

## Non-Goals

- Editing channel names in-DAW; names are owned by the driver.
- Localising channel names; render whatever the driver returns.
- Per-channel pad / phantom-power controls — those live in the native control panel (story 212 / 221).
- Honouring the driver's stereo-pair grouping when it disagrees with the L/R suffix heuristic — the heuristic wins for now.

## Technical Notes

- Files: `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/AsioBackend.java`, `WasapiBackend.java`, `CoreAudioBackend.java`, `JackBackend.java` (override inputChannels / outputChannels), `daw-core/native/asio/asioshim.cpp` (export getChannelCount + getChannelInfo), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (wire suppliers), and `daw-core/src/main/java/com/benesquivelmusic/daw/core/track/Track.java` for the channelNameSnapshot field.
- `AudioChannelInfo`, `ChannelKind` records already exist in `daw-sdk/src/main/java/com/benesquivelmusic/daw/sdk/audio/`.
- Reference original story: **215 — Driver-Reported Channel Names in Routing UI**.
