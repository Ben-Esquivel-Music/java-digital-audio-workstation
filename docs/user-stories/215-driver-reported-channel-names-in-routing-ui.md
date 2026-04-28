---
title: "Driver-Reported Channel Names in I/O Routing Dropdowns"
labels: ["enhancement", "audio-engine", "ui", "mixer"]
---

# Driver-Reported Channel Names in I/O Routing Dropdowns

## Motivation

Story 092 wires per-track input and output routing dropdowns populated from the active backend's channel count, producing entries like "Input 1", "Input 1-2", "Output 3-4". Multi-channel hardware drivers report semantic channel names that are far more useful: "Mic/Line 1", "Mic/Line 2", "Hi-Z Inst 3", "S/PDIF L", "S/PDIF R", "Main Out L", "Phones 1 L". When a user assigns a kick mic to "Mic 3" rather than "Input 3", routing mistakes drop sharply, and headphone-cue, S/PDIF passthrough, and instrument-input conventions become discoverable from the dropdown rather than the printed manual.

The ASIO API exposes this via `ASIOGetChannelInfo(channel, isInput) → ASIOChannelInfo{ name, type, isActive }`. CoreAudio uses `kAudioObjectPropertyElementName` per channel. WASAPI reports per-channel `IPart::GetName` for capture and render endpoints. JACK uses port aliases. All four backends therefore have a structured source — story 130 already loads them at device-open time, this story plumbs them into the UI.

## Goals

- Add `record AudioChannelInfo(int index, String displayName, ChannelKind kind)` in `com.benesquivelmusic.daw.sdk.audio` with `ChannelKind` sealed: `Mic`, `Line`, `Instrument`, `Digital`, `Monitor`, `Headphone`, `Generic`.
- Extend `AudioBackend` (story 130) with `List<AudioChannelInfo> inputChannels(DeviceId)` and `List<AudioChannelInfo> outputChannels(DeviceId)`; backends populate via the API listed above.
- `AsioBackend` parses `ASIOChannelInfo.name` and infers `ChannelKind` from a small heuristic table (`/mic|line/i → Mic|Line`, `/inst|hi.?z/i → Instrument`, `/spdif|adat|aes/i → Digital`, `/main|monitor/i → Monitor`, `/phone|headphone/i → Headphone`); fall back to `Generic` with the raw name preserved.
- `Track`'s `inputRouting` / `outputRouting` (story 092) carries the index plus a snapshot of `displayName` so projects that move between machines render meaningful labels even when the new machine reports different names.
- `TrackStripController` and the mixer-view I/O dropdowns render `displayName` instead of `Input <index>`; a small icon next to each entry reflects `ChannelKind` (microphone, plug, instrument, digital, headphone glyph).
- Stereo pairs are auto-grouped when consecutive channels share a stem name suffixed `L`/`R` ("Mic 1 L" + "Mic 1 R" → "Mic 1 (Stereo)"); single-channel only when the user explicitly picks the mono variant.
- When the driver reports a channel as inactive (ASIO `isActive=false`, e.g., disabled in the driver's own panel), grey it in the dropdown and tooltip "Disabled in driver".
- Persist channel-name snapshots in `ProjectSerializer`; on load, if the live driver reports a different name at the same index, prefer the live name and warn the user once per project ("Channel names changed since last save: 'Mic 3' → 'Hi-Z Inst 3'").
- Tests: a fake backend reporting "Mic 1 L" + "Mic 1 R" yields a single "Mic 1 (Stereo)" stereo entry; rename mismatch on load produces a single warning, not one per track.

## Non-Goals

- Editing channel names in-DAW; names are owned by the driver.
- Localising channel names; we render whatever the driver returns.
- Per-channel gain or pad controls (those are hardware features driven from the native control panel — story 212).
- Honoring the driver's stereo-pair grouping when it disagrees with the L/R suffix heuristic; the heuristic wins for now.
