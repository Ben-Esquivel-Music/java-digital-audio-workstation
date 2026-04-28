---
title: "Hardware Clock Source Selection (Internal / Word Clock / S/PDIF / ADAT)"
labels: ["enhancement", "audio-engine", "ui", "platform"]
---

# Hardware Clock Source Selection (Internal / Word Clock / S/PDIF / ADAT)

## Motivation

Multi-channel audio interfaces almost always offer more than one clock source: internal crystal, word-clock BNC input, S/PDIF input, ADAT optical, AES/EBU. Choosing the right source matters whenever the interface is part of a larger rig — locking to an external word clock is the standard for studios that share clock across converters; locking to S/PDIF is necessary when receiving from an outboard preamp with a digital out; locking to ADAT is necessary when expanding inputs through a lightpipe-connected preamp. Picking the wrong source produces audible clicks every few seconds (sample slips) or, worse, silent corruption that only shows up on careful listening. Pro Tools has "Clock Source" in its Hardware Setup; Cubase has it in "Studio Setup → Audio System"; Logic exposes it via Audio MIDI Setup on macOS.

The ASIO API exposes available sources via `ASIOGetClockSources(ASIOClockSource[], int* numSources)` and lets the host pick one with `ASIOSetClockSource(int)`. CoreAudio uses `kAudioDevicePropertyClockSource`. WASAPI does not expose this directly — selection happens in the device's own control panel. JACK runs at the server's clock and has no per-device choice.

Story 098 (audio settings UI) and story 212 (driver control-panel launch) are adjacent; this story is the in-DAW first-class UI for selecting and monitoring the clock so the user does not have to context-switch into the vendor utility for a setting that affects every recording.

## Goals

- Add `record ClockSource(int id, String name, boolean current, ClockKind kind)` with `ClockKind` sealed: `Internal`, `WordClock`, `Spdif`, `Adat`, `Aes`, `External`.
- Extend `AudioBackend` (story 130) with `List<ClockSource> clockSources(DeviceId)` and `void selectClockSource(DeviceId, int id)`.
- `AsioBackend` implements both via `ASIOGetClockSources` / `ASIOSetClockSource`. `CoreAudioBackend` implements via `kAudioDevicePropertyClockSource`. `WasapiBackend` and `JackBackend` return empty list and disable the UI control.
- `AudioSettingsDialog` (story 098) gains a "Clock Source" combo box populated from `clockSources()`; defaults to the source the driver reports as currently selected. Greyed and tooltipped when the backend returns empty.
- After selection, re-query `BufferSizeRange` and `supportedSampleRates` (story 213) since some interfaces only allow specific rates per clock source.
- Add a transport-bar clock-status indicator: a small badge showing the active clock source ("INT 48k", "EXT W/C", "SPDIF") that flashes red on lock failure. Lock state derives from `ASIOFuture(kAsioGetExternalClockLocked)` polled at 1 Hz from a non-audio thread.
- When the driver reports clock unlock during transport (`kAsioResyncRequest` or polled `false`), surface a `NotificationManager` warning ("External clock not locked — recording quality compromised") and pause recording (not playback).
- Persist the selected clock source per device in `~/.daw/audio-settings.json` keyed by device ID, so swapping back to the device restores the previous selection.
- Tests: a fake backend reports three clock sources, selection is remembered across restarts, lock-loss during recording stops capture but leaves the dialog responsive.

## Non-Goals

- Synchronising the DAW's transport to external timecode (LTC, MTC) — that is a separate sync-engine story.
- Word-clock generation from the DAW (we are always a clock follower from the OS audio system's view).
- Diagnosing why an external clock fails to lock; the indicator just reports the state.
- Hardware-level sample-rate-conversion when the external clock is at a different rate than the project; we surface the mismatch and let the user reconcile it.
