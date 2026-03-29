---
title: "Metronome UI Toggle and Configuration in Transport Bar"
labels: ["enhancement", "ui", "transport"]
---

# Metronome UI Toggle and Configuration in Transport Bar

## Motivation

The `Metronome` class in `daw-core` is fully implemented with configurable click sounds (`ClickSound` enum with SINE, WOODBLOCK, COWBELL, HI_HAT, RIMSHOT), volume control, subdivision modes, accent patterns, and count-in generation. However, there is no UI element in the transport bar or anywhere in the application that allows users to enable, disable, or configure the metronome. The transport toolbar has play, stop, record, loop, skip-back, and skip-forward buttons, but no metronome toggle. The `DawIcon.METRONOME` icon exists in the icon pack and is used by the tempo label tooltip, but it is not assigned to a clickable metronome button. Without a UI toggle, users cannot activate the metronome for recording count-ins or tempo reference during playback.

## Goals

- Add a metronome toggle button to the transport toolbar area (near the tempo display) using the existing `DawIcon.METRONOME` icon
- Toggle the metronome enabled state on click, with a visual active/inactive indicator matching the loop button style
- Wire the toggle to `Metronome.setEnabled()` so the audio engine includes metronome clicks in the output during playback
- Provide a right-click context menu or popover on the metronome button to configure:
  - Click sound selection (from the existing `ClickSound` enum values)
  - Volume level (slider, 0–100%)
  - Subdivision mode (quarter, eighth, sixteenth)
  - Count-in mode (off, one bar, two bars) for recording
- Register a keyboard shortcut for metronome toggle via the `KeyBindingManager` (default: `M`)
- Persist metronome settings across sessions using the existing `Preferences` infrastructure
- Show a notification via `NotificationBar` when the metronome is toggled on or off

## Non-Goals

- Custom metronome sound file import
- Visual beat indicator flashing on the UI
- MIDI clock output synchronized with the metronome
