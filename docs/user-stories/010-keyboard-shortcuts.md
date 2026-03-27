---
title: "Comprehensive Keyboard Shortcuts and Customizable Key Bindings"
labels: ["enhancement", "ui", "usability"]
---

# Comprehensive Keyboard Shortcuts and Customizable Key Bindings

## Motivation

A DAW is a power-user tool where keyboard shortcuts dramatically improve workflow speed. The current implementation has minimal keyboard shortcuts — basic transport controls exist but many common operations lack bindings. The `SettingsDialog` has a Key Bindings tab, but the actual shortcut system is limited. Professional DAW users expect shortcuts for: transport (Space=play/stop, R=record), editing (S=split, Ctrl+Z=undo, Ctrl+C/V/X=clipboard), navigation (Ctrl+Home=go to start, +/- =zoom), track operations (Ctrl+T=new track), and view switching (F1-F4 for views). Without comprehensive shortcuts, the application feels clunky and slow.

## Goals

- Implement a complete set of default keyboard shortcuts covering transport, editing, navigation, track operations, and view switching
- Make all shortcuts user-customizable through the Settings > Key Bindings tab
- Persist custom key bindings using the existing `SettingsModel` / Preferences API
- Support modifier key combinations (Ctrl, Shift, Alt, Cmd on macOS)
- Prevent conflicts when assigning new shortcuts
- Display shortcut hints in tooltips and menu items
- Provide a "Reset to Defaults" button for restoring factory key bindings

## Non-Goals

- Macro recording (binding a sequence of actions to a single shortcut)
- MIDI controller mapping to DAW functions
- Importing key binding profiles from other DAWs
