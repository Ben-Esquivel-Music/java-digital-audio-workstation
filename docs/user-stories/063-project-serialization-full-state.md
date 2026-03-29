---
title: "Complete Project Serialization Including Clips, Mixer, and Automation"
labels: ["enhancement", "persistence", "project", "core"]
---

# Complete Project Serialization Including Clips, Mixer, and Automation

## Motivation

User story 019 describes full project save/load/auto-save. The `ProjectManager`, `CheckpointManager`, `AutoSaveConfig`, `RecentProjectsStore`, and `ProjectMetadata` classes exist in the persistence package, and the `ProjectLifecycleController` in the UI module wires save/open/new-project menu actions. However, the actual serialization of the complete project state — track clip positions, audio data references, mixer channel settings (volume, pan, mute, solo, inserts), transport settings (tempo, time signature, loop region), automation breakpoints, and marker positions — to a file format and faithfully restoring it has gaps. The `DawProjectXmlSerializer` and `DawProjectXmlParser` in the session package handle DAWproject interchange format, but the native project save/load path needs to persist ALL project data including recently added features like track colors, track groups, reference tracks, and metronome settings. Without complete serialization, users risk losing mixer settings, automation data, or clip positions when they save and reopen a project.

## Goals

- Serialize all project state to the native JSON project file format including:
  - All track properties: name, type, color, volume, pan, mute, solo, armed, input device
  - All clips on each track: name, file path, startBeat, durationBeats, sourceOffsetBeats, gain, fades, time-stretch/pitch-shift settings
  - Mixer channel settings: volume, pan, insert effects with their parameter values, send levels
  - Automation breakpoints for each automated parameter
  - Transport state: tempo, time signature, loop enabled, loop start/end
  - Marker positions and labels from `MarkerManager`
  - Track groups from the track grouping system
  - Metronome settings (enabled, volume, click sound, subdivision)
- Deserialize and restore a saved project so that reopening produces the exact same state
- Verify round-trip fidelity with automated tests (save → load → compare)
- Handle missing audio files gracefully (show a notification listing missing files, allow relinking)

## Non-Goals

- Cloud backup or sync
- Collaborative multi-user editing
- Backward compatibility with older save formats (this is the initial format)
