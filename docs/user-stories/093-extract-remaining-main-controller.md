---
title: "Extract Remaining Responsibilities from MainController"
labels: ["enhancement", "ui", "usability"]
---

# Extract Remaining Responsibilities from MainController

## Motivation

`MainController` is 2,199 lines despite six prior extraction stories (046–051) that moved transport, project lifecycle, metronome, view navigation, visualization panel, browser panel, and toolbar appearance logic into dedicated controllers. The class remains a monolith because it still owns several large, independent responsibilities:

1. **Plugin management wiring** — opening the Plugin Manager dialog, handling built-in plugin menu construction, opening plugin views via `openBuiltInPluginView`, and managing plugin lifecycle callbacks
2. **Menu bar construction** — building the entire `MenuBar` with File, Edit, Plugins, Window, and Help menus, wiring accelerators, and maintaining `syncMenuState()` for disabled-state management
3. **Track strip construction** — despite story 046 proposing a `TrackStripController`, `addTrackToUI`, `buildTrackContextMenu`, `startTrackRename`, and related helpers still reside in `MainController`
4. **Audio device I/O enumeration** — querying available audio inputs/outputs and populating the recording source dialog
5. **Keyboard shortcut registration** — wiring `KeyBindingManager` actions to their handler methods across the entire scene graph

Each of these is a cohesive responsibility that can be extracted into its own controller class, reducing `MainController` to a thin coordinator that delegates to specialized controllers.

## Goals

- Extract plugin menu construction and plugin view management into a `PluginMenuController` (or similar)
- Extract menu bar construction and `syncMenuState()` into a `MenuBarController` that owns the `MenuBar` node and all menu item wiring
- Complete the `TrackStripController` extraction proposed in story 046 if not yet done — move `addTrackToUI`, `buildTrackContextMenu`, `startTrackRename`, and `midiInstrumentIcon` out of `MainController`
- Extract audio device enumeration into the audio engine or a dedicated `AudioDeviceManager` in `daw-core`
- Reduce `MainController` to under 800 lines — it should only initialize the layout, create sub-controllers, and wire top-level event handlers
- Each extracted controller receives its dependencies via constructor injection and is independently unit-testable
- No visible behavior changes — this is a purely structural refactoring

## Non-Goals

- Introducing a new architectural pattern (MVC, MVP, MVVM) beyond what already exists
- Redesigning the UI layout or visual appearance
- Adding new features or changing existing functionality
- Refactoring other large classes (`EditorView`, `ArrangementCanvas`) — those are separate stories
