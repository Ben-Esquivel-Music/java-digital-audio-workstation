---
title: "SoundFont Browser and MIDI Instrument Assignment"
labels: ["enhancement", "midi", "ui", "instruments"]
---

# SoundFont Browser and MIDI Instrument Assignment

## Motivation

The `FluidSynthRenderer` and `JavaSoundRenderer` classes exist for MIDI rendering via SoundFont files, and the `SoundFontInfo`, `SoundFontPreset`, and `SoundFontRenderer` SDK types define the interface. However, there is no UI for browsing SoundFont files, selecting instruments from within a SoundFont, or assigning SoundFont presets to MIDI tracks. Users creating MIDI compositions need to audition and select instruments (piano, strings, drums, etc.) from their SoundFont library. Without this UI, MIDI tracks can only play with a default instrument or no sound at all.

## Goals

- Add a SoundFont browser dialog accessible from MIDI track properties
- Allow loading SoundFont (.sf2) files from the file system
- Display a list of presets (instruments) contained in the loaded SoundFont
- Allow selecting a preset for each MIDI track
- Preview SoundFont presets by playing a short MIDI phrase when selected
- Show the bank and program number for each preset
- Support loading multiple SoundFont files simultaneously
- Persist the SoundFont assignment per MIDI track when saving the project

## Non-Goals

- SoundFont editing or creation
- Downloading SoundFonts from the internet
- Non-SoundFont virtual instruments (VST/CLAP instruments — separate feature)
