---
title: "Graceful MIDI Playback Degradation with User Notification"
labels: ["enhancement", "midi", "usability", "instruments"]
---

# Graceful MIDI Playback Degradation with User Notification

## Motivation

MIDI track playback depends on FluidSynth (`libfluidsynth`) for SoundFont synthesis via FFM bindings. When FluidSynth is not installed on the user's system, the `FluidSynthRenderer` fails to load and `MidiTrackRenderer` falls back to `JavaSoundRenderer`. However, `JavaSoundRenderer` is explicitly documented as unable to render float audio buffers for mixing — it uses the javax.sound.midi `Synthesizer` which outputs through its own audio device, not through the DAW's audio engine. The result is that MIDI tracks are **completely silent** during playback with no indication to the user of why.

A user who creates a MIDI track, records or imports MIDI notes, assigns a SoundFont, and presses Play will hear nothing. There is only a log-level warning in the console that most users will never see. This is a critical UX failure — the application should clearly communicate when a required capability is unavailable and offer guidance on how to resolve it.

## Goals

- When `FluidSynthRenderer` fails to initialize (native library not found), display a user-visible notification via the `NotificationBar` explaining that FluidSynth is required for MIDI playback and providing a link to installation instructions
- Show the notification when: (1) the application starts and detects FluidSynth is unavailable, if any MIDI tracks exist in the loaded project, or (2) the user creates a new MIDI track while FluidSynth is unavailable
- Mark MIDI tracks visually in the track list when FluidSynth is unavailable — e.g., a warning icon or dimmed instrument label with a tooltip: "MIDI playback requires FluidSynth — click for setup instructions"
- Disable the "Play" transport action for MIDI-only projects when no functional SoundFont renderer is available, with a status bar message explaining why
- Add a "MIDI Engine Status" entry in a Help or Diagnostics menu that shows whether FluidSynth is detected, the library path searched, and installation instructions for the current platform (Windows, macOS, Linux)
- If `JavaSoundRenderer` can produce audible output through its own device (even if not mixed into the engine), offer it as an explicit fallback option with a warning that it will not be mixed with audio tracks

## Non-Goals

- Bundling FluidSynth with the application (licensing and distribution complexity)
- Implementing a pure-Java SoundFont synthesizer as a replacement (significant undertaking — separate project)
- MIDI playback through external hardware synthesizers
- Automatic FluidSynth installation
