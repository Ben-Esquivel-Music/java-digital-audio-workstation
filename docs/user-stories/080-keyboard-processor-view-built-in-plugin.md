---
title: "Virtual Keyboard Built-In Plugin (KeyboardProcessorView)"
labels: ["enhancement", "plugins", "midi", "ui"]
---

# Virtual Keyboard Built-In Plugin (KeyboardProcessorView)

## Motivation

The `KeyboardProcessor` in `daw-core` and its corresponding `KeyboardProcessorView` in `daw-app` already provide a fully featured virtual MIDI keyboard with preset selection, octave/transpose controls, velocity curves, and MIDI recording/playback. However, this capability is currently embedded directly into the application's view hierarchy and is not accessible through the plugin system. Users cannot discover it in the Plugins menu or launch it independently.

Wrapping the virtual keyboard as a `BuiltInDawPlugin` implementation would make it the first built-in plugin, setting the pattern for all future built-in plugins. It would appear in the Plugins menu under the "Instruments" category, be launchable with a single click, and follow the standard `DawPlugin` lifecycle (`initialize` → `activate` → `deactivate` → `dispose`). The existing `KeyboardProcessorView` UI can be reused as the plugin's visual component, and the `KeyboardProcessor` core logic remains unchanged.

## Goals

- Create a `KeyboardPlugin` class (or similar name) in `daw-core` that implements `BuiltInDawPlugin`
- The class has a public no-arg constructor for reflective instantiation
- `getDescriptor()` returns a `PluginDescriptor` with id `"com.benesquivelmusic.daw.keyboard"`, name `"Virtual Keyboard"`, type `INSTRUMENT`
- `getMenuLabel()` returns `"Virtual Keyboard"`
- `getCategory()` returns `BuiltInPluginCategory.INSTRUMENT`
- `initialize(PluginContext)` creates a `KeyboardProcessor` configured with the context's sample rate and buffer size
- `activate()` opens the `KeyboardProcessorView` in a new window or panel, wired to the plugin's `KeyboardProcessor` instance
- `deactivate()` hides the keyboard view and sends all-notes-off to stop any sustained notes
- `dispose()` disposes the `KeyboardProcessorView` and releases the `KeyboardProcessor` resources
- Add the `KeyboardPlugin` to the `BuiltInDawPlugin` permits clause so it is automatically discovered
- The plugin is fully testable: unit tests verify lifecycle, descriptor metadata, and that the `KeyboardProcessor` is correctly initialized

## Non-Goals

- Redesigning the `KeyboardProcessorView` UI — reuse the existing implementation as-is
- Adding new features to the `KeyboardProcessor` (recording, playback, velocity curves already exist)
- Supporting multiple simultaneous keyboard plugin instances (one instance is sufficient initially)
- MIDI output routing to external devices (the keyboard plays through the internal `SoundFontRenderer`)
