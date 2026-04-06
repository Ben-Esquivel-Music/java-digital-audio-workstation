---
title: "Plugin Parameter Automation with Write, Latch, and Touch Recording Modes"
labels: ["enhancement", "automation", "plugins", "mixer"]
---

# Plugin Parameter Automation with Write, Latch, and Touch Recording Modes

## Motivation

Stories 003 and 059 explicitly list "plugin parameter automation" as a non-goal, limiting automation to mixer channel parameters (volume, pan, mute, send level). However, plugin parameter automation is essential for professional mixing — automating an EQ sweep, a reverb wet/dry mix, a compressor threshold, or a delay feedback amount over time is a fundamental workflow in every commercial DAW.

The automation infrastructure already supports arbitrary parameters: `AutomationLane` stores breakpoints keyed by a parameter identifier, and `getValueAtTime()` returns interpolated values. The missing pieces are: (1) discovering automatable parameters from plugins, (2) mapping automation lanes to plugin parameters, and (3) recording automation from user interaction with plugin controls.

Additionally, no automation **recording** mode exists. Users can only manually draw automation breakpoints. Professional DAWs offer Write (overwrites all automation during playback), Latch (starts writing when a control is touched, continues until stop), and Touch (writes while a control is held, snaps back to existing automation on release) modes.

## Goals

- Add a `getAutomatableParameters()` method to `DawPlugin` (or `BuiltInDawPlugin`) that returns a list of `AutomatableParameter` records (id, display name, min, max, default, unit)
- Allow creating automation lanes for plugin parameters in addition to mixer channel parameters — the automation lane's parameter selector dropdown should show plugin parameters when a plugin is inserted on the channel
- Map plugin parameter automation lanes to the plugin's parameter setter so that automation values from story 087 are applied to plugin parameters during playback
- Implement three automation recording modes as an `AutomationMode` enum: `READ` (play back automation), `WRITE` (overwrite all automation during playback), `LATCH` (start writing on first parameter change, continue until stop), `TOUCH` (write while parameter is being adjusted, snap back on release)
- Add an automation mode selector per track in the arrangement view (a dropdown on the track strip)
- During recording modes (WRITE/LATCH/TOUCH), capture parameter changes from UI fader/knob interactions and write them as new automation breakpoints at the current transport position
- Thin automation data after recording to reduce point density while preserving the curve shape (e.g., Ramer-Douglas-Peucker simplification)
- All automation recording operations should be undoable as a single compound action per recording pass
- Add tests verifying: (1) plugin parameters appear in the automation lane selector, (2) recorded automation breakpoints match the parameter movements, (3) each recording mode behaves correctly

## Non-Goals

- Automation from external MIDI controllers (CC mapping — separate feature)
- Automation curves with mathematical expressions or LFO generators
- Automation of third-party CLAP plugin parameters (requires CLAP parameter discovery — future enhancement)
- Bezier or spline interpolation modes (linear is sufficient initially)
