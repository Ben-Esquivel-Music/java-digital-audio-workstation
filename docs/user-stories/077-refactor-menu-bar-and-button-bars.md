---
title: "Refactor menu bar and button bars to be more intuitive"
labels: ["enhancement", "ui", "refactor"]
---

# Refactor menu bar and button bars to be more intuitive

## Motivation

The DAW application's button bar and menu bar layout was confusing and unintuitive. Buttons for actions like Add Track, Undo/Redo, Save, and Plugin Manager were embedded in the transport bar alongside playback controls, making the transport bar cluttered and difficult to scan. Additionally, the sidebar contained edit-tool and zoom-control buttons that were always visible regardless of whether the current view supported those actions (e.g., zoom and edit tools have no relevance when the Mixer or Telemetry view is active). This violated the principle that only actionable controls should be presented to the user.

Professional DAW applications (Pro Tools, Logic Pro, Ableton Live, Reaper) use a traditional menu bar at the top of the window with a standard layout (File, Edit, Plugins, Window, Help), keeping the transport bar focused solely on playback controls. View-specific tools are either embedded in their respective views or hidden when not applicable.

## Goals

- Add a traditional menu bar (File, Edit, Plugins, Window, Help) at the top of the window, above the transport bar, with dark-theme styling consistent with the application's existing visual language
- File menu: New, Open, Recent, Save, Import/Export Session, Import Audio File — all synchronized to project state (e.g., Save disabled when project is not dirty, Export disabled when no tracks exist)
- Edit menu: Undo, Redo, Copy, Cut, Paste, Duplicate, Delete Selection, Toggle Snap — with proper disabled states based on undo history, clipboard content, and clip selection
- Plugins menu: Plugin Manager, Settings
- Window menu: View switching (Arrangement, Mixer, Editor, Telemetry, Mastering), panel toggles (Browser, History, Notifications, Visualizations, Toolbar)
- Help menu: Help dialog access
- All menu items display keyboard accelerators resolved from the KeyBindingManager
- Remove non-transport buttons (Add Track, Undo/Redo, Snap, Save, Plugins) from the transport bar since they are now accessible via the menu bar, leaving the transport bar focused on playback controls
- Hide view-specific sidebar sections (TOOLS and ZOOM) when the active view does not support them (only shown for Arrangement and Editor views)
- Ensure all menu items have appropriate icons from the DAW icon pack
- Fully synchronize menu item disabled states with live project state via `syncMenuState()` called on every state change

## Non-Goals

- Replacing the sidebar navigation entirely — the sidebar remains for quick view switching and project management
- Adding new actions or features beyond what already exists in the application
- Custom menu bar rendering (uses standard JavaFX MenuBar with CSS theming)
- Context menus on menu items (right-click behavior remains on toolbar buttons as before)
