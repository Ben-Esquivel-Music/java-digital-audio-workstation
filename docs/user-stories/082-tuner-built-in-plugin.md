---
title: "Chromatic Tuner Built-In Plugin"
labels: ["enhancement", "plugins", "utility", "ui"]
---

# Chromatic Tuner Built-In Plugin

## Motivation

Musicians recording guitar, bass, vocals, or other pitched instruments need a tuner readily available in their DAW. Currently, users must rely on external hardware tuners or third-party plugins. A built-in chromatic tuner plugin would give users instant access to a tuning tool directly from the Plugins menu, eliminating the need to leave the DAW or configure an external plugin.

The tuner would analyze the pitch of incoming audio from the selected input or track, display the detected note name, and show how many cents sharp or flat the pitch is from the nearest semitone. This is a fundamental studio utility that every professional DAW provides (Pro Tools, Logic Pro, Cubase, Ableton Live all include built-in tuners).

## Goals

- Create a `TunerPlugin` class in `daw-core` that implements `BuiltInDawPlugin`
- The class has a public no-arg constructor for reflective instantiation
- `getDescriptor()` returns a `PluginDescriptor` with id `"com.benesquivelmusic.daw.tuner"`, name `"Chromatic Tuner"`, type `ANALYZER`
- `getMenuLabel()` returns `"Chromatic Tuner"`
- `getCategory()` returns `BuiltInPluginCategory.UTILITY`
- `activate()` opens a floating window with the tuner display
- Perform real-time pitch detection on the selected audio input using an autocorrelation or YIN algorithm
- Display the detected note name (e.g., A4), octave number, and frequency in Hz
- Show a cents-offset indicator (needle or bar meter) ranging from −50 to +50 cents, with a clear "in tune" zone (±3 cents highlighted green)
- Support configurable reference pitch (default A4 = 440 Hz, adjustable from 415 Hz to 466 Hz for alternate tuning standards)
- Display a "no signal" state when input level is below a threshold
- `deactivate()` stops pitch detection and hides the tuner window
- `dispose()` releases audio input resources
- Add the `TunerPlugin` to the `BuiltInDawPlugin` permits clause

## Non-Goals

- Polyphonic tuning detection (monophonic only — one note at a time)
- Guitar/bass-specific string tuning modes (e.g., "tune string 6 to E2") — chromatic only
- Auto-tuning or pitch correction of audio (that is a separate effect plugin)
- MIDI tuning output
