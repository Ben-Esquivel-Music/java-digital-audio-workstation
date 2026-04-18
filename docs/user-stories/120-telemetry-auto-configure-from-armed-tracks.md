---
title: "Auto-Configure Sound Wave Telemetry Sound Sources from Armed Arrangement Tracks"
labels: ["enhancement", "telemetry", "recording", "ui", "workflow"]
---

# Auto-Configure Sound Wave Telemetry Sound Sources from Armed Arrangement Tracks

## Motivation

Today, configuring the Sound Wave Telemetry plugin (story 085) requires the user to manually add every sound source in `TelemetrySetupPanel` — entering a name, X/Y/Z coordinates, and power (dB) for each instrument — even though the Arrangement View already carries the authoritative list of tracks being recorded. Each `Track` in `daw-core` has an `armed` flag (`Track.isArmed()` / `setArmed(boolean)`, persisted via `ProjectSerializer` / `ProjectDeserializer` and bulk-toggled by `TrackGroup.setArmed`), and the recording workflow (stories 007 and 060) uses that flag to determine which tracks capture audio when the user presses Record. Users therefore have to duplicate the same "what am I recording?" decision in two places — once in the Arrangement View by arming tracks, and once in the telemetry setup panel by typing source names — and keep them in sync by hand when they arm/disarm a track mid-session.

This is exactly the kind of redundant configuration the problem statement calls out: the user should not have to "configure sound sources separately from existing armed tracks." The telemetry plugin's window already observes the active `DawProject` for room configuration persistence, so it has a natural hook to observe the project's tracks as well. Pulling the source list directly from the armed tracks makes telemetry instantly meaningful the moment a user arms an instrument, aligns the plugin with the DAW's primary recording workflow, and eliminates a whole class of "why doesn't my guitar show up on the canvas?" support questions caused by the two lists drifting out of sync.

Professional acoustic analyzers (Sonarworks SoundID Reference, Dirac Live, Room EQ Wizard capture sessions) derive their input list from the host DAW's armed inputs rather than asking the user to re-declare them. Adopting the same behavior brings Sound Wave Telemetry in line with user expectations and lets it participate in the existing arm/record workflow as a first-class citizen.

## Goals

- Add an `ArmedTrackSourceProvider` service in `daw-core` that exposes an observable list of armed tracks for the current `DawProject`, computed from `Track.isArmed()` and kept current as tracks are armed, disarmed, added, removed, or renamed
- When the Sound Wave Telemetry plugin is opened (or the active project changes), auto-populate `RoomConfiguration.soundSources` from the armed tracks of that project:
  - Source name = track name
  - Initial X/Y/Z = a deterministic layout inside the current `RoomDimensions` (e.g., evenly spaced along a circle around room center) so users see something sensible immediately
  - Initial power (dB) = `TelemetrySetupPanel.DEFAULT_POWER_DB`, or the track's last known peak/RMS level if available from the meter service
- Add an "Auto-sync with armed tracks" toggle at the top of `TelemetrySetupPanel` (default: on):
  - When **on**, arming a track inserts a matching `SoundSource`, disarming removes it, and renaming updates the source's name; user-edited positions/power for a still-armed source are preserved across updates (match by stable track id, not by name)
  - When **off**, the source list behaves exactly as today (fully manual), so existing workflows are unchanged
- When the user drags an auto-added source on `RoomTelemetryDisplay`, the new position sticks even after further arm/disarm events, keyed to the underlying track id (drag state should never be clobbered by a sync pass)
- If the user manually adds a `SoundSource` with a name that does not match any track, it is treated as a "free" source and is never removed by the auto-sync
- Unit tests cover: empty armed set produces empty source list; arming a track adds a source at a valid in-room position; disarming removes only that source and leaves free sources intact; renaming a track updates the source name without losing drag position; toggling auto-sync off freezes the current list; toggling it back on re-reconciles without duplicating entries
- The `SoundWaveTelemetryPluginTest` is extended to verify the plugin wires the provider correctly on `activate()` and unsubscribes on `deactivate()` / `dispose()`

## Non-Goals

- Recording audio from telemetry (recording continues to be driven by the existing recording workflow in stories 007 and 060 — telemetry is analysis only)
- Inferring a source's X/Y/Z from the audio signal itself (localization from multi-mic capture is a separate, much larger research effort)
- Auto-configuring microphones from armed input channels — this story is scoped to sound sources; microphones remain manually placed (auto room-dimension configuration from microphone distance is covered by story 121)
- Changing the `Track` domain model or persistence format — the armed flag is already persisted, and this story only consumes it
- Syncing tracks that are muted-but-armed or frozen (story 035) differently — any armed track contributes a source regardless of its playback state
