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

## Note from Story 338

- **The transport-bar badge is built**: `ClockStatusIndicator`, mounted immediately before the transport status label (story 338, ruling E32). It shows the current source and rate ("INT 48k", "W/C 48k"), dim while lock is not measured (the stream closed, starting, checking, or a lock that cannot be checked), "⚠ W/C 48k UNLOCKED" in the unlocked treatment, which flashes unless Reduce Motion is on, and nothing on a device that reports no clock sources. No tooltip or spoken text names a motion (ruling E101).
- **Lock is not read from `ASIOFuture(kAsioGetExternalClockLocked)`.** `AsioClockLockMonitor` polls the sample position once a second through the native export `asioshim_getSamplePosition` and counts the driver's resync requests; two consecutive bad poll intervals unlock and three good ones relock (rulings E29, E106, E120 and E121). Only a source not classified internal, on a driver that reports more than one source, is polled (ruling E60). With an `asioshim.dll` built without that export, every external source reads "Lock cannot be checked on this device" (ruling E100).
- **An unlock never pauses or stops recording.** The take keeps recording and is marked in `take.manifest` (`clock=unlocked|<frame>`, `clock=locked|<frame>`); the notice reads "External clock lost on `<name>` (`<source>`). Check the clock cable and the clock master.", with "Recording continues; the take is marked from this point." during an audio take, never "recording quality compromised"; the relock notice is at the information level (rulings E30, E31, E108 and E110). The test "lock-loss during recording stops capture" above is overruled by ruling E30.
- **A source the person switches in Audio Settings** gets the heartbeat watchdog's start-up allowance, and the resyncs around the switch are not taken as an unlock (rulings E107, E122, E137 and E138).
