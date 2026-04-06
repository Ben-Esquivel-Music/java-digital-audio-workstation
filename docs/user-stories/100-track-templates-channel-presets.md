---
title: "Track Templates and Channel Strip Presets for Workflow Acceleration"
labels: ["enhancement", "mixer", "usability", "persistence"]
---

# Track Templates and Channel Strip Presets for Workflow Acceleration

## Motivation

Setting up tracks for a recording or mixing session is repetitive. A vocal track typically needs a compressor, EQ, and reverb send configured the same way every time. A drum bus needs specific insert effects, volume levels, and routing. Currently, users must manually add each track, insert effects one by one, configure parameters, and set up sends for every new project. There is no way to save a configured track or channel strip as a reusable template.

Professional DAWs (Logic Pro, Pro Tools, Reaper, Studio One) offer track templates and channel strip presets that capture the entire signal chain — insert effects with their parameter values, send routing, volume/pan defaults, track color, and input/output routing — and allow users to create new tracks from these templates with a single action.

## Goals

- Define a `TrackTemplate` record in `daw-core` that captures: track type (audio/MIDI), track name pattern, insert effects chain (effect types and their parameter values), send routing configuration, default volume and pan, track color, and input/output routing
- Define a `ChannelStripPreset` record that captures just the mixer channel state: insert effects, sends, volume, pan — applicable to any existing track
- Add a "Save as Template" option to the track context menu that serializes the current track's configuration as a `TrackTemplate`
- Add a "Save Channel Strip Preset" option to the mixer channel context menu
- Store templates and presets as XML files in a user-accessible directory (e.g., `~/.daw/templates/` and `~/.daw/presets/`)
- Add a "New Track from Template" submenu to the File menu and the Add Track dialog, listing available templates
- Add a "Load Channel Strip Preset" option to the mixer channel context menu, listing available presets
- Ship a set of factory templates: "Vocal Track" (compressor + EQ + reverb send), "Drum Bus" (compressor + EQ + limiter), "Guitar Track" (EQ + reverb send), "Synth Track" (EQ + chorus)
- Template and preset creation/application should be undoable
- Add tests verifying: (1) template serialization/deserialization round-trips correctly, (2) creating a track from a template applies all settings, (3) loading a channel strip preset applies insert effects and parameters

## Non-Goals

- Project templates (entire project scaffolds with multiple tracks — separate feature)
- Sharing templates via cloud or network
- Automatic template suggestion based on track content
- Template versioning or migration
