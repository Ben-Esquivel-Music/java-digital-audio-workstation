---
title: "Sound Wave Telemetry Built-In Plugin"
labels: ["enhancement", "plugins", "analyzer", "ui", "telemetry", "refactor"]
---

# Sound Wave Telemetry Built-In Plugin

## Motivation

The Sound Wave Telemetry view (`TelemetryView`) is currently one of the five main DAW views alongside Arrangement, Mixer, Editor, and Mastering. It occupies the center content area when active and is accessible via `Cmd+4` / `Ctrl+4`. However, room acoustics analysis is a specialized tool — not a core audio production workflow — and giving it equal billing with the primary editing views creates several problems:

1. **Navigation clutter**: The four core views (Arrangement, Mixer, Editor, Mastering) represent the linear audio production workflow. Telemetry is an orthogonal analysis tool that many users will never use. Inserting it between Editor (`Cmd+3`) and Mastering (`Cmd+5`) forces users to skip past it and wastes a prime keyboard shortcut.

2. **Inconsistency with other analysis tools**: The Spectrum Analyzer (story 081) is already packaged as a `BuiltInDawPlugin` under the "Analyzers" category in the Plugins menu. Sound Wave Telemetry is also an analysis/visualization tool but is accessed through an entirely different mechanism (the View menu and navigation bar). Users must look in two different places to find analysis tools.

3. **Workspace occlusion**: When the telemetry view is active, it replaces the entire center content area. Users cannot see the arrangement, mixer, or editor alongside the room analysis. Professional room analysis tools (Sonarworks SoundID, Room EQ Wizard, IK Multimedia ARC) typically open in their own floating window so the user can reference their mix while analyzing room acoustics.

4. **Plugin discovery model**: New users searching for "room analysis" or "acoustic telemetry" would naturally look in the Plugins menu under Analyzers, not in the View navigation. Moving telemetry to a built-in plugin aligns its discovery path with user expectations.

The existing `BuiltInDawPlugin` sealed interface, plugin lifecycle (`initialize` → `activate` → `deactivate` → `dispose`), and Plugins menu infrastructure (story 078, 079) already support this migration. The `TelemetryView` UI — including its two-state setup/display flow, `RoomTelemetryDisplay` canvas, drag-and-drop repositioning, debounced recomputation, animation timer, and project integration — can be reused as the plugin's floating window content with minimal modification.

## Goals

- Create a `SoundWaveTelemetryPlugin` class in `daw-core` that implements `BuiltInDawPlugin`
- The class has a public no-arg constructor for reflective instantiation
- `getDescriptor()` returns a `PluginDescriptor` with id `"com.benesquivelmusic.daw.builtin.sound-wave-telemetry"`, name `"Sound Wave Telemetry"`, type `ANALYZER`
- `getMenuLabel()` returns `"Sound Wave Telemetry"`
- `getMenuIcon()` returns `"surround"` (reusing the existing `DawIcon.SURROUND` identifier)
- `getCategory()` returns `BuiltInPluginCategory.ANALYZER`
- `activate()` opens the existing `TelemetryView` in a floating plugin window, wired to the current `DawProject` for room configuration persistence
- The floating window preserves the two-state setup/display flow: users configure room dimensions, wall material, sound sources, and microphones in the setup panel, then generate telemetry to see the animated room visualization
- Drag-and-drop repositioning of sources and microphones continues to work with debounced recomputation
- The animation timer runs only while the plugin window is visible
- Project integration is preserved: room configuration is saved to and loaded from `DawProject` so that re-opening the plugin restores the last configuration
- `deactivate()` stops the animation timer and hides the floating window
- `dispose()` releases animation resources and clears the project reference
- Add `SoundWaveTelemetryPlugin` to the `BuiltInDawPlugin` sealed `permits` clause so it is automatically discovered
- Remove `DawView.TELEMETRY` from the `DawView` enum
- Remove `DawAction.VIEW_TELEMETRY` from the `DawAction` enum and its `Cmd+4` keyboard shortcut
- Remove telemetry-specific code from `ViewNavigationController` (the `telemetryView` field, animation start/stop on view switch, `onProjectChanged` rebinding)
- Reassign `Cmd+4` / `Ctrl+4` to `VIEW_MASTERING` (promoting it from `Cmd+5`) since Mastering becomes the fourth main view
- Update the menu bar to remove the Telemetry entry from the View menu (it is now in the Plugins menu under Analyzers)
- The plugin is fully testable: unit tests verify lifecycle, descriptor metadata, menu entry fields, and sealed-interface discovery

## Non-Goals

- Redesigning the `TelemetryView` or `RoomTelemetryDisplay` UI — reuse the existing implementation as the plugin window's content
- Adding new telemetry features (additional visualization layers are covered by story 066)
- Supporting multiple simultaneous telemetry plugin instances (one instance is sufficient — room analysis is per-project)
- Changing the `SoundWaveTelemetryEngine` computation logic — it remains in `daw-core` unchanged
- Modifying project serialization format — room configuration continues to be stored in `DawProject` the same way
