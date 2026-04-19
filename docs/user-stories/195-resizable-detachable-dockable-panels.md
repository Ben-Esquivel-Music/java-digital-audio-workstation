---
title: "Resizable and Detachable Dockable Panels"
labels: ["enhancement", "ui", "workflow"]
---

# Resizable and Detachable Dockable Panels

## Motivation

The current UI glues the mixer to the bottom and the arrangement to the top with fixed split ratios. Professional users want to detach the mixer to a second monitor, drag the browser to the right side, or collapse a panel entirely. Every DAW has a dock system: Cubase's fully-dockable MixConsole, Logic's floating windows, Reaper's docker that lets any panel pop out. Without docking, multi-monitor workflows fight the tool.

## Goals

- Add `DockManager` in `daw-app.ui.dock` implementing dock zones (top/bottom/left/right/center) with drag-drop panel docking.
- Every top-level panel (`ArrangementView`, `MixerView`, `BrowserPanel`, `EditorView`, `TelemetrySetupPanel`, `MasteringChainView`, etc.) implements a `Dockable` interface providing its name, icon, and preferred location.
- Tabs stack panels in the same dock zone; user can drag tabs between zones or out to a floating `DockWindow` (a new `Stage`).
- Floating windows persist their screen position and size across app restarts.
- Keyboard shortcuts: `F5` shows/hides arrangement, `F3` mixer, `F4` browser, etc. (consistent with Cubase where users expect them).
- Dock state serializes into the active `Workspace` (story 193) so workspace switches restore dock layout.
- Graceful handling when a floating window's monitor is disconnected (dock back to main window).
- Tests: a panel dragged from docked to floating and back produces identical bounds; floating-window persistence survives app restart; workspace switches apply dock state atomically.

## Non-Goals

- Cross-process panels (panels running in separate JVMs).
- Mobile / touch-optimized layouts.
- Panels that cannot be docked (all panels must be `Dockable`).
