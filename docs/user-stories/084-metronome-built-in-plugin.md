---
title: "Metronome Built-In Plugin"
labels: ["enhancement", "plugins", "utility", "transport"]
---

# Metronome Built-In Plugin

## Motivation

The DAW already has metronome functionality (story 016) integrated into the transport system. However, by wrapping the metronome as a `BuiltInDawPlugin`, it gains a dedicated configuration window accessible from the Plugins menu, consistent with how other DAWs treat their metronomes (Logic Pro, Ableton Live, and Cubase all provide a metronome settings panel accessible from a menu or toolbar). This plugin would serve as the configuration and control surface for the metronome — allowing users to adjust click sounds, accent patterns, count-in behavior, and volume without cluttering the transport bar with metronome-specific controls.

Packaging the metronome as a built-in plugin also establishes the pattern for utility plugins that wrap existing core functionality, demonstrating that `BuiltInDawPlugin` is not only for new features but also for surfacing existing capabilities in a discoverable way.

## Goals

- Create a `MetronomePlugin` class in `daw-core` that implements `BuiltInDawPlugin`
- The class has a public no-arg constructor for reflective instantiation
- `getDescriptor()` returns a `PluginDescriptor` with id `"com.benesquivelmusic.daw.metronome"`, name `"Metronome"`, type `MIDI_EFFECT`
- `getMenuLabel()` returns `"Metronome"`
- `getCategory()` returns `BuiltInPluginCategory.UTILITY`
- `activate()` opens a floating panel with metronome configuration controls
- Provide controls for: click sound selection (built-in click, woodblock, rimshot, or custom sample), accent pattern (accent on beat 1 only, accent on beats 1 and 3 for 4/4, etc.), click volume, count-in bars (0–4), and subdivision (quarter notes, eighth notes, triplets)
- The plugin delegates to the existing metronome engine in `daw-core` — it is a configuration UI, not a reimplementation
- Synchronize the metronome enable/disable state with the transport bar toggle
- `deactivate()` hides the configuration panel (metronome continues running if enabled via transport)
- `dispose()` disconnects from the metronome engine
- Add the `MetronomePlugin` to the `BuiltInDawPlugin` permits clause

## Non-Goals

- Replacing the existing metronome engine or transport bar toggle — the plugin provides an extended configuration UI
- Visual metronome or tempo tap-to-detect (separate features)
- Polyrhythmic click patterns
- MIDI output of metronome clicks to external devices
